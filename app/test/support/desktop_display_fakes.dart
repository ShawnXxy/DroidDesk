import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:droiddesk/services/platform_bridge.dart';

Map<Object?, Object?> desktopSnapshot({
  String phase = 'attached',
  int? activeId = 2,
  String? profile = 'monitor',
  int? width = 3840,
  int? height = 2160,
  bool secondMonitor = true,
  bool autoOpenOnMonitor = true,
}) => {
  'schemaVersion': 1,
  'autoOpenOnMonitor': autoOpenOnMonitor,
  'displayLocalMode': false,
  'crossDisplayMovesAllowed': true,
  'sessionRunning': phase != 'stopped',
  'sessionRestarting': false,
  'sessionEnvironment': activeId == null
      ? null
      : activeId == 0
      ? 'phone'
      : 'otherExternal',
  'sessionDisplayId': activeId,
  'sessionHeadless': false,
  'phase': phase,
  'activeDisplayId': activeId,
  'activeDisplayKey': activeId == null ? null : displayKey(activeId),
  'profileKind': activeId == null ? null : profile,
  'renderWidth': width,
  'renderHeight': height,
  'displays': [
    displayInfo(0, 'Phone', true),
    displayInfo(2, 'Monitor', false),
    if (secondMonitor) displayInfo(3, 'Monitor', false),
  ],
  'error': null,
};

Map<Object?, Object?> runtimeCapabilitiesMap({
  String rendererBackend = 'softwareLlvmPipe',
  bool hardwareAccelerationVerified = false,
}) => {
  'schemaVersion': 1,
  'hostApiLevel': 36,
  'abi': 'arm64-v8a',
  for (final key in [
    'host',
    'localExecution',
    'proot',
    'x11',
    'audio',
    'storage',
    'renderer',
  ])
    key: {'state': 'SUPPORTED_AND_PROBED', 'reason': null},
  'clipboard': {'state': 'PRESENT_UNVERIFIED', 'reason': 'Foreground only'},
  'chroot': {'state': 'UNAVAILABLE', 'reason': null},
  'avfFeature': {'state': 'UNAVAILABLE', 'reason': null},
  'avfPermission': {'state': 'UNAVAILABLE', 'reason': null},
  'rendererBackend': rendererBackend,
  'availableBytes': 10 * 1024 * 1024 * 1024,
  'requiredBytes': 4 * 1024 * 1024 * 1024,
  'prootCandidate': true,
  'avfAdapterAvailable': false,
  'hardwareAccelerationVerified': hardwareAccelerationVerified,
  'distribution': {
    'channel': 'sideloadLocalLinux',
    'targetSdk': 28,
    'requiresAppPrivateElfExecution': true,
  },
};

Map<Object?, Object?> desktopSnapshotV2({
  String phase = 'attached',
  int? activeId = 2,
  String? profile = 'monitor',
  int? width = 3840,
  int? height = 2160,
  bool secondMonitor = true,
  bool autoOpenOnMonitor = true,
  String placementMode = 'movable',
  String placementSelectionSource = 'runtimeEvidence',
  String placementReason = 'Runtime evidence permits placement across displays',
  bool? automaticMovesAllowed,
  String? placementOverride,
  String? sessionEnvironment,
  String? presentationClass = 'externalPointer',
  String? stableDisplayKey = 'external:3:DEL|4:4112|11:Dell U2723Q',
  Map<Object?, Object?>? inputCapabilities = const {
    'touchEvidence': 'none',
    'pointerAssociation': 'targetDisplay',
    'hasMouse': true,
    'hasTouchpad': false,
    'hasKeyboard': true,
    'hasStylus': false,
  },
  String? externalInputRoute = 'native',
  bool? rendererPointerCaptureRequested = true,
  bool? relayPointerCaptureRequested = false,
  Object? refreshPreferredRate = 60.0,
  int? refreshPreferredModeId = 4,
  String? refreshReason = 'selected exact-resolution mode 4',
  Map<Object?, Object?>? runtimeCapabilities,
}) {
  final isLocal = placementMode == 'displayLocal';
  final effectiveAutoMoves =
      automaticMovesAllowed ??
      (!isLocal &&
          placementSelectionSource != 'safeDefault' &&
          placementSelectionSource != 'deviceQuirk');
  return {
    ...desktopSnapshot(
      phase: phase,
      activeId: activeId,
      profile: profile,
      width: width,
      height: height,
      secondMonitor: secondMonitor,
      autoOpenOnMonitor: autoOpenOnMonitor,
    ),
    'schemaVersion': 2,
    'displayLocalMode': isLocal,
    'crossDisplayMovesAllowed': !isLocal,
    'sessionEnvironment': ?sessionEnvironment,
    'placementMode': placementMode,
    'placementSelectionSource': placementSelectionSource,
    'placementReason': placementReason,
    'automaticMovesAllowed': effectiveAutoMoves,
    'placementOverride': placementOverride,
    'presentationClass': presentationClass,
    'stableDisplayKey': stableDisplayKey,
    'inputCapabilities': inputCapabilities,
    'externalInputRoute': externalInputRoute,
    'rendererPointerCaptureRequested': rendererPointerCaptureRequested,
    'relayPointerCaptureRequested': relayPointerCaptureRequested,
    'refreshPreferredRate': refreshPreferredRate,
    'refreshPreferredModeId': refreshPreferredModeId,
    'refreshReason': refreshReason,
    'runtimeCapabilities': runtimeCapabilities ?? runtimeCapabilitiesMap(),
  };
}

