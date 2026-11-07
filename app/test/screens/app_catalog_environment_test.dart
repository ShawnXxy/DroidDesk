import 'dart:async';

import 'package:droiddesk/models/linux_environment.dart';
import 'package:droiddesk/screens/apps/app_catalog_screen.dart';
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

  testWidgets('showsPersistentTermuxNativeTarget', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      kind: 'termuxNative',
      installed: true,
    );

    await _pumpStore(tester, state);

    expect(find.text('Termux Native'), findsWidgets);
  });

  testWidgets('showsPersistentDebianProotTarget', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      kind: 'prootDebian',
      installed: true,
    );

    await _pumpStore(tester, state);

    expect(find.text('Debian PRoot'), findsWidgets);
  });

  testWidgets('reloadsSectionsWhenSelectedEnvironmentChanges', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      kind: 'prootDebian',
      installed: true,
    );
    native.catalogEntries = [packageEntryPayload(displayName: 'Firefox ESR')];
    await _pumpStore(tester, state);
    expect(find.text('Firefox ESR'), findsWidgets);

    native.catalogEntries = [
      packageEntryPayload(entryId: 'repo.git', displayName: 'Git'),
    ];
    state.selectEnvironment(LinuxEnvironmentKind.termuxNative);
    await tester.pumpAndSettle();

    expect(find.text('Git'), findsWidgets);
    final catalogCalls = native.calls.where(
      (call) =>
          call.method == 'queryPackageStore' &&
          (call.arguments as Map)['queryKind'] == 'catalog',
    );
    expect(
      catalogCalls.map((call) => (call.arguments as Map)['environmentKind']),
      containsAll(<String>['prootDebian', 'termuxNative']),
    );
  });

  testWidgets('unsupportedChrootShowsReasonAndNoGetButtons', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      kind: 'chrootUbuntu',
      installed: true,
    );
    native.availability = 'unsupportedEnvironment';
    native.availabilityReason =
        'The App Store does not support Ubuntu chroot yet';
    native.catalogEntries = [];

    await _pumpStore(tester, state);

    expect(
      find.text('The App Store does not support Ubuntu chroot yet'),
      findsOneWidget,
    );
    expect(find.text('GET'), findsNothing);
  });

  testWidgets('debianCompatibilityIsNotShownAsAnOptionalApp', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      kind: 'termuxNative',
      installed: true,
    );

    await _pumpStore(tester, state);

    expect(find.text('Debian Compatibility'), findsNothing);
  });

  testWidgets('prootBrowseContainsOnlyCuratedRecipes', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      kind: 'prootDebian',
      installed: true,
    );
    native.catalogEntries = [
      packageEntryPayload(entryId: 'app.firefox', displayName: 'Firefox ESR'),
      packageEntryPayload(entryId: 'tool.python', displayName: 'Python'),
    ];

    await _pumpStore(tester, state);

    expect(find.text('Firefox ESR'), findsWidgets);
    expect(find.text('Python'), findsWidgets);
    expect(find.textContaining('repo.'), findsNothing);
  });

  testWidgets('supportLevelAndLimitationsAreVisible', (tester) async {
    native.catalogEntries = [
      packageEntryPayload(
        supportLevel: 'limited',
        limitations: ['Software rendering only'],
      ),
    ];

    await _pumpStore(tester, state);

    expect(find.text('LIMITED'), findsWidgets);
    expect(find.text('Software rendering only'), findsWidgets);
  });

  testWidgets('experimentalEntryRequiresConfirmation', (tester) async {
    native.catalogEntries = [
      packageEntryPayload(
        entryId: 'app.vscode',
        displayName: 'Visual Studio Code',
        supportLevel: 'experimental',
        limitations: ['Sandbox validation pending'],
      ),
    ];

    await _pumpStore(tester, state);
    await tester.tap(find.text('GET').first);
    await tester.pumpAndSettle();

    expect(find.text('Install experimental application?'), findsOneWidget);
    expect(
      native.calls.where((call) => call.method == 'runPackageStoreOperation'),
      isEmpty,
    );
  });

  testWidgets('unavailableExperimentalEntryCannotBeInstalled', (tester) async {
    native.catalogEntries = [
      packageEntryPayload(
        entryId: 'app.vscode',
        displayName: 'Visual Studio Code',
        supportLevel: 'experimental',
        state: 'unavailable',
        limitations: ['Sandbox validation failed'],
      ),
    ];

    await _pumpStore(tester, state);

    expect(find.text('UNAVAILABLE'), findsWidgets);
    expect(find.text('GET'), findsNothing);
    expect(
      native.calls.where((call) => call.method == 'runPackageStoreOperation'),
      isEmpty,
    );
  });

  testWidgets('incompleteSupportedRecipeCanBeReinstalled', (tester) async {
    native.catalogEntries = [
      packageEntryPayload(
        entryId: 'tool.nodejs',
        displayName: 'Node.js + npm',
        state: 'unavailable',
        limitations: ['The recipe installation is incomplete.'],
      ),
    ];

    await _pumpStore(tester, state);

    expect(find.text('GET'), findsWidgets);
    expect(find.text('UNAVAILABLE'), findsNothing);
  });

  testWidgets('targetChangedFailureIsVisibleInsteadOfEmptySuccess', (
    tester,
  ) async {
    native.operationResult = packageResultPayload(
      outcome: 'rejected',
      errorCode: 'TARGET_CHANGED',
      errorMessage: 'Package store target changed',
    );

    await _pumpStore(tester, state);
    await tester.tap(find.text('GET').first);
    await tester.pumpAndSettle();

    expect(find.textContaining('TARGET_CHANGED'), findsWidgets);
  });

  testWidgets('startingANewMutationClearsThePreviousOperationError', (
    tester,
  ) async {
    native.operationResult = packageResultPayload(
      outcome: 'cancelled',
      errorCode: 'CANCELLED',
      errorMessage: 'Debian package operation cancelled',
    );

    await _pumpStore(tester, state);
    await tester.tap(find.text('GET').first);
    await tester.pumpAndSettle();
    expect(find.textContaining('CANCELLED'), findsWidgets);

    native.operationReply = Completer<Map<Object?, Object?>>();
    await tester.tap(find.text('GET').first);
    await tester.pump();

    expect(find.textContaining('CANCELLED'), findsNothing);
    native.operationReply!.complete(packageResultPayload());
    await tester.pump(const Duration(seconds: 1));
  });

  testWidgets('successfulInstallRefreshesFeaturedEntryState', (tester) async {
    native.operationReply = Completer<Map<Object?, Object?>>();

    await _pumpStore(tester, state);
    await tester.tap(find.text('GET').first);
    await tester.pump();
    native.catalogEntries = [packageEntryPayload(state: 'installed')];
    native.installedEntries = [packageEntryPayload(state: 'installed')];
    native.operationReply!.complete(packageResultPayload());
    await tester.pumpAndSettle();

    expect(find.text('INSTALLED'), findsWidgets);
    expect(find.text('GET'), findsNothing);
  });

  testWidgets('mutationRefreshPreservesBrowseSearchFilter', (tester) async {
    native.catalogEntries = [
      packageEntryPayload(displayName: 'Firefox ESR'),
      packageEntryPayload(entryId: 'tool.python', displayName: 'Python'),
    ];
    native.operationReply = Completer<Map<Object?, Object?>>();

    await _pumpStore(tester, state);
    await tester.tap(find.text('Browse'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'python');
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pumpAndSettle();
    expect(find.text('Python'), findsOneWidget);
    expect(find.text('Firefox ESR'), findsNothing);

    await tester.tap(find.text('GET'));
    await tester.pump();
    native.catalogEntries = [
      packageEntryPayload(displayName: 'Firefox ESR'),
      packageEntryPayload(
        entryId: 'tool.python',
        displayName: 'Python',
        state: 'installed',
      ),
    ];
    native.installedEntries = [
      packageEntryPayload(
        entryId: 'tool.python',
        displayName: 'Python',
        state: 'installed',
      ),
    ];
    native.operationReply!.complete(packageResultPayload());
    await tester.pumpAndSettle();

    expect(find.text('Python'), findsOneWidget);
    expect(find.text('Firefox ESR'), findsNothing);
    expect(find.text('INSTALLED'), findsOneWidget);
  });

  testWidgets('mutationControlsDisableWhileAnOperationRuns', (tester) async {
    native.operationState = packageQueryPayload(
      operation: packageOperationSnapshot(
        operationId: 77,
        entryId: 'app.other',
      ),
    );

    await _pumpStore(tester, state);
    final callsBefore = native.calls
        .where((call) => call.method == 'runPackageStoreOperation')
        .length;
    await tester.tap(find.text('GET').first);
    await tester.pump();

    expect(
      native.calls
          .where((call) => call.method == 'runPackageStoreOperation')
          .length,
      callsBefore,
    );
  });

  testWidgets('replayedCompletionRefreshesInstalledState', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      kind: 'prootDebian',
      installed: true,
    );
    native.operationState = packageQueryPayload(
      operation: packageOperationSnapshot(operationId: 77),
    );

    await _pumpStore(tester, state);
    expect(find.text('INSTALLED'), findsNothing);

    native.catalogEntries = [packageEntryPayload(state: 'installed')];
    native.installedEntries = [packageEntryPayload(state: 'installed')];
    native.emitPackageResult(packageResultPayload(operationId: 77));
    expect(state.packageResultId, 1);
    await tester.pumpAndSettle();

    expect(
      native.calls
          .where(
            (call) =>
                call.method == 'queryPackageStore' &&
                (call.arguments as Map)['queryKind'] == 'catalog',
          )
          .length,
      greaterThan(1),
    );
    expect(find.text('INSTALLED'), findsWidgets);
    expect(find.text('GET'), findsNothing);
  });
}

Future<void> _pumpStore(WidgetTester tester, AppState state) async {
  await state.initialize();
  await tester.pumpWidget(
    ChangeNotifierProvider<AppState>.value(
      value: state,
      child: const MaterialApp(home: AppCatalogScreen()),
    ),
  );
  await tester.pumpAndSettle();
}
