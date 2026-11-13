import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:async';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/services/update_service.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/models/desktop_display.dart';
import 'package:droiddesk/models/linux_environment.dart';
import 'package:droiddesk/models/package_store.dart';

/// Central state management for the entire DroidDesk app.
class AppState extends ChangeNotifier {
  bool _disposed = false;
  DesktopDisplayState _desktopDisplays = DesktopDisplayState();
  bool _desktopOperationBusy = false;
  String? _desktopOperationError;
  int _desktopEventRevision = 0;
  int _sessionControlRevision = 0;
  bool _sessionControlBusy = false;

  DesktopDisplayState get desktopDisplays => _desktopDisplays;
  bool get desktopDisplayBusy =>
      _desktopOperationBusy || _sessionControlBusy || _desktopDisplays.isMoving;
  String? get desktopOperationError => _desktopOperationError;
  String? get desktopDisplayError =>
      _desktopOperationError ?? _desktopDisplays.error?.toString();

  void _attachDisplayCallbacks() {
    if (_disposed) return;
    DroidDeskPlatform.onDesktopDisplayStateChanged = _receiveDesktopDisplays;
    DroidDeskPlatform.onDesktopDisplayError = _receiveDesktopDisplayError;
  }

  void _receiveDesktopDisplays(DesktopDisplayState state) {
    if (_disposed) return;
    _desktopEventRevision++;
    _desktopDisplays = state;
    _isRunning = state.sessionRunning;
    notifyListeners();
    if (state.phase == DesktopDisplayPhase.stopped ||
        state.error?.code == 'SESSION_STOPPED') {
      unawaited(refreshStatus());
    }
  }

  void _receiveDesktopDisplayError(FormatException error) {
    if (_disposed) return;
    _recordDisplayError(error);
    unawaited(refreshDesktopDisplays());
  }

  void _recordDisplayError(Object error) {
    if (_disposed) return;
    _desktopOperationError = error is PlatformException
        ? '${error.code}: ${error.message ?? 'Desktop display operation failed'}'
        : error.toString();
    debugPrint('Desktop display operation failed: $error');
    notifyListeners();
  }

  void clearDesktopDisplayError() {
    if (_disposed) return;
    _desktopOperationError = null;
    notifyListeners();
  }

  Future<void> refreshDesktopDisplays() async {
    if (_disposed) return;
    _attachDisplayCallbacks();
    final revision = _desktopEventRevision;
    try {
      final state = await DroidDeskPlatform.getDesktopDisplays();
      if (_disposed || revision != _desktopEventRevision) return;
      _desktopDisplays = state;
      _isRunning = state.sessionRunning;
      notifyListeners();
    } on PlatformException catch (error) {
      _recordDisplayError(error);
    } on FormatException catch (error) {
      _recordDisplayError(error);
    }
  }

  Future<DesktopDisplaySettings> getDesktopDisplaySettings(String key) =>
      DroidDeskPlatform.getDesktopDisplaySettings(key);

  Future<void> setAutoOpenOnMonitor(bool enabled) async {
    if (!_allowDisplayRouting()) return;
    await _runDesktopOperation(
      () => DroidDeskPlatform.setAutoOpenOnMonitor(enabled),
    );
  }

  Future<void> openDesktopOnDisplay(DesktopDisplayInfo display) async {
    if (!_allowDisplayRouting()) return;
    await _runDesktopOperation(
      () => DroidDeskPlatform.openDesktopOnDisplay(
        displayId: display.id,
        displayKey: display.key,
      ),
    );
  }

  Future<void> moveDesktopToPhone() async {
    if (!_allowDisplayRouting()) return;
    await _runDesktopOperation(DroidDeskPlatform.moveDesktopToPhone);
  }

  bool _allowDisplayRouting() {
    if (_disposed) return false;
    if (_desktopDisplays.crossDisplayMovesAllowed) return true;
    _recordDisplayError(
      StateError('The phone or TNT launcher selects the display environment'),
    );
    return false;
  }

  Future<void> saveDesktopDisplaySettings(
    DesktopDisplaySettings settings, {
    DesktopDisplaySettingsScope? scope,
  }) => _runDesktopOperation(
    () => DroidDeskPlatform.saveDesktopDisplaySettings(settings, scope: scope),
  );

  Future<void> _runDesktopOperation(
    Future<DesktopDisplayState> Function() operation,
  ) async {
    if (_disposed) return;
    if (desktopDisplayBusy) {
      _recordDisplayError(
        StateError('Another desktop display action is in progress'),
      );
      return;
    }
    _desktopOperationBusy = true;
    _desktopOperationError = null;
    notifyListeners();
    final revision = _desktopEventRevision;
    try {
      final state = await operation();
      if (!_disposed && revision == _desktopEventRevision) {
        _desktopDisplays = state;
      }
    } on PlatformException catch (error) {
      _recordDisplayError(error);
    } on FormatException catch (error) {
      _recordDisplayError(error);
    } on ArgumentError catch (error) {
      _recordDisplayError(error);
    } finally {
      if (!_disposed) {
        _desktopOperationBusy = false;
        notifyListeners();
        await refreshDesktopDisplays();
      }
    }
  }

  // ── Theme State ──
  ThemeMode _themeMode = ThemeMode.dark;

  // ── Setup State ──
  bool _isBootstrapped = false;
  bool _isRunning = false;
  bool _hasRoot = false;
  String _installedDistro = '';
  String _installedDE = '';
  String _selectedDistro = 'ubuntu';
  static const String _selectedDE = 'xfce4';
  int _setupStep = 0; // 0=welcome, 1=distro, 2=de, 3=install, 4=done

  // ── Download/Install Progress ──
  double _downloadProgress = 0.0;
  String _downloadStatus = '';
  double _extractProgress = 0.0;
  String _extractStatus = '';
  bool _isDownloading = false;
  bool _isExtracting = false;
  bool _isInstallingDE = false;
  String? _statusMessage;
  String _setupLog = '';
  LinuxEnvironmentStatus? _environmentStatus;
  LinuxEnvironmentKind _selectedEnvironment = LinuxEnvironmentKind.prootDebian;
  bool _environmentSelectionExplicit = false;