String displayKey(int id) => id == 0 ? 'phone-key' : 'monitor-$id';

Map<Object?, Object?> displayInfo(int id, String name, bool isDefault) => {
  'id': id,
  'key': displayKey(id),
  'name': name,
  'isDefault': isDefault,
  'eligible': true,
  'reason': null,
};

class FakeDesktopPlatform {
  static const channel = MethodChannel('com.droiddesk/core');
  Map<Object?, Object?> snapshot = desktopSnapshot();
  final calls = <MethodCall>[];
  bool running = true;
  Map<Object?, Object?> _runtimeStatus = {
    'schemaVersion': 1,
    'environmentKind': 'termuxNative',
    'installed': true,
    'isBootstrapped': true,
    'running': true,
    'isRunning': true,
    'provisioning': false,
    'repairNeeded': false,
    'hasRoot': false,
    'distro': 'termux-native',
    'installedDE': 'xfce4',
    'rootfsPath': '',
    'rootfsSizeBytes': 0,
    'rootfsSizeMB': 0,
    'lastError': null,
    'capabilities': {
      'schemaVersion': 1,
      'hostApiLevel': 36,
      'abi': 'arm64-v8a',
      for (final key in [
        'host',
        'localExecution',
        'proot',
        'x11',
        'audio',
        'storage',
        'renderer',
      ])
        key: {'state': 'SUPPORTED_AND_PROBED', 'reason': null},
      'clipboard': {'state': 'PRESENT_UNVERIFIED', 'reason': 'Foreground only'},
      'chroot': {'state': 'UNAVAILABLE', 'reason': null},
      'avfFeature': {'state': 'UNAVAILABLE', 'reason': null},
      'avfPermission': {'state': 'UNAVAILABLE', 'reason': null},
      'rendererBackend': 'softwareLlvmPipe',
      'availableBytes': 10 * 1024 * 1024 * 1024,
      'requiredBytes': 4 * 1024 * 1024 * 1024,
      'prootCandidate': true,
      'avfAdapterAvailable': false,
      'hardwareAccelerationVerified': false,
      'distribution': {
        'channel': 'sideloadLocalLinux',
        'targetSdk': 28,
        'requiresAppPrivateElfExecution': true,
      },
    },
  };
  Map<Object?, Object?> get runtimeStatus => _runtimeStatus;
  set runtimeStatus(Map<Object?, Object?> value) {
    _runtimeStatus = value;
    running = value['running'] == true;
  }

  PlatformException? openError;
  PlatformException? autoOpenError;
  PlatformException? placementOverrideError;
  bool hasRoot = false;
  bool provisionSuccess = true;
  String? provisionFailureMessage;
  Completer<Map<Object?, Object?>>? displayReply;
  Completer<Map<Object?, Object?>>? openReply;
  Completer<Map<Object?, Object?>>? autoOpenReply;
  Completer<Map<Object?, Object?>>? settingsReply;
  Completer<bool>? startReply;
  final settings = <String, Map<Object?, Object?>>{};

