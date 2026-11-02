import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/app_theme.dart';
import 'package:pideck_flutter/chat/chat_page.dart';
import 'package:pideck_flutter/host_channel.dart';

/// 聊天页组件可用性：**点下去要真的发出动作**。
///
/// 为什么要这样测：Flutter 页上的按钮不直接干活，只发一个动作名给 WebView。
/// 按钮画得对、onPressed 也接上了，但动作名拼错 / 参数缺失时，界面上完全没有反馈
/// （不崩、不报错），真机点了才知道。这里把宿主通道换成记录器，逐个点过去，
/// 断言每个组件都发出了预期的那条动作。
///
/// 覆盖：顶栏（菜单 / 工作区 / 刷新 / 返回网页版）、信息 chips、输入区（附件 /
/// 提示词 / 发送 / 停止）、选择面板、消息长按菜单、空态提示词。
class _Recorder {
  final List<Map<String, Object?>> actions = <Map<String, Object?>>[];
  final List<String> methods = <String>[];

  List<String> get types =>
      actions.map((Map<String, Object?> item) => '${item['type']}').toList(growable: false);

  bool has(String type) => types.contains(type);

  Map<String, Object?>? last(String type) {
    for (final Map<String, Object?> item in actions.reversed) {
      if ('${item['type']}' == type) return item;
    }
    return null;
  }
}

Future<_Recorder> _mount(WidgetTester tester, Map<String, Object?> snapshot) async {
  final _Recorder recorder = _Recorder();
  tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(hostChannel, (
    MethodCall call,
  ) async {
    recorder.methods.add(call.method);
    if (call.method == 'chatAction') {
      final Map<Object?, Object?> args = call.arguments as Map<Object?, Object?>;
      recorder.actions.add(
        (jsonDecode('${args['json']}') as Map<Object?, Object?>).map(
          (Object? key, Object? value) => MapEntry<String, Object?>('$key', value),
        ),
      );
    }
    return null;
  });
  addTearDown(() {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(hostChannel, null);
  });

  await tester.pumpWidget(
    MaterialApp(
      theme: buildPiTheme(Brightness.dark),
      home: ChatScreen(initialJson: jsonEncode(snapshot)),
    ),
  );
  await tester.pump();
  return recorder;
}

Map<String, Object?> _richSnapshot() => <String, Object?>{
      'sessionId': 's1',
      'title': '测试会话',
      'status': 'idle',
      'header': <String, Object?>{
        'chips': <Object?>[
          <String, Object?>{'key': 'model', 'label': 'gpt-x'},
          <String, Object?>{'key': 'thinking', 'label': '中'},
          <String, Object?>{'key': 'security', 'label': '严格'},
          <String, Object?>{'key': 'plan', 'label': '计划'},
          <String, Object?>{'key': 'history', 'label': '历史'},
        ],
      },
      'history': <Object?>[
        <String, Object?>{
          'id': 'm1',
          'role': 'assistant',
          'text': '你好，我是 Pi。',
          'time': '10:00',
        },
      ],
      'messageMenus': <String, Object?>{
        'assistant': <Object?>[
          <String, Object?>{'id': 'copy', 'label': '复制'},
          <String, Object?>{'id': 'fork', 'label': '分叉'},
        ],
      },
      'suggestions': <Object?>['介绍一下你自己'],
    };

