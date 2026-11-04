import 'package:droiddesk/models/linux_environment.dart';
import 'package:droiddesk/screens/setup/setup_progress.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

class EnvironmentSelectionScreen extends StatelessWidget {
  const EnvironmentSelectionScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();
    final selected = state.selectedEnvironment;
    final blockingReason = state.environmentBlockingReason;

    return Scaffold(
      body: Container(
        decoration: BoxDecoration(gradient: DroidTheme.backgroundGradient),
        child: SafeArea(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(24, 28, 24, 32),
            children: [
              Text('Choose your Linux environment', style: DroidTheme.headingLg),
              const SizedBox(height: 10),
              Text(
                'You can switch later. Only one desktop environment runs at a time.',
                style: DroidTheme.bodyMd,
              ),
              const SizedBox(height: 28),
              _EnvironmentCard(
                selected: selected == LinuxEnvironmentKind.prootDebian,
                enabled: true,
                title: 'Compatibility mode',
                subtitle: 'Debian ARM64 · standard apt and .deb packages',
                badge: 'Recommended when supported',
                icon: Icons.inventory_2_rounded,
                onTap: () => state.selectEnvironment(
                  LinuxEnvironmentKind.prootDebian,
                ),
              ),
              const SizedBox(height: 14),
              _EnvironmentCard(
                selected: selected == LinuxEnvironmentKind.termuxNative,
                enabled: true,
                title: 'Performance mode',
                subtitle: 'Termux Native · lower overhead, smaller package catalog',
                badge: 'Fast fallback',
                icon: Icons.bolt_rounded,
                onTap: () => state.selectEnvironment(
                  LinuxEnvironmentKind.termuxNative,
                ),
              ),
              if (selected == LinuxEnvironmentKind.prootDebian &&
                  blockingReason != null) ...[
                const SizedBox(height: 14),
                _Notice(
                  icon: Icons.error_outline_rounded,
                  color: DroidTheme.error,
                  text: blockingReason,
                ),
              ],
              if (state.showEnvironmentStorageWarning) ...[
                const SizedBox(height: 14),
                _Notice(
                  icon: Icons.storage_rounded,
                  color: DroidTheme.warning,
                  text:
                      'Less than the recommended 6 GiB free space remains. '
                      'Installation can continue, but upgrades may need cleanup.',
                ),
              ],
              if (state.avfDiagnostic case final diagnostic?) ...[
                const SizedBox(height: 14),
                _Notice(
                  icon: Icons.developer_board_rounded,
                  color: DroidTheme.textMuted,
                  text: diagnostic,
                ),
              ],
              const SizedBox(height: 20),
              _Notice(
                icon: Icons.info_outline_rounded,
                color: DroidTheme.textMuted,
                text:
                    'Debian PRoot has no real root, systemd, Docker, kernel '
                    'modules, or mount privileges. Software rendering is the '
                    'portable baseline. Clearing app data removes its Linux files.',
              ),
              const SizedBox(height: 28),
              FilledButton(
                onPressed: state.canProvisionSelectedEnvironment
                    ? () => Navigator.of(context).push(
                          MaterialPageRoute(
                            builder: (_) => const SetupProgressScreen(),
                          ),
                        )
                    : null,
                child: const Text('Continue'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _EnvironmentCard extends StatelessWidget {
  const _EnvironmentCard({
    required this.selected,
    required this.enabled,
    required this.title,
    required this.subtitle,
    required this.badge,
    required this.icon,
    required this.onTap,
  });

  final bool selected;
  final bool enabled;
  final String title;
  final String subtitle;
  final String badge;
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected ? DroidTheme.primary.withValues(alpha: 0.13) : DroidTheme.surface,
      borderRadius: BorderRadius.circular(20),
      child: InkWell(
        borderRadius: BorderRadius.circular(20),
        onTap: enabled ? onTap : null,
        child: Container(
          padding: const EdgeInsets.all(20),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20),
            border: Border.all(
              color: selected ? DroidTheme.primary : DroidTheme.surfaceBorder,
              width: selected ? 2 : 1,
            ),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(icon, color: selected ? DroidTheme.primary : DroidTheme.textMuted),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: DroidTheme.headingSm),
                    const SizedBox(height: 5),
                    Text(subtitle, style: DroidTheme.bodyMd),
                    const SizedBox(height: 10),
                    Text(
                      badge,
                      style: DroidTheme.bodySm.copyWith(
                        color: selected ? DroidTheme.primary : DroidTheme.textMuted,
                      ),
                    ),
                  ],
                ),
              ),
              Icon(
                selected
                    ? Icons.radio_button_checked_rounded
                    : Icons.radio_button_unchecked_rounded,
                color: selected ? DroidTheme.primary : DroidTheme.textMuted,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice({
    required this.icon,
    required this.color,
    required this.text,
  });

  final IconData icon;
  final Color color;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: color),
          const SizedBox(width: 10),
          Expanded(child: Text(text, style: DroidTheme.bodySm)),
        ],
      ),
    );
  }
}
