package com.orailnoor.droiddesk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.orailnoor.droiddesk.MainActivity
import com.orailnoor.droiddesk.runtime.AndroidAppBridge
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.view.DesktopActivity
import com.orailnoor.droiddesk.x11.X11ServerService
import kotlin.concurrent.thread
import com.orailnoor.droiddesk.display.DesktopDisplayCoordinator
import com.orailnoor.droiddesk.display.DesktopDisplayException
import com.orailnoor.droiddesk.runtime.AndroidAppSync
import com.orailnoor.droiddesk.runtime.RootShell
import com.orailnoor.droiddesk.runtime.ProcessLinuxEnvironmentRegistry
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.lang.ref.WeakReference
import java.io.IOException

enum class DesktopStopReason { USER, ENVIRONMENT_CHANGE }

internal class ServiceStartGate {
    private var started = false
    @Synchronized fun start(operation: () -> Unit) { if (!started) { operation(); started = true } }
    @Synchronized fun reset() { started = false }
}

internal class ProvisioningNotificationState(private val publish: (String?) -> Unit) {
    private var epoch: Long? = null; private var failed = false; private var published: String? = null
    @Volatile var text: String? = null; private set

    fun start(epoch: Long, message: String, isSessionCurrent: (Long) -> Boolean = { true }, startService: () -> Unit = {}) {
        synchronized(this) { requireCurrent(epoch, isSessionCurrent); this.epoch = epoch; failed = false; text = message }
        try { startService() } catch (error: RuntimeException) {
            val failure = if (error is DesktopDisplayException || error is SecurityException || error.message != null) error
            else IllegalStateException("Android could not start the Linux service", error)
            fail(epoch, failure, isSessionCurrent); throw error
        }
        synchronized(this) { requireCurrent(epoch, isSessionCurrent); if (this.epoch == epoch && !failed) emit() }
    }
    @Synchronized fun progress(epoch: Long, message: String, isSessionCurrent: (Long) -> Boolean = { true }) {
        evictStale(isSessionCurrent); if (this.epoch == epoch && !failed && message.isNotBlank()) set(message)
    }
    @Synchronized fun complete(epoch: Long, isSessionCurrent: (Long) -> Boolean = { true }) {
        evictStale(isSessionCurrent); if (this.epoch == epoch && !failed) clear()
    }
    @Synchronized fun fail(epoch: Long, failure: Throwable, isSessionCurrent: (Long) -> Boolean = { true }) {
        evictStale(isSessionCurrent); if (this.epoch != epoch) return
        if ((failure as? DesktopDisplayException)?.code == "SESSION_STOPPED" || failure is CancellationException) clear()
        else if (!failed) { failed = true; set("Linux desktop failed to start: " + if (failure is SecurityException) "Android denied starting the Linux service" else failure.message ?: "Check the session log") }
    }
    @Synchronized fun activeText(currentEpoch: Long, isStopped: Boolean): String? {
        if (epoch != null && (isStopped || epoch != currentEpoch)) clear(); return text
    }
    @Synchronized fun clear() { epoch = null; failed = false; set(null) }
    private fun requireCurrent(epoch: Long, isSessionCurrent: (Long) -> Boolean) {
        if (!isSessionCurrent(epoch)) { evictStale(isSessionCurrent); throw DesktopDisplayException("SESSION_STOPPED", "The session was stopped before provisioning started") }
    }
    private fun evictStale(isSessionCurrent: (Long) -> Boolean) { epoch?.let { if (!isSessionCurrent(it)) clear() } }
    private fun set(message: String?) { text = message; emit() }
    private fun emit() { if (published != text) { published = text; publish(text) } }
}

