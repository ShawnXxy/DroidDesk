import 'package:droiddesk/models/linux_environment.dart';

enum DesktopDisplayPhase {
  hidden,
  attached,
  moving,
  starting,
  restarting,
  stopped,
  error,
}

enum DesktopDisplayEnvironment { phone, tnt, otherExternal }

enum DesktopProfileKind { phone, wide, monitor }

enum DesktopPresentationClass {
  phoneTouch('phoneTouch', 'Phone touch'),
  largeTouch('largeTouch', 'Large touch'),
  externalPointer('externalPointer', 'External pointer'),
  externalTouch('externalTouch', 'External touch');

  const DesktopPresentationClass(this.wireValue, this.label);
  final String wireValue;
  final String label;

  static DesktopPresentationClass parse(Object? value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => throw const FormatException('Invalid presentation class'),
  );
}

enum DesktopDisplaySettingsScope {
  thisDisplay('thisDisplay', 'This display'),
  classDefault('classDefault', 'All displays of this type');

  const DesktopDisplaySettingsScope(this.wireValue, this.label);
  final String wireValue;
  final String label;

  static DesktopDisplaySettingsScope parse(Object? value) => values.firstWhere(
    (item) => item.wireValue == value,
    orElse: () => throw const FormatException('Invalid display settings scope'),
  );
}

enum DesktopRenderPolicy {
  nativeResolution('native'),
  cap1080('cap1080'),
  cap1440('cap1440'),
  legacyPhone('legacyPhone');

  const DesktopRenderPolicy(this.wireValue);
  final String wireValue;
}

enum DesktopExternalInputRoute {
  automatic('automatic'),
  native('native'),
  phoneRelay('phoneRelay');

  const DesktopExternalInputRoute(this.wireValue);
  final String wireValue;
}

mixin _WireEnum on Enum {
  String get wireValue => name;
  String get label =>
      name[0].toUpperCase() +
      name
          .substring(1)
          .replaceAllMapped(RegExp('[A-Z]'), (m) => ' ${m[0]!.toLowerCase()}');
}

enum DesktopPlacementMode with _WireEnum {
  movable('Move existing desktop view'),
  displayLocal('Restart Linux on current display');

  const DesktopPlacementMode(this.label);
  @override
  final String label;
}

enum DesktopPlacementSelectionSource with _WireEnum {
  userOverride,
  runtimeEvidence,
  deviceQuirk,
  safeDefault,
}

enum DesktopTouchEvidence with _WireEnum {
  none,
  currentDisplay,
  unknownAssociation,
}

enum DesktopPointerAssociation with _WireEnum {
  noExternalPointer,
  targetDisplay,
  defaultOrUnassociated,
  otherDisplay,
  unknown,
}

enum DesktopResolvedExternalInputRoute with _WireEnum {
  native('Android native'),
  phoneRelay('Phone relay');

  const DesktopResolvedExternalInputRoute(this.label);
  @override
  final String label;
}

class DesktopInputCapabilities {
  const DesktopInputCapabilities({
    required this.touchEvidence,
    required this.pointerAssociation,
    required this.hasMouse,
    required this.hasTouchpad,
    required this.hasKeyboard,
    required this.hasStylus,
  });

  factory DesktopInputCapabilities.fromMap(Map<Object?, Object?> m) {
    const touch = DesktopTouchEvidence.values;
    const pointer = DesktopPointerAssociation.values;
    return DesktopInputCapabilities(
      touchEvidence: _enum(touch, m['touchEvidence'], 'touchEvidence'),
      pointerAssociation: _enum(pointer, m['pointerAssociation'], 'pointer'),
      hasMouse: _required<bool>(m, 'hasMouse'),
      hasTouchpad: _required<bool>(m, 'hasTouchpad'),
      hasKeyboard: _required<bool>(m, 'hasKeyboard'),
      hasStylus: _required<bool>(m, 'hasStylus'),
    );
  }

  static DesktopInputCapabilities _p(Object v) =>
      DesktopInputCapabilities.fromMap(_map(v, 'inputCapabilities'));

  final DesktopTouchEvidence touchEvidence;
  final DesktopPointerAssociation pointerAssociation;
  final bool hasMouse, hasTouchpad, hasKeyboard, hasStylus;
}

class DesktopDisplayInfo {
  const DesktopDisplayInfo({
    required this.id,
    required this.key,
    required this.name,
    required this.isDefault,
    required this.eligible,
    this.reason,
  });

