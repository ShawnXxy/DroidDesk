package com.orailnoor.droiddesk.x11

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Binds a UI-side LorieView to the X server process without running Binder calls on the UI. */
class X11ServiceClient(
    context: Context,
    private val onConnected: (Int, ParcelFileDescriptor, ParcelFileDescriptor?) -> Unit,
    private val onError: (String, Throwable?) -> Unit,
    private val canReuseServer: (Int) -> Boolean = { false }, private val onReused: (Int) -> Unit = {}, private val onDisconnected: () -> Unit = {},
) {
    companion object {
        /** X11 service process currently attached to the UI process's native client. */
        @Volatile var connectedServerPid: Int = -1
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "X11BinderClient").apply { isDaemon = true } }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false); private val connectionSeq = AtomicLong(0); @Volatile private var currentBinder: IBinder? = null
    private var bound = false
    val isConnected: Boolean get() = active.get() && currentBinder?.isBinderAlive == true && connectedServerPid > 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!active.get() || !binder.isBinderAlive) return
            currentBinder = binder; val seq = connectionSeq.incrementAndGet()
            val service = IX11Service.Stub.asInterface(binder)
            fun isCurrent() = active.get() && connectionSeq.get() == seq && currentBinder === binder && binder.isBinderAlive
            fun enqueue(allowReuse: Boolean) { if (isCurrent()) executor.execute {
                var pendingFd: ParcelFileDescriptor? = null; var pendingLogcatFd: ParcelFileDescriptor? = null
                try {
                    if (!isCurrent()) return@execute
                    if (!service.startServer()) {
                        if (isCurrent()) postError("The X11 service could not start the native server", null)
                        return@execute
                    }

                    val serverPid = service.serverPid
                    if (!isCurrent()) return@execute
                    if (allowReuse && canReuseServer(serverPid)) {
                        mainHandler.post { if (isCurrent()) { if (canReuseServer(serverPid)) onReused(serverPid) else enqueue(false) } }
                        return@execute
                    }
                    connectedServerPid = -1
                    val connectionFd = service.xConnection.also { pendingFd = it }
                    if (connectionFd == null) {
                        if (isCurrent()) postError("The X11 service returned no client connection", null)
                        return@execute
                    }
                    if (!isCurrent()) { pendingFd = null; connectionFd.close(); return@execute }
                    val logcatFd = service.logcatOutput.also { pendingLogcatFd = it }
                    pendingFd = null; pendingLogcatFd = null

                    if (!mainHandler.post {
                        if (isCurrent()) {
                            onConnected(serverPid, connectionFd, logcatFd)
                        } else {
                            connectionFd.close()
                            logcatFd?.close()
                        }
                    }) { connectionFd.close(); logcatFd?.close() }
                } catch (error: Throwable) {
                    pendingFd?.close(); pendingLogcatFd?.close()
                    if (isCurrent()) postError("Failed to communicate with the X11 service", error)
                }
            } }
            enqueue(true)
        }

        override fun onServiceDisconnected(name: ComponentName) = handleDisconnect("The X11 service process disconnected", rebind = false)

        override fun onBindingDied(name: ComponentName) = handleDisconnect("The X11 service binding died", rebind = true)

        override fun onNullBinding(name: ComponentName) {
            if (active.get()) postError("The X11 service returned a null binding", null)
        }
    }

    fun connect() {
        if (!active.compareAndSet(false, true)) return
        val intent = Intent(appContext, X11ServerService::class.java)
        // A bound-only service is destroyed as soon as DesktopActivity goes
        // away. Keep the isolated X server started while the Linux session is
        // running so returning to the desktop can reuse the live connection.
        appContext.startService(intent)
        bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) postError("Android refused the X11 service binding", null)
    }

    fun disconnect() {
        if (!active.getAndSet(false)) return
        connectionSeq.incrementAndGet(); currentBinder = null
        if (bound) {
            appContext.unbindService(connection)
            bound = false
        }
    }

    private fun handleDisconnect(message: String, rebind: Boolean) {
        if (!active.get()) return
        connectionSeq.incrementAndGet(); currentBinder = null; connectedServerPid = -1
        if (rebind && bound) { appContext.unbindService(connection); bound = false }
        onDisconnected(); onError(message, null)
        if (rebind && active.get()) { val i = Intent(appContext, X11ServerService::class.java); appContext.startService(i); bound = appContext.bindService(i, connection, Context.BIND_AUTO_CREATE); if (!bound) postError("Android refused the X11 service binding", null) }
    }

    private fun postError(message: String, error: Throwable?) {
        mainHandler.post {
            if (active.get()) onError(message, error)
        }
    }
}