internal class ServiceNotificationTracker(private val sequence: AtomicLong = AtomicLong()) {
    private var foreground = false; private var recovery = false; private var lastText: String? = null
    fun currentSequence(): Long = sequence.get()
    @Synchronized fun isForeground(): Boolean = foreground
    @Synchronized fun onStartForeground(text: String) { foreground = true; recovery = false; lastText = text }
    @Synchronized fun onStopForeground() { sequence.incrementAndGet(); foreground = false; recovery = false; lastText = null }
    @Synchronized fun onRecoveryShown() { sequence.incrementAndGet(); recovery = true; lastText = null }
    @Synchronized fun nextUpdate(provisioningText: String?, fallbackText: String, refreshSequence: Long? = null): String? {
        if (!foreground || (refreshSequence != null && (refreshSequence != sequence.get() || (recovery && provisioningText == null)))) return null
        val candidate = provisioningText ?: fallbackText
        if (candidate == lastText) return null
        recovery = false; lastText = candidate; return candidate
    }
}

internal data class DesktopReturnNotificationSpec(
    val contentTitle: String,
    val actionLabel: String,
    val action: String,
)

internal fun desktopReturnNotificationSpec(
    snapshot: Map<String, Any?>,
    runtimeRunning: Boolean,
): DesktopReturnNotificationSpec {
    val canReturnToPhone =
        snapshot["crossDisplayMovesAllowed"] == true &&
            (snapshot["sessionRunning"] == true || runtimeRunning)
    return if (canReturnToPhone) {
        DesktopReturnNotificationSpec(
            "Linux session preserved",
            "Return to phone",
            MainActivity.ACTION_RETURN_TO_PHONE,
        )
    } else {
        DesktopReturnNotificationSpec(
            "Desktop placement needs attention",
            "Open",
            MainActivity.ACTION_OPEN_DESKTOP,
        )
    }
}

internal fun notificationRuntimeRunning(
    snapshot: Map<String, Any?>,
    readRuntimeRunning: () -> Boolean,
    onFailure: (Exception) -> Unit,
): Boolean = try {
    readRuntimeRunning()
} catch (error: Exception) {
    onFailure(error)
    snapshot["sessionRunning"] == true
}

internal fun performDesktopTeardown(
    stopEnvironment: () -> Unit,
    stopLegacyBridge: () -> Unit,
    stopDisplayService: () -> Unit,
    onFailure: (String, Exception) -> Unit,
): Exception? {
    try {
        stopEnvironment()
    } catch (error: Exception) {
        onFailure("Linux environment stop", error)
        return error
    }

    var failure: Exception? = null
    listOf(
        "Legacy intent bridge stop" to stopLegacyBridge,
        "X11 display service stop" to stopDisplayService,
    ).forEach { (name, operation) ->
        try {
            operation()
        } catch (error: Exception) {
            onFailure(name, error)
            if (failure == null) failure = error
        }
    }
    return failure
}

internal fun <T : AutoCloseable> prepareDesktopStop(
    acquireAdmission: () -> T,
    startStopping: () -> Unit,
): Result<T> = runCatching {
    val admission = acquireAdmission()
    try {
        startStopping()
        admission
    } catch (error: Exception) {
        admission.close()
        throw error
    }
}

/**
 * Foreground service that keeps the Linux runtime alive.
 *
 * Android aggressively kills background processes (especially Android 12+'s
 * Phantom Process Killer). This service ensures our native Termux/chroot session, desktop
 * environment, and Wayland compositor survive when the user switches apps.
 */
class DroidDeskService : Service() {

