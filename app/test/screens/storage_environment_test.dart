import 'package:droiddesk/screens/storage_screen.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/linux_environment_test.dart';
import '../support/package_store_fakes.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late FakePackageStorePlatform native;
  late AppState state;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    native = FakePackageStorePlatform();
    native.install();
    state = AppState();
  });

  tearDown(() {
    state.dispose();
    native.uninstall();
  });

  testWidgets('storageShowsTheSelectedEnvironmentTarget', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      kind: 'prootDebian',
      installed: true,
    );

    await _pumpStorage(tester, state);

    expect(find.text('Debian PRoot'), findsWidgets);
    expect(find.textContaining('6 packages'), findsOneWidget);
  });

  testWidgets('cleanCacheUsesTheSelectedEnvironment', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      kind: 'prootDebian',
      installed: true,
    );
    native.operationResult = packageResultPayload()
      ..['kind'] = 'cleanCache'
      ..['entryId'] = null
      ..['bytesFreed'] = 4096;

    await _pumpStorage(tester, state);
    await tester.tap(find.text('CLEAN'));
    await tester.pumpAndSettle();

    final call = native.calls.lastWhere(
      (call) => call.method == 'runPackageStoreOperation',
    );
    expect((call.arguments as Map)['environmentKind'], 'prootDebian');
    expect((call.arguments as Map)['operationKind'], 'cleanCache');
  });

  testWidgets('unsupportedStoreDoesNotShowNativeSizes', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      kind: 'chrootUbuntu',
      installed: true,
    );
    native.availability = 'unsupportedEnvironment';
    native.availabilityReason =
        'The App Store does not support Ubuntu chroot yet';
    native.storage = null;

    await _pumpStorage(tester, state);

    expect(
      find.text('The App Store does not support Ubuntu chroot yet'),
      findsOneWidget,
    );
    expect(find.text('LINUX USES'), findsNothing);
  });

  testWidgets('typedCleanupFailureIsVisible', (tester) async {
    native.operationResult =
        packageResultPayload(
            outcome: 'failed',
            errorCode: 'PACKAGE_MANAGER_BUSY',
            errorMessage: 'Another Debian package manager process is running',
          )
          ..['kind'] = 'cleanCache'
          ..['entryId'] = null;

    await _pumpStorage(tester, state);
    await tester.tap(find.text('CLEAN'));
    await tester.pumpAndSettle();

    expect(find.textContaining('PACKAGE_MANAGER_BUSY'), findsWidgets);
  });

  testWidgets('replayedCompletionRefreshesStorageState', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      kind: 'prootDebian',
      installed: true,
    );
    native.operationState = packageQueryPayload(
      operation: packageOperationSnapshot(
        operationId: 77,
        kind: 'cleanCache',
        entryId: '',
      )..['entryId'] = null,
    );

    await _pumpStorage(tester, state);
    expect(find.text('4.0 KB of downloaded files'), findsOneWidget);

    native.storage = packageStoragePayload(cacheBytes: 0);
    native.emitPackageResult(
      packageResultPayload(operationId: 77)
        ..['kind'] = 'cleanCache'
        ..['entryId'] = null
        ..['bytesFreed'] = 4096,
    );
    expect(state.packageResultId, 1);
    await tester.pumpAndSettle();

    expect(
      native.calls
          .where(
            (call) =>
                call.method == 'queryPackageStore' &&
                (call.arguments as Map)['queryKind'] == 'storage',
          )
          .length,
      greaterThan(1),
    );
    expect(find.text('Nothing to clean'), findsOneWidget);
  });
}

Future<void> _pumpStorage(WidgetTester tester, AppState state) async {
  await state.initialize();
  await tester.pumpWidget(
    ChangeNotifierProvider<AppState>.value(
      value: state,
      child: const MaterialApp(home: StorageScreen()),
    ),
  );
  await tester.pumpAndSettle();
}