  factory DesktopDisplayInfo.fromMap(Map<Object?, Object?> map) {
    final id = _required<int>(map, 'id');
    if (id < 0) throw const FormatException('Invalid display id');
    return DesktopDisplayInfo(
      id: id,
      key: _required<String>(map, 'key'),
      name: _required<String>(map, 'name'),
      isDefault: _required<bool>(map, 'isDefault'),
      eligible: _required<bool>(map, 'eligible'),
      reason: _nullable<String>(map, 'reason'),
    );
  }

  final int id;
  final String key;
  final String name;
  final bool isDefault;
  final bool eligible;
  final String? reason;
}

class DesktopDisplayFailure {
  const DesktopDisplayFailure(this.code, this.message);
  factory DesktopDisplayFailure.fromMap(Map<Object?, Object?> map) =>
      DesktopDisplayFailure(
        _required<String>(map, 'code'),
        _required<String>(map, 'message'),
      );
  final String code;
  final String message;

  @override
  String toString() => '$code: $message';
}

class DesktopDisplayState {
  DesktopDisplayState({
    this.schemaVersion = 1,
    this.phase = DesktopDisplayPhase.hidden,
    this.autoOpenOnMonitor = true,
    this.displayLocalMode = false,
    this.crossDisplayMovesAllowed = true,
    this.sessionRunning = false,
    this.sessionRestarting = false,
    this.sessionEnvironment,
    this.sessionDisplayId,
    this.sessionHeadless = false,
    this.activeDisplayId,
    this.activeDisplayKey,
    this.profileKind,
    this.renderWidth,
    this.renderHeight,
    this.placementMode,
    this.placementSelectionSource,
    this.placementReason,
    this.automaticMovesAllowed,
    this.placementOverride,
    this.presentationClass,
    this.stableDisplayKey,
    this.inputCapabilities,
    this.externalInputRoute,
    this.rendererPointerCaptureRequested,
    this.relayPointerCaptureRequested,
    this.refreshPreferredRate,
    this.refreshPreferredModeId,
    this.refreshReason,
    this.runtimeCapabilities,
    List<DesktopDisplayInfo> displays = const [],
    this.error,
  }) : displays = List.unmodifiable(displays);

