import 'package:droiddesk/models/linux_environment.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('decodes all supported Linux environment kinds', () {
    for (final entry in <String, LinuxEnvironmentKind>{
      'termuxNative': LinuxEnvironmentKind.termuxNative,
      'prootDebian': LinuxEnvironmentKind.prootDebian,
      'chrootUbuntu': LinuxEnvironmentKind.chrootUbuntu,
    }.entries) {
      final status = LinuxEnvironmentStatus.fromMap(
        environmentStatusPayload(kind: entry.key),
      );
      expect(status.kind, entry.value);
      expect(status.capabilities.prootCandidate, isTrue);
      expect(status.capabilities.distribution.targetSdk, 28);
    }
  });

  test('rejects missing schema and unknown environment kind', () {
    expect(
      () => LinuxEnvironmentStatus.fromMap(
        environmentStatusPayload()..remove('schemaVersion'),
      ),
      throwsFormatException,
    );
    expect(
      () => LinuxEnvironmentStatus.fromMap(
        environmentStatusPayload()..['environmentKind'] = 'mystery',
      ),
      throwsFormatException,
    );
  });

  test('rejects malformed capability evidence', () {
    final payload = environmentStatusPayload();
    final capabilities = Map<Object?, Object?>.from(
      payload['capabilities']! as Map,
    );
    capabilities['proot'] = {
      'state': 'probably',
      'reason': null,
    };
    payload['capabilities'] = capabilities;

    expect(
      () => LinuxEnvironmentStatus.fromMap(payload),
      throwsFormatException,
    );
  });

  test('rejects negative sizes and contradictory running provisioning state', () {
    expect(
      () => LinuxEnvironmentStatus.fromMap(
        environmentStatusPayload()..['rootfsSizeBytes'] = -1,
      ),
      throwsFormatException,
    );
    expect(
      () => LinuxEnvironmentStatus.fromMap(
        environmentStatusPayload()
          ..['running'] = true
          ..['provisioning'] = true,
      ),
      throwsFormatException,
    );
  });
}

Map<Object?, Object?> environmentStatusPayload({
  String kind = 'prootDebian',
  bool installed = false,
  bool running = false,
  bool provisioning = false,
  bool repairNeeded = false,
  bool prootCandidate = true,
  String prootState = 'SUPPORTED_AND_PROBED',
  int availableBytes = 10 * 1024 * 1024 * 1024,
  int requiredBytes = 4 * 1024 * 1024 * 1024,
  String avfFeatureState = 'UNAVAILABLE',
  String avfPermissionState = 'UNAVAILABLE',
}) => {
  'schemaVersion': 1,
  'environmentKind': kind,
  'installed': installed,
  'isBootstrapped': installed,
  'running': running,
  'isRunning': running,
  'provisioning': provisioning,
  'repairNeeded': repairNeeded,
  'hasRoot': kind == 'chrootUbuntu',
  'distro': switch (kind) {
    'termuxNative' => 'termux-native',
    'chrootUbuntu' => 'ubuntu-chroot',
    _ => 'debian-bookworm',
  },
  'installedDE': installed ? 'xfce4' : '',
  'rootfsPath': installed ? '/rootfs' : '',
  'rootfsSizeBytes': installed ? 1024 : 0,
  'rootfsSizeMB': 0,
  'lastError': null,
  'capabilities': capabilityPayload(
    prootCandidate: prootCandidate,
    prootState: prootState,
    availableBytes: availableBytes,
    requiredBytes: requiredBytes,
    avfFeatureState: avfFeatureState,
    avfPermissionState: avfPermissionState,
  ),
};

Map<Object?, Object?> capabilityPayload({
  bool prootCandidate = true,
  String prootState = 'SUPPORTED_AND_PROBED',
  int availableBytes = 10 * 1024 * 1024 * 1024,
  int requiredBytes = 4 * 1024 * 1024 * 1024,
  String avfFeatureState = 'UNAVAILABLE',
  String avfPermissionState = 'UNAVAILABLE',
}) {
  Map<Object?, Object?> evidence(String state, [String? reason]) => {
    'state': state,
    'reason': reason,
  };

  return {
    'schemaVersion': 1,
    'hostApiLevel': 36,
    'abi': 'arm64-v8a',
    'host': evidence('SUPPORTED_AND_PROBED'),
    'localExecution': evidence('SUPPORTED_AND_PROBED'),
    'proot': evidence(prootState, prootState == 'FAILED_SELF_TEST' ? 'ptrace failed' : null),
    'x11': evidence('SUPPORTED_AND_PROBED'),
    'audio': evidence('SUPPORTED_AND_PROBED'),
    'clipboard': evidence('PRESENT_UNVERIFIED', 'Foreground only'),
    'chroot': evidence('UNAVAILABLE'),
    'avfFeature': evidence(avfFeatureState),
    'avfPermission': evidence(
      avfPermissionState,
      avfPermissionState == 'BLOCKED_BY_PERMISSION' ? 'Platform permission required' : null,
    ),
    'renderer': evidence('SUPPORTED_AND_PROBED'),
    'rendererBackend': 'softwareLlvmPipe',
    'storage': evidence(
      availableBytes >= requiredBytes ? 'SUPPORTED_AND_PROBED' : 'BLOCKED_BY_POLICY',
      availableBytes >= requiredBytes ? null : 'Insufficient transaction storage',
    ),
    'availableBytes': availableBytes,
    'requiredBytes': requiredBytes,
    'prootCandidate': prootCandidate,
    'avfAdapterAvailable': false,
    'hardwareAccelerationVerified': false,
    'distribution': {
      'channel': 'sideloadLocalLinux',
      'targetSdk': 28,
      'requiresAppPrivateElfExecution': true,
    },
  };
}