  void install() {
    DroidDeskPlatform.init();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          switch (call.method) {
            case 'getDesktopDisplays':
              return displayReply == null
                  ? snapshot
                  : await displayReply!.future;
            case 'setAutoOpenOnMonitor':
              if (autoOpenError != null) throw autoOpenError!;
              final arguments = call.arguments as Map<Object?, Object?>;
              snapshot = {
                ...snapshot,
                'autoOpenOnMonitor': arguments['enabled'],
              };
              if (autoOpenReply != null) return await autoOpenReply!.future;
              return snapshot;
            case 'setDesktopPlacementOverride':
              if (placementOverrideError != null) {
                throw placementOverrideError!;
              }
              if (snapshot['sessionRunning'] == true) {
                throw PlatformException(
                  code: 'INVALID_ARGUMENT',
                  message: 'Stop Linux before changing desktop placement mode',
                );
              }
              final arguments = call.arguments as Map<Object?, Object?>;
              final mode = arguments['placementMode'] as String?;
              final resolvedMode = mode ?? 'movable';
              final isLocal = resolvedMode == 'displayLocal';
              snapshot = {
                ...snapshot,
                'displayLocalMode': isLocal,
                'crossDisplayMovesAllowed': !isLocal,
                'placementMode': resolvedMode,
                'placementSelectionSource': mode == null
                    ? 'runtimeEvidence'
                    : 'userOverride',
                'placementReason': mode == null
                    ? 'Runtime evidence permits placement across displays'
                    : 'The user selected $mode placement',
                'automaticMovesAllowed': !isLocal,
                'placementOverride': mode,
              };
              return snapshot;
            case 'getDesktopDisplaySettings':
              final arguments = call.arguments as Map<Object?, Object?>;
              final key = arguments['displayKey'] as String;
              if (settingsReply != null) return await settingsReply!.future;
              return settings[key] ??
                  {
                    'displayKey': key,
                    'presentationClass': key == 'phone-key'
                        ? 'phoneTouch'
                        : 'externalPointer',
                    'stableDisplayKey': key == 'phone-key'
                        ? 'default'
                        : 'external:0:|0:|7:Monitor|$key',
                    'settingsScope': 'thisDisplay',
                    'renderPolicy': key == 'phone-key'
                        ? 'legacyPhone'
                        : 'native',
                    'uiScalePercent': key == 'phone-key' ? null : 200,
                    'inputMode': key == 'phone-key' ? 3 : 1,
                    'inputRoute': 'automatic',
                  };
            case 'saveDesktopDisplaySettings':
              final arguments = Map<Object?, Object?>.from(
                call.arguments as Map,
              );
              final stableKey = arguments['stableDisplayKey'] as String?;
              if (arguments['settingsScope'] == 'thisDisplay' &&
                  (stableKey == null || stableKey.trim().isEmpty)) {
                throw PlatformException(
                  code: 'INVALID_ARGUMENT',
                  message:
                      'Exact display settings require a stable display key',
                );
              }
              settings[arguments['displayKey'] as String] = arguments;
              return snapshot;
            case 'openDesktopOnDisplay':
              if (openError != null) throw openError!;
              return openReply == null ? snapshot : await openReply!.future;
            case 'moveDesktopToPhone':
              snapshot = desktopSnapshot(
                activeId: 0,
                profile: 'phone',
                width: 720,
                height: 1600,
              );
              return snapshot;
            case 'getRuntimeStatus':
              return runtimeStatus;
            case 'getDeviceInfo':
              return {
                'model': 'Test phone',
                'androidVersion': '14',
                'gpuVendor': 'Software',
                'totalRamMB': 4096,
              };
            case 'getAppVersion':
              throw PlatformException(code: 'TEST_UPDATE_DISABLED');
            case 'checkRoot':
              return hasRoot;
            case 'downloadRootfs':
            case 'extractRootfs':
              return true;
            case 'updateStatusBarTheme':
              return null;
            case 'startLinux':
              if (startReply != null) return await startReply!.future;
              return true;
            case 'stopLinux':
              running = false;
              _runtimeStatus = {
                ..._runtimeStatus,
                'running': false,
                'isRunning': false,
              };
              snapshot = desktopSnapshot(
                phase: 'stopped',
                activeId: null,
                width: null,
                height: null,
              );
              return true;
            case 'provisionEnvironment':
              if (!provisionSuccess) {
                final failureMessage = provisionFailureMessage;
                if (failureMessage != null) {
                  DroidDeskPlatform.onInstallProgress?.call(-1, failureMessage);
                }
                return false;
              }
              final arguments = call.arguments as Map<Object?, Object?>;
              final kind = arguments['kind'] as String;
              _runtimeStatus = {
                ..._runtimeStatus,
                'environmentKind': kind,
                'installed': true,
                'isBootstrapped': true,
                'running': false,
                'isRunning': false,
                'distro': kind == 'prootDebian'
                    ? 'debian-bookworm'
                    : kind == 'chrootUbuntu'
                    ? 'ubuntu-chroot'
                    : 'termux-native',
                'installedDE': 'xfce4',
              };
              return true;
            default:
              return handleAdditionalCall(call);
          }
        });
  }

  Future<Object?> handleAdditionalCall(MethodCall call) async {
    throw PlatformException(
      code: 'UNEXPECTED_TEST_METHOD',
      message: call.method,
    );
  }

  void uninstall() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    DroidDeskPlatform.onDesktopDisplayStateChanged = null;
    DroidDeskPlatform.onDesktopDisplayError = null;
    DroidDeskPlatform.onDownloadProgress = null;
    DroidDeskPlatform.onExtractProgress = null;
    DroidDeskPlatform.onInstallProgress = null;
    DroidDeskPlatform.onTerminalOutput = null;
  }
}