  factory DesktopDisplayState.fromMap(Map<Object?, Object?> map) {
    final schemaVersion = map['schemaVersion'];
    if (schemaVersion is! int || (schemaVersion != 1 && schemaVersion != 2)) {
      throw const FormatException('Unsupported desktop display schema');
    }
    final phase = _enum(DesktopDisplayPhase.values, map['phase'], 'phase');
    final width = _nullable<int>(map, 'renderWidth');
    final height = _nullable<int>(map, 'renderHeight');
    if ((width == null) != (height == null) ||
        (width != null && width <= 0) ||
        (height != null && height <= 0)) {
      throw const FormatException('Invalid applied desktop geometry');
    }
    final activeId = _nullable<int>(map, 'activeDisplayId');
    final activeKey = _nullable<String>(map, 'activeDisplayKey');
    final profile = _nullable<String>(map, 'profileKind');
    final error = _nullable<Object>(map, 'error');
    final local = _required<bool>(map, 'displayLocalMode');
    final crossDisplay = _required<bool>(map, 'crossDisplayMovesAllowed');
    final running = _required<bool>(map, 'sessionRunning');
    final restarting = _required<bool>(map, 'sessionRestarting');
    final environment = _nullable<String>(map, 'sessionEnvironment');
    final sessionId = _nullable<int>(map, 'sessionDisplayId');
    final headless = _required<bool>(map, 'sessionHeadless');
    if ((sessionId != null && sessionId < 0) ||
        (sessionId == null) != (environment == null) ||
        (restarting != (phase == DesktopDisplayPhase.restarting)) ||
        (headless &&
            (!local ||
                !running ||
                sessionId == null ||
                activeId != null ||
                (phase != DesktopDisplayPhase.hidden &&
                    phase != DesktopDisplayPhase.restarting)))) {
      throw const FormatException('Invalid desktop session identity');
    }
    if ((activeId != null && activeId < 0) ||
        (activeId == null) != (activeKey == null) ||
        (phase == DesktopDisplayPhase.attached && activeId == null)) {
      throw const FormatException('Invalid active display');
    }
    final rawDisplays = _required<List<Object?>>(map, 'displays');
    final displays = rawDisplays
        .map((value) => DesktopDisplayInfo.fromMap(_map(value, 'display')))
        .toList();
    if (displays.map((display) => display.id).toSet().length !=
            displays.length ||
        displays.map((display) => display.key).toSet().length !=
            displays.length) {
      throw const FormatException('Duplicate desktop display identity');
    }
    const modes = DesktopPlacementMode.values;
    const sources = DesktopPlacementSelectionSource.values;
    const routes = DesktopResolvedExternalInputRoute.values;
    final pMode = _e(modes, map, 'placementMode');
    final pSource = _e(sources, map, 'placementSelectionSource');
    final pReason = _opt<String>(map, 'placementReason');
    final autoMoves = _opt<bool>(map, 'automaticMovesAllowed');
    final pOverride = _e(modes, map, 'placementOverride');
    final pCount = [pMode, pSource, pReason, autoMoves].nonNulls.length;
    const userSrc = DesktopPlacementSelectionSource.userOverride;
    const safeSrc = DesktopPlacementSelectionSource.safeDefault;
    if ((pCount != 0 && pCount != 4) ||
        (pSource == userSrc) != (pOverride != null) ||
        (pOverride != null && pMode != pOverride) ||
        (pCount == 4 &&
            ((pMode == DesktopPlacementMode.displayLocal) != local ||
                (pMode == DesktopPlacementMode.movable) != crossDisplay ||
                (local && autoMoves!) ||
                (pSource == safeSrc && (local || autoMoves!))))) {
      throw const FormatException('Invalid desktop placement diagnostics');
    }
    final rendererCapture = _opt<bool>(map, 'rendererPointerCaptureRequested');
    final relayCapture = _opt<bool>(map, 'relayPointerCaptureRequested');
    if (rendererCapture == true && relayCapture == true) {
      throw const FormatException('Conflicting desktop pointer capture');
    }
    final modeId = _opt<int>(map, 'refreshPreferredModeId');
    if (modeId != null && modeId <= 0) {
      throw const FormatException('Invalid refreshPreferredModeId');
    }
    const rtFromMap = LinuxRuntimeCapabilities.fromMap;
    final pClass = _m(map, 'presentationClass', DesktopPresentationClass.parse);
    final inCaps = _m(map, 'inputCapabilities', DesktopInputCapabilities._p);
    final rtCaps = _m(map, 'runtimeCapabilities', rtFromMap);
    return DesktopDisplayState(
      schemaVersion: schemaVersion,
      phase: phase,
      autoOpenOnMonitor: _required<bool>(map, 'autoOpenOnMonitor'),
      displayLocalMode: local,
      crossDisplayMovesAllowed: crossDisplay,
      sessionRunning: running,
      sessionRestarting: restarting,
      sessionEnvironment: environment == null
          ? null
          : _enum(
              DesktopDisplayEnvironment.values,
              environment,
              'sessionEnvironment',
            ),
      sessionDisplayId: sessionId,
      sessionHeadless: headless,
      activeDisplayId: activeId,
      activeDisplayKey: activeKey,
      profileKind: profile == null
          ? null
          : _enum(DesktopProfileKind.values, profile, 'profileKind'),
      renderWidth: width,
      renderHeight: height,
      placementMode: pMode,
      placementSelectionSource: pSource,
      placementReason: pReason,
      automaticMovesAllowed: autoMoves,
      placementOverride: pOverride,
      presentationClass: pClass,
      stableDisplayKey: _opt<String>(map, 'stableDisplayKey'),
      inputCapabilities: inCaps,
      externalInputRoute: _e(routes, map, 'externalInputRoute'),
      rendererPointerCaptureRequested: rendererCapture,
      relayPointerCaptureRequested: relayCapture,
      refreshPreferredRate: _optRate(map, 'refreshPreferredRate'),
      refreshPreferredModeId: modeId,
      refreshReason: _opt<String>(map, 'refreshReason'),
      runtimeCapabilities: rtCaps,
      displays: displays,
      error: error == null
          ? null
          : DesktopDisplayFailure.fromMap(_map(error, 'error')),
    );
  }

