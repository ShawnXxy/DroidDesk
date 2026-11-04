import 'package:droiddesk/models/linux_environment.dart';
import 'package:droiddesk/screens/setup/environment_selection_screen.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import '../models/linux_environment_test.dart';
import '../support/desktop_display_fakes.dart';

void main() {
  late FakeDesktopPlatform native;
  late AppState state;

  setUp(() {
    native = FakeDesktopPlatform()..install();
    state = AppState();
  });

  tearDown(() {
    state.dispose();
    native.uninstall();
  });

  Future<void> pumpScreen(WidgetTester tester) async {
    await state.refreshStatus();
    await tester.pumpWidget(
      ChangeNotifierProvider.value(
        value: state,
        child: const MaterialApp(home: EnvironmentSelectionScreen()),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('shows compatibility and performance modes only', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      avfFeatureState: 'PRESENT_UNVERIFIED',
      avfPermissionState: 'BLOCKED_BY_PERMISSION',
    );

    await pumpScreen(tester);

    expect(find.text('Compatibility mode'), findsOneWidget);
    expect(find.text('Performance mode'), findsOneWidget);
    expect(find.textContaining('System VM'), findsNothing);
    expect(find.textContaining('Platform permission'), findsOneWidget);
  });

  testWidgets('Native remains selectable', (tester) async {
    native.runtimeStatus = environmentStatusPayload();
    await pumpScreen(tester);

    await tester.tap(find.text('Performance mode'));
    await tester.pumpAndSettle();

    expect(state.selectedEnvironment, LinuxEnvironmentKind.termuxNative);
  });

  testWidgets('low recommended margin warns without blocking', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      availableBytes: 5 * 1024 * 1024 * 1024,
      requiredBytes: 4 * 1024 * 1024 * 1024,
    );
    await pumpScreen(tester);

    expect(find.textContaining('6 GiB'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.widgetWithText(FilledButton, 'Continue'),
      240,
    );
    final continueButton = tester.widget<FilledButton>(
      find.widgetWithText(FilledButton, 'Continue'),
    );
    expect(continueButton.onPressed, isNotNull);
  });

  testWidgets('blocked storage disables Continue and shows reason', (tester) async {
    native.runtimeStatus = environmentStatusPayload(
      prootCandidate: false,
      availableBytes: 2 * 1024 * 1024 * 1024,
      requiredBytes: 4 * 1024 * 1024 * 1024,
    );
    await pumpScreen(tester);
    state.selectEnvironment(LinuxEnvironmentKind.prootDebian);
    await tester.pumpAndSettle();

    expect(find.textContaining('Insufficient transaction storage'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.widgetWithText(FilledButton, 'Continue'),
      240,
    );
    final continueButton = tester.widget<FilledButton>(
      find.widgetWithText(FilledButton, 'Continue'),
    );
    expect(continueButton.onPressed, isNull);
  });
}
