enum LinuxEnvironmentKind {
  termuxNative('termuxNative'),
  prootDebian('prootDebian'),
  chrootUbuntu('chrootUbuntu');

  const LinuxEnvironmentKind(this.wireValue);
  final String wireValue;

  static LinuxEnvironmentKind parse(Object? value) {
    if (value is! String) {
      throw const FormatException('environmentKind must be a string');
    }
    return values.firstWhere(
      (kind) => kind.wireValue == value,
      orElse: () => throw FormatException('Unknown Linux environment: $value'),
    );
  }
}

enum CapabilityEvidenceState {
  supportedAndProbed('SUPPORTED_AND_PROBED'),
  presentUnverified('PRESENT_UNVERIFIED'),
  unavailable('UNAVAILABLE'),
  blockedByPermission('BLOCKED_BY_PERMISSION'),
  blockedByPolicy('BLOCKED_BY_POLICY'),
  failedSelfTest('FAILED_SELF_TEST');

  const CapabilityEvidenceState(this.wireValue);
  final String wireValue;

  static CapabilityEvidenceState parse(Object? value) {
    if (value is! String) {
      throw const FormatException('Capability state must be a string');
    }
    return values.firstWhere(
      (state) => state.wireValue == value,
      orElse: () => throw FormatException('Unknown capability state: $value'),
    );
  }

  bool get blocksProvisioning => switch (this) {
    blockedByPermission || blockedByPolicy || failedSelfTest || unavailable => true,
    _ => false,
  };
}

class CapabilityEvidence {
  const CapabilityEvidence(this.state, this.reason);

  final CapabilityEvidenceState state;
  final String? reason;

  factory CapabilityEvidence.fromMap(Object? value, String name) {
    final map = _strictMap(value, name);
    final reason = map['reason'];
    if (reason != null && reason is! String) {
      throw FormatException('$name.reason must be a string or null');
    }
    return CapabilityEvidence(
      CapabilityEvidenceState.parse(map['state']),
      reason as String?,
    );
  }
}

class LinuxDistributionContract {
  const LinuxDistributionContract({
    required this.channel,
    required this.targetSdk,
    required this.requiresAppPrivateElfExecution,
  });

  final String channel;
  final int targetSdk;
  final bool requiresAppPrivateElfExecution;

  factory LinuxDistributionContract.fromMap(Object? value) {
    final map = _strictMap(value, 'distribution');
    return LinuxDistributionContract(
      channel: _requiredString(map, 'channel'),
      targetSdk: _nonNegativeInt(map, 'targetSdk'),
      requiresAppPrivateElfExecution: _requiredBool(
        map,
        'requiresAppPrivateElfExecution',
      ),
    );
  }
}

class LinuxRuntimeCapabilities {
  const LinuxRuntimeCapabilities({
    required this.hostApiLevel,
    required this.abi,
    required this.host,
    required this.localExecution,
    required this.proot,
    required this.x11,
    required this.audio,
    required this.clipboard,
    required this.chroot,
    required this.avfFeature,
    required this.avfPermission,
    required this.renderer,
    required this.rendererBackend,
    required this.storage,
    required this.availableBytes,
    required this.requiredBytes,
    required this.prootCandidate,
    required this.avfAdapterAvailable,
    required this.hardwareAccelerationVerified,
    required this.distribution,
  });

  final int hostApiLevel;
  final String abi;
  final CapabilityEvidence host;
  final CapabilityEvidence localExecution;
  final CapabilityEvidence proot;
  final CapabilityEvidence x11;
  final CapabilityEvidence audio;
  final CapabilityEvidence clipboard;
  final CapabilityEvidence chroot;
  final CapabilityEvidence avfFeature;
  final CapabilityEvidence avfPermission;
  final CapabilityEvidence renderer;
  final String rendererBackend;
  final CapabilityEvidence storage;
  final int availableBytes;
  final int requiredBytes;
  final bool prootCandidate;
  final bool avfAdapterAvailable;
  final bool hardwareAccelerationVerified;
  final LinuxDistributionContract distribution;