  final int schemaVersion;
  final DesktopDisplayPhase phase;
  final bool autoOpenOnMonitor;
  final bool displayLocalMode;
  final bool crossDisplayMovesAllowed;
  final bool sessionRunning;
  final bool sessionRestarting;
  final DesktopDisplayEnvironment? sessionEnvironment;
  final int? sessionDisplayId;
  final bool sessionHeadless;
  final int? activeDisplayId;
  final String? activeDisplayKey;
  final DesktopProfileKind? profileKind;
  final int? renderWidth;
  final int? renderHeight;
  final DesktopPlacementMode? placementMode, placementOverride;
  final DesktopPlacementSelectionSource? placementSelectionSource;
  final String? placementReason, stableDisplayKey, refreshReason;
  final bool? automaticMovesAllowed;
  final DesktopPresentationClass? presentationClass;
  final DesktopInputCapabilities? inputCapabilities;
  final DesktopResolvedExternalInputRoute? externalInputRoute;
  final bool? rendererPointerCaptureRequested, relayPointerCaptureRequested;
  final double? refreshPreferredRate;
  final int? refreshPreferredModeId;
  final LinuxRuntimeCapabilities? runtimeCapabilities;
  final List<DesktopDisplayInfo> displays;
  final DesktopDisplayFailure? error;

  static const environmentRestartWarning =
      'Opening DroidDesk in another display restarts Linux/XFCE. Save unsaved work first.';

  bool get supportsCompatibilityDiagnostics =>
      schemaVersion == 2 &&
      [
            placementMode,
            placementSelectionSource,
            placementReason,
            automaticMovesAllowed,
          ].nonNulls.length ==
          4;

  String? get effectivePlacementLabel => placementMode == null
      ? null
      : placementSelectionSource == DesktopPlacementSelectionSource.safeDefault
      ? 'Safe default'
      : placementMode == DesktopPlacementMode.displayLocal
      ? 'Display-local restart'
      : 'Movable';

  String get pointerCaptureOwnerLabel => rendererPointerCaptureRequested == true
      ? 'Desktop renderer'
      : relayPointerCaptureRequested == true
      ? 'Phone relay'
      : (rendererPointerCaptureRequested == false ||
            relayPointerCaptureRequested == false)
      ? 'None'
      : 'Not active';

  bool get showEnvironmentWarning => displayLocalMode && sessionRunning;
  bool get isMoving =>
      phase == DesktopDisplayPhase.moving ||
      phase == DesktopDisplayPhase.starting ||
      sessionRestarting;
  Iterable<DesktopDisplayInfo> get monitors =>
      displays.where((display) => !display.isDefault && display.eligible);
}

class DesktopDisplaySettings {
  DesktopDisplaySettings({
    required this.displayKey,
    required this.presentationClass,
    this.stableDisplayKey,
    DesktopDisplaySettingsScope? settingsScope,
    required this.renderPolicy,
    required this.uiScalePercent,
    required this.inputMode,
    required this.inputRoute,
  }) : settingsScope =
           settingsScope ??
           (stableDisplayKey != null
               ? DesktopDisplaySettingsScope.thisDisplay
               : DesktopDisplaySettingsScope.classDefault) {
    if (displayKey.trim().isEmpty ||
        (stableDisplayKey != null && stableDisplayKey!.trim().isEmpty) ||
        (uiScalePercent != null && !uiScales.contains(uiScalePercent)) ||
        inputMode < 1 ||
        inputMode > 3) {
      throw ArgumentError('Invalid desktop display settings');
    }
    if (this.settingsScope == DesktopDisplaySettingsScope.thisDisplay &&
        stableDisplayKey == null) {
      throw ArgumentError(
        'Exact display settings require a stable display key',
      );
    }
  }

  factory DesktopDisplaySettings.fromMap(Map<Object?, Object?> map) {
    final displayKey = _required<String>(map, 'displayKey');
    final stableKey = _nullable<String>(map, 'stableDisplayKey');
    if (displayKey.trim().isEmpty) {
      throw const FormatException('Invalid displayKey');
    }
    if (stableKey != null && stableKey.trim().isEmpty) {
      throw const FormatException('Invalid stableDisplayKey');
    }
    final presentationClass = DesktopPresentationClass.parse(
      map['presentationClass'],
    );
    final scope = DesktopDisplaySettingsScope.parse(map['settingsScope']);
    if (scope == DesktopDisplaySettingsScope.thisDisplay && stableKey == null) {
      throw const FormatException(
        'Exact display settings require a stable display key',
      );
    }
    final scale = _nullable<int>(map, 'uiScalePercent');
    final mode = _required<int>(map, 'inputMode');
    if ((scale != null && !uiScales.contains(scale)) || mode < 1 || mode > 3) {
      throw const FormatException('Invalid desktop scale or input mode');
    }
    final policy = DesktopRenderPolicy.values.where(
      (policy) => policy.wireValue == map['renderPolicy'],
    );
    if (policy.isEmpty) throw const FormatException('Invalid render policy');
    final inputRoute = DesktopExternalInputRoute.values.where(
      (route) => route.wireValue == map['inputRoute'],
    );
    if (inputRoute.isEmpty) {
      throw const FormatException('Invalid external input route');
    }
    return DesktopDisplaySettings(
      displayKey: displayKey,
      presentationClass: presentationClass,
      stableDisplayKey: stableKey,
      settingsScope: scope,
      renderPolicy: policy.single,
      uiScalePercent: scale,
      inputMode: mode,
      inputRoute: inputRoute.single,
    );
  }

