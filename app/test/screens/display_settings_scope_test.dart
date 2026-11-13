import 'package:droiddesk/models/desktop_display.dart';
import 'package:droiddesk/screens/display_settings_screen.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:flutter/material.dart';
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

  test(
    'AppState sends scoped display settings and blocks exact-display save without stable identity',
    () async {
      await expectLater(
        state.getDesktopDisplaySettings('   '),
        throwsArgumentError,
      );

      final loaded = await state.getDesktopDisplaySettings('monitor-2');
      expect(
        loaded.presentationClass,
        DesktopPresentationClass.externalPointer,
      );
      expect(loaded.stableDisplayKey, 'external:0:|0:|7:Monitor|monitor-2');
      expect(loaded.settingsScope, DesktopDisplaySettingsScope.thisDisplay);

      await state.saveDesktopDisplaySettings(
        loaded.copyWith(renderPolicy: DesktopRenderPolicy.cap1440),
        scope: DesktopDisplaySettingsScope.classDefault,
      );

      expect(state.desktopOperationError, isNull);
      expect(native.settings['monitor-2'], {
        'displayKey': 'monitor-2',
        'presentationClass': 'externalPointer',
        'stableDisplayKey': 'external:0:|0:|7:Monitor|monitor-2',
        'settingsScope': 'classDefault',
        'renderPolicy': 'cap1440',
        'uiScalePercent': 200,
        'inputMode': 1,
        'inputRoute': 'automatic',
      });

      native.calls.clear();
      final unidentified = DesktopDisplaySettings(
        displayKey: 'monitor-3',
        presentationClass: DesktopPresentationClass.externalTouch,
        stableDisplayKey: null,
        renderPolicy: DesktopRenderPolicy.nativeResolution,
        uiScalePercent: null,
        inputMode: 3,
        inputRoute: DesktopExternalInputRoute.automatic,
      );

      await state.saveDesktopDisplaySettings(
        unidentified,
        scope: DesktopDisplaySettingsScope.thisDisplay,
      );

      expect(
        state.desktopOperationError,
        contains('Exact display settings require a stable display key'),
      );
      expect(
        native.calls.where(
          (call) => call.method == 'saveDesktopDisplaySettings',
        ),
        isEmpty,
      );
    },
  );

  testWidgets(
    'defaults to This display when stable identity exists, shows presentation class, and removes legacy all-monitors copy',
    (tester) async {
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: state,
          child: const MaterialApp(home: DisplaySettingsScreen()),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.text(
          'Render, scale, and input-mode preferences apply to all monitors. Input routing is stored for the selected monitor.',
        ),
        findsNothing,
      );

      await tester.scrollUntilVisible(
        find.byKey(const Key('desktop-presentation-class')),
        250,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('Presentation class: External pointer'), findsOneWidget);

      final scopeDropdown = tester
          .widget<DropdownButtonFormField<DesktopDisplaySettingsScope>>(
            find.byKey(const Key('desktop-settings-scope')),
          );
      expect(
        scopeDropdown.initialValue,
        DesktopDisplaySettingsScope.thisDisplay,
      );

      await tester.tap(find.byKey(const Key('desktop-settings-scope')));
      await tester.pumpAndSettle();
      expect(find.text('This display'), findsWidgets);
      expect(find.text('All displays of this type'), findsWidgets);
      await tester.tap(find.text('All displays of this type').last);
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.byKey(const Key('desktop-ui-scale')),
        250,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.byKey(const Key('desktop-ui-scale')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Automatic').last);
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.byKey(const Key('desktop-save-settings')),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.byKey(const Key('desktop-save-settings')));
      await tester.pumpAndSettle();

      expect(native.settings['monitor-2'], {
        'displayKey': 'monitor-2',
        'presentationClass': 'externalPointer',
        'stableDisplayKey': 'external:0:|0:|7:Monitor|monitor-2',
        'settingsScope': 'classDefault',
        'renderPolicy': 'native',
        'uiScalePercent': null,
        'inputMode': 1,
        'inputRoute': 'automatic',
      });
    },
  );

  testWidgets(
    'falls back to All displays of this type, disables This display, and explains fallback without stable identity',
    (tester) async {
      native.settings['monitor-2'] = {
        'displayKey': 'monitor-2',
        'presentationClass': 'externalTouch',
        'stableDisplayKey': null,
        'settingsScope': 'classDefault',
        'renderPolicy': 'native',
        'uiScalePercent': 150,
        'inputMode': 3,
        'inputRoute': 'automatic',
      };

      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: state,
          child: const MaterialApp(home: DisplaySettingsScreen()),
        ),
      );
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.byKey(const Key('desktop-settings-scope-hint')),
        250,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('Presentation class: External touch'), findsOneWidget);
      expect(
        find.textContaining('Exact display identity is unavailable'),
        findsOneWidget,
      );
      expect(
        find.textContaining('external touch class default'),
        findsOneWidget,
      );

      final scopeDropdown = tester
          .widget<DropdownButtonFormField<DesktopDisplaySettingsScope>>(
            find.byKey(const Key('desktop-settings-scope')),
          );
      expect(
        scopeDropdown.initialValue,
        DesktopDisplaySettingsScope.classDefault,
      );

      await tester.tap(find.byKey(const Key('desktop-settings-scope')));
      await tester.pumpAndSettle();
      final disabledItems = tester
          .widgetList<DropdownMenuItem<DesktopDisplaySettingsScope>>(
            find.byWidgetPredicate(
              (widget) =>
                  widget is DropdownMenuItem<DesktopDisplaySettingsScope> &&
                  widget.value == DesktopDisplaySettingsScope.thisDisplay,
            ),
          )
          .toList();
      expect(disabledItems, isNotEmpty);
      expect(disabledItems.every((item) => !item.enabled), isTrue);

      await tester.tap(find.text('All displays of this type').last);
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.byKey(const Key('desktop-save-settings')),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.byKey(const Key('desktop-save-settings')));
      await tester.pumpAndSettle();

      expect(native.settings['monitor-2']?['stableDisplayKey'], isNull);
      expect(native.settings['monitor-2']?['settingsScope'], 'classDefault');
      expect(
        native.settings['monitor-2']?['presentationClass'],
        'externalTouch',
      );
    },
  );

  testWidgets(
    'switching between identified and unidentified displays updates presentation class and scope',
    (tester) async {
      native.settings['monitor-3'] = {
        'displayKey': 'monitor-3',
        'presentationClass': 'externalTouch',
        'stableDisplayKey': null,
        'settingsScope': 'classDefault',
        'renderPolicy': 'cap1080',
        'uiScalePercent': 125,
        'inputMode': 2,
        'inputRoute': 'native',
      };

      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: state,
          child: const MaterialApp(home: DisplaySettingsScreen()),
        ),
      );
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.byKey(const Key('desktop-presentation-class')),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('Presentation class: External pointer'), findsOneWidget);

      await tester.scrollUntilVisible(
        find.byKey(const Key('desktop-display-target')),
        -200,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.byKey(const Key('desktop-display-target')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Monitor (display 3)').last);
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.byKey(const Key('desktop-settings-scope-hint')),
        250,
        scrollable: find.byType(Scrollable).first,
      );
      expect(find.text('Presentation class: External touch'), findsOneWidget);
      expect(
        find.textContaining('Exact display identity is unavailable'),
        findsOneWidget,
      );
      final scopeDropdown = tester
          .widget<DropdownButtonFormField<DesktopDisplaySettingsScope>>(
            find.byKey(const Key('desktop-settings-scope')),
          );
      expect(
        scopeDropdown.initialValue,
        DesktopDisplaySettingsScope.classDefault,
      );
    },
  );
}
