import 'package:droiddesk/models/desktop_display.dart';
import 'package:droiddesk/screens/display_settings_screen.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../support/desktop_display_fakes.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late FakeDesktopPlatform native;
  late AppState state;

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    native = FakeDesktopPlatform()..install();
    state = AppState();
    await state.initialize();
  });

  tearDown(() {
    state.dispose();
    native.uninstall();
  });

  testWidgets(
    'schema-1 snapshot keeps compatibility details and placement override dormant and sends no unsupported platform call',
    (tester) async {
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: state,
          child: const MaterialApp(home: DisplaySettingsScreen()),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('desktop-compatibility-details')),
        findsNothing,
      );
      expect(find.byKey(const Key('desktop-placement-override')), findsNothing);
      expect(
        find.byKey(const Key('desktop-safe-default-warning')),
        findsNothing,
      );

      native.calls.clear();
      await state.setDesktopPlacementOverride(DesktopPlacementMode.movable);

      expect(
        state.desktopOperationError,
        contains('Placement override is unavailable'),
      );
      expect(
        native.calls.where(
          (call) => call.method == 'setDesktopPlacementOverride',
        ),
        isEmpty,
      );
    },
  );

  testWidgets(
    'schema-2 snapshot missing placement diagnostics keeps controls dormant and blocks setDesktopPlacementOverride',
    (tester) async {
      native.snapshot = {...desktopSnapshot(), 'schemaVersion': 2};
      await state.refreshDesktopDisplays();

      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: state,
          child: const MaterialApp(home: DisplaySettingsScreen()),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('desktop-compatibility-details')),
        findsNothing,
      );
      expect(find.byKey(const Key('desktop-placement-override')), findsNothing);

      native.calls.clear();
      await state.setDesktopPlacementOverride(
        DesktopPlacementMode.displayLocal,
      );

      expect(
        state.desktopOperationError,
        contains('Placement override is unavailable'),
      );
      expect(
        native.calls.where(
          (call) => call.method == 'setDesktopPlacementOverride',
        ),
        isEmpty,
      );
    },
  );

  testWidgets(
    'shows safe-default warning, full compatibility details, and disables placement override while Linux is running',
    (tester) async {
      native.snapshot = desktopSnapshotV2(
        placementMode: 'movable',
        placementSelectionSource: 'safeDefault',
        placementReason:
            'Placement evidence is inconclusive; automatic moves are disabled',
        automaticMovesAllowed: false,
        placementOverride: null,
        presentationClass: 'externalPointer',
        stableDisplayKey: 'external:3:DEL|4:4112|11:Dell U2723Q',
        inputCapabilities: const {
          'touchEvidence': 'currentDisplay',
          'pointerAssociation': 'defaultOrUnassociated',
          'hasMouse': true,
          'hasTouchpad': true,
          'hasKeyboard': true,
          'hasStylus': false,
        },
        externalInputRoute: 'phoneRelay',
        rendererPointerCaptureRequested: false,
        relayPointerCaptureRequested: true,
        refreshPreferredRate: 60.0,
        refreshPreferredModeId: null,
        refreshReason: 'requested 60.0Hz without mode switch',
      );
      await state.refreshDesktopDisplays();

      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: state,
          child: const MaterialApp(home: DisplaySettingsScreen()),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('desktop-safe-default-warning')),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: find.byKey(const Key('desktop-safe-default-warning')),
          matching: find.textContaining('safe default'),
        ),
        findsOneWidget,
      );

      final overrideDropdown = tester.widget<DropdownButtonFormField<String>>(
        find.byKey(const Key('desktop-placement-override')),
      );
      expect(overrideDropdown.initialValue, 'auto');
      expect(overrideDropdown.onChanged, isNull);
      expect(
        find.textContaining(
          'Stop Linux before changing desktop placement mode',
        ),
        findsOneWidget,
      );

      native.calls.clear();
      await state.setDesktopPlacementOverride(
        DesktopPlacementMode.displayLocal,
      );
      expect(
        state.desktopOperationError,
        contains('Stop Linux before changing desktop placement mode'),
      );
      expect(
        native.calls.where(
          (call) => call.method == 'setDesktopPlacementOverride',
        ),
        isEmpty,
      );

      await tester.scrollUntilVisible(
        find.byKey(const Key('desktop-compatibility-details')),
        250,
        scrollable: find.byType(Scrollable).first,
      );
      final details = find.byKey(const Key('desktop-compatibility-details'));
      expect(details, findsOneWidget);
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining('Safe default'),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining(
            'Placement evidence is inconclusive; automatic moves are disabled',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining('External pointer'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining('Current display'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining('Mouse, Touchpad, Keyboard'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining('Default or unassociated'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining('Phone relay'),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(of: details, matching: find.textContaining('60 Hz')),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining(
            'Frame-rate hint only (no mode switch)',
          ),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining('softwareLlvmPipe'),
        ),
        findsOneWidget,
      );
    },
  );

  testWidgets(
    'renders Movable and Display-local restart diagnostics, physical mode ID, renderer capture, and applies overrides when stopped',
    (tester) async {
      native.runtimeStatus = {
        ...native.runtimeStatus,
        'running': false,
        'isRunning': false,
      };
      native.snapshot = desktopSnapshotV2(
        phase: 'stopped',
        activeId: null,
        width: null,
        height: null,
        placementMode: 'movable',
        placementSelectionSource: 'runtimeEvidence',
        placementReason: 'Runtime evidence permits placement across displays',
        automaticMovesAllowed: true,
        placementOverride: null,
        presentationClass: 'externalPointer',
        externalInputRoute: 'native',
        rendererPointerCaptureRequested: true,
        relayPointerCaptureRequested: false,
        refreshPreferredRate: 60.0,
        refreshPreferredModeId: 4,
        refreshReason: 'selected exact-resolution mode 4',
        runtimeCapabilities: runtimeCapabilitiesMap(
          rendererBackend: 'virgl',
          hardwareAccelerationVerified: true,
        ),
      );
      await state.refreshStatus();
      await state.refreshDesktopDisplays();

      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: state,
          child: const MaterialApp(home: DisplaySettingsScreen()),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.byKey(const Key('desktop-safe-default-warning')),
        findsNothing,
      );

      final details = find.byKey(const Key('desktop-compatibility-details'));
      await tester.scrollUntilVisible(
        details,
        250,
        scrollable: find.byType(Scrollable).first,
      );
      expect(
        find.descendant(of: details, matching: find.textContaining('Movable')),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining('Runtime evidence'),
        ),
        findsWidgets,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining('Android native'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining('Desktop renderer'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining('Mode ID 4'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(of: details, matching: find.textContaining('virgl')),
        findsOneWidget,
      );

      await tester.scrollUntilVisible(
        find.text('One Linux session, one active view'),
        -250,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.byKey(const Key('desktop-placement-override')));
      await tester.pumpAndSettle();
      expect(find.text('Automatic'), findsWidgets);
      expect(find.text('Move existing desktop view'), findsWidgets);
      expect(find.text('Restart Linux on current display'), findsWidgets);

      native.calls.clear();
      await tester.tap(find.text('Restart Linux on current display').last);
      await tester.pumpAndSettle();

      final localCall = native.calls.singleWhere(
        (call) => call.method == 'setDesktopPlacementOverride',
      );
      expect(localCall.arguments, {'placementMode': 'displayLocal'});

      final updatedOverride = tester.widget<DropdownButtonFormField<String>>(
        find.byKey(const Key('desktop-placement-override')),
      );
      expect(updatedOverride.initialValue, 'displayLocal');

      await tester.scrollUntilVisible(
        details,
        250,
        scrollable: find.byType(Scrollable).first,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining('Display-local restart'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.textContaining('User override'),
        ),
        findsOneWidget,
      );

      await tester.scrollUntilVisible(
        find.text('One Linux session, one active view'),
        -250,
        scrollable: find.byType(Scrollable).first,
      );
      native.calls.clear();
      await tester.tap(find.byKey(const Key('desktop-placement-override')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Move existing desktop view').last);
      await tester.pumpAndSettle();

      final movableCall = native.calls.singleWhere(
        (call) => call.method == 'setDesktopPlacementOverride',
      );
      expect(movableCall.arguments, {'placementMode': 'movable'});

      native.calls.clear();
      await tester.tap(find.byKey(const Key('desktop-placement-override')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Automatic').last);
      await tester.pumpAndSettle();

      final autoCall = native.calls.singleWhere(
        (call) => call.method == 'setDesktopPlacementOverride',
      );
      expect(autoCall.arguments, {'placementMode': null});
      final autoDropdown = tester.widget<DropdownButtonFormField<String>>(
        find.byKey(const Key('desktop-placement-override')),
      );
      expect(autoDropdown.initialValue, 'auto');
    },
  );

  testWidgets(
    'display-local copy avoids TNT assumption unless sessionEnvironment is explicitly tnt',
    (tester) async {
      native.snapshot = desktopSnapshotV2(
        placementMode: 'displayLocal',
        placementSelectionSource: 'runtimeEvidence',
        placementReason:
            'Runtime evidence permits placement only on the current external display',
        automaticMovesAllowed: false,
        sessionEnvironment: 'otherExternal',
      );
      await state.refreshDesktopDisplays();

      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: state,
          child: const MaterialApp(home: DisplaySettingsScreen()),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.text(
          'Display-local mode selects the active display from the launcher. Changing display environments restarts Linux/XFCE; scaling settings remain available.',
        ),
        findsOneWidget,
      );
      expect(find.textContaining('TNT'), findsNothing);

      await state.moveDesktopToPhone();
      expect(
        state.desktopOperationError,
        contains(
          'Display-local mode requires launching from the target display',
        ),
      );
      expect(state.desktopOperationError, isNot(contains('TNT')));

      state.clearDesktopDisplayError();
      native.snapshot = desktopSnapshotV2(
        placementMode: 'displayLocal',
        placementSelectionSource: 'deviceQuirk',
        placementReason:
            'An isolated device quirk selected displayLocal placement',
        automaticMovesAllowed: false,
        sessionEnvironment: 'tnt',
      );
      await state.refreshDesktopDisplays();
      await tester.pumpAndSettle();

      expect(
        find.text(
          'The phone or TNT launcher selects the display environment. Changing environments restarts Linux/XFCE; scaling settings remain available.',
        ),
        findsOneWidget,
      );

      await state.moveDesktopToPhone();
      expect(
        state.desktopOperationError,
        contains('The phone or TNT launcher selects the display environment'),
      );
    },
  );

  testWidgets(
    'reports unrequested refresh accurately when only refreshReason is present and resets placement dropdown on platform rejection',
    (tester) async {
      native.runtimeStatus = {
        ...native.runtimeStatus,
        'running': false,
        'isRunning': false,
      };
      native.snapshot = desktopSnapshotV2(
        phase: 'stopped',
        activeId: null,
        width: null,
        height: null,
        refreshPreferredRate: null,
        refreshPreferredModeId: null,
        refreshReason: 'missing current display mode',
      );
      await state.refreshStatus();
      await state.refreshDesktopDisplays();

      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: state,
          child: const MaterialApp(home: DisplaySettingsScreen()),
        ),
      );
      await tester.pumpAndSettle();

      final details = find.byKey(const Key('desktop-compatibility-details'));
      await tester.scrollUntilVisible(
        details,
        250,
        scrollable: find.byType(Scrollable).first,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.text('Refresh request: Not requested'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: details,
          matching: find.text('Refresh reason: missing current display mode'),
        ),
        findsOneWidget,
      );

      await tester.scrollUntilVisible(
        find.text('One Linux session, one active view'),
        -250,
        scrollable: find.byType(Scrollable).first,
      );
      native.placementOverrideError = PlatformException(
        code: 'INVALID_ARGUMENT',
        message: 'Linux started concurrently',
      );
      await tester.tap(find.byKey(const Key('desktop-placement-override')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Restart Linux on current display').last);
      await tester.pumpAndSettle();

      expect(
        state.desktopOperationError,
        contains('INVALID_ARGUMENT: Linux started concurrently'),
      );
      expect(
        find.descendant(
          of: find.byKey(const Key('desktop-placement-override')),
          matching: find.text('Automatic'),
        ),
        findsOneWidget,
      );
      expect(
        find.descendant(
          of: find.byKey(const Key('desktop-placement-override')),
          matching: find.text('Restart Linux on current display'),
        ),
        findsNothing,
      );
    },
  );

  testWidgets(
    'placement preference dropdown does not overflow on a 360px viewport at 2x text scale',
    (tester) async {
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      tester.view.devicePixelRatio = 1.0;
      tester.view.physicalSize = const Size(360, 800);
      tester.platformDispatcher.textScaleFactorTestValue = 2.0;

      native.runtimeStatus = {
        ...native.runtimeStatus,
        'running': false,
        'isRunning': false,
      };
      native.snapshot = desktopSnapshotV2(
        phase: 'stopped',
        activeId: null,
        width: null,
        height: null,
      );
      await state.refreshStatus();
      await state.refreshDesktopDisplays();

      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: state,
          child: const MaterialApp(home: DisplaySettingsScreen()),
        ),
      );
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(
        find.byKey(const Key('desktop-placement-override')),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.ensureVisible(
        find.byKey(const Key('desktop-placement-override')),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);

      await tester.tap(find.byKey(const Key('desktop-placement-override')));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      await tester.tap(find.text('Automatic').last);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    },
  );
}