    companion object {
        const val CHANNEL_ID = "droiddesk_service"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "DroidDeskService"
        private const val ACTION_STOP = "com.orailnoor.droiddesk.action.STOP_DESKTOP"
        private val sessionExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "DesktopSessionOperations") }
        private val mainHandler = Handler(Looper.getMainLooper())
        private val startGate = ServiceStartGate()
        private val recoverySequence = AtomicLong()
        private var activeService: WeakReference<DroidDeskService>? = null
        private val provisioningState = ProvisioningNotificationState { val seq = recoverySequence.get(); mainHandler.post { activeService?.get()?.refreshNotification(seq) } }
        @Volatile private var legacyIntentProcess: Process? = null
        @Volatile private var legacyIntentActive = false

        fun executeSession(operation: () -> Unit) {
            sessionExecutor.execute(operation)
        }

        fun ensureStarted(context: Context) = startGate.start {
            val app = context.applicationContext
            ContextCompat.startForegroundService(app, Intent(app, DroidDeskService::class.java))
        }
        private fun isCurrent(epoch: Long) = DesktopDisplayCoordinator.shared.isSessionCurrent(epoch)
        fun beginProvisioning(context: Context, epoch: Long, message: String) =
            provisioningState.start(epoch, message, ::isCurrent) { ensureStarted(context) }
        fun updateProvisioning(epoch: Long, message: String) = provisioningState.progress(epoch, message, ::isCurrent)
        fun completeProvisioning(epoch: Long) = provisioningState.complete(epoch, ::isCurrent)
        fun failProvisioning(epoch: Long, failure: Throwable) = provisioningState.fail(epoch, failure, ::isCurrent)

        fun stopDesktop(
            context: Context,
            reason: DesktopStopReason = DesktopStopReason.USER,
            completion: (Result<Unit>) -> Unit,
        ) {
            val app = context.applicationContext
            val coordinator = DesktopDisplayCoordinator.shared
            val environments = ProcessLinuxEnvironmentRegistry.get(app).environmentManager
            val admission = prepareDesktopStop(
                acquireAdmission = environments::acquireStopAdmission,
                startStopping = {
                    if (reason == DesktopStopReason.USER) coordinator.cancelPendingRestart()
                    coordinator.startStopping(
                        preserveRestart = reason == DesktopStopReason.ENVIRONMENT_CHANGE,
                    )
                    provisioningState.clear()
                },
            ).getOrElse { failure ->
                completion(Result.failure(failure))
                return
            }
            executeSession {
                val failure = try {
                    performDesktopTeardown(
                        stopEnvironment = { admission.stop().getOrThrow() },
                        stopLegacyBridge = ::stopLegacyIntentBridge,
                        stopDisplayService = {
                            app.stopService(Intent(app, X11ServerService::class.java))
                            Unit
                        },
                        onFailure = { operation, error ->
                            Log.e(TAG, "$operation failed", error)
                        },
                    )
                } finally {
                    admission.close()
                }
                mainHandler.post {
                    coordinator.finishStopping(failure)
                    if (failure == null) {
                        activeService?.get()?.let {
                            it.notifications.onStopForeground()
                            ServiceCompat.stopForeground(it, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        } ?: recoverySequence.incrementAndGet()
                        app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                        activeService = null; startGate.reset()
                        app.stopService(Intent(app, DroidDeskService::class.java))
                        completion(Result.success(Unit))
                    } else {
                        activeService?.get()?.updateNotification("Desktop stop failed. Try Stop again.")
                        completion(Result.failure(failure))
                    }
                }
            }
        }

        fun ensureLegacyIntentBridge(context: Context, runtime: ChrootRuntime) {
            if (legacyIntentActive) return
            val pipe = runtime.getRootfsPath() + "/tmp/android_intent_pipe"
            val prepared = runtime.executeCommand(
                "mkdir -p /tmp; if [ -e /tmp/android_intent_pipe ]; then " +
                    "test -p /tmp/android_intent_pipe || exit 1; else mkfifo /tmp/android_intent_pipe; fi; " +
                    "chmod 666 /tmp/android_intent_pipe",
            ) { Log.d(TAG, it) }
            check(prepared == "Exit code: 0") { "Could not prepare the Android launcher pipe: $prepared" }
            AndroidAppSync.syncApps(context.applicationContext, runtime.getRootfsPath())
            val su = RootShell(context).findSuPath() ?: error("Root launcher bridge has no su binary")
            val process = ProcessBuilder(su, "-c", "tail -f '$pipe'").start()
            legacyIntentProcess = process
            legacyIntentActive = true
            thread(name = "DesktopAndroidIntentBridge") {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        while (legacyIntentActive) {
                            val packageName = reader.readLine()?.trim() ?: break
                            if (packageName.isEmpty()) continue
                            mainHandler.post {
                                if (legacyIntentActive && legacyIntentProcess === process &&
                                    !AndroidAppBridge.launchAndroidPackage(context, packageName)
                                ) {
                                    Log.w(TAG, "Could not launch Android package $packageName")
                                }
                            }
                        }
                    }
                } catch (error: IOException) {
                    if (legacyIntentActive && legacyIntentProcess === process) {
                        Log.e(TAG, "Android launcher pipe disconnected", error)
                    }
                } finally {
                    if (legacyIntentProcess === process) {
                        legacyIntentProcess = null
                        legacyIntentActive = false
                    }
                    process.destroy()
                }
            }
        }

        private fun stopLegacyIntentBridge() {
            legacyIntentActive = false
            legacyIntentProcess?.destroy()
            legacyIntentProcess = null
        }

        fun notifyReturnRequired(context: Context, message: String) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val coordinator = DesktopDisplayCoordinator.shared
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "DroidDesk Linux Service", NotificationManager.IMPORTANCE_LOW,
            ))
            val snapshot = coordinator.snapshot()
            val running = notificationRuntimeRunning(
                snapshot = snapshot,
                readRuntimeRunning = {
                    ProcessLinuxEnvironmentRegistry.get(context).environmentManager.status().running
                },
                onFailure = {
                    Log.e(TAG, "Could not read Linux runtime status for recovery notification", it)
                },
            )
            val spec = desktopReturnNotificationSpec(snapshot, running)
            val returnIntent = PendingIntent.getActivity(
                context, 2, Intent(context, MainActivity::class.java)
                    .setAction(spec.action)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stopIntent = PendingIntent.getService(
                context, 1, Intent(context, DroidDeskService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            activeService?.get()?.notifications?.onRecoveryShown() ?: recoverySequence.incrementAndGet()
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(spec.contentTitle)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(returnIntent)
                .addAction(0, spec.actionLabel, returnIntent)
                .addAction(0, "Stop", stopIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build())
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var startupNotification: DesktopStartupNotification? = null
    private var unsubscribeCoordinator: (() -> Unit)? = null
    private val notifications = ServiceNotificationTracker(recoverySequence)
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        DesktopDisplayCoordinator.shared.initialize(applicationContext)
        AndroidAppBridge.start(this)
        createNotificationChannel()
        startupNotification = DesktopStartupNotification(DesktopDisplayCoordinator.shared, ::updateNotification)
        unsubscribeCoordinator = DesktopDisplayCoordinator.shared.subscribe {
            if (provisioningState.text != null) refreshNotification(notifications.currentSequence())
        }
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopDesktop()
            return START_NOT_STICKY
        }
        startGate.start {}
        activeService = WeakReference(this)
        val contentText = currentProvisioningText() ?: checkNotNull(startupNotification).text
        val notification = buildNotification(contentText)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
        notifications.onStartForeground(contentText)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unsubscribeCoordinator?.invoke(); unsubscribeCoordinator = null
        startupNotification?.close(); startupNotification = null
        notifications.onStopForeground()
        AndroidAppBridge.stop()
        releaseWakeLock()
        if (activeService?.get() === this) { activeService = null; startGate.reset(); provisioningState.clear() }
        super.onDestroy()
    }

    /** Same teardown as the Flutter "stopLinux" call, triggered from the notification. */
    private fun stopDesktop() {
        if (stopping) return
        stopping = true
        stopDesktop(applicationContext) { outcome ->
            if (outcome.isFailure) { stopping = false; if (!notifications.isForeground()) stopSelf() }
        }
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DroidDesk Linux Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Linux desktop environment running"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        // Brings the existing task forward (showing the desktop if it is open); otherwise
        // MainActivity reopens the running session without restarting it.
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_DESKTOP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DroidDeskService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DroidDesk")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(0, "Open", openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun updateNotification(text: String) = postNotification(text, null)
    private fun refreshNotification(seq: Long) { startupNotification?.let { postNotification(it.text, seq) } }
    private fun postNotification(fallbackText: String, refreshSequence: Long?) {
        val contentText = notifications.nextUpdate(currentProvisioningText(), fallbackText, refreshSequence) ?: return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(contentText))
    }
    private fun currentProvisioningText() = DesktopDisplayCoordinator.shared.let { provisioningState.activeText(it.sessionGeneration, it.isStopped) }

    // ── Wake Lock ──

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DroidDesk::LinuxRuntime"
        ).apply {
            acquire(Long.MAX_VALUE)  // Keep CPU alive
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
