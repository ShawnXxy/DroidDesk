import 'package:droiddesk/models/linux_environment.dart';

enum PackageStoreAvailability {
  ready('ready'),
  environmentNotInstalled('environmentNotInstalled'),
  unsupportedEnvironment('unsupportedEnvironment'),
  temporarilyUnavailable('temporarilyUnavailable');

  const PackageStoreAvailability(this.wireValue);
  final String wireValue;

  static PackageStoreAvailability parse(Object? value) =>
      _parseEnum(values, value, 'availability', (item) => item.wireValue);
}

enum PackageSupportLevel {
  supported('supported'),
  limited('limited'),
  experimental('experimental'),
  repository('repository');

  const PackageSupportLevel(this.wireValue);
  final String wireValue;

  static PackageSupportLevel parse(Object? value) =>
      _parseEnum(values, value, 'supportLevel', (item) => item.wireValue);
}

enum PackageEntryState {
  available('available'),
  installed('installed'),
  updateAvailable('updateAvailable'),
  unavailable('unavailable');

  const PackageEntryState(this.wireValue);
  final String wireValue;

  static PackageEntryState parse(Object? value) =>
      _parseEnum(values, value, 'state', (item) => item.wireValue);
}

enum PackageStoreQueryKind {
  catalog('catalog'),
  installed('installed'),
  updates('updates'),
  storage('storage'),
  operationState('operationState');

  const PackageStoreQueryKind(this.wireValue);
  final String wireValue;
}

enum PackageOperationKind {
  install('install'),
  remove('remove'),
  upgrade('upgrade'),
  upgradeAll('upgradeAll'),
  cleanCache('cleanCache');

  const PackageOperationKind(this.wireValue);
  final String wireValue;

  static PackageOperationKind parse(Object? value) =>
      _parseEnum(values, value, 'kind', (item) => item.wireValue);
}

enum PackageOperationPhase {
  preparing('preparing'),
  repairing('repairing'),
  refreshingIndex('refreshingIndex'),
  downloading('downloading'),
  installing('installing'),
  configuring('configuring'),
  integratingDesktop('integratingDesktop'),
  verifying('verifying'),
  cleaningUp('cleaningUp');

  const PackageOperationPhase(this.wireValue);
  final String wireValue;

  static PackageOperationPhase parse(Object? value) =>
      _parseEnum(values, value, 'phase', (item) => item.wireValue);
}

enum PackageOperationOutcome {
  succeeded('succeeded'),
  failed('failed'),
  cancelled('cancelled'),
  rejected('rejected');

  const PackageOperationOutcome(this.wireValue);
  final String wireValue;

  static PackageOperationOutcome parse(Object? value) =>
      _parseEnum(values, value, 'outcome', (item) => item.wireValue);
}

final class PackageStoreCapabilities {
  const PackageStoreCapabilities({
    required this.browse,
    required this.arbitraryRepositoryPackages,
    required this.remove,
    required this.updateOne,
    required this.updateAll,
    required this.cleanCache,
  });

  final bool browse;
  final bool arbitraryRepositoryPackages;
  final bool remove;
  final bool updateOne;
  final bool updateAll;
  final bool cleanCache;

  factory PackageStoreCapabilities.fromMap(Map<Object?, Object?> map) =>
      PackageStoreCapabilities(
        browse: _requiredBool(map, 'browse'),
        arbitraryRepositoryPackages: _requiredBool(
          map,
          'arbitraryRepositoryPackages',
        ),
        remove: _requiredBool(map, 'remove'),
        updateOne: _requiredBool(map, 'updateOne'),
        updateAll: _requiredBool(map, 'updateAll'),
        cleanCache: _requiredBool(map, 'cleanCache'),
      );
}

final class PackageStoreTarget {
  const PackageStoreTarget({
    required this.environmentKind,
    required this.displayName,
    required this.availability,
    required this.reason,
    required this.capabilities,
  });