  // Terminal history
  final List<String> _terminalOutput = [
    'DroidDesk Linux Terminal\nType commands below.\n',
  ];
  List<String> get terminalOutput => _terminalOutput;

  /// A command is still running in the terminal; new lines are its stdin
  /// (answers such as apt's "[Y/n]"), not new commands.
  int _runningCommands = 0;
  bool get isCommandRunning => _runningCommands > 0;

  // ── Device Info ──
  Map<String, dynamic> _deviceInfo = {};

  // ── Error State ──
  String? _errorMessage;

  // ── Auto Start ──
  bool _autoStartDesktop = false;
  bool get autoStartDesktop => _autoStartDesktop;

  // ── Getters ──
  ThemeMode get themeMode => _themeMode;
  bool get isDarkMode => DroidTheme.isDark;
  bool get isBootstrapped => _isBootstrapped;
  bool get isRunning => _isRunning;
  bool get hasRoot => _hasRoot;
  String get installedDistro => _installedDistro;
  String get installedDE => _installedDE;
  String get selectedDistro => _selectedDistro;
  String get selectedDE => _selectedDE;
  int get setupStep => _setupStep;
  double get downloadProgress => _downloadProgress;
  String get downloadStatus => _downloadStatus;
  double get extractProgress => _extractProgress;
  String get extractStatus => _extractStatus;
  String? get statusMessage => _statusMessage;
  String get setupLog => _setupLog;
  bool get isDownloading => _isDownloading;
  bool get isExtracting => _isExtracting;
  bool get isInstallingDE => _isInstallingDE;
  Map<String, dynamic> get deviceInfo => _deviceInfo;
  String? get errorMessage => _errorMessage;
  LinuxEnvironmentStatus? get environmentStatus => _environmentStatus;
  LinuxEnvironmentKind get selectedEnvironment => _selectedEnvironment;

  bool get canProvisionSelectedEnvironment {
    final status = _environmentStatus;
    if (status == null) return false;
    return switch (_selectedEnvironment) {
      LinuxEnvironmentKind.prootDebian => status.capabilities.prootInstallable,
      LinuxEnvironmentKind.termuxNative =>
        !status.capabilities.host.state.blocksProvisioning &&
            !status.capabilities.localExecution.state.blocksProvisioning,
      LinuxEnvironmentKind.chrootUbuntu =>
        !status.capabilities.chroot.state.blocksProvisioning,
    };
  }

  String? get environmentBlockingReason {
    final status = _environmentStatus;
    if (status == null) return 'Runtime capabilities have not been loaded';
    return switch (_selectedEnvironment) {
      LinuxEnvironmentKind.prootDebian =>
        status.capabilities.prootBlockingReason,
      LinuxEnvironmentKind.termuxNative =>
        status.capabilities.host.reason ??
            status.capabilities.localExecution.reason,
      LinuxEnvironmentKind.chrootUbuntu =>
        status.capabilities.chroot.state.blocksProvisioning
            ? status.capabilities.chroot.reason
            : null,
    };
  }

  List<String> get environmentCapabilityDiagnostics {
    final capabilities = _environmentStatus?.capabilities;
    if (capabilities == null ||
        _selectedEnvironment != LinuxEnvironmentKind.prootDebian) {
      return const [];
    }
    final checks = <String, CapabilityEvidence>{
      'Android host': capabilities.host,
      'App-private execution': capabilities.localExecution,
      'PRoot': capabilities.proot,
      'X11': capabilities.x11,
      'Audio': capabilities.audio,
      'Clipboard': capabilities.clipboard,
      'Renderer': capabilities.renderer,
      'Storage': capabilities.storage,
    };
    final primaryBlockingReason = environmentBlockingReason;
    return [
      for (final entry in checks.entries)
        if (entry.value.state != CapabilityEvidenceState.supportedAndProbed &&
            entry.value.reason?.isNotEmpty == true &&
            entry.value.reason != primaryBlockingReason)
          '${entry.key}: ${entry.value.reason}',
    ];
  }

  String? get environmentRecommendationReason {
    final status = _environmentStatus;
    if (status == null) return null;
    if (_selectedEnvironment == LinuxEnvironmentKind.prootDebian) {
      return 'Standard Debian packages and ARM64 .deb compatibility';
    }
    return status.capabilities.prootBlockingReason ??
        'Native mode is selected for lower overhead';
  }

  bool get showEnvironmentStorageWarning {
    final capabilities = _environmentStatus?.capabilities;
    if (capabilities == null) return false;
    return capabilities.availableBytes >= capabilities.requiredBytes &&
        capabilities.availableBytes < 6 * 1024 * 1024 * 1024;
  }

  String? get avfDiagnostic {
    final capabilities = _environmentStatus?.capabilities;
    if (capabilities == null ||
        capabilities.avfFeature.state == CapabilityEvidenceState.unavailable) {
      return null;
    }
    return capabilities.avfPermission.reason ??
        'Android system virtualization is not available to this app';
  }

  bool get isSetupComplete => _isBootstrapped && _installedDE.isNotEmpty;
  bool get isDEInstalled => _installedDE.isNotEmpty;

  String get gpuType {
    final vendor = _deviceInfo['gpuVendor']?.toString() ?? '';
    if (vendor.contains('adreno')) return 'Adreno (Snapdragon)';
    if (vendor.contains('mali')) return 'Mali (MediaTek/Exynos)';
    if (vendor.contains('powervr')) return 'PowerVR';
    return 'Unknown GPU';
  }

  // ── Initialization ──

  /// Re-reads the runtime status whenever the app returns to the foreground, since the
  /// desktop can be stopped from outside Flutter (e.g. the notification's Stop action).
  AppLifecycleListener? _lifecycleListener;

