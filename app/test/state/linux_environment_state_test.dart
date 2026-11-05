import 'package:droiddesk/models/linux_environment.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/linux_environment_test.dart';
import '../support/desktop_display_fakes.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
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

  test('non-root setup preselects Debian when required probes pass', () async {
    native.runtimeStatus = environmentStatusPayload();

    await state.refreshStatus();

    expect(state.selectedEnvironment, LinuxEnvironmentKind.prootDebian);
    expect(state.canProvisionSelectedEnvironment, isTrue);
  });

  test('failed PRoot probe preselects Native and retains the reason', () async {
    native.runtimeStatus = environmentStatusPayload(
      prootCandidate: false,
      prootState: 'FAILED_SELF_TEST',
    );

    await state.refreshStatus();

    expect(state.selectedEnvironment, LinuxEnvironmentKind.termuxNative);
    expect(state.environmentRecommendationReason, contains('ptrace failed'));
  });

  test(
    'failed audio probe blocks Debian in both native and Flutter contracts',
    () async {
      final payload = environmentStatusPayload(prootCandidate: false);
      final capabilities = payload['capabilities']! as Map<Object?, Object?>;
      capabilities['audio'] = {
        'state': 'FAILED_SELF_TEST',
        'reason': 'PulseAudio connection failed',
      };
      native.runtimeStatus = payload;

      await state.refreshStatus();
      state.selectEnvironment(LinuxEnvironmentKind.prootDebian);

      expect(state.canProvisionSelectedEnvironment, isFalse);
      expect(state.environmentBlockingReason, 'PulseAudio connection failed');
    },
  );

  test('existing Native install remains selected after upgrade', () async {
    native.runtimeStatus = environmentStatusPayload(
      kind: 'termuxNative',
      installed: true,
    );

    await state.refreshStatus();

    expect(state.selectedEnvironment, LinuxEnvironmentKind.termuxNative);
  });

  test('switching a running environment stops before provisioning', () async {
    native.runtimeStatus = environmentStatusPayload(
      kind: 'termuxNative',
      installed: true,
      running: true,
    );
    await state.refreshStatus();

    final ok = await state.runSetup(
      environment: LinuxEnvironmentKind.prootDebian,
    );

    expect(ok, isTrue);
    final methods = native.calls.map((call) => call.method).toList();
    expect(
      methods.indexOf('stopLinux'),
      lessThan(methods.indexOf('provisionEnvironment')),
    );
  });

  test('insufficient transaction storage blocks provisioning', () async {
    native.runtimeStatus = environmentStatusPayload(
      prootCandidate: false,
      availableBytes: 2 * 1024 * 1024 * 1024,
      requiredBytes: 4 * 1024 * 1024 * 1024,
    );

    await state.refreshStatus();
    state.selectEnvironment(LinuxEnvironmentKind.prootDebian);

    expect(state.canProvisionSelectedEnvironment, isFalse);
    expect(state.environmentBlockingReason, contains('storage'));
  });

  test('provisioning preserves the native failure reason', () async {
    SharedPreferences.setMockInitialValues({});
    await state.initialize();
    native.runtimeStatus = environmentStatusPayload();
    native.provisionSuccess = false;
    native.provisionFailureMessage = 'Docker Hub and ECR were unreachable';
    await state.refreshStatus();

    final ok = await state.runSetup(
      environment: LinuxEnvironmentKind.prootDebian,
    );

    expect(ok, isFalse);
    expect(state.errorMessage, 'Docker Hub and ECR were unreachable');
  });

  test(
    'rooted chroot setup persists its selection through managed provisioning',
    () async {
      native.hasRoot = true;
      native.runtimeStatus = environmentStatusPayload(
        kind: 'termuxNative',
        installed: true,
      );
      await state.refreshStatus();

      final ok = await state.runSetup(useRoot: true);

      expect(ok, isTrue);
      final provision = native.calls.lastWhere(
        (call) => call.method == 'provisionEnvironment',
      );
      expect(provision.arguments, {'kind': 'chrootUbuntu', 'de': 'xfce4'});
      expect(state.selectedEnvironment, LinuxEnvironmentKind.chrootUbuntu);
    },
  );
}