  final LinuxEnvironmentKind environmentKind;
  final String displayName;
  final PackageStoreAvailability availability;
  final String? reason;
  final PackageStoreCapabilities capabilities;

  factory PackageStoreTarget.fromMap(Map<Object?, Object?> map) {
    final availability = PackageStoreAvailability.parse(map['availability']);
    final reason = _nullableString(map, 'reason');
    if (availability != PackageStoreAvailability.ready &&
        (reason == null || reason.trim().isEmpty)) {
      throw const FormatException(
        'Unavailable package store target requires a reason',
      );
    }
    return PackageStoreTarget(
      environmentKind: LinuxEnvironmentKind.parse(map['environmentKind']),
      displayName: _requiredNonBlankString(map, 'displayName'),
      availability: availability,
      reason: reason,
      capabilities: PackageStoreCapabilities.fromMap(
        _requiredMap(map, 'capabilities'),
      ),
    );
  }
}

final class PackageStoreEntry {
  const PackageStoreEntry({
    required this.entryId,
    required this.displayName,
    required this.summary,
    required this.category,
    required this.iconNames,
    required this.gui,
    required this.supportLevel,
    required this.state,
    required this.availableVersion,
    required this.installedVersion,
    required this.removable,
    required this.limitations,
    required this.sourceLabel,
  });

  final String entryId;
  final String displayName;
  final String summary;
  final String category;
  final List<String> iconNames;
  final bool gui;
  final PackageSupportLevel supportLevel;
  final PackageEntryState state;
  final String? availableVersion;
  final String? installedVersion;
  final bool removable;
  final List<String> limitations;
  final String sourceLabel;

  factory PackageStoreEntry.fromMap(Map<Object?, Object?> map) {
    final state = PackageEntryState.parse(map['state']);
    final availableVersion = _nullableNonBlankString(map, 'availableVersion');
    final installedVersion = _nullableNonBlankString(map, 'installedVersion');
    if (state == PackageEntryState.installed && installedVersion == null) {
      throw const FormatException(
        'Installed package store entry requires installedVersion',
      );
    }
    if (state == PackageEntryState.updateAvailable &&
        (availableVersion == null || installedVersion == null)) {
      throw const FormatException(
        'Update package store entry requires both versions',
      );
    }
    return PackageStoreEntry(
      entryId: _requiredNonBlankString(map, 'entryId'),
      displayName: _requiredNonBlankString(map, 'displayName'),
      summary: _requiredNonBlankString(map, 'summary'),
      category: _requiredNonBlankString(map, 'category'),
      iconNames: _requiredStringList(map, 'iconNames'),
      gui: _requiredBool(map, 'gui'),
      supportLevel: PackageSupportLevel.parse(map['supportLevel']),
      state: state,
      availableVersion: availableVersion,
      installedVersion: installedVersion,
      removable: _requiredBool(map, 'removable'),
      limitations: _requiredStringList(map, 'limitations'),
      sourceLabel: _requiredNonBlankString(map, 'sourceLabel'),
    );
  }
}

final class PackageStoreStorage {
  const PackageStoreStorage({
    required this.available,
    required this.systemBytes,
    required this.homeBytes,
    required this.cacheBytes,
    required this.packageCount,
    required this.deviceFreeBytes,
    required this.deviceTotalBytes,
  });

  final bool available;
  final int systemBytes;
  final int homeBytes;
  final int cacheBytes;
  final int packageCount;
  final int deviceFreeBytes;
  final int deviceTotalBytes;

  factory PackageStoreStorage.fromMap(Map<Object?, Object?> map) {
    final storage = PackageStoreStorage(
      available: _requiredBool(map, 'available'),
      systemBytes: _requiredNonNegativeInt(map, 'systemBytes'),
      homeBytes: _requiredNonNegativeInt(map, 'homeBytes'),
      cacheBytes: _requiredNonNegativeInt(map, 'cacheBytes'),
      packageCount: _requiredNonNegativeInt(map, 'packageCount'),
      deviceFreeBytes: _requiredNonNegativeInt(map, 'deviceFreeBytes'),
      deviceTotalBytes: _requiredNonNegativeInt(map, 'deviceTotalBytes'),
    );
    if (storage.deviceFreeBytes > storage.deviceTotalBytes) {
      throw const FormatException(
        'deviceFreeBytes must not exceed deviceTotalBytes',
      );
    }
    return storage;
  }
}

