import 'dart:async';

import 'package:droiddesk/models/linux_environment.dart';
import 'package:droiddesk/models/package_store.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/widgets/droid_ui.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

class StorageScreen extends StatefulWidget {
  const StorageScreen({super.key});

  @override
  State<StorageScreen> createState() => _StorageScreenState();
}

class _StorageScreenState extends State<StorageScreen> {
  static const _systemColor = DroidTheme.primary;
  static const _homeColor = Color(0xFFBF5AF2);
  static const _cacheColor = DroidTheme.warning;

  LinuxEnvironmentKind? _environment;
  PackageStoreTarget? _target;
  PackageStoreStorage? _storage;
  String? _error;
  bool _loading = true;
  bool _cleaning = false;
  int _packageResultId = -1;
  AppState? _appState;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final state = context.read<AppState>();
    if (!identical(_appState, state)) {
      _appState?.removeListener(_onAppStateChanged);
      _appState = state;
      state.addListener(_onAppStateChanged);
    }
    _syncAppState(state, notify: false);
  }

  void _onAppStateChanged() {
    final state = _appState;
    if (!mounted || state == null) return;
    _syncAppState(state, notify: true);
  }

  void _syncAppState(AppState state, {required bool notify}) {
    final environment = state.selectedEnvironment;
    if (_environment != environment) {
      void reset() {
        _environment = environment;
        _packageResultId = state.packageResultId;
        _target = null;
        _storage = null;
        _loading = true;
        _error = null;
      }

      if (notify) {
        setState(reset);
      } else {
        reset();
      }
      unawaited(_load(environment));
      return;
    }
    if (_packageResultId == state.packageResultId) return;
    _packageResultId = state.packageResultId;
    if (_cleaning) return;
    final result = state.lastPackageStoreResult;
    if (result == null || result.environmentKind != environment) return;
    void updateError() {
      _error = result.error == null
          ? null
          : '${result.error!.code}: ${result.error!.message}';
    }

    if (notify) {
      setState(updateError);
    } else {
      updateError();
    }
    unawaited(_load(environment));
  }

  @override
  void dispose() {
    _appState?.removeListener(_onAppStateChanged);
    super.dispose();
  }

  Future<void> _load(LinuxEnvironmentKind environment) async {
    try {
      final result = await DroidDeskPlatform.queryPackageStore(
        environment: environment,
        query: PackageStoreQueryKind.storage,
      );
      if (!mounted || _environment != environment) return;
      setState(() {
        _target = result.target;
        _storage = result.storage;
        _loading = false;
      });
    } on Object catch (error) {
      if (!mounted || _environment != environment) return;
      setState(() {
        _error = _errorText(error);
        _loading = false;
      });
    }
  }

  Future<void> _clean() async {
    if (_cleaning || context.read<AppState>().activePackage != null) return;
    final environment = _environment;
    if (environment == null) return;
    setState(() {
      _cleaning = true;
      _error = null;
    });
    final result = await context.read<AppState>().cleanSelectedPackageCache(
      environment: environment,
    );
    if (!mounted) return;
    _packageResultId = context.read<AppState>().packageResultId;
    setState(() {
      _cleaning = false;
      _error = result.error == null
          ? null
          : '${result.error!.code}: ${result.error!.message}';
    });
    if (_environment == environment) await _load(environment);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          result.error == null
              ? 'Freed ${_format(result.bytesFreed ?? 0)}'
              : '${result.error!.code}: ${result.error!.message}',
        ),
        backgroundColor: result.error == null
            ? DroidTheme.success
            : DroidTheme.error,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final environment = context.watch<AppState>().selectedEnvironment;
    final targetLabel = _target?.displayName ?? _environmentLabel(environment);
    return Scaffold(
      body: Container(
        decoration: BoxDecoration(gradient: DroidTheme.backgroundGradient),
        child: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const DroidPageHeader(
                title: 'Storage',
                subtitle: 'Space used by the selected Linux environment',
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 14, 20, 2),
                child: _targetChip(targetLabel),
              ),
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 10, 20, 0),
                  child: Text(
                    _error!,
                    style: DroidTheme.bodySm.copyWith(color: DroidTheme.error),
                  ),
                ),
              Expanded(child: _body()),
            ],
          ),
        ),
      ),
    );
  }

  Widget _body() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    final target = _target;
    if (target == null) {
      return _empty('Storage unavailable', _error ?? 'Try again.');
    }
    if (target.availability != PackageStoreAvailability.ready) {
      return _empty(
        'Storage unavailable',
        target.reason ?? 'This package store is unavailable.',
      );
    }
    final storage = _storage;
    if (storage == null || !storage.available) {
      return _empty('Storage unavailable', 'No storage data was returned.');
    }
    return RefreshIndicator(
      onRefresh: () => _load(_environment!),
      child: _content(storage),
    );
  }

  Widget _content(PackageStoreStorage storage) {
    final linux = storage.systemBytes + storage.homeBytes + storage.cacheBytes;
    final other = (storage.deviceTotalBytes - storage.deviceFreeBytes - linux)
        .clamp(0, storage.deviceTotalBytes);
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 20, 20, 32),
      children: [
        DroidGroupCard(
          child: Padding(
            padding: const EdgeInsets.all(18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('LINUX USES', style: DroidTheme.label),
                const SizedBox(height: 6),
                Text(
                  _format(linux),
                  style: DroidTheme.headingXl.copyWith(fontSize: 32),
                ),
                Text(
                  '${storage.packageCount} packages · '
                  '${_format(storage.deviceFreeBytes)} free of '
                  '${_format(storage.deviceTotalBytes)}',
                  style: DroidTheme.bodySm,
                ),
                const SizedBox(height: 18),
                _usageBar(storage.deviceTotalBytes, [
                  (storage.systemBytes, _systemColor),
                  (storage.homeBytes, _homeColor),
                  (storage.cacheBytes, _cacheColor),
                  (other, DroidTheme.textDim.withValues(alpha: 0.45)),
                ]),
                const SizedBox(height: 16),
                _legend('Linux system', storage.systemBytes, _systemColor),
                _legend('Your files (home)', storage.homeBytes, _homeColor),
                _legend('Package cache', storage.cacheBytes, _cacheColor),
                _legend('Other apps & Android', other, DroidTheme.textDim),
                _legend(
                  'Free',
                  storage.deviceFreeBytes,
                  DroidTheme.surfaceBorder,
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 26),
        const DroidSectionLabel('Clean up'),
        const SizedBox(height: 10),
        DroidGroupCard(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Row(
              children: [
                Container(
                  width: 42,
                  height: 42,
                  decoration: BoxDecoration(
                    color: _cacheColor.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Icon(
                    Icons.cleaning_services_rounded,
                    color: _cacheColor,
                    size: 21,
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Package cache',
                        style: DroidTheme.headingSm.copyWith(fontSize: 15),
                      ),
                      Text(
                        storage.cacheBytes > 0
                            ? '${_format(storage.cacheBytes)} of downloaded files'
                            : 'Nothing to clean',
                        style: DroidTheme.bodySm,
                      ),
                    ],
                  ),
                ),
                if (_cleaning)
                  const SizedBox(
                    width: 22,
                    height: 22,
                    child: CircularProgressIndicator(strokeWidth: 2.4),
                  )
                else
                  TextButton(
                    onPressed: storage.cacheBytes > 0 ? _clean : null,
                    child: const Text('CLEAN'),
                  ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  Widget _targetChip(String label) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
    decoration: BoxDecoration(
      color: DroidTheme.primary.withValues(alpha: 0.14),
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: DroidTheme.primary.withValues(alpha: 0.35)),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Icon(Icons.storage_rounded, size: 16, color: DroidTheme.primary),
        const SizedBox(width: 7),
        Text(
          label,
          style: DroidTheme.bodySm.copyWith(fontWeight: FontWeight.w700),
        ),
      ],
    ),
  );

  Widget _empty(String title, String subtitle) => ListView(
    padding: const EdgeInsets.fromLTRB(20, 24, 20, 32),
    children: [
      DroidEmptyCard(
        icon: Icons.storage_rounded,
        title: title,
        subtitle: subtitle,
      ),
    ],
  );

  Widget _usageBar(int total, List<(int, Color)> segments) {
    if (total <= 0) return const SizedBox(height: 10);
    return ClipRRect(
      borderRadius: BorderRadius.circular(5),
      child: Row(
        children: [
          for (final (bytes, color) in segments)
            if (bytes > 0)
              Expanded(
                flex: (bytes * 1000 / total).round().clamp(1, 1000),
                child: Container(height: 10, color: color),
              ),
        ],
      ),
    );
  }

  Widget _legend(String label, int bytes, Color color) => Padding(
    padding: const EdgeInsets.only(top: 7),
    child: Row(
      children: [
        Container(
          width: 9,
          height: 9,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 8),
        Expanded(child: Text(label, style: DroidTheme.bodySm)),
        Text(_format(bytes), style: DroidTheme.monoSm),
      ],
    ),
  );

  String _format(int bytes) {
    if (bytes >= 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
    }
    if (bytes >= 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    }
    if (bytes >= 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '$bytes B';
  }

  String _environmentLabel(LinuxEnvironmentKind environment) =>
      switch (environment) {
        LinuxEnvironmentKind.termuxNative => 'Termux Native',
        LinuxEnvironmentKind.prootDebian => 'Debian PRoot',
        LinuxEnvironmentKind.chrootUbuntu => 'Ubuntu chroot',
      };

  String _errorText(Object error) => switch (error) {
    PlatformException(:final code, :final message) =>
      '$code: ${message ?? 'Storage request failed'}',
    FormatException() => 'Invalid package store response: $error',
    _ => error.toString(),
  };
}
