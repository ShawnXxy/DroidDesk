import 'package:droiddesk/models/desktop_display.dart';
import 'package:droiddesk/models/linux_environment.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/desktop_display_fakes.dart';

void main() {
  test('display settings preserve the external input route override', () {
    final settings = DesktopDisplaySettings.fromMap({
      'displayKey': 'monitor-5',
      'presentationClass': 'externalPointer',
      'stableDisplayKey': 'external:3:DEL|4:4112|8:Monitor ',
      'settingsScope': 'thisDisplay',
      'renderPolicy': 'native',
      'uiScalePercent': 150,
      'inputMode': 1,
      'inputRoute': 'phoneRelay',
    });

    expect(
      settings.presentationClass,
      DesktopPresentationClass.externalPointer,
    );
    expect(settings.presentationClass.label, 'External pointer');
    expect(settings.stableDisplayKey, 'external:3:DEL|4:4112|8:Monitor ');
    expect(settings.settingsScope, DesktopDisplaySettingsScope.thisDisplay);
    expect(settings.settingsScope.label, 'This display');
    expect(settings.inputRoute, DesktopExternalInputRoute.phoneRelay);
    expect(settings.toMap(), {
      'displayKey': 'monitor-5',
      'presentationClass': 'externalPointer',
      'stableDisplayKey': 'external:3:DEL|4:4112|8:Monitor ',
      'settingsScope': 'thisDisplay',
      'renderPolicy': 'native',
      'uiScalePercent': 150,
      'inputMode': 1,
      'inputRoute': 'phoneRelay',
    });
  });

  test('presentation classes expose wire names and human-readable labels', () {
    const cases = [
      ('phoneTouch', DesktopPresentationClass.phoneTouch, 'Phone touch'),
      ('largeTouch', DesktopPresentationClass.largeTouch, 'Large touch'),
      (
        'externalPointer',
        DesktopPresentationClass.externalPointer,
        'External pointer',
      ),
      (
        'externalTouch',
        DesktopPresentationClass.externalTouch,
        'External touch',
      ),
    ];

    for (final (wire, expectedClass, expectedLabel) in cases) {
      final parsed = DesktopDisplaySettings.fromMap({
        'displayKey': 'display-1',
        'presentationClass': wire,
        'stableDisplayKey': null,
        'settingsScope': 'classDefault',
        'renderPolicy': 'native',
        'uiScalePercent': null,
        'inputMode': 1,
        'inputRoute': 'automatic',
      });
      expect(parsed.presentationClass, expectedClass);
      expect(parsed.presentationClass.wireValue, wire);
      expect(parsed.presentationClass.label, expectedLabel);
      expect(parsed.settingsScope, DesktopDisplaySettingsScope.classDefault);
      expect(parsed.settingsScope.label, 'All displays of this type');
    }
  });

  test(
    'defaults scope from stable identity and preserves fields across copyWith',
    () {
      final identified = DesktopDisplaySettings(
        displayKey: 'monitor-2',
        presentationClass: DesktopPresentationClass.externalPointer,
        stableDisplayKey: 'external:monitor-2',
        renderPolicy: DesktopRenderPolicy.nativeResolution,
        uiScalePercent: 150,
        inputMode: 1,
        inputRoute: DesktopExternalInputRoute.phoneRelay,
      );
      final unidentified = DesktopDisplaySettings(
        displayKey: 'monitor-3',
        presentationClass: DesktopPresentationClass.externalTouch,
        renderPolicy: DesktopRenderPolicy.nativeResolution,
        uiScalePercent: null,
        inputMode: 3,
        inputRoute: DesktopExternalInputRoute.automatic,
      );

      expect(identified.settingsScope, DesktopDisplaySettingsScope.thisDisplay);
      expect(identified.hasStableIdentity, isTrue);
      expect(
        unidentified.settingsScope,
        DesktopDisplaySettingsScope.classDefault,
      );
      expect(unidentified.hasStableIdentity, isFalse);

      final edited = identified.copyWith(
        settingsScope: DesktopDisplaySettingsScope.classDefault,
        renderPolicy: DesktopRenderPolicy.cap1440,
        uiScalePercent: null,
      );
      expect(edited.displayKey, 'monitor-2');
      expect(
        edited.presentationClass,
        DesktopPresentationClass.externalPointer,
      );
      expect(edited.stableDisplayKey, 'external:monitor-2');
      expect(edited.settingsScope, DesktopDisplaySettingsScope.classDefault);
      expect(edited.renderPolicy, DesktopRenderPolicy.cap1440);
      expect(edited.uiScalePercent, isNull);
      expect(edited.inputMode, 1);
      expect(edited.inputRoute, DesktopExternalInputRoute.phoneRelay);
    },
  );

  test(
    'rejects unknown enums, missing fields, blank keys, and exact scope without stable identity',
    () {
      Map<Object?, Object?> validMap() => {
        'displayKey': 'monitor-2',
        'presentationClass': 'externalPointer',
        'stableDisplayKey': 'external:monitor-2',
        'settingsScope': 'thisDisplay',
        'renderPolicy': 'native',
        'uiScalePercent': 125,
        'inputMode': 1,
        'inputRoute': 'automatic',
      };

      expect(
        () => DesktopDisplaySettings.fromMap({
          ...validMap(),
          'presentationClass': 'desktopMonitor',
        }),
        throwsFormatException,
      );
      expect(
        () => DesktopDisplaySettings.fromMap({
          ...validMap(),
          'settingsScope': 'global',
        }),
        throwsFormatException,
      );
      expect(
        () => DesktopDisplaySettings.fromMap(
          Map<Object?, Object?>.from(validMap())..remove('stableDisplayKey'),
        ),
        throwsFormatException,
      );
      expect(
        () => DesktopDisplaySettings.fromMap({
          ...validMap(),
          'displayKey': '   ',
        }),
        throwsFormatException,
      );
      expect(
        () => DesktopDisplaySettings.fromMap({
          ...validMap(),
          'stableDisplayKey': '   ',
        }),
        throwsFormatException,
      );
      expect(
        () => DesktopDisplaySettings.fromMap({
          ...validMap(),
          'stableDisplayKey': null,
          'settingsScope': 'thisDisplay',
        }),
        throwsFormatException,
      );

      expect(
        () => DesktopDisplaySettings(
          displayKey: 'monitor-2',
          presentationClass: DesktopPresentationClass.externalPointer,
          stableDisplayKey: null,
          settingsScope: DesktopDisplaySettingsScope.thisDisplay,
          renderPolicy: DesktopRenderPolicy.nativeResolution,
          uiScalePercent: null,
          inputMode: 1,
          inputRoute: DesktopExternalInputRoute.automatic,
        ),
        throwsArgumentError,
      );
      expect(
        () => DesktopDisplaySettings(
          displayKey: 'monitor-2',
          presentationClass: DesktopPresentationClass.externalPointer,
          stableDisplayKey: '  ',
          settingsScope: DesktopDisplaySettingsScope.classDefault,
          renderPolicy: DesktopRenderPolicy.nativeResolution,
          uiScalePercent: null,
          inputMode: 1,
          inputRoute: DesktopExternalInputRoute.automatic,
        ),
        throwsArgumentError,
      );
    },
  );

  test(
    'schema-1 snapshot remains valid and keeps compatibility diagnostics dormant',
    () {
      final defaultState = DesktopDisplayState();
      expect(defaultState.schemaVersion, 1);
      expect(defaultState.supportsCompatibilityDiagnostics, isFalse);

      final parsed = DesktopDisplayState.fromMap(desktopSnapshot());
      expect(parsed.schemaVersion, 1);
      expect(parsed.supportsCompatibilityDiagnostics, isFalse);
      expect(parsed.placementMode, isNull);
      expect(parsed.placementSelectionSource, isNull);
      expect(parsed.placementReason, isNull);
      expect(parsed.automaticMovesAllowed, isNull);
      expect(parsed.placementOverride, isNull);
      expect(parsed.presentationClass, isNull);
      expect(parsed.stableDisplayKey, isNull);
      expect(parsed.inputCapabilities, isNull);
      expect(parsed.externalInputRoute, isNull);
      expect(parsed.rendererPointerCaptureRequested, isNull);
      expect(parsed.relayPointerCaptureRequested, isNull);
      expect(parsed.refreshPreferredRate, isNull);
      expect(parsed.refreshPreferredModeId, isNull);
      expect(parsed.refreshReason, isNull);
      expect(parsed.runtimeCapabilities, isNull);
      expect(parsed.effectivePlacementLabel, isNull);
      expect(parsed.pointerCaptureOwnerLabel, 'Not active');
    },
  );

  test(
    'complete schema-2 snapshot parses placement, input, refresh, and runtime capabilities',
    () {
      final parsed = DesktopDisplayState.fromMap(
        desktopSnapshotV2(
          refreshPreferredRate: 59.94,
          refreshPreferredModeId: 7,
          refreshReason:
              'selected exact-resolution mode 7 (3840x2160@59.94Hz) for target 60.0Hz',
          runtimeCapabilities: runtimeCapabilitiesMap(
            rendererBackend: 'virgl',
            hardwareAccelerationVerified: true,
          ),
        ),
      );

      expect(parsed.schemaVersion, 2);
      expect(parsed.supportsCompatibilityDiagnostics, isTrue);
      expect(parsed.placementMode, DesktopPlacementMode.movable);
      expect(
        parsed.placementSelectionSource,
        DesktopPlacementSelectionSource.runtimeEvidence,
      );
      expect(
        parsed.placementReason,
        'Runtime evidence permits placement across displays',
      );
      expect(parsed.automaticMovesAllowed, isTrue);
      expect(parsed.placementOverride, isNull);
      expect(parsed.effectivePlacementLabel, 'Movable');
      expect(
        parsed.presentationClass,
        DesktopPresentationClass.externalPointer,
      );
      expect(parsed.stableDisplayKey, 'external:3:DEL|4:4112|11:Dell U2723Q');

      final input = parsed.inputCapabilities!;
      expect(input.touchEvidence, DesktopTouchEvidence.none);
      expect(input.pointerAssociation, DesktopPointerAssociation.targetDisplay);
      expect(input.hasMouse, isTrue);
      expect(input.hasTouchpad, isFalse);
      expect(input.hasKeyboard, isTrue);
      expect(input.hasStylus, isFalse);

      expect(
        parsed.externalInputRoute,
        DesktopResolvedExternalInputRoute.native,
      );
      expect(parsed.rendererPointerCaptureRequested, isTrue);
      expect(parsed.relayPointerCaptureRequested, isFalse);
      expect(parsed.pointerCaptureOwnerLabel, 'Desktop renderer');

      expect(parsed.refreshPreferredRate, closeTo(59.94, 0.0001));
      expect(parsed.refreshPreferredModeId, 7);
      expect(
        parsed.refreshReason,
        'selected exact-resolution mode 7 (3840x2160@59.94Hz) for target 60.0Hz',
      );

      final runtime = parsed.runtimeCapabilities!;
      expect(runtime.rendererBackend, 'virgl');
      expect(runtime.hardwareAccelerationVerified, isTrue);
      expect(runtime.host.state, CapabilityEvidenceState.supportedAndProbed);
    },
  );

  test(
    'schema-2 snapshot accepts omitted diagnostics, nullable refresh modeId, and integer refresh rate',
    () {
      final withoutDiagnostics = DesktopDisplayState.fromMap({
        ...desktopSnapshot(),
        'schemaVersion': 2,
      });
      expect(withoutDiagnostics.schemaVersion, 2);
      expect(withoutDiagnostics.supportsCompatibilityDiagnostics, isFalse);
      expect(withoutDiagnostics.placementMode, isNull);
      expect(withoutDiagnostics.runtimeCapabilities, isNull);

      final safeDefault = DesktopDisplayState.fromMap(
        desktopSnapshotV2(
          placementMode: 'movable',
          placementSelectionSource: 'safeDefault',
          placementReason:
              'Placement evidence is inconclusive; automatic moves are disabled',
          automaticMovesAllowed: false,
          presentationClass: null,
          stableDisplayKey: null,
          inputCapabilities: null,
          externalInputRoute: null,
          rendererPointerCaptureRequested: false,
          relayPointerCaptureRequested: false,
          refreshPreferredRate: 60,
          refreshPreferredModeId: null,
          refreshReason: 'requested 60.0Hz without mode switch',
        )..['runtimeCapabilities'] = null,
      );

      expect(safeDefault.supportsCompatibilityDiagnostics, isTrue);
      expect(safeDefault.effectivePlacementLabel, 'Safe default');
      expect(safeDefault.presentationClass, isNull);
      expect(safeDefault.stableDisplayKey, isNull);
      expect(safeDefault.inputCapabilities, isNull);
      expect(safeDefault.externalInputRoute, isNull);
      expect(safeDefault.pointerCaptureOwnerLabel, 'None');
      expect(safeDefault.refreshPreferredRate, 60.0);
      expect(safeDefault.refreshPreferredModeId, isNull);
      expect(safeDefault.runtimeCapabilities, isNull);

      final displayLocalOverride = DesktopDisplayState.fromMap(
        desktopSnapshotV2(
          placementMode: 'displayLocal',
          placementSelectionSource: 'userOverride',
          placementReason: 'The user selected displayLocal placement',
          automaticMovesAllowed: false,
          placementOverride: 'displayLocal',
          externalInputRoute: 'phoneRelay',
          rendererPointerCaptureRequested: false,
          relayPointerCaptureRequested: true,
        ),
      );
      expect(
        displayLocalOverride.effectivePlacementLabel,
        'Display-local restart',
      );
      expect(
        displayLocalOverride.placementOverride,
        DesktopPlacementMode.displayLocal,
      );
      expect(displayLocalOverride.pointerCaptureOwnerLabel, 'Phone relay');
    },
  );

  test(
    'parses all schema-2 enum wire values and rejects unsupported schemas, unknown enums, or invalid scalars',
    () {
      expect(DesktopPlacementMode.values.map((v) => v.wireValue), [
        'movable',
        'displayLocal',
      ]);
      expect(DesktopPlacementSelectionSource.values.map((v) => v.wireValue), [
        'userOverride',
        'runtimeEvidence',
        'deviceQuirk',
        'safeDefault',
      ]);
      expect(DesktopTouchEvidence.values.map((v) => v.wireValue), [
        'none',
        'currentDisplay',
        'unknownAssociation',
      ]);
      expect(DesktopPointerAssociation.values.map((v) => v.wireValue), [
        'noExternalPointer',
        'targetDisplay',
        'defaultOrUnassociated',
        'otherDisplay',
        'unknown',
      ]);
      expect(DesktopResolvedExternalInputRoute.values.map((v) => v.wireValue), [
        'native',
        'phoneRelay',
      ]);

      for (final badSchema in <Object?>[0, 3, -1, '2', 2.0, null]) {
        expect(
          () => DesktopDisplayState.fromMap({
            ...desktopSnapshot(),
            'schemaVersion': badSchema,
          }),
          throwsA(
            isA<FormatException>().having(
              (e) => e.message,
              'message',
              'Unsupported desktop display schema',
            ),
          ),
        );
      }

      final valid = desktopSnapshotV2();
      final invalidOverrides = <Map<Object?, Object?>>[
        {...valid, 'placementMode': 'floating'},
        {...valid, 'placementSelectionSource': 'heuristic'},
        {...valid, 'placementReason': '   '},
        {...valid, 'placementOverride': 'automatic'},
        {...valid, 'presentationClass': 'tablet'},
        {...valid, 'stableDisplayKey': ''},
        {
          ...valid,
          'inputCapabilities': {
            ...(valid['inputCapabilities'] as Map<Object?, Object?>),
            'touchEvidence': 'stylusOnly',
          },
        },
        {
          ...valid,
          'inputCapabilities': {
            ...(valid['inputCapabilities'] as Map<Object?, Object?>),
            'pointerAssociation': 'mirrored',
          },
        },
        {...valid, 'externalInputRoute': 'automatic'},
        {...valid, 'externalInputRoute': 'bluetooth'},
        {...valid, 'refreshPreferredRate': 0},
        {...valid, 'refreshPreferredRate': -60.0},
        {...valid, 'refreshPreferredRate': double.nan},
        {...valid, 'refreshPreferredRate': double.infinity},
        {...valid, 'refreshPreferredModeId': 0},
        {...valid, 'refreshPreferredModeId': -1},
        {...valid, 'refreshReason': '  '},
        {
          ...valid,
          'runtimeCapabilities': {'schemaVersion': 99},
        },
      ];

      for (final payload in invalidOverrides) {
        expect(
          () => DesktopDisplayState.fromMap(payload),
          throwsFormatException,
        );
      }
    },
  );

  test(
    'rejects inconsistent schema-2 placement and pointer-capture state invariants',
    () {
      final valid = desktopSnapshotV2();

      for (final key in [
        'placementMode',
        'placementSelectionSource',
        'placementReason',
        'automaticMovesAllowed',
      ]) {
        expect(
          () => DesktopDisplayState.fromMap({...valid, key: null}),
          throwsFormatException,
        );
      }

      final inconsistentPayloads = <Map<Object?, Object?>>[
        // displayLocalMode disagrees with placementMode
        {
          ...desktopSnapshotV2(
            placementMode: 'displayLocal',
            placementSelectionSource: 'deviceQuirk',
            automaticMovesAllowed: false,
          ),
          'displayLocalMode': false,
        },
        // crossDisplayMovesAllowed disagrees with placementMode
        {...valid, 'crossDisplayMovesAllowed': false},
        // displayLocal cannot allow automatic moves
        {
          ...desktopSnapshotV2(
            placementMode: 'displayLocal',
            placementSelectionSource: 'deviceQuirk',
            automaticMovesAllowed: false,
          ),
          'automaticMovesAllowed': true,
        },
        // safeDefault cannot allow automatic moves
        {
          ...valid,
          'placementSelectionSource': 'safeDefault',
          'automaticMovesAllowed': true,
        },
        // safeDefault must use movable mode
        {
          ...desktopSnapshotV2(
            placementMode: 'displayLocal',
            placementSelectionSource: 'deviceQuirk',
            automaticMovesAllowed: false,
          ),
          'placementSelectionSource': 'safeDefault',
        },
        // userOverride source requires non-null placementOverride
        {
          ...valid,
          'placementSelectionSource': 'userOverride',
          'placementOverride': null,
        },
        // non-null placementOverride requires userOverride source
        {
          ...valid,
          'placementSelectionSource': 'runtimeEvidence',
          'placementOverride': 'movable',
        },
        // placementOverride must match placementMode
        {
          ...valid,
          'placementSelectionSource': 'userOverride',
          'placementMode': 'movable',
          'placementOverride': 'displayLocal',
        },
        // dangling placementOverride without placement tuple
        {
          ...desktopSnapshot(),
          'schemaVersion': 2,
          'placementOverride': 'movable',
        },
        // conflicting pointer-capture ownership
        {
          ...valid,
          'rendererPointerCaptureRequested': true,
          'relayPointerCaptureRequested': true,
        },
      ];

      for (final payload in inconsistentPayloads) {
        expect(
          () => DesktopDisplayState.fromMap(payload),
          throwsFormatException,
        );
      }
    },
  );
}
