import 'package:droiddesk/models/linux_environment.dart';
import 'package:droiddesk/models/package_store.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('parsesCompletePackageStoreQueryResult', () {
    final result = PackageStoreQueryResult.fromMap(_queryPayload());

    expect(result.schemaVersion, 1);
    expect(result.target.environmentKind, LinuxEnvironmentKind.prootDebian);
    expect(result.target.availability, PackageStoreAvailability.ready);
    expect(result.entries.single.entryId, 'app.firefox');
    expect(result.entries.single.supportLevel, PackageSupportLevel.supported);
    expect(result.storage!.cacheBytes, 4096);
    expect(result.operation!.phase, PackageOperationPhase.installing);
    expect(result.completedResult, isNull);
    expect(result.refreshedAtEpochMs, 1_762_500_000_000);
  });

  test('parsesIndeterminateOperationEvent', () {
    final event = PackageOperationEvent.fromMap({
      'schemaVersion': 1,
      'operationId': 12,
      'environmentKind': 'prootDebian',
      'phase': 'downloading',
      'progress': null,
      'message': 'Downloading Firefox',
      'logChunk': null,
      'cancellable': true,
    });

    expect(event.operationId, 12);
    expect(event.progress, isNull);
    expect(event.logChunk, isNull);
    expect(event.cancellable, isTrue);
  });

  test('rejectsUnknownSchemaEnumAndEnvironment', () {
    expect(
      () => PackageStoreQueryResult.fromMap(
        _queryPayload()..['schemaVersion'] = 2,
      ),
      throwsFormatException,
    );
    expect(
      () => PackageStoreQueryResult.fromMap(
        _queryPayload()
          ..['target'] = {
            ...(_queryPayload()['target']! as Map<Object?, Object?>),
            'environmentKind': 'mystery',
          },
      ),
      throwsFormatException,
    );
    expect(
      () => PackageOperationEvent.fromMap({
        'schemaVersion': 1,
        'operationId': 12,
        'environmentKind': 'prootDebian',
        'phase': 'teleporting',
        'progress': null,
        'message': 'Unknown',
        'logChunk': null,
        'cancellable': false,
      }),
      throwsFormatException,
    );
  });

  test('rejectsNegativeSizesAndOutOfRangeProgress', () {
    final negativeStorage = _queryPayload();
    negativeStorage['storage'] = {
      ...(_queryPayload()['storage']! as Map<Object?, Object?>),
      'systemBytes': -1,
    };

    expect(
      () => PackageStoreQueryResult.fromMap(negativeStorage),
      throwsFormatException,
    );
    expect(
      () => PackageOperationEvent.fromMap({
        'schemaVersion': 1,
        'operationId': 12,
        'environmentKind': 'prootDebian',
        'phase': 'installing',
        'progress': 1.1,
        'message': 'Installing',
        'logChunk': null,
        'cancellable': true,
      }),
      throwsFormatException,
    );
  });

  test('rejectsSucceededResultWithError', () {
    expect(
      () => PackageOperationResult.fromMap({
        ..._resultPayload(outcome: 'succeeded'),
        'error': {
          'code': 'VERIFY_FAILED',
          'message': 'Unexpected error',
          'logTail': null,
        },
      }),
      throwsFormatException,
    );
  });

  test('rejectsFailedResultWithoutError', () {
    expect(
      () => PackageOperationResult.fromMap(
        _resultPayload(outcome: 'failed')..['error'] = null,
      ),
      throwsFormatException,
    );
  });

  test('parsesSucceededResultWithDesktopIntegrationWarning', () {
    final result = PackageOperationResult.fromMap({
      ..._resultPayload(outcome: 'succeeded'),
      'warnings': [
        {
          'code': 'DESKTOP_INTEGRATION_LIMITED',
          'message': 'Open the XFCE Applications menu manually',
        },
      ],
    });

    expect(result.outcome, PackageOperationOutcome.succeeded);
    expect(result.error, isNull);
    expect(result.warnings.single.code, 'DESKTOP_INTEGRATION_LIMITED');
  });
}

Map<Object?, Object?> _queryPayload() => {
  'schemaVersion': 1,
  'target': {
    'environmentKind': 'prootDebian',
    'displayName': 'Debian PRoot',
    'availability': 'ready',
    'reason': null,
    'capabilities': {
      'browse': true,
      'arbitraryRepositoryPackages': false,
      'remove': true,
      'updateOne': true,
      'updateAll': true,
      'cleanCache': true,
    },
  },
  'entries': [
    {
      'entryId': 'app.firefox',
      'displayName': 'Firefox ESR',
      'summary': 'Web browser',
      'category': 'Internet',
      'iconNames': ['firefox-esr'],
      'gui': true,
      'supportLevel': 'supported',
      'state': 'updateAvailable',
      'availableVersion': '129.0esr',
      'installedVersion': '128.9.0esr',
      'removable': true,
      'limitations': <String>[],
      'sourceLabel': 'Debian Bookworm',
    },
  ],
  'storage': {
    'available': true,
    'systemBytes': 1024,
    'homeBytes': 2048,
    'cacheBytes': 4096,
    'packageCount': 42,
    'deviceFreeBytes': 8 * 1024 * 1024,
    'deviceTotalBytes': 16 * 1024 * 1024,
  },
  'operation': {
    'operationId': 12,
    'environmentKind': 'prootDebian',
    'kind': 'install',
    'entryId': 'app.firefox',
    'phase': 'installing',
    'progress': 0.5,
    'message': 'Installing Firefox',
    'cancellable': true,
    'logTail': 'apt-get install firefox-esr',
  },
  'completedResult': null,
  'refreshedAtEpochMs': 1_762_500_000_000,
};

Map<Object?, Object?> _resultPayload({required String outcome}) => {
  'schemaVersion': 1,
  'operationId': 12,
  'environmentKind': 'prootDebian',
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
          'code': 'INSTALL_FAILED',
          'message': 'apt-get failed',
          'logTail': 'exit 1',
        },
};
