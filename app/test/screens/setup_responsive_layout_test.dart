import 'package:droiddesk/models/linux_environment.dart';
import 'package:droiddesk/screens/setup/setup_progress.dart';
import 'package:droiddesk/screens/welcome_screen.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/linux_environment_test.dart';
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

  Future<void> useNubiaViewport(WidgetTester tester) async {
    tester.view.physicalSize = const Size(360, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
  }

  testWidgets('welcome content fits the Nubia viewport', (tester) async {
    await useNubiaViewport(tester);

    await tester.pumpWidget(
      ChangeNotifierProvider.value(
        value: state,
        child: const MaterialApp(home: WelcomeScreen()),
      ),
    );
    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
  });

  testWidgets('setup failure with logs remains scrollable on Nubia', (
    tester,
  ) async {
    await useNubiaViewport(tester);
    native.runtimeStatus = environmentStatusPayload();
    native.provisionSuccess = false;
    await state.refreshStatus();
    state.selectEnvironment(LinuxEnvironmentKind.prootDebian);

    await tester.pumpWidget(
      ChangeNotifierProvider.value(
        value: state,
        child: const MaterialApp(home: SetupProgressScreen()),
      ),
    );
    await tester.pump();
    for (
      var attempt = 0;
      attempt < 20 && state.errorMessage == null;
      attempt++
    ) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    DroidDeskPlatform.onTerminalOutput?.call(
      List.filled(
        30,
        'A representative package installation log line.',
      ).join('\n'),
    );
    await tester.pump();
    for (
      var attempt = 0;
      attempt < 10 && find.text('Retry').evaluate().isEmpty;
      attempt++
    ) {
      await tester.drag(find.byType(ListView), const Offset(0, -200));
      await tester.pump();
    }

    expect(find.text('Retry'), findsOneWidget);
    expect(find.textContaining(RegExp(r'-\d+%')), findsNothing);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });
}
