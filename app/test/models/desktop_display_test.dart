import 'package:droiddesk/models/desktop_display.dart';
import 'package:flutter_test/flutter_test.dart';

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
}
