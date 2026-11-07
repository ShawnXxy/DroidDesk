import 'dart:async';

import 'package:droiddesk/models/package_store.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:flutter/services.dart';

import 'desktop_display_fakes.dart';

class FakePackageStorePlatform extends FakeDesktopPlatform {
  Map<Object?, Object?> operationState = packageQueryPayload();
  Map<Object?, Object?> operationResult = packageResultPayload();
  List<Map<Object?, Object?>> catalogEntries = [packageEntryPayload()];
  List<Map<Object?, Object?>> installedEntries = [];
  List<Map<Object?, Object?>> updateEntries = [];
  Map<Object?, Object?>? storage = packageStoragePayload();
  String availability = 'ready';
  String? availabilityReason;
  Completer<Map<Object?, Object?>>? operationReply;
  bool cancelResult = true;

  @override
  Future<Object?> handleAdditionalCall(MethodCall call) async {
    switch (call.method) {
      case 'queryPackageStore':
        final arguments = call.arguments as Map<Object?, Object?>;
        if (arguments['queryKind'] == 'operationState') return operationState;
        final queryKind = arguments['queryKind'];
        final search = (arguments['search'] as String? ?? '')
            .trim()
            .toLowerCase();
        final catalog = search.isEmpty
            ? catalogEntries
            : catalogEntries.where((entry) {
                final name = (entry['displayName'] as String).toLowerCase();
                final entryId = (entry['entryId'] as String).toLowerCase();
                return name.contains(search) || entryId.contains(search);
              }).toList();
        return packageQueryPayload(
          environmentKind: arguments['environmentKind'] as String,
          availability: availability,
          reason: availabilityReason,
          entries: switch (queryKind) {
            'catalog' => catalog,
            'installed' => installedEntries,
            'updates' => updateEntries,
            _ => const [],
          },
          storage: queryKind == 'storage' ? storage : null,
        );
      case 'runPackageStoreOperation':
        if (operationReply != null) return operationReply!.future;
        return operationResult;
      case 'cancelPackageStoreOperation':
        return cancelResult;
      case 'acknowledgePackageStoreOperationResult':
        return true;
      default:
        return super.handleAdditionalCall(call);
    }
  }

  void emitPackageEvent(Map<Object?, Object?> payload) {
    DroidDeskPlatform.onPackageStoreOperationEvent?.call(
      PackageOperationEvent.fromMap(payload),
    );
  }

  void emitPackageResult(Map<Object?, Object?> payload) {
    DroidDeskPlatform.onPackageStoreOperationResult?.call(
      PackageOperationResult.fromMap(payload),
    );
  }
}

PackageStoreEntry packageStoreEntry({
  String entryId = 'app.firefox',
  String displayName = 'Firefox ESR',
  String supportLevel = 'supported',
  List<String> limitations = const [],
}) => PackageStoreEntry.fromMap(
  packageEntryPayload(
    entryId: entryId,
    displayName: displayName,
    supportLevel: supportLevel,
    limitations: limitations,
  ),
);

Map<Object?, Object?> packageEntryPayload({
  String entryId = 'app.firefox',
  String displayName = 'Firefox ESR',
  String supportLevel = 'supported',
  String state = 'available',
  List<String> limitations = const [],
}) => {
  'entryId': entryId,
  'displayName': displayName,
  'summary': 'Web browser',
  'category': 'Internet',
  'iconNames': ['firefox-esr'],
  'gui': true,
  'supportLevel': supportLevel,
  'state': state,
  'availableVersion': '128.9.0esr',
  'installedVersion': state == 'installed' ? '128.9.0esr' : null,
  'removable': true,
  'limitations': limitations,
  'sourceLabel': 'Debian Bookworm',
};

Map<Object?, Object?> packageQueryPayload({
  String environmentKind = 'prootDebian',
  Map<Object?, Object?>? operation,
  Map<Object?, Object?>? completedResult,
  String availability = 'ready',
  String? reason,
  List<Map<Object?, Object?>> entries = const [],
  Map<Object?, Object?>? storage,
}) => {
  'schemaVersion': 1,
  'target': {
    'environmentKind': environmentKind,
    'displayName': environmentKind == 'prootDebian'
        ? 'Debian PRoot'
        : 'Termux Native',
    'availability': availability,
    'reason': reason,
    'capabilities': {
      'browse': availability == 'ready',
      'arbitraryRepositoryPackages': environmentKind == 'termuxNative',
      'remove': availability == 'ready',
      'updateOne': availability == 'ready',
      'updateAll': availability == 'ready',
      'cleanCache': availability == 'ready',
    },
  },
  'entries': entries,
  'storage': storage,
  'operation': operation,
  'completedResult': completedResult,
  'refreshedAtEpochMs': null,
};

Map<Object?, Object?> packageStoragePayload({
  int systemBytes = 1024,
  int homeBytes = 2048,
  int cacheBytes = 4096,
  int packageCount = 6,
}) => {
  'available': true,
  'systemBytes': systemBytes,
  'homeBytes': homeBytes,
  'cacheBytes': cacheBytes,
  'packageCount': packageCount,
  'deviceFreeBytes': 8 * 1024 * 1024,
  'deviceTotalBytes': 16 * 1024 * 1024,
};

Map<Object?, Object?> packageOperationSnapshot({
  int operationId = 77,
  String environmentKind = 'prootDebian',
  String kind = 'install',
  String entryId = 'app.firefox',
  String phase = 'installing',
  double? progress = 0.4,
  String message = 'Installing Firefox',
  bool cancellable = true,
  String logTail = 'apt-get install firefox-esr',
}) => {
  'operationId': operationId,
  'environmentKind': environmentKind,
  'kind': kind,
  'entryId': entryId,
  'phase': phase,
  'progress': progress,
  'message': message,
  'cancellable': cancellable,
  'logTail': logTail,
};

Map<Object?, Object?> packageEventPayload({
  int operationId = 77,
  String environmentKind = 'prootDebian',
  String phase = 'installing',
  double? progress = 0.5,
  String message = 'Installing Firefox',
  String? logChunk = 'downloaded\n',
  bool cancellable = true,
}) => {
  'schemaVersion': 1,
  'operationId': operationId,
  'environmentKind': environmentKind,
  'phase': phase,
  'progress': progress,
  'message': message,
  'logChunk': logChunk,
  'cancellable': cancellable,
};

Map<Object?, Object?> packageResultPayload({
  int operationId = 77,
  String environmentKind = 'prootDebian',
  String outcome = 'succeeded',
  String? errorCode,
  String? errorMessage,
  String? logTail,
}) => {
  'schemaVersion': 1,
  'operationId': operationId,
  'environmentKind': environmentKind,
  'kind': 'install',
  'entryId': 'app.firefox',
  'outcome': outcome,
  'message': outcome == 'succeeded' ? 'Installed' : 'Failed',
  'entry': null,
  'bytesFreed': null,
  'warnings': <Object?>[],
  'error': outcome == 'succeeded'
      ? null
      : {
          'code': errorCode ?? 'INSTALL_FAILED',
          'message': errorMessage ?? 'Install failed',
          'logTail': logTail,
        },
};