final class PackageOperationSnapshot {
  const PackageOperationSnapshot({
    required this.operationId,
    required this.environmentKind,
    required this.kind,
    required this.entryId,
    required this.phase,
    required this.progress,
    required this.message,
    required this.cancellable,
    required this.logTail,
  });

  final int operationId;
  final LinuxEnvironmentKind environmentKind;
  final PackageOperationKind kind;
  final String? entryId;
  final PackageOperationPhase phase;
  final double? progress;
  final String message;
  final bool cancellable;
  final String logTail;

  factory PackageOperationSnapshot.fromMap(Map<Object?, Object?> map) {
    final kind = PackageOperationKind.parse(map['kind']);
    final entryId = _nullableNonBlankString(map, 'entryId');
    _validateOperationEntry(kind, entryId);
    return PackageOperationSnapshot(
      operationId: _requiredPositiveInt(map, 'operationId'),
      environmentKind: LinuxEnvironmentKind.parse(map['environmentKind']),
      kind: kind,
      entryId: entryId,
      phase: PackageOperationPhase.parse(map['phase']),
      progress: _nullableProgress(map, 'progress'),
      message: _requiredNonBlankString(map, 'message'),
      cancellable: _requiredBool(map, 'cancellable'),
      logTail: _requiredString(map, 'logTail'),
    );
  }
}

final class PackageStoreQueryResult {
  const PackageStoreQueryResult({
    required this.schemaVersion,
    required this.target,
    required this.entries,
    required this.storage,
    required this.operation,
    required this.completedResult,
    required this.refreshedAtEpochMs,
  });

  final int schemaVersion;
  final PackageStoreTarget target;
  final List<PackageStoreEntry> entries;
  final PackageStoreStorage? storage;
  final PackageOperationSnapshot? operation;
  final PackageOperationResult? completedResult;
  final int? refreshedAtEpochMs;

  factory PackageStoreQueryResult.fromMap(Map<Object?, Object?> map) {
    final schemaVersion = _requireSchema(map);
    final refreshedAtEpochMs = _nullableNonNegativeInt(
      map,
      'refreshedAtEpochMs',
    );
    return PackageStoreQueryResult(
      schemaVersion: schemaVersion,
      target: PackageStoreTarget.fromMap(_requiredMap(map, 'target')),
      entries: _requiredMapList(
        map,
        'entries',
      ).map(PackageStoreEntry.fromMap).toList(growable: false),
      storage: _nullableMap(map, 'storage')?.let(PackageStoreStorage.fromMap),
      operation: _nullableMap(
        map,
        'operation',
      )?.let(PackageOperationSnapshot.fromMap),
      completedResult: _nullableMap(
        map,
        'completedResult',
      )?.let(PackageOperationResult.fromMap),
      refreshedAtEpochMs: refreshedAtEpochMs,
    );
  }
}

final class PackageOperationEvent {
  const PackageOperationEvent({
    required this.schemaVersion,
    required this.operationId,
    required this.environmentKind,
    required this.phase,
    required this.progress,
    required this.message,
    required this.logChunk,
    required this.cancellable,
  });

  final int schemaVersion;
  final int operationId;
  final LinuxEnvironmentKind environmentKind;
  final PackageOperationPhase phase;
  final double? progress;
  final String message;
  final String? logChunk;
  final bool cancellable;