  Future<void> initialize() async {
    _attachDisplayCallbacks();
    DroidDeskPlatform.onPackageStoreOperationEvent = _receivePackageStoreEvent;
    DroidDeskPlatform.onPackageStoreOperationResult =
        _receivePackageStoreResult;
    DroidDeskPlatform.onPackageStoreError = _receivePackageStoreError;
    _lifecycleListener ??= AppLifecycleListener(
      onResume: () {
        refreshStatus();
        refreshDesktopDisplays();
        checkForUpdate();
      },
    );
    await _loadThemeMode();

    // Set up progress callbacks
    DroidDeskPlatform.onDownloadProgress = (progress, status) {
      _downloadProgress = progress;
      _downloadStatus = status;
      if (progress < 0) {
        _isDownloading = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        _isDownloading = false;
      }
      notifyListeners();
    };

    DroidDeskPlatform.onExtractProgress = (progress, status) {
      _extractProgress = progress;
      _extractStatus = status;
      if (progress < 0) {
        _isExtracting = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        _isExtracting = false;
        refreshStatus();
      }
      notifyListeners();
    };

    DroidDeskPlatform.onInstallProgress = (progress, status) {
      _extractProgress = progress; // reusing extract progress state for UI
      _extractStatus = status;
      _statusMessage = status;
      _isInstallingDE = progress >= 0 && progress < 1.0;
      if (progress < 0) {
        _isExtracting = false;
        _isInstallingDE = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        _isExtracting = false;
        _isInstallingDE = false;
        refreshStatus();
      }
      notifyListeners();
    };

    DroidDeskPlatform.onTerminalOutput = (text) {
      if (_terminalOutput.isEmpty) _terminalOutput.add('');

      final cleanedText = text.replaceAll(RegExp(r'.*\r(?!\n)'), '');
      if (_setupStep == 3) {
        _setupLog += cleanedText;
        if (_setupLog.length > 20000) {
          _setupLog = _setupLog.substring(_setupLog.length - 20000);
        }
      }
      final lines = cleanedText.split('\n');

      for (int i = 0; i < lines.length; i++) {
        if (i == 0) {
          _terminalOutput[_terminalOutput.length - 1] += lines[i];
        } else {
          _terminalOutput.add(lines[i]);
        }
      }
      notifyListeners();
    };

    await refreshStatus();
    await _restorePackageOperation();
    await loadDeviceInfo();
    await refreshDesktopDisplays();
    DroidDeskPlatform.onUpdateProgress = _onUpdateProgress;
    checkForUpdate();

    final prefs = await SharedPreferences.getInstance();
    _autoStartDesktop = prefs.getBool('autoStartDesktop') ?? false;

    // Android admits auto-start on a fresh app launch; rebuilding Dart must not restart Stop.
  }

  @override
  void dispose() {
    _disposed = true;
    if (DroidDeskPlatform.onDesktopDisplayStateChanged ==
        _receiveDesktopDisplays) {
      DroidDeskPlatform.onDesktopDisplayStateChanged = null;
    }
    if (DroidDeskPlatform.onDesktopDisplayError ==
        _receiveDesktopDisplayError) {
      DroidDeskPlatform.onDesktopDisplayError = null;
    }
    if (DroidDeskPlatform.onPackageStoreOperationEvent ==
        _receivePackageStoreEvent) {
      DroidDeskPlatform.onPackageStoreOperationEvent = null;
    }
    if (DroidDeskPlatform.onPackageStoreOperationResult ==
        _receivePackageStoreResult) {
      DroidDeskPlatform.onPackageStoreOperationResult = null;
    }
    if (DroidDeskPlatform.onPackageStoreError == _receivePackageStoreError) {
      DroidDeskPlatform.onPackageStoreError = null;
    }
    _lifecycleListener?.dispose();
    super.dispose();
  }

  // ── Theme Control ──

