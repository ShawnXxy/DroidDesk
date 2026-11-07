import 'dart:async';

import 'package:droiddesk/models/linux_environment.dart';
import 'package:droiddesk/models/package_store.dart';
import 'package:droiddesk/services/platform_bridge.dart';
import 'package:droiddesk/state/app_state.dart';
import 'package:droiddesk/theme/droid_theme.dart';
import 'package:droiddesk/widgets/droid_ui.dart';
import 'package:droiddesk/widgets/linux_app_icon.dart';
import 'package:droiddesk/widgets/package_operation_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

class AppCatalogScreen extends StatefulWidget {
  const AppCatalogScreen({super.key});

  @override
  State<AppCatalogScreen> createState() => _AppCatalogScreenState();
}

class _AppCatalogScreenState extends State<AppCatalogScreen> {
  final _searchController = TextEditingController();
  Timer? _searchDebounce;
  LinuxEnvironmentKind? _environment;
  PackageStoreTarget? _target;
  List<PackageStoreEntry> _featured = const [];
  List<PackageStoreEntry> _searchResults = const [];
  List<PackageStoreEntry> _installed = const [];
  List<PackageStoreEntry> _updates = const [];
  String? _operationError;
  bool _loading = true;
  bool _searching = false;
  bool _loadingUpdates = false;
  int _tab = 0;
  int _packageResultId = -1;
  bool _localMutationPending = false;
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
        _featured = const [];
        _searchResults = const [];
        _installed = const [];
        _updates = const [];
        _loading = true;
        _operationError = null;
      }

      if (notify) {
        setState(reset);
      } else {
        reset();
      }
      unawaited(_reload(environment));
      return;
    }
    if (_packageResultId == state.packageResultId) return;
    _packageResultId = state.packageResultId;
    if (_localMutationPending) return;
    final result = state.lastPackageStoreResult;
    if (result == null ||
        result.environmentKind != environment ||
        result.kind == PackageOperationKind.cleanCache) {
      return;
    }
    void updateError() {
      _operationError = result.error == null
          ? null
          : '${result.error!.code}: ${result.error!.message}';
    }

    if (notify) {
      setState(updateError);
    } else {
      updateError();
    }
    unawaited(_refreshMutationState(environment));
  }

  @override
  void dispose() {
    _appState?.removeListener(_onAppStateChanged);
    _searchDebounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _reload(LinuxEnvironmentKind environment) async {
    try {
      final results = await Future.wait([
        DroidDeskPlatform.queryPackageStore(
          environment: environment,
          query: PackageStoreQueryKind.catalog,
        ),
        DroidDeskPlatform.queryPackageStore(
          environment: environment,
          query: PackageStoreQueryKind.installed,
        ),
      ]);
      if (!mounted || _environment != environment) return;
      setState(() {
        _target = results[0].target;
        _featured = results[0].entries;
        _searchResults = results[0].entries;
        _installed = results[1].entries;
        _updates = const [];
        _loading = false;
      });
      final search = _searchController.text.trim();
      if (search.isNotEmpty && mounted && _environment == environment) {
        await _search(search);
      }
    } on Object catch (error) {
      if (!mounted || _environment != environment) return;
      setState(() {
        _operationError = _errorText(error);
        _loading = false;
      });
    }
  }

  void _queueSearch(String query) {
    _searchDebounce?.cancel();
    _searchDebounce = Timer(
      const Duration(milliseconds: 350),
      () => _search(query),
    );
  }

  Future<void> _search(String query) async {
    final environment = _environment;
    if (environment == null ||
        _target?.availability != PackageStoreAvailability.ready) {
      return;
    }
    setState(() => _searching = true);
    try {
      final result = await DroidDeskPlatform.queryPackageStore(
        environment: environment,
        query: PackageStoreQueryKind.catalog,
        search: query.trim(),
      );
      if (!mounted ||
          _environment != environment ||
          query.trim() != _searchController.text.trim()) {
        return;
      }
      setState(() {
        _target = result.target;
        _searchResults = result.entries;
      });
    } on Object catch (error) {
      if (mounted) setState(() => _operationError = _errorText(error));
    } finally {
      if (mounted) setState(() => _searching = false);
    }
  }

  Future<void> _loadInstalled() async {
    final environment = _environment;
    if (environment == null) return;
    try {
      final result = await DroidDeskPlatform.queryPackageStore(
        environment: environment,
        query: PackageStoreQueryKind.installed,
      );
      if (!mounted || _environment != environment) return;
      setState(() {
        _target = result.target;
        _installed = result.entries;
      });
    } on Object catch (error) {
      if (mounted) setState(() => _operationError = _errorText(error));
    }
  }

  Future<void> _loadUpdates({bool refresh = false}) async {
    final environment = _environment;
    if (environment == null) return;
    setState(() => _loadingUpdates = true);
    try {
      final result = await DroidDeskPlatform.queryPackageStore(
        environment: environment,
        query: PackageStoreQueryKind.updates,
        refreshIndex: refresh,
      );
      if (!mounted || _environment != environment) return;
      setState(() {
        _target = result.target;
        _updates = result.entries;
      });
    } on Object catch (error) {
      if (mounted) setState(() => _operationError = _errorText(error));
    } finally {
      if (mounted) setState(() => _loadingUpdates = false);
    }
  }

  void _selectTab(int index) {
    setState(() => _tab = index);
    if (index == 2) unawaited(_loadUpdates());
    if (index == 3) unawaited(_loadInstalled());
  }

  Future<void> _install(PackageStoreEntry entry) async {
    final environment = _environment;
    if (environment == null) return;
    var acceptExperimentalRisk = false;
    if (entry.supportLevel == PackageSupportLevel.experimental) {
      final accepted = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Install experimental application?'),
          content: Text(
            [
              '${entry.displayName} has not completed all compatibility and sandbox checks.',
              ...entry.limitations,
            ].join('\n\n'),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Install anyway'),
            ),
          ],
        ),
      );
      if (accepted != true) return;
      acceptExperimentalRisk = true;
    }
    if (mounted) setState(() => _operationError = null);
    _localMutationPending = true;
    final result = await context.read<AppState>().installPackageEntry(
      entry,
      environment: environment,
      acceptExperimentalRisk: acceptExperimentalRisk,
    );
    await _finishMutation(result, environment);
  }

  Future<void> _remove(PackageStoreEntry entry) async {
    final environment = _environment;
    if (environment == null) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Remove ${entry.displayName}?'),
        content: const Text(
          'The application package will be removed from this Linux environment.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Remove'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _operationError = null);
    _localMutationPending = true;
    final result = await context.read<AppState>().removePackageEntry(
      entry,
      environment: environment,
    );
    await _finishMutation(result, environment);
  }

  Future<void> _upgrade(PackageStoreEntry entry) async {
    final environment = _environment;
    if (environment == null) return;
    setState(() => _operationError = null);
    _localMutationPending = true;
    final result = await context.read<AppState>().upgradePackageEntry(
      entry,
      environment: environment,
    );
    await _finishMutation(result, environment);
  }

  Future<void> _upgradeAll() async {
    final environment = _environment;
    if (environment == null) return;
    setState(() => _operationError = null);
    _localMutationPending = true;
    final result = await context.read<AppState>().upgradeAllPackageEntries(
      environment: environment,
    );
    await _finishMutation(result, environment);
  }

  Future<void> _finishMutation(
    PackageOperationResult result,
    LinuxEnvironmentKind environment,
  ) async {
    if (!mounted) return;
    _localMutationPending = false;
    _packageResultId = context.read<AppState>().packageResultId;
    setState(() {
      _operationError = result.error == null
          ? null
          : '${result.error!.code}: ${result.error!.message}';
    });
    await _refreshMutationState(environment);
  }

  Future<void> _refreshMutationState(LinuxEnvironmentKind environment) async {
    if (_environment != environment) return;
    await _reload(environment);
    if (mounted && _environment == environment && _tab == 2) {
      await _loadUpdates();
    }
  }

  @override
  Widget build(BuildContext context) {
    final environment = context.watch<AppState>().selectedEnvironment;
    final targetLabel = _target?.displayName ?? _environmentLabel(environment);
    return Scaffold(
      body: Container(
        decoration: BoxDecoration(gradient: DroidTheme.backgroundGradient),
        child: SafeArea(
          bottom: false,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const DroidPageHeader(
                title: 'Linux App Store',
                subtitle: 'Apps and tools for your selected Linux environment',
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 14, 20, 2),
                child: _targetChip(targetLabel),
              ),
              if (_operationError != null)
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 10, 20, 0),
                  child: Text(
                    _operationError!,
                    style: DroidTheme.bodySm.copyWith(color: DroidTheme.error),
                  ),
                ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 18, 20, 8),
                child: DroidSegmentedTabs(
                  labels: const ['Featured', 'Browse', 'Updates', 'Installed'],
                  icons: const [
                    Icons.auto_awesome_rounded,
                    Icons.search_rounded,
                    Icons.system_update_alt_rounded,
                    Icons.check_circle_outline_rounded,
                  ],
                  selected: _tab,
                  onChanged: _selectTab,
                ),
              ),
              Expanded(child: _body()),
            ],
          ),
        ),
      ),
      bottomNavigationBar: const SafeArea(
        top: false,
        child: PackageOperationCard(margin: EdgeInsets.fromLTRB(14, 0, 14, 12)),
      ),
    );
  }

  Widget _body() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    final target = _target;
    if (target == null) {
      return _empty(
        'Package store unavailable',
        _operationError ?? 'Try again.',
      );
    }
    if (target.availability != PackageStoreAvailability.ready) {
      return _empty(
        'Package store unavailable',
        target.reason ?? 'This environment is unavailable.',
      );
    }
    return switch (_tab) {
      0 => _featuredView(),
      1 => _browseView(),
      2 => _updatesView(),
      _ => _installedView(),
    };
  }

  Widget _featuredView() {
    if (_featured.isEmpty) {
      return _empty(
        'No featured applications',
        'No curated applications are available.',
      );
    }
    final hero = _featured.first;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
      children: [
        _heroCard(hero),
        if (_featured.length > 1) ...[
          const SizedBox(height: 26),
          const DroidSectionLabel('Available applications'),
          const SizedBox(height: 10),
          DroidGroupCard(
            child: Column(
              children: [
                for (var index = 1; index < _featured.length; index++) ...[
                  if (index > 1) const DroidInsetDivider(indent: 76),
                  _packageRow(_featured[index]),
                ],
              ],
            ),
          ),
        ],
      ],
    );
  }

  Widget _heroCard(PackageStoreEntry entry) {
    final color = _categoryColor(entry.category);
    return Container(
      padding: const EdgeInsets.all(20),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(DroidTheme.radiusXl),
        gradient: LinearGradient(
          colors: [
            color.withValues(alpha: 0.30),
            color.withValues(alpha: 0.08),
          ],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        border: Border.all(color: color.withValues(alpha: 0.30)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            entry.supportLevel.wireValue.toUpperCase(),
            style: DroidTheme.label.copyWith(color: color, letterSpacing: 1),
          ),
          const SizedBox(height: 14),
          Row(
            children: [
              _entryIcon(entry, color, size: 60),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(entry.displayName, style: DroidTheme.headingMd),
                    const SizedBox(height: 3),
                    Text(entry.summary, style: DroidTheme.bodySm),
                  ],
                ),
              ),
            ],
          ),
          if (entry.limitations.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text(entry.limitations.join('\n'), style: DroidTheme.bodySm),
          ],
          const SizedBox(height: 16),
          _actionButton(entry, prominent: true),
        ],
      ),
    );
  }

  Widget _browseView() => Column(
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 10),
        child: DroidSearchField(
          controller: _searchController,
          hint: 'Search curated apps and tools',
          onChanged: _queueSearch,
          busy: _searching,
        ),
      ),
      Expanded(
        child: _searchResults.isEmpty && !_searching
            ? _empty('No matching applications', 'Try another name or keyword.')
            : ListView(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
                children: [
                  if (_searchResults.isNotEmpty)
                    DroidGroupCard(
                      child: Column(
                        children: [
                          for (
                            var index = 0;
                            index < _searchResults.length;
                            index++
                          ) ...[
                            if (index > 0) const DroidInsetDivider(indent: 76),
                            _packageRow(_searchResults[index]),
                          ],
                        ],
                      ),
                    ),
                ],
              ),
      ),
    ],
  );

  Widget _updatesView() {
    final state = context.watch<AppState>();
    if (_loadingUpdates)
      return const Center(child: CircularProgressIndicator());
    return RefreshIndicator(
      onRefresh: () => _loadUpdates(refresh: true),
      child: ListView(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
        children: [
          DroidGroupCard(
            child: Padding(
              padding: const EdgeInsets.all(18),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      _updates.isEmpty
                          ? 'Everything is up to date'
                          : '${_updates.length} update${_updates.length == 1 ? '' : 's'} available',
                      style: DroidTheme.headingSm,
                    ),
                  ),
                  if (_updates.isNotEmpty)
                    _capsule(
                      label: 'Update all',
                      color: DroidTheme.primary,
                      onTap: state.activePackage == null ? _upgradeAll : null,
                    ),
                ],
              ),
            ),
          ),
          if (_updates.isNotEmpty) ...[
            const SizedBox(height: 20),
            DroidGroupCard(
              child: Column(
                children: [
                  for (var index = 0; index < _updates.length; index++) ...[
                    if (index > 0) const DroidInsetDivider(indent: 76),
                    _packageRow(_updates[index], update: true),
                  ],
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _installedView() => RefreshIndicator(
    onRefresh: _loadInstalled,
    child: _installed.isEmpty
        ? _empty(
            'Nothing installed yet',
            'Applications installed in this environment appear here.',
          )
        : ListView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
            children: [
              const DroidSectionLabel('Installed applications'),
              const SizedBox(height: 10),
              DroidGroupCard(
                child: Column(
                  children: [
                    for (var index = 0; index < _installed.length; index++) ...[
                      if (index > 0) const DroidInsetDivider(indent: 76),
                      _packageRow(_installed[index], allowRemove: true),
                    ],
                  ],
                ),
              ),
            ],
          ),
  );

  Widget _packageRow(
    PackageStoreEntry entry, {
    bool allowRemove = false,
    bool update = false,
  }) {
    final color = _categoryColor(entry.category);
    final metadata = [
      entry.supportLevel.wireValue.toUpperCase(),
      entry.sourceLabel,
      if (entry.installedVersion != null) entry.installedVersion!,
    ].join(' · ');
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 12, 12, 12),
      child: Row(
        children: [
          _entryIcon(entry, color),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  entry.displayName,
                  style: DroidTheme.headingSm.copyWith(fontSize: 15),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                const SizedBox(height: 2),
                Text(entry.summary, style: DroidTheme.bodySm),
                const SizedBox(height: 3),
                Text(metadata, style: DroidTheme.monoSm),
                if (entry.limitations.isNotEmpty) ...[
                  const SizedBox(height: 3),
                  Text(entry.limitations.join('\n'), style: DroidTheme.bodySm),
                ],
              ],
            ),
          ),
          const SizedBox(width: 10),
          _actionButton(entry, allowRemove: allowRemove, update: update),
        ],
      ),
    );
  }

  Widget _actionButton(
    PackageStoreEntry entry, {
    bool allowRemove = false,
    bool update = false,
    bool prominent = false,
  }) {
    final state = context.watch<AppState>();
    final busy = state.activePackage != null;
    final active = state.activePackage == entry.entryId;
    if (active) {
      final progress = state.packageProgress;
      return SizedBox(
        width: prominent ? double.infinity : 74,
        child: LinearProgressIndicator(
          value: state.activePackagePhase == null ? null : progress,
        ),
      );
    }
    if (entry.state == PackageEntryState.unavailable &&
        entry.supportLevel == PackageSupportLevel.experimental) {
      return _capsule(
        label: 'Unavailable',
        color: DroidTheme.textMuted,
        onTap: null,
      );
    }
    if (update || entry.state == PackageEntryState.updateAvailable) {
      return _capsule(
        label: 'Update',
        color: DroidTheme.primary,
        onTap: busy ? null : () => _upgrade(entry),
      );
    }
    if (allowRemove && entry.removable) {
      return _capsule(
        label: 'Remove',
        color: DroidTheme.error,
        onTap: busy ? null : () => _remove(entry),
      );
    }
    if (entry.state == PackageEntryState.installed) {
      return _capsule(
        label: 'Installed',
        color: DroidTheme.accent,
        onTap: null,
      );
    }
    return _capsule(
      label: 'Get',
      color: DroidTheme.primary,
      filled: prominent,
      onTap: busy ? null : () => _install(entry),
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
        const Icon(
          Icons.inventory_2_rounded,
          size: 16,
          color: DroidTheme.primary,
        ),
        const SizedBox(width: 7),
        Text(
          label,
          style: DroidTheme.bodySm.copyWith(fontWeight: FontWeight.w700),
        ),
      ],
    ),
  );

  Widget _entryIcon(PackageStoreEntry entry, Color color, {double size = 46}) =>
      LinuxAppIcon(
        package: entry.entryId,
        iconNames: entry.iconNames,
        size: size,
        fallback: Container(
          width: size,
          height: size,
          decoration: BoxDecoration(
            color: color.withValues(alpha: 0.15),
            borderRadius: BorderRadius.circular(size * 0.28),
          ),
          child: Icon(_categoryIcon(entry.category, entry.gui), color: color),
        ),
      );

  Widget _empty(String title, String subtitle) => ListView(
    padding: const EdgeInsets.fromLTRB(20, 24, 20, 32),
    children: [
      DroidEmptyCard(
        icon: Icons.inventory_2_outlined,
        title: title,
        subtitle: subtitle,
      ),
    ],
  );

  Widget _capsule({
    required String label,
    required Color color,
    required VoidCallback? onTap,
    bool filled = false,
  }) {
    final enabled = onTap != null;
    return Material(
      color: filled
          ? color.withValues(alpha: enabled ? 1 : 0.4)
          : color.withValues(alpha: enabled ? 0.14 : 0.06),
      borderRadius: BorderRadius.circular(20),
      child: InkWell(
        borderRadius: BorderRadius.circular(20),
        onTap: onTap,
        child: Container(
          constraints: const BoxConstraints(minWidth: 74),
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 7),
          alignment: Alignment.center,
          child: Text(
            label.toUpperCase(),
            style: DroidTheme.label.copyWith(
              fontSize: 13,
              color: filled
                  ? Colors.white
                  : color.withValues(alpha: enabled ? 1 : 0.4),
            ),
          ),
        ),
      ),
    );
  }

  IconData _categoryIcon(String category, bool gui) {
    final value = category.toLowerCase();
    if (value.contains('internet')) return Icons.public_rounded;
    if (value.contains('develop')) return Icons.code_rounded;
    if (value.contains('graphic')) return Icons.palette_rounded;
    if (value.contains('media')) return Icons.movie_rounded;
    if (value.contains('office')) return Icons.description_rounded;
    return gui ? Icons.apps_rounded : Icons.terminal_rounded;
  }

  Color _categoryColor(String category) {
    final value = category.toLowerCase();
    if (value.contains('internet')) return const Color(0xFF39A0FF);
    if (value.contains('develop')) return const Color(0xFF9B7BFF);
    if (value.contains('graphic')) return const Color(0xFFFF6B9E);
    if (value.contains('media')) return const Color(0xFFFF9F43);
    if (value.contains('office')) return const Color(0xFF38C793);
    return DroidTheme.primaryLight;
  }

  String _environmentLabel(LinuxEnvironmentKind environment) =>
      switch (environment) {
        LinuxEnvironmentKind.termuxNative => 'Termux Native',
        LinuxEnvironmentKind.prootDebian => 'Debian PRoot',
        LinuxEnvironmentKind.chrootUbuntu => 'Ubuntu chroot',
      };

  String _errorText(Object error) => switch (error) {
    PlatformException(:final code, :final message) =>
      '$code: ${message ?? 'Package store request failed'}',
    FormatException() => 'Invalid package store response: $error',
    _ => error.toString(),
  };
}