  factory PackageOperationEvent.fromMap(Map<Object?, Object?> map) =>
      PackageOperationEvent(
        schemaVersion: _requireSchema(map),
        operationId: _requiredPositiveInt(map, 'operationId'),
        environmentKind: LinuxEnvironmentKind.parse(map['environmentKind']),
        phase: PackageOperationPhase.parse(map['phase']),
        progress: _nullableProgress(map, 'progress'),
        message: _requiredNonBlankString(map, 'message'),
        logChunk: _nullableString(map, 'logChunk'),
        cancellable: _requiredBool(map, 'cancellable'),
      );
}

final class PackageStoreError {
  const PackageStoreError({
    required this.code,
    required this.message,
    required this.logTail,
  });

  final String code;
  final String message;
  final String? logTail;

  factory PackageStoreError.fromMap(Map<Object?, Object?> map) =>
      PackageStoreError(
        code: _requiredNonBlankString(map, 'code'),
        message: _requiredNonBlankString(map, 'message'),
        logTail: _nullableString(map, 'logTail'),
      );
}

final class PackageStoreWarning {
  const PackageStoreWarning({required this.code, required this.message});

  final String code;
  final String message;

  factory PackageStoreWarning.fromMap(Map<Object?, Object?> map) =>
      PackageStoreWarning(
        code: _requiredNonBlankString(map, 'code'),
        message: _requiredNonBlankString(map, 'message'),
      );
}

final class PackageOperationResult {
  const PackageOperationResult({
    required this.schemaVersion,
    required this.operationId,
    required this.environmentKind,
    required this.kind,
    required this.entryId,
    required this.outcome,
    required this.message,
    required this.entry,
    required this.bytesFreed,
    required this.warnings,
    required this.error,
  });

  final int schemaVersion;
  final int operationId;
  final LinuxEnvironmentKind environmentKind;
  final PackageOperationKind kind;
  final String? entryId;
  final PackageOperationOutcome outcome;
  final String message;
  final PackageStoreEntry? entry;
  final int? bytesFreed;
  final List<PackageStoreWarning> warnings;
  final PackageStoreError? error;

  factory PackageOperationResult.fromMap(Map<Object?, Object?> map) {
    final kind = PackageOperationKind.parse(map['kind']);
    final entryId = _nullableNonBlankString(map, 'entryId');
    final outcome = PackageOperationOutcome.parse(map['outcome']);
    final errorMap = _nullableMap(map, 'error');
    final error = errorMap == null ? null : PackageStoreError.fromMap(errorMap);
    _validateOperationEntry(kind, entryId);
    if (outcome == PackageOperationOutcome.succeeded && error != null) {
      throw const FormatException(
        'Successful package operation must not contain an error',
      );
    }
    if (outcome != PackageOperationOutcome.succeeded && error == null) {
      throw const FormatException(
        'Non-successful package operation requires an error',
      );
    }
    return PackageOperationResult(
      schemaVersion: _requireSchema(map),
      operationId: _requiredPositiveInt(map, 'operationId'),
      environmentKind: LinuxEnvironmentKind.parse(map['environmentKind']),
      kind: kind,
      entryId: entryId,
      outcome: outcome,
      message: _requiredNonBlankString(map, 'message'),
      entry: _nullableMap(map, 'entry')?.let(PackageStoreEntry.fromMap),
      bytesFreed: _nullableNonNegativeInt(map, 'bytesFreed'),
      warnings: _requiredMapList(
        map,
        'warnings',
      ).map(PackageStoreWarning.fromMap).toList(growable: false),
      error: error,
    );
  }
}

const _packageStoreSchemaVersion = 1;

int _requireSchema(Map<Object?, Object?> map) {
  final schemaVersion = map['schemaVersion'];
  if (schemaVersion != _packageStoreSchemaVersion) {
    throw FormatException('Unsupported package store schema: $schemaVersion');
  }
  return _packageStoreSchemaVersion;
}