  Future<void> _loadThemeMode() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final savedMode = prefs.getString('theme_mode');
      if (savedMode == 'light') {
        _themeMode = ThemeMode.light;
      } else if (savedMode == 'system') {
        _themeMode = ThemeMode.system;
      } else {
        _themeMode = ThemeMode.dark;
      }
      DroidTheme.currentThemeMode = _themeMode;
      notifyListeners();
      await DroidDeskPlatform.updateStatusBarTheme(!isDarkMode);
    } catch (_) {
      // Default to dark theme if preferences fail
    }
  }

  Future<void> setThemeMode(ThemeMode mode) async {
    _themeMode = mode;
    DroidTheme.currentThemeMode = mode;
    notifyListeners();
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('theme_mode', mode.name);
      await DroidDeskPlatform.updateStatusBarTheme(!isDarkMode);
    } catch (_) {}
  }

  Future<void> toggleThemeMode() async {
    if (_themeMode == ThemeMode.dark) {
      await setThemeMode(ThemeMode.light);
    } else {
      await setThemeMode(ThemeMode.dark);
    }
  }

  Future<void> refreshStatus() async {
    final revision = _desktopEventRevision;
    try {
      final rawStatus = await DroidDeskPlatform.getRuntimeStatus();
      final status = LinuxEnvironmentStatus.fromMap(rawStatus);
      if (_disposed) return;
      _environmentStatus = status;
      _isBootstrapped = status.installed;
      if (revision == _desktopEventRevision) _isRunning = status.running;
      _hasRoot = status.hasRoot;
      _installedDistro = status.distro;
      _installedDE = status.desktop;
      if (status.installed || !_environmentSelectionExplicit) {
        _selectedEnvironment = status.kind;
      }

      if (_disposed) return;
      notifyListeners();
    } catch (e) {
      if (_disposed) return;
      _errorMessage = 'Failed to get runtime status: $e';
      notifyListeners();
    }
  }

  Future<void> loadDeviceInfo() async {
    try {
      _deviceInfo = await DroidDeskPlatform.getDeviceInfo();
      notifyListeners();
    } catch (e) {
      // Non-fatal — continue without device info
    }
  }

  // ── Setup Flow ──

  void setSelectedDistro(String distro) {
    _selectedDistro = distro;
    notifyListeners();
  }

  void selectEnvironment(LinuxEnvironmentKind environment) {
    if (_activePackageOperationId != null || _packageRequestPending) {
      _errorMessage =
          'Cannot change Linux environment while a package operation is running';
      notifyListeners();
      return;
    }
    _selectedEnvironment = environment;
    _environmentSelectionExplicit = true;
    _errorMessage = null;
    notifyListeners();
  }

  void setSetupStep(int step) {
    _setupStep = step;
    _errorMessage = null;
    notifyListeners();
  }

  Future<void> setAutoStartDesktop(bool value) async {
    _autoStartDesktop = value;
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('autoStartDesktop', value);
  }

  Future<bool> detectRootForSetup() async {
    _hasRoot = await DroidDeskPlatform.checkRoot();
    notifyListeners();
    return _hasRoot;
  }

  /// Main setup entry point. [useRoot] is retained for the rooted-device
  /// confirmation flow while [environment] selects a non-root runtime.
  Future<bool> runSetup({
    LinuxEnvironmentKind? environment,
    bool? useRoot,
  }) async {
    try {
      _errorMessage = null;
      _setupLog = '';
      _setupStep = 3;
      notifyListeners();

      final chosen = useRoot == true
          ? LinuxEnvironmentKind.chrootUbuntu
          : environment ?? _selectedEnvironment;
      final current = _environmentStatus;
      if (current?.running == true && current?.kind != chosen) {
        await DroidDeskPlatform.stopLinux();
      }
      _selectedEnvironment = chosen;
      _environmentSelectionExplicit = true;
      _hasRoot = chosen == LinuxEnvironmentKind.chrootUbuntu;
      notifyListeners();

      if (chosen == LinuxEnvironmentKind.chrootUbuntu) {
        await _runChrootSetup();
      } else {
        await _runManagedSetup(chosen);
      }

      _setupStep = 4;
      notifyListeners();
      return true;
    } catch (e) {
      _errorMessage ??= 'Setup failed: $e';
      _isDownloading = false;
      _isExtracting = false;
      _isInstallingDE = false;
      notifyListeners();
      return false;
    }
  }

  Future<void> _runManagedSetup(LinuxEnvironmentKind environment) async {
    _isExtracting = true;
    _extractProgress = 0.0;
    _extractStatus = environment == LinuxEnvironmentKind.prootDebian
        ? 'Preparing Debian compatibility environment...'
        : 'Preparing native Termux environment...';
    _statusMessage = _extractStatus;
    notifyListeners();
    _isInstallingDE = true;
    notifyListeners();
    final installed = await DroidDeskPlatform.provisionEnvironment(
      kind: environment.wireValue,
      de: _selectedDE,
    );
    if (!installed) {
      throw StateError('${environment.wireValue} provisioning failed');
    }
    _isExtracting = false;
    _isInstallingDE = false;

    await refreshStatus();
  }

  Future<void> _runChrootSetup() async {
    _statusMessage = 'Downloading Ubuntu rootfs...';
    _isDownloading = true;
    _downloadProgress = 0.0;
    notifyListeners();
    if (!await DroidDeskPlatform.downloadRootfs(_selectedDistro)) {
      throw StateError(
        'Ubuntu download failed. Check your connection and retry.',
      );
    }

    _isDownloading = false;
    _isExtracting = true;
    _extractProgress = 0.0;
    _statusMessage = 'Extracting rootfs...';
    notifyListeners();
    if (!await DroidDeskPlatform.extractRootfs()) {
      throw StateError('Ubuntu filesystem extraction failed');
    }

    _statusMessage =
        'Installing desktop environment (this may take a while)...';
    _isInstallingDE = true;
    notifyListeners();
    if (!await DroidDeskPlatform.provisionEnvironment(
      kind: LinuxEnvironmentKind.chrootUbuntu.wireValue,
      de: _selectedDE,
    )) {
      throw StateError('Desktop Essentials package installation failed');
    }
    _isExtracting = false;
    _isInstallingDE = false;

    await refreshStatus();
  }

  Future<void> runExtraction() async {
    // Handled inside runSetup for chroot mode.
    // Kept for API compatibility.
    _extractProgress = 1.0;
    _extractStatus = 'Extraction handled by setup flow';
    notifyListeners();
  }

  Future<void> installDesktopEnvironment() async {
    try {
      _isExtracting = true;
      _extractProgress = 0.0;
      _statusMessage = 'Installing Desktop Environment...';
      _errorMessage = null;
      notifyListeners();

      if (_hasRoot) {
        if (!await DroidDeskPlatform.installDesktopEnvironment(_selectedDE)) {
          throw StateError('Desktop Essentials package installation failed');
        }
      } else {
        final installed = await DroidDeskPlatform.provisionEnvironment(
          kind: _selectedEnvironment.wireValue,
          de: _selectedDE,
        );
        if (!installed) {
          throw StateError('Native Termux package installation failed');
        }
      }

      _isExtracting = false;
      _isInstallingDE = false;
      await refreshStatus();
    } catch (e) {
      _errorMessage = 'Installation failed: $e';
      _isExtracting = false;
      _isInstallingDE = false;
      notifyListeners();
    }
  }

  // ── In-app updates ──

  String _appVersion = '';
  AppRelease? _availableUpdate;
  bool _checkingUpdate = false;
  bool _downloadingUpdate = false;
  int _updateDownloaded = 0;
  int _updateTotal = 0;
  String? _updateError;
  String? _updateErrorCode;
  String? _downloadedUpdatePath;
  bool _updatePromptShown = false;

  String get appVersion => _appVersion;
  AppRelease? get availableUpdate => _availableUpdate;
  bool get isCheckingUpdate => _checkingUpdate;
  bool get isDownloadingUpdate => _downloadingUpdate;
  int get updateDownloaded => _updateDownloaded;
  int get updateTotal => _updateTotal;
  double get updateProgress =>
      _updateTotal > 0 ? (_updateDownloaded / _updateTotal).clamp(0.0, 1.0) : 0;
  String? get updateError => _updateError;
  String? get updateErrorCode => _updateErrorCode;
  String? get downloadedUpdatePath => _downloadedUpdatePath;

  /// True once per app session when an automatic check found an update to offer.
  bool takeUpdatePrompt() {
    if (_availableUpdate == null || _updatePromptShown) return false;
    _updatePromptShown = true;
    return true;
  }

  void _onUpdateProgress(int downloaded, int total) {
    _updateDownloaded = downloaded;
    _updateTotal = total;
    notifyListeners();
  }

  Future<String> _installedVersion() async {
    if (_appVersion.isEmpty) {
      try {
        final info = await DroidDeskPlatform.getAppVersion();
        _appVersion = info['versionName']?.toString() ?? '';
      } catch (_) {}
    }
    return _appVersion;
  }

  /// Looks for a newer GitHub release. Automatic checks are throttled and silent;
  /// [manual] checks always run and surface errors in [updateError].
  Future<void> checkForUpdate({bool manual = false}) async {
    if (_checkingUpdate || _downloadingUpdate) return;
    _checkingUpdate = true;
    if (manual) {
      _updateError = null;
      _updateErrorCode = null;
    }
    notifyListeners();
    try {
      final installed = await _installedVersion();
      if (installed.isEmpty) return;
      final release = await UpdateService.check(
        installedVersion: installed,
        manual: manual,
      );
      if (release != null || manual) {
        if (_availableUpdate?.tag != release?.tag) _downloadedUpdatePath = null;
        _availableUpdate = release;
      }
      if (release == null && manual) DroidDeskPlatform.cleanupUpdates();
    } catch (error) {
      if (manual) {
        _updateError = 'Could not reach GitHub. Check your connection.';
        _updateErrorCode = 'network';
      }
    } finally {
      _checkingUpdate = false;
      notifyListeners();
    }
  }

  Future<void> skipUpdate() async {
    final release = _availableUpdate;
    if (release == null) return;
    await UpdateService.skip(release.tag);
    _availableUpdate = null;
    notifyListeners();
  }

  /// Downloads (if needed) and opens the installer. Returns false on error or cancel.
  Future<bool> downloadAndInstallUpdate() async {
    final release = _availableUpdate;
    if (release == null || _downloadingUpdate) return false;
    _updateError = null;
    _updateErrorCode = null;
    var path = _downloadedUpdatePath;
    if (path == null) {
      _downloadingUpdate = true;
      _updateDownloaded = 0;
      _updateTotal = release.apkSize;
      notifyListeners();
      final result = await DroidDeskPlatform.downloadUpdate(
        url: release.apkUrl,
        size: release.apkSize,
        tag: release.tag,
      );
      _downloadingUpdate = false;
      if (result['ok'] != true) {
        _updateErrorCode = result['code']?.toString();
        _updateError = switch (_updateErrorCode) {
          'cancelled' => null,
          'signature_mismatch' =>
            'This release is signed with a different key. Install it once from the release page.',
          'size_mismatch' || 'network' =>
            'The download did not finish. Check your connection and try again.',
          _ =>
            result['message']?.toString() ??
                'The update could not be downloaded.',
        };
        notifyListeners();
        return false;
      }
      path = result['path'].toString();
      _downloadedUpdatePath = path;
    }
    notifyListeners();
    final opened = await DroidDeskPlatform.installUpdate(path);
    if (!opened) {
      _updateError = 'Could not open the installer.';
      _updateErrorCode = 'installer';
      notifyListeners();
    }
    return opened;
  }

  Future<void> cancelUpdateDownload() async {
    if (!_downloadingUpdate) return;
    await DroidDeskPlatform.cancelUpdateDownload();
  }

  // ── Package operations ──
  //
  // Installs and removals from the App Store live here rather than in the screen, so they keep
  // running (and stay visible on Home) when the user navigates away.

  String? _activePackage;
  String _activePackageName = '';
  List<String> _activePackageIconNames = const [];
  bool _packageRemoving = false;
  bool _packageCancelling = false;
  bool _packageCancellable = false;
  bool _packageRequestPending = false;
  double _packageProgress = 0;
  String _packageStatus = '';
  String _packageLog = '';
  int? _activePackageOperationId;
  int _latestPackageOperationId = 0;
  LinuxEnvironmentKind? _activePackageEnvironment;
  PackageOperationKind? _activePackageKind;
  PackageOperationPhase? _activePackagePhase;
  PackageOperationResult? _lastPackageStoreResult;
  PackageOperationPresentationResult? _lastPackageResult;
  int _packageResultId = 0;

  String? get activePackage => _activePackage;
  String get activePackageName => _activePackageName;
  List<String> get activePackageIconNames => _activePackageIconNames;
  bool get packageRemoving => _packageRemoving;
  bool get packageCancelling => _packageCancelling;
  bool get packageCancellable => _packageCancellable;
  double get packageProgress => _packageProgress;
  String get packageStatus => _packageStatus;
  String get packageLog => _packageLog;
  int? get activePackageOperationId => _activePackageOperationId;
  LinuxEnvironmentKind? get activePackageEnvironment =>
      _activePackageEnvironment;
  PackageOperationKind? get activePackageKind => _activePackageKind;
  PackageOperationPhase? get activePackagePhase => _activePackagePhase;
  PackageOperationResult? get lastPackageStoreResult => _lastPackageStoreResult;
  String get packageOperationTitle {
    if (_packageCancelling) return 'Cancelling package operation';
    return switch (_activePackageKind) {
      PackageOperationKind.remove => 'Removing $_activePackageName',
      PackageOperationKind.upgrade => 'Updating $_activePackageName',
      PackageOperationKind.upgradeAll => 'Updating packages',
      PackageOperationKind.cleanCache => 'Cleaning package cache',
      PackageOperationKind.install || null => 'Installing $_activePackageName',
    };
  }

  /// Result of the most recent finished operation; [packageResultId] changes with each one.
  PackageOperationPresentationResult? get lastPackageResult =>
      _lastPackageResult;
  int get packageResultId => _packageResultId;

  void _startPackageOperation(
    String? entryId,
    String displayName,
    List<String> iconNames,
    PackageOperationKind kind,
    LinuxEnvironmentKind environment,
  ) {
    _activePackage = entryId ?? updatesOperation;
    _activePackageName = displayName;
    _activePackageIconNames = iconNames;
    _packageRemoving = kind == PackageOperationKind.remove;
    _packageCancelling = false;
    _packageCancellable = false;
    _packageRequestPending = true;
    _packageProgress = 0;
    _packageStatus = switch (kind) {
      PackageOperationKind.remove => 'Preparing removal...',
      PackageOperationKind.upgrade => 'Preparing update...',
      PackageOperationKind.upgradeAll => 'Preparing package updates...',
      PackageOperationKind.cleanCache => 'Preparing cache cleanup...',
      PackageOperationKind.install => 'Preparing $displayName...',
    };
    _packageLog = '';
    _activePackageOperationId = null;
    _activePackageEnvironment = environment;
    _activePackageKind = kind;
    _activePackagePhase = PackageOperationPhase.preparing;
    notifyListeners();
  }

  void _receivePackageStoreResult(PackageOperationResult result) {
    if (_disposed) return;
    if (result.operationId < _latestPackageOperationId) {
      unawaited(_acknowledgePackageResult(result.operationId));
      return;
    }
    final activeOperationId = _activePackageOperationId;
    if (activeOperationId != null && result.operationId != activeOperationId) {
      return;
    }
    if (activeOperationId == null &&
        _packageRequestPending &&
        result.environmentKind != _activePackageEnvironment) {
      return;
    }
    if (activeOperationId == null &&
        !_packageRequestPending &&
        result.operationId == _latestPackageOperationId) {
      unawaited(_acknowledgePackageResult(result.operationId));
      return;
    }
    final error = result.error;
    if (error != null) _errorMessage = '${error.code}: ${error.message}';
    _finishPackageOperation(
      result,
      _activePackageName.isEmpty
          ? result.entryId ?? 'packages'
          : _activePackageName,
      _activePackageIconNames,
    );
    unawaited(_acknowledgePackageResult(result.operationId));
  }

  Future<void> _acknowledgePackageResult(int operationId) async {
    try {
      await DroidDeskPlatform.acknowledgePackageStoreOperationResult(
        operationId,
      );
    } on PlatformException {
      // The result remains available for replay on the next engine.
    }
  }

  void _receivePackageStoreEvent(PackageOperationEvent event) {
    if (_disposed) return;
    if (_activePackageOperationId == null) {
      if (!_packageRequestPending ||
          event.environmentKind != _activePackageEnvironment ||
          event.operationId <= _latestPackageOperationId) {
        return;
      }
      _activePackageOperationId = event.operationId;
      _latestPackageOperationId = event.operationId;
    }
    if (event.operationId != _activePackageOperationId ||
        event.environmentKind != _activePackageEnvironment) {
      return;
    }
    _activePackagePhase = event.phase;
    if (event.progress != null) _packageProgress = event.progress!;
    _packageStatus = event.message;
    _packageCancellable = event.cancellable;
    final logChunk = event.logChunk;
    if (logChunk != null) {
      _packageLog += logChunk;
      if (_packageLog.length > 12000) {
        _packageLog = _packageLog.substring(_packageLog.length - 12000);
      }
    }
    notifyListeners();
  }

  void _receivePackageStoreError(FormatException error) {
    if (_disposed) return;
    _errorMessage = 'Invalid package store state: $error';
    notifyListeners();
  }

  Future<void> _restorePackageOperation() async {
    try {
      final result = await DroidDeskPlatform.queryPackageStore(
        environment: _activePackageEnvironment ?? _selectedEnvironment,
        query: PackageStoreQueryKind.operationState,
      );
      if (_disposed) return;
      final operation = result.operation;
      if (operation != null) {
        _activePackageOperationId = operation.operationId;
        _latestPackageOperationId =
            operation.operationId > _latestPackageOperationId
            ? operation.operationId
            : _latestPackageOperationId;
        _activePackageEnvironment = operation.environmentKind;
        _activePackageKind = operation.kind;
        _activePackagePhase = operation.phase;
        _activePackage = operation.entryId ?? updatesOperation;
        _activePackageName = operation.entryId ?? 'packages';
        _activePackageIconNames = const [];
        _packageRemoving = operation.kind == PackageOperationKind.remove;
        _packageCancelling = false;
        _packageCancellable = operation.cancellable;
        _packageRequestPending = false;
        _packageProgress = operation.progress ?? 0;
        _packageStatus = operation.message;
        _packageLog = operation.logTail;
        notifyListeners();
      }
      final completedResult = result.completedResult;
      if (completedResult != null) {
        _receivePackageStoreResult(completedResult);
        if (operation != null &&
            completedResult.operationId < operation.operationId) {
          unawaited(_acknowledgePackageResult(completedResult.operationId));
        }
      }
    } on PlatformException catch (error) {
      if (_disposed) return;
      _errorMessage =
          '${error.code}: ${error.message ?? 'Package store state failed'}';
      notifyListeners();
    } on FormatException catch (error) {
      _receivePackageStoreError(error);
    }
  }

  void _resetPackageOperation() {
    _activePackage = null;
    _activePackageOperationId = null;
    _activePackageEnvironment = null;
    _activePackageKind = null;
    _activePackagePhase = null;
    _packageRequestPending = false;
    _packageCancelling = false;
    _packageCancellable = false;
  }

  void _finishPackageOperation(
    PackageOperationResult result,
    String displayName,
    List<String> iconNames,
  ) {
    _latestPackageOperationId = result.operationId > _latestPackageOperationId
        ? result.operationId
        : _latestPackageOperationId;
    _lastPackageStoreResult = result;
    _lastPackageResult = PackageOperationPresentationResult(
      result: result,
      displayName: displayName,
      iconNames: iconNames,
    );
    _resetPackageOperation();
    _packageResultId++;
    notifyListeners();
  }

  Future<PackageOperationResult> installPackageEntry(
    PackageStoreEntry entry, {
    required LinuxEnvironmentKind environment,
    bool acceptExperimentalRisk = false,
  }) => _runPackageStoreOperation(
    kind: PackageOperationKind.install,
    entry: entry,
    environment: environment,
    acceptExperimentalRisk: acceptExperimentalRisk,
  );

  Future<PackageOperationResult> removePackageEntry(
    PackageStoreEntry entry, {
    required LinuxEnvironmentKind environment,
  }) => _runPackageStoreOperation(
    kind: PackageOperationKind.remove,
    entry: entry,
    environment: environment,
  );

  Future<PackageOperationResult> upgradePackageEntry(
    PackageStoreEntry entry, {
    required LinuxEnvironmentKind environment,
  }) => _runPackageStoreOperation(
    kind: PackageOperationKind.upgrade,
    entry: entry,
    environment: environment,
  );

  Future<PackageOperationResult> upgradeAllPackageEntries({
    required LinuxEnvironmentKind environment,
  }) => _runPackageStoreOperation(
    kind: PackageOperationKind.upgradeAll,
    environment: environment,
  );

  Future<PackageOperationResult> cleanSelectedPackageCache({
    required LinuxEnvironmentKind environment,
  }) => _runPackageStoreOperation(
    kind: PackageOperationKind.cleanCache,
    environment: environment,
  );

  Future<PackageOperationResult> _runPackageStoreOperation({
    required PackageOperationKind kind,
    required LinuxEnvironmentKind environment,
    PackageStoreEntry? entry,
    bool acceptExperimentalRisk = false,
  }) async {
    if (_activePackageOperationId != null || _packageRequestPending) {
      return _localPackageFailure(
        kind,
        entry?.entryId,
        'OPERATION_IN_PROGRESS',
        'Another package operation is already running',
        PackageOperationOutcome.rejected,
      );
    }
    final displayName = entry?.displayName ?? 'packages';
    final iconNames = entry?.iconNames ?? const <String>[];
    _startPackageOperation(
      entry?.entryId,
      displayName,
      iconNames,
      kind,
      environment,
    );
    try {
      final result = await DroidDeskPlatform.runPackageStoreOperation(
        environment: environment,
        operation: kind,
        entryId: entry?.entryId,
        acceptExperimentalRisk: acceptExperimentalRisk,
      );
      if (_disposed) return result;
      final activeOperationId = _activePackageOperationId;
      if (activeOperationId == null) {
        if (result.operationId <= _latestPackageOperationId) return result;
        _activePackageOperationId = result.operationId;
      } else if (activeOperationId != result.operationId) {
        final mismatch = _localPackageFailure(
          kind,
          entry?.entryId,
          'OPERATION_ID_MISMATCH',
          'Package operation result did not match the active operation',
          PackageOperationOutcome.failed,
          operationId: result.operationId,
          environment: environment,
        );
        _errorMessage = '${mismatch.error!.code}: ${mismatch.error!.message}';
        _finishPackageOperation(mismatch, displayName, iconNames);
        return mismatch;
      }
      final error = result.error;
      if (error != null) {
        _errorMessage = '${error.code}: ${error.message}';
      }
      _finishPackageOperation(result, displayName, iconNames);
      if (error?.code == 'TARGET_CHANGED') await refreshStatus();
      return result;
    } on PlatformException catch (error) {
      final result = _localPackageFailure(
        kind,
        entry?.entryId,
        error.code,
        error.message ?? 'Package operation failed',
        PackageOperationOutcome.failed,
      );
      _errorMessage =
          '${error.code}: ${error.message ?? 'Package operation failed'}';
      _finishPackageOperation(result, displayName, iconNames);
      return result;
    } on FormatException catch (error) {
      final result = _localPackageFailure(
        kind,
        entry?.entryId,
        'INVALID_NATIVE_STATE',
        error.toString(),
        PackageOperationOutcome.failed,
      );
      _receivePackageStoreError(error);
      _finishPackageOperation(result, displayName, iconNames);
      return result;
    }
  }

  PackageOperationResult _localPackageFailure(
    PackageOperationKind kind,
    String? entryId,
    String code,
    String message,
    PackageOperationOutcome outcome, {
    int? operationId,
    LinuxEnvironmentKind? environment,
  }) => PackageOperationResult(
    schemaVersion: 1,
    operationId: operationId ?? _activePackageOperationId ?? 1,
    environmentKind:
        environment ?? _activePackageEnvironment ?? _selectedEnvironment,
    kind: kind,
    entryId: entryId,
    outcome: outcome,
    message: message,
    entry: null,
    bytesFreed: null,
    warnings: const [],
    error: PackageStoreError(
      code: code,
      message: message,
      logTail: _packageLog,
    ),
  );

  /// Temporary compatibility wrapper for the existing App Store screen.
  Future<bool> installPackage(
    String package, {
    String? displayName,
    List<String> iconNames = const [],
  }) async {
    final result = await installPackageEntry(
      _legacyPackageEntry(package, displayName, iconNames),
      environment: _selectedEnvironment,
    );
    return result.outcome == PackageOperationOutcome.succeeded;
  }

  Future<bool> removePackage(
    String package, {
    String? displayName,
    List<String> iconNames = const [],
  }) async {
    final result = await removePackageEntry(
      _legacyPackageEntry(package, displayName, iconNames),
      environment: _selectedEnvironment,
    );
    return result.outcome == PackageOperationOutcome.succeeded;
  }

  /// Package id used for the "update everything" operation.
  static const updatesOperation = '__updates__';

  bool get isUpdatingAll =>
      _activePackageKind == PackageOperationKind.upgradeAll;

  /// Upgrades all non-core packages through the same progress UI as installs.
  Future<bool> updateAllPackages() async {
    final result = await upgradeAllPackageEntries(
      environment: _selectedEnvironment,
    );
    return result.outcome == PackageOperationOutcome.succeeded;
  }

  Future<void> cancelPackageOperation() async {
    final operationId = _activePackageOperationId;
    if (operationId == null || _packageCancelling || !_packageCancellable) {
      return;
    }
    _packageCancelling = true;
    _packageStatus = 'Cancelling package operation...';
    notifyListeners();
    await DroidDeskPlatform.cancelPackageStoreOperation(operationId);
  }

  PackageStoreEntry _legacyPackageEntry(
    String package,
    String? displayName,
    List<String> iconNames,
  ) {
    final entryId = switch (package) {
      'firefox' => 'app.firefox',
      'code-oss' || 'code' => 'app.code-oss',
      'libreoffice' => 'app.libreoffice',
      'gimp' => 'app.gimp',
      'blender' => 'app.blender',
      'vlc' => 'app.vlc',
      'nodejs' => 'tool.nodejs',
      'python' => 'tool.python',
      'imagemagick' => 'tool.imagemagick',
      _ => 'repo.$package',
    };
    return PackageStoreEntry(
      entryId: entryId,
      displayName: displayName ?? package,
      summary: 'Linux package',
      category: 'System',
      iconNames: iconNames,
      gui: iconNames.isNotEmpty,
      supportLevel: entryId.startsWith('repo.')
          ? PackageSupportLevel.repository
          : PackageSupportLevel.supported,
      state: PackageEntryState.available,
      availableVersion: null,
      installedVersion: null,
      removable: true,
      limitations: const [],
      sourceLabel: _selectedEnvironment == LinuxEnvironmentKind.prootDebian
          ? 'Debian Bookworm'
          : 'Termux repositories',
    );
  }

  /// Hides the "finished" card on Home.
  void dismissPackageResult() {
    if (_lastPackageResult == null) return;
    _lastPackageResult = null;
    notifyListeners();
  }

  // ── Session Control ──

  Future<void> startLinux({String mode = 'x11'}) async {
    if (_disposed) return;
    if (desktopDisplayBusy) {
      _recordDisplayError(StateError('Another desktop action is in progress'));
      return;
    }
    final operation = ++_sessionControlRevision;
    final revision = _desktopEventRevision;
    _sessionControlBusy = true;
    notifyListeners();
    try {
      _errorMessage = null;
      final started = await DroidDeskPlatform.startLinux(
        de: _selectedDE,
        mode: mode,
      );
      if (!started) {
        throw StateError('Linux runtime is not ready');
      }
      if (_disposed || operation != _sessionControlRevision) return;
      if (revision == _desktopEventRevision) _isRunning = true;
      notifyListeners();
    } catch (e) {
      if (_disposed || operation != _sessionControlRevision) return;
      _errorMessage = 'Failed to start: $e';
      notifyListeners();
    } finally {
      if (!_disposed && operation == _sessionControlRevision) {
        _sessionControlBusy = false;
        notifyListeners();
      }
    }
  }

  Future<void> launchDesktopActivity() async {
    if (_disposed) return;
    if (desktopDisplayBusy) {
      _recordDisplayError(StateError('Another desktop action is in progress'));
      return;
    }
    final operation = ++_sessionControlRevision;
    _sessionControlBusy = true;
    notifyListeners();
    try {
      await DroidDeskPlatform.launchDesktopActivity();
    } catch (e) {
      if (_disposed || operation != _sessionControlRevision) return;
      _errorMessage = 'Failed to launch desktop activity: $e';
      notifyListeners();
    } finally {
      if (!_disposed && operation == _sessionControlRevision) {
        _sessionControlBusy = false;
        notifyListeners();
      }
    }
  }

  Future<void> stopLinux() async {
    if (_disposed) return;
    final operation = ++_sessionControlRevision;
    final revision = _desktopEventRevision;
    _sessionControlBusy = true;
    notifyListeners();
    try {
      await DroidDeskPlatform.stopLinux();
      if (_disposed || operation != _sessionControlRevision) return;
      if (revision == _desktopEventRevision) _isRunning = false;
      notifyListeners();
    } catch (e) {
      if (_disposed || operation != _sessionControlRevision) return;
      _errorMessage = 'Failed to stop: $e';
      notifyListeners();
    } finally {
      if (!_disposed && operation == _sessionControlRevision) {
        _sessionControlBusy = false;
        notifyListeners();
        await refreshDesktopDisplays();
      }
    }
  }

  Future<String> executeCommand(String command) async {
    final isInput = isCommandRunning;
    if (!isInput) _runningCommands++;
    try {
      _terminalOutput.add(isInput ? '$command\n' : '\$ $command\n');
      notifyListeners();
      return await DroidDeskPlatform.executeCommand(command, stdin: isInput);
    } catch (e) {
      return "Error executing command: $e";
    } finally {
      if (!isInput) {
        _runningCommands--;
        notifyListeners();
      }
    }
  }

  void appendTerminalOutput(String output) {
    if (_terminalOutput.isEmpty) _terminalOutput.add('');
    _terminalOutput[_terminalOutput.length - 1] += output;
    notifyListeners();
  }

  void clearTerminal() {
    _terminalOutput.clear();
    _terminalOutput.add('DroidDesk Linux Terminal\nType commands below.\n');
    notifyListeners();
  }

  Future<void> interruptCommand() async {
    try {
      await DroidDeskPlatform.interruptCommand();
    } catch (e) {
      debugPrint("Error interrupting command: $e");
    }
  }

  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }
}

