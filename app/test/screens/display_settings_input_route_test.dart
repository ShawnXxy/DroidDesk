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

  testWidgets(
    'monitor input route override is saved for the selected display',
    (tester) async {
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: state,
          child: const MaterialApp(home: DisplaySettingsScreen()),
        ),
      );
      await tester.pumpAndSettle();

      await tester.scrollUntilVisible(
        find.byKey(const Key('desktop-external-input-route')),
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.drag(find.byType(ListView), const Offset(0, -120));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('desktop-external-input-route')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Phone relay').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('desktop-save-settings')));
      await tester.pumpAndSettle();

      final saved = native.settings['monitor-2'];
      expect(
        saved?['inputRoute'],
        DesktopExternalInputRoute.phoneRelay.wireValue,
      );
    },
  );
}
