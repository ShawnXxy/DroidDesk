import 'dart:async';

import 'package:droiddesk/models/linux_environment.dart';
import 'package:droiddesk/models/package_store.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/linux_environment_test.dart';
import '../support/package_store_fakes.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late FakePackageStorePlatform native;
  late AppState state;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    native = FakePackageStorePlatform()
      ..runtimeStatus = environmentStatusPayload(
        kind: 'prootDebian',
        installed: true,
      );
    native.install();
    state = AppState();
  });

  tearDown(() {
    state.dispose();
    native.uninstall();
  });

  test('installSendsTheEnvironmentTheUserSaw', () async {
    await state.initialize();

    final result = await state.installPackageEntry(
      packageStoreEntry(),
      environment: LinuxEnvironmentKind.prootDebian,
    );

    final call = native.calls.lastWhere(
      (call) => call.method == 'runPackageStoreOperation',
    );
    expect(call.arguments, {
      'environmentKind': 'prootDebian',
      'operationKind': 'install',
      'entryId': 'app.firefox',
      'acceptExperimentalRisk': false,
    });
    expect(result.outcome, PackageOperationOutcome.succeeded);
  });

  test(
    'installKeepsTheExplicitStorefrontTargetAfterSelectionChanges',
    () async {
      await state.initialize();
      state.selectEnvironment(LinuxEnvironmentKind.termuxNative);

      await state.installPackageEntry(
        packageStoreEntry(),
        environment: LinuxEnvironmentKind.prootDebian,
      );

      final call = native.calls.lastWhere(
        (call) => call.method == 'runPackageStoreOperation',
      );
      expect(
        (call.arguments as Map<Object?, Object?>)['environmentKind'],
        'prootDebian',
      );
    },
  );

  test('targetChangedSurfacesTypedFailureAndRefreshesStatus', () async {
    native.operationResult = packageResultPayload(
      outcome: 'rejected',
      errorCode: 'TARGET_CHANGED',
      errorMessage: 'Package store target changed',
    );
    await state.initialize();
    final statusCallsBefore = native.calls
        .where((call) => call.method == 'getRuntimeStatus')
        .length;

    final result = await state.installPackageEntry(
      packageStoreEntry(),
      environment: LinuxEnvironmentKind.prootDebian,
    );

    expect(result.error!.code, 'TARGET_CHANGED');
    expect(state.errorMessage, contains('TARGET_CHANGED'));
    expect(
      native.calls.where((call) => call.method == 'getRuntimeStatus').length,
      greaterThan(statusCallsBefore),
    );
  });

  test('operationEventsUpdateOnlyTheMatchingOperationId', () async {
    native.operationReply = Completer<Map<Object?, Object?>>();
    await state.initialize();
    final future = state.installPackageEntry(
      packageStoreEntry(),
      environment: LinuxEnvironmentKind.prootDebian,
    );
    await Future<void>.delayed(Duration.zero);

    native.emitPackageEvent(
      packageEventPayload(operationId: 42, progress: 0.3),
    );
    native.emitPackageEvent(
      packageEventPayload(
        operationId: 43,
        progress: 0.9,
        message: 'Wrong operation',
      ),
    );

    expect(state.activePackageOperationId, 42);
    expect(state.packageProgress, 0.3);
    expect(state.packageStatus, 'Installing Firefox');
    native.operationReply!.complete(packageResultPayload(operationId: 42));
    await future;
  });

  test('olderEventCannotClaimANewerPendingRequest', () async {
    native.operationResult = packageResultPayload(operationId: 50);
    await state.initialize();
    await state.installPackageEntry(
      packageStoreEntry(),
      environment: LinuxEnvironmentKind.prootDebian,
    );
    native.operationReply = Completer<Map<Object?, Object?>>();

    final future = state.installPackageEntry(
      packageStoreEntry(),
      environment: LinuxEnvironmentKind.prootDebian,
    );
    await Future<void>.delayed(Duration.zero);
    native.emitPackageEvent(packageEventPayload(operationId: 49));

    expect(state.activePackageOperationId, isNull);
    native.emitPackageEvent(packageEventPayload(operationId: 51));
    expect(state.activePackageOperationId, 51);
    native.operationReply!.complete(packageResultPayload(operationId: 51));
    await future;
  });

  test('mismatchedFinalResultClearsPendingOperationState', () async {
    native.operationReply = Completer<Map<Object?, Object?>>();
    await state.initialize();

    final future = state.installPackageEntry(
      packageStoreEntry(),
      environment: LinuxEnvironmentKind.prootDebian,
    );
    await Future<void>.delayed(Duration.zero);
    native.emitPackageEvent(packageEventPayload(operationId: 42));
    native.operationReply!.complete(packageResultPayload(operationId: 43));
    final result = await future;

    expect(result.error!.code, 'OPERATION_ID_MISMATCH');
    expect(state.activePackageOperationId, isNull);
    expect(state.activePackage, isNull);
  });

  test('oldEventAfterActivityRecreationIsIgnored', () async {
    await state.initialize();
    state.dispose();

    native.operationState = packageQueryPayload(
      operation: packageOperationSnapshot(operationId: 50, progress: 0.2),
    );
    final restored = AppState();
    state = restored;
    await restored.initialize();

    native.emitPackageEvent(
      packageEventPayload(
        operationId: 42,
        progress: 0.9,
        message: 'Old operation',
      ),
    );

    expect(restored.activePackageOperationId, 50);
    expect(restored.packageProgress, 0.2);
    expect(restored.packageStatus, 'Installing Firefox');
  });

  test('initializationRestoresTheCurrentNativeOperationSnapshot', () async {
    native.operationState = packageQueryPayload(
      operation: packageOperationSnapshot(
        operationId: 77,
        progress: 0.4,
        logTail: 'restored log',
      ),
    );

    await state.initialize();

    expect(state.activePackageOperationId, 77);
    expect(state.activePackageEnvironment, LinuxEnvironmentKind.prootDebian);
    expect(state.packageProgress, 0.4);
    expect(state.packageLog, 'restored log');
  });

  test('restoredOperationClearsWhenProcessResultArrives', () async {
    native.operationState = packageQueryPayload(
      operation: packageOperationSnapshot(operationId: 77),
    );
    await state.initialize();
    native.emitPackageResult(packageResultPayload(operationId: 77));

    expect(state.activePackageOperationId, isNull);
    expect(state.activePackage, isNull);
  });

  test('initializationReplaysAndAcknowledgesCompletedNativeResult', () async {
    native.operationState = packageQueryPayload(
      completedResult: packageResultPayload(operationId: 88),
    );

    await state.initialize();
    await Future<void>.delayed(Duration.zero);

    expect(state.lastPackageStoreResult!.operationId, 88);
    expect(state.activePackageOperationId, isNull);
    final acknowledgement = native.calls.lastWhere(
      (call) => call.method == 'acknowledgePackageStoreOperationResult',
    );
    expect(acknowledgement.arguments, {'operationId': 88});
  });

  test('resultKeepsErrorCodeMessageAndLogTail', () async {
    native.operationResult = packageResultPayload(
      outcome: 'failed',
      errorCode: 'VERIFY_FAILED',
      errorMessage: 'firefox-esr --version failed',
      logTail: 'exit 1',
    );
    await state.initialize();

    final result = await state.installPackageEntry(
      packageStoreEntry(),
      environment: LinuxEnvironmentKind.prootDebian,
    );

    expect(result.error!.code, 'VERIFY_FAILED');
    expect(result.error!.message, 'firefox-esr --version failed');
    expect(result.error!.logTail, 'exit 1');
    expect(state.lastPackageStoreResult, same(result));
  });

  test('cancelUsesTheCurrentOperationId', () async {
    native.operationState = packageQueryPayload(
      operation: packageOperationSnapshot(operationId: 77),
    );
    await state.initialize();

    await state.cancelPackageOperation();

    final call = native.calls.lastWhere(
      (call) => call.method == 'cancelPackageStoreOperation',
    );
    expect(call.arguments, {'operationId': 77});
  });

  test('environmentSelectionIsBlockedWhileMutationRuns', () async {
    native.operationState = packageQueryPayload(
      operation: packageOperationSnapshot(operationId: 77),
    );
    await state.initialize();

    state.selectEnvironment(LinuxEnvironmentKind.termuxNative);

    expect(state.selectedEnvironment, LinuxEnvironmentKind.prootDebian);
    expect(state.errorMessage, contains('package operation'));
  });
}
