import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/app_theme.dart';
import 'package:pideck_flutter/host_channel.dart';
import 'package:pideck_flutter/settings/settings_page.dart';

/// 设置页组件可用性：点了要真的发 `settingsAction`。
///
/// 设置页是纯视图 —— 主题、通知、设备这些状态都在 WebView 侧，
/// 这里点下去的每个动作都必须原样回传，否则就是「点了没反应」。
class _Recorder {
  final List<Map<String, Object?>> actions = <Map<String, Object?>>[];
  final List<String> methods = <String>[];

  Map<String, Object?>? last(String type) {
    for (final Map<String, Object?> item in actions.reversed) {
      if ('${item['type']}' == type) return item;
    }
    return null;
  }
}

Future<_Recorder> _mount(WidgetTester tester, Map<String, Object?> data) async {
  final _Recorder recorder = _Recorder();
  tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(hostChannel, (
    MethodCall call,
  ) async {
    recorder.methods.add(call.method);
    if (call.method == 'settingsAction') {
      final Map<Object?, Object?> args = call.arguments as Map<Object?, Object?>;
      recorder.actions.add(
        (jsonDecode('${args['json']}') as Map<Object?, Object?>).map(
          (Object? key, Object? value) => MapEntry<String, Object?>('$key', value),
        ),
      );
    }
    if (call.method == 'getSettings') return jsonEncode(data);
    return null;
  });
  addTearDown(() {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(hostChannel, null);
  });

  await tester.pumpWidget(
    MaterialApp(
      theme: buildPiTheme(Brightness.dark),
      home: SettingsScreen(initialJson: jsonEncode(data)),
    ),
  );
  await tester.pump();
  return recorder;
}

Map<String, Object?> _data() => <String, Object?>{
      'theme': <String, Object?>{
        'mode': 'system',
        'options': <Object?>[
          <String, Object?>{'value': 'system', 'label': '跟随系统'},
          <String, Object?>{'value': 'dark', 'label': '深色'},
          <String, Object?>{'value': 'light', 'label': '浅色'},
        ],
      },
      'notifyEnabled': true,
      'connection': <String, Object?>{'serviceUrl': 'http://10.0.0.2:8787', 'token': 'abc'},
      'devices': <Object?>[],
      'models': <Object?>[],
      'skills': <Object?>[],
      'extensions': <Object?>[],
      'dshPlugins': <Object?>[],
      'dshTarget': '',
    };

void main() {
  testWidgets('外观：点选项发 theme(value)', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _data());

    await tester.tap(find.text('深色'));
    await tester.pump(const Duration(milliseconds: 200));
    expect(recorder.last('theme')?['value'], 'dark', reason: '主题选项点了没发出 theme');
  });

  testWidgets('通知开关发 notify(enabled)', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _data());

    await tester.tap(find.byType(Switch));
    await tester.pump(const Duration(milliseconds: 200));
    expect(recorder.last('notify')?['enabled'], isFalse, reason: '通知开关没发出 notify');
  });

  testWidgets('刷新按钮发 refresh', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _data());

    await tester.tap(find.text('刷新'));
    await tester.pump(const Duration(milliseconds: 200));
    expect(recorder.last('refresh'), isNotNull, reason: '刷新按钮没发出 refresh');
  });

  testWidgets('返回键让宿主直接关闭页面', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _data());

    await tester.tap(find.byIcon(Icons.arrow_back));
    await tester.pump(const Duration(milliseconds: 200));
    expect(recorder.methods.contains('close'), isTrue, reason: '返回没调用 closeHost');
  });

  testWidgets('连接信息原样展示（token 打码由宿主决定，这里只确认不丢字段）', (
    WidgetTester tester,
  ) async {
    await _mount(tester, _data());
    // 「关于」在页面最底下，得先滚下去（侧栏与设置页都是正序分区）
    await tester.dragUntilVisible(
      find.text('服务地址'),
      find.byType(ListView),
      const Offset(0, -240),
    );
    expect(find.text('http://10.0.0.2:8787'), findsOneWidget, reason: '服务地址没画出来');
    // token 短于 8 位时按字符数打成 `***`，长 token 首尾各留 4 位
    expect(find.text('***'), findsOneWidget, reason: '访问令牌没打码');
  });
}