/// Outcome of a finished App Store install or removal.
class PackageOperationPresentationResult {
  final PackageOperationResult result;
  final String displayName;
  final List<String> iconNames;

  const PackageOperationPresentationResult({
    required this.result,
    required this.displayName,
    required this.iconNames,
  });

  String get package => result.entryId ?? AppState.updatesOperation;
  bool get removed => result.kind == PackageOperationKind.remove;
  bool get cancelled => result.outcome == PackageOperationOutcome.cancelled;
  bool get ok => result.outcome == PackageOperationOutcome.succeeded;
  bool get isUpdateAll => result.kind == PackageOperationKind.upgradeAll;

  String get message => switch (result.kind) {
    PackageOperationKind.install => cancelled
        ? '$displayName installation cancelled'
        : ok
        ? '$displayName installed'
        : '$displayName installation failed',
    PackageOperationKind.remove => cancelled
        ? '$displayName removal cancelled'
        : ok
        ? '$displayName removed'
        : 'Could not remove $displayName',
    PackageOperationKind.upgrade => cancelled
        ? '$displayName update cancelled'
        : ok
        ? '$displayName updated'
        : 'Could not update $displayName',
    PackageOperationKind.upgradeAll => cancelled
        ? 'Update cancelled'
        : ok
        ? 'Packages updated'
        : 'Some updates failed',
    PackageOperationKind.cleanCache => cancelled
        ? 'Cache cleanup cancelled'
        : ok
        ? 'Package cache cleaned'
        : 'Could not clean package cache',
  };

  String get successDetail => switch (result.kind) {
    PackageOperationKind.install => 'Find it in the desktop app menu',
    PackageOperationKind.remove => 'The application was removed',
    PackageOperationKind.upgrade => 'The installed application is up to date',
    PackageOperationKind.upgradeAll => 'Installed applications are up to date',
    PackageOperationKind.cleanCache => 'Downloaded package files were removed',
  };
}