T _parseEnum<T>(
  List<T> values,
  Object? value,
  String field,
  String Function(T value) wireValue,
) {
  if (value is! String) {
    throw FormatException('$field must be a string');
  }
  for (final item in values) {
    if (wireValue(item) == value) return item;
  }
  throw FormatException('Unknown $field: $value');
}

String _requiredString(Map<Object?, Object?> map, String field) {
  final value = map[field];
  if (value is! String) throw FormatException('$field must be a string');
  return value;
}

String _requiredNonBlankString(Map<Object?, Object?> map, String field) {
  final value = _requiredString(map, field);
  if (value.trim().isEmpty) throw FormatException('$field must not be blank');
  return value;
}

String? _nullableString(Map<Object?, Object?> map, String field) {
  final value = map[field];
  if (value == null) return null;
  if (value is! String)
    throw FormatException('$field must be a string or null');
  return value;
}

String? _nullableNonBlankString(Map<Object?, Object?> map, String field) {
  final value = _nullableString(map, field);
  if (value != null && value.trim().isEmpty) {
    throw FormatException('$field must be non-blank or null');
  }
  return value;
}

bool _requiredBool(Map<Object?, Object?> map, String field) {
  final value = map[field];
  if (value is! bool) throw FormatException('$field must be a bool');
  return value;
}

int _requiredPositiveInt(Map<Object?, Object?> map, String field) {
  final value = map[field];
  if (value is! int || value <= 0) {
    throw FormatException('$field must be a positive integer');
  }
  return value;
}

int _requiredNonNegativeInt(Map<Object?, Object?> map, String field) {
  final value = map[field];
  if (value is! int || value < 0) {
    throw FormatException('$field must be a non-negative integer');
  }
  return value;
}

int? _nullableNonNegativeInt(Map<Object?, Object?> map, String field) {
  final value = map[field];
  if (value == null) return null;
  if (value is! int || value < 0) {
    throw FormatException('$field must be a non-negative integer or null');
  }
  return value;
}

double? _nullableProgress(Map<Object?, Object?> map, String field) {
  final value = map[field];
  if (value == null) return null;
  if (value is! num || value < 0 || value > 1) {
    throw FormatException('$field must be null or between 0 and 1');
  }
  return value.toDouble();
}

Map<Object?, Object?> _requiredMap(Map<Object?, Object?> map, String field) {
  final value = map[field];
  if (value is! Map) throw FormatException('$field must be a map');
  return Map<Object?, Object?>.from(value);
}

Map<Object?, Object?>? _nullableMap(Map<Object?, Object?> map, String field) {
  final value = map[field];
  if (value == null) return null;
  if (value is! Map) throw FormatException('$field must be a map or null');
  return Map<Object?, Object?>.from(value);
}

List<Map<Object?, Object?>> _requiredMapList(
  Map<Object?, Object?> map,
  String field,
) {
  final value = map[field];
  if (value is! List) throw FormatException('$field must be a list');
  return value
      .map((item) {
        if (item is! Map) throw FormatException('$field entries must be maps');
        return Map<Object?, Object?>.from(item);
      })
      .toList(growable: false);
}

List<String> _requiredStringList(Map<Object?, Object?> map, String field) {
  final value = map[field];
  if (value is! List) throw FormatException('$field must be a list');
  return value
      .map((item) {
        if (item is! String || item.trim().isEmpty) {
          throw FormatException('$field entries must be non-blank strings');
        }
        return item;
      })
      .toList(growable: false);
}

void _validateOperationEntry(PackageOperationKind kind, String? entryId) {
  switch (kind) {
    case PackageOperationKind.install:
    case PackageOperationKind.remove:
    case PackageOperationKind.upgrade:
      if (entryId == null) {
        throw FormatException('${kind.wireValue} requires entryId');
      }
    case PackageOperationKind.upgradeAll:
    case PackageOperationKind.cleanCache:
      if (entryId != null) {
        throw FormatException('${kind.wireValue} does not accept entryId');
      }
  }
}

extension<T> on T {
  R let<R>(R Function(T value) transform) => transform(this);
}
