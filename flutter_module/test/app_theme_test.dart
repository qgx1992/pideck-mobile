import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/app_theme.dart';
import 'package:pideck_flutter/settings/settings_page.dart';

void main() {
  test('themeModeFrom 认 Web 侧那三个值，其余按深色', () {
    expect(themeModeFrom('light'), ThemeMode.light);
    expect(themeModeFrom('system'), ThemeMode.system);
    expect(themeModeFrom('dark'), ThemeMode.dark);
    expect(themeModeFrom(''), ThemeMode.dark);
    expect(themeModeFrom('whatever'), ThemeMode.dark);
  });

  test('两套调色板亮暗相反', () {
    expect(PiColors.dark.background.computeLuminance(),
        lessThan(PiColors.light.background.computeLuminance()));
    expect(PiColors.dark.text.computeLuminance(),
        greaterThan(PiColors.light.text.computeLuminance()));
  });

  testWidgets('宿主推来 light 时把主题模式报上去', (tester) async {
    final reported = <ThemeMode>[];
    await tester.pumpWidget(
      MaterialApp(
        theme: buildPiTheme(Brightness.light),
        darkTheme: buildPiTheme(Brightness.dark),
        themeMode: ThemeMode.dark,
        home: SettingsScreen(
          initialJson: jsonEncode({
            'theme': {
              'mode': 'light',
              'options': [
                {'value': 'dark', 'label': '深色'},
                {'value': 'light', 'label': '浅色'},
              ],
            },
          }),
          onThemeModeChanged: reported.add,
        ),
      ),
    );
    await tester.pump();

    expect(reported, [ThemeMode.light]);
    // 初始数据够用就不该再去拉一次
    expect(find.text('正在读取设置…'), findsNothing);
    expect(find.text('浅色'), findsOneWidget);
  });
}