void main() {
  // 「收起工作区」那半边不在这里点：抽屉打开后 AppBar 被 barrier 盖住，
  // 那一下会落到 barrier 上（由 `onEndDrawerChanged` 兜底），
  // 对应的开合语义由 workspace_drawer_test 直接喂快照来测。
  testWidgets('顶栏：工作区按钮与刷新按钮都发得出动作', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _richSnapshot());

    await tester.tap(find.byIcon(Icons.view_sidebar_outlined));
    await tester.pump(const Duration(milliseconds: 400));
    expect(recorder.has('workspaceOpen'), isTrue, reason: '工作区按钮没发出 workspaceOpen');

    await tester.tap(find.byIcon(Icons.refresh_rounded));
    await tester.pump(const Duration(milliseconds: 200));
    expect(recorder.has('refresh'), isTrue, reason: '刷新按钮没发出 refresh');
  });

  testWidgets('顶栏：返回网页版走宿主 close，而不是动作', (WidgetTester tester) async {
    // 单独一个用例：抽屉开合会盖一层 barrier，混在一起时点击落到 barrier 上，
    // 看起来像「按钮坏了」，其实是测试自己挡住了自己。
    final _Recorder recorder = await _mount(tester, _richSnapshot());

    await tester.tap(find.byIcon(Icons.open_in_browser_rounded));
    await tester.pump(const Duration(milliseconds: 200));
    expect(recorder.methods.contains('close'), isTrue, reason: '返回网页版没调用 closeHost');
  });

  testWidgets('菜单键打开侧栏抽屉', (WidgetTester tester) async {
    await _mount(tester, _richSnapshot());

    await tester.tap(find.byIcon(Icons.menu_rounded));
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byType(Drawer), findsOneWidget, reason: '菜单键没开出侧栏抽屉');
  });

  testWidgets('信息 chips 点下去发 chip + key', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _richSnapshot());

    await tester.tap(find.text('gpt-x'));
    await tester.pump(const Duration(milliseconds: 300));
    final Map<String, Object?>? model = recorder.last('chip');
    expect(model, isNotNull, reason: '模型 chip 点下去没反应');
    expect(model!['key'], 'model');

    await tester.tap(find.text('历史'));
    await tester.pump(const Duration(milliseconds: 300));
    expect(recorder.last('chip')!['key'], 'history');
  });

  testWidgets('输入区：附件 / 提示词 / 发送 / 停止', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _richSnapshot());

    // 附件：点了要走宿主选图（这里是 mock 宿主，动作不一定发，但不许崩）
    expect(find.byIcon(Icons.add_photo_alternate_outlined), findsOneWidget);
    await tester.tap(find.byIcon(Icons.add_photo_alternate_outlined));
    await tester.pump(const Duration(milliseconds: 200));

    // 打字 → 发送
    await tester.enterText(find.byType(TextField), '你好');
    await tester.pump();
    await tester.tap(find.byIcon(Icons.arrow_upward_rounded));
    await tester.pump(const Duration(milliseconds: 200));
    final Map<String, Object?>? send = recorder.last('send');
    expect(send, isNotNull, reason: '发送按钮没发出 send');
    expect(send!['text'], '你好');
  });

  testWidgets('运行中：发送键变成停止键，点了发 stop', (WidgetTester tester) async {
    final Map<String, Object?> snapshot = _richSnapshot()..['status'] = 'running';
    final _Recorder recorder = await _mount(tester, snapshot);

    expect(find.byIcon(Icons.arrow_upward_rounded), findsNothing);
    await tester.tap(find.byIcon(Icons.stop_rounded));
    await tester.pump(const Duration(milliseconds: 200));
    expect(recorder.has('stop'), isTrue, reason: '停止按钮没发出 stop');
  });

  testWidgets('长按消息开菜单，选中菜单项发 messageAction', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _richSnapshot());

    await tester.longPress(find.textContaining('我是 Pi'));
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.text('复制'), findsOneWidget, reason: '长按没弹菜单');

    await tester.tap(find.text('复制'));
    await tester.pump(const Duration(milliseconds: 400));
    final Map<String, Object?>? action = recorder.last('messageAction');
    expect(action, isNotNull, reason: '菜单项点了没发出 messageAction');
    expect(action!['id'], 'm1');
    expect(action['action'], 'copy');
  });

  testWidgets('空态提示词点一下只填输入框，不直接发送', (WidgetTester tester) async {
    final Map<String, Object?> snapshot = _richSnapshot()..remove('history');
    final _Recorder recorder = await _mount(tester, snapshot);

    expect(find.text('介绍一下你自己'), findsOneWidget);
    await tester.tap(find.text('介绍一下你自己'));
    await tester.pump();

    expect(recorder.has('send'), isFalse, reason: '提示词不该直接发出去');
    final EditableText field = tester.widget<EditableText>(find.byType(EditableText).last);
    expect(field.controller.text, '介绍一下你自己', reason: '提示词没填进输入框');
  });
}