  factory LinuxRuntimeCapabilities.fromMap(Object? value) {
    final map = _strictMap(value, 'capabilities');
    if (map['schemaVersion'] != 1) {
      throw const FormatException('Unsupported capability schema');
    }
    return LinuxRuntimeCapabilities(
      hostApiLevel: _nonNegativeInt(map, 'hostApiLevel'),
      abi: _requiredString(map, 'abi'),
      host: CapabilityEvidence.fromMap(map['host'], 'host'),
      localExecution: CapabilityEvidence.fromMap(
        map['localExecution'],
        'localExecution',
      ),
      proot: CapabilityEvidence.fromMap(map['proot'], 'proot'),
      x11: CapabilityEvidence.fromMap(map['x11'], 'x11'),
      audio: CapabilityEvidence.fromMap(map['audio'], 'audio'),
      clipboard: CapabilityEvidence.fromMap(map['clipboard'], 'clipboard'),
      chroot: CapabilityEvidence.fromMap(map['chroot'], 'chroot'),
      avfFeature: CapabilityEvidence.fromMap(map['avfFeature'], 'avfFeature'),
      avfPermission: CapabilityEvidence.fromMap(
        map['avfPermission'],
        'avfPermission',
      ),
      renderer: CapabilityEvidence.fromMap(map['renderer'], 'renderer'),
      rendererBackend: _requiredString(map, 'rendererBackend'),
      storage: CapabilityEvidence.fromMap(map['storage'], 'storage'),
      availableBytes: _nonNegativeInt(map, 'availableBytes'),
      requiredBytes: _nonNegativeInt(map, 'requiredBytes'),
      prootCandidate: _requiredBool(map, 'prootCandidate'),
      avfAdapterAvailable: _requiredBool(map, 'avfAdapterAvailable'),
      hardwareAccelerationVerified: _requiredBool(
        map,
        'hardwareAccelerationVerified',
      ),
      distribution: LinuxDistributionContract.fromMap(map['distribution']),
    );
  }

  bool get prootInstallable =>
      prootCandidate &&
      !host.state.blocksProvisioning &&
      !localExecution.state.blocksProvisioning &&
      !proot.state.blocksProvisioning &&
      !x11.state.blocksProvisioning &&
      !audio.state.blocksProvisioning &&
      !clipboard.state.blocksProvisioning &&
      !renderer.state.blocksProvisioning &&
      !storage.state.blocksProvisioning;

  String? get prootBlockingReason {
    for (final evidence in [
      host,
      localExecution,
      proot,
      x11,
      audio,
      clipboard,
      renderer,
      storage,
    ]) {
      if (evidence.state.blocksProvisioning) return evidence.reason;
    }
    if (!prootCandidate) return 'Debian compatibility requirements are not met';
    return null;
  }
}

class LinuxEnvironmentStatus {
  const LinuxEnvironmentStatus({
    required this.kind,
    required this.installed,
    required this.running,
    required this.provisioning,
    required this.repairNeeded,
    required this.hasRoot,
    required this.distro,
    required this.desktop,
    required this.rootfsPath,
    required this.rootfsSizeBytes,
    required this.capabilities,
    required this.lastError,
  });

  final LinuxEnvironmentKind kind;
  final bool installed;
  final bool running;
  final bool provisioning;
  final bool repairNeeded;
  final bool hasRoot;
  final String distro;
  final String desktop;
  final String rootfsPath;
  final int rootfsSizeBytes;
  final LinuxRuntimeCapabilities capabilities;
  final String? lastError;

  factory LinuxEnvironmentStatus.fromMap(Map<Object?, Object?> map) {
    if (map['schemaVersion'] != 1) {
      throw const FormatException('Unsupported Linux environment schema');
    }
    final running = _requiredBool(map, 'running');
    final provisioning = _requiredBool(map, 'provisioning');
    if (running && provisioning) {
      throw const FormatException('An environment cannot run while provisioning');
    }
    final lastError = map['lastError'];
    if (lastError != null && lastError is! String) {
      throw const FormatException('lastError must be a string or null');
    }
    return LinuxEnvironmentStatus(
      kind: LinuxEnvironmentKind.parse(map['environmentKind']),
      installed: _requiredBool(map, 'installed'),
      running: running,
      provisioning: provisioning,
      repairNeeded: _requiredBool(map, 'repairNeeded'),
      hasRoot: _requiredBool(map, 'hasRoot'),
      distro: _requiredString(map, 'distro'),
      desktop: _requiredString(map, 'installedDE'),
      rootfsPath: _requiredString(map, 'rootfsPath'),
      rootfsSizeBytes: _nonNegativeInt(map, 'rootfsSizeBytes'),
      capabilities: LinuxRuntimeCapabilities.fromMap(map['capabilities']),
      lastError: lastError as String?,
    );
  }
}

Map<Object?, Object?> _strictMap(Object? value, String name) {
  if (value is! Map) throw FormatException('$name must be a map');
  return Map<Object?, Object?>.from(value);
}

String _requiredString(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is! String) throw FormatException('$key must be a string');
  return value;
}

bool _requiredBool(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is! bool) throw FormatException('$key must be a Boolean');
  return value;
}

int _nonNegativeInt(Map<Object?, Object?> map, String key) {
  final value = map[key];
  if (value is! int || value < 0) {
    throw FormatException('$key must be a non-negative integer');
  }
  return value;
}