  static const uiScales = [100, 125, 150, 175, 200];
  static const Object _unset = Object();

  final String displayKey;
  final DesktopPresentationClass presentationClass;
  final String? stableDisplayKey;
  final DesktopDisplaySettingsScope settingsScope;
  final DesktopRenderPolicy renderPolicy;
  final int? uiScalePercent;
  final int inputMode;
  final DesktopExternalInputRoute inputRoute;

  bool get hasStableIdentity => stableDisplayKey != null;

  DesktopDisplaySettings copyWith({
    String? displayKey,
    DesktopPresentationClass? presentationClass,
    Object? stableDisplayKey = _unset,
    DesktopDisplaySettingsScope? settingsScope,
    DesktopRenderPolicy? renderPolicy,
    Object? uiScalePercent = _unset,
    int? inputMode,
    DesktopExternalInputRoute? inputRoute,
  }) => DesktopDisplaySettings(
    displayKey: displayKey ?? this.displayKey,
    presentationClass: presentationClass ?? this.presentationClass,
    stableDisplayKey: identical(stableDisplayKey, _unset)
        ? this.stableDisplayKey
        : stableDisplayKey as String?,
    settingsScope: settingsScope ?? this.settingsScope,
    renderPolicy: renderPolicy ?? this.renderPolicy,
    uiScalePercent: identical(uiScalePercent, _unset)
        ? this.uiScalePercent
        : uiScalePercent as int?,
    inputMode: inputMode ?? this.inputMode,
    inputRoute: inputRoute ?? this.inputRoute,
  );

  Map<String, Object?> toMap() => {
    'displayKey': displayKey,
    'presentationClass': presentationClass.wireValue,
    'stableDisplayKey': stableDisplayKey,
    'settingsScope': settingsScope.wireValue,
    'renderPolicy': renderPolicy.wireValue,
    'uiScalePercent': uiScalePercent,
    'inputMode': inputMode,
    'inputRoute': inputRoute.wireValue,
  };
}

Map<Object?, Object?> desktopDisplayMap(Object? value) =>
    _map(value, 'desktop display payload');

Map<Object?, Object?> _map(Object? value, String field) {
  if (value is! Map<Object?, Object?>) {
    throw FormatException('Invalid $field');
  }
  return value;
}

T _required<T>(Map<Object?, Object?> map, String field) {
  final value = map[field];
  if (value is! T || (value is String && value.isEmpty)) {
    throw FormatException('Invalid $field');
  }
  return value;
}

T? _nullable<T>(Map<Object?, Object?> map, String field) {
  if (!map.containsKey(field)) throw FormatException('Missing $field');
  return map[field] == null ? null : _required<T>(map, field);
}

T _enum<T extends Enum>(List<T> values, Object? value, String field) =>
    values.firstWhere(
      (item) => item.name == value,
      orElse: () => throw FormatException('Invalid $field'),
    );

R? _m<R>(Map<Object?, Object?> m, String k, R Function(Object) fn) =>
    m[k] == null ? null : fn(m[k]!);

T? _e<T extends Enum>(List<T> v, Map<Object?, Object?> m, String k) =>
    m[k] == null ? null : _enum(v, m[k], k);

T? _opt<T extends Object>(Map<Object?, Object?> m, String k) {
  final v = m[k];
  if (v == null) return null;
  if (v is! T || (v is String && v.trim().isEmpty)) {
    throw FormatException('Invalid $k');
  }
  return v;
}

double? _optRate(Map<Object?, Object?> m, String k) {
  final v = _opt<num>(m, k)?.toDouble();
  if (v != null && (!v.isFinite || v <= 0)) throw FormatException('Invalid $k');
  return v;
}
