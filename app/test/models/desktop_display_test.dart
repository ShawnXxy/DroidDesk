import 'package:droiddesk/models/desktop_display.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('display settings preserve the external input route override', () {
    final settings = DesktopDisplaySettings.fromMap({
      'displayKey': 'monitor-5',
      'renderPolicy': 'native',
      'uiScalePercent': 150,
      'inputMode': 1,
      'inputRoute': 'phoneRelay',
    });

    expect(settings.inputRoute, DesktopExternalInputRoute.phoneRelay);
    expect(settings.toMap()['inputRoute'], 'phoneRelay');
  });
}
