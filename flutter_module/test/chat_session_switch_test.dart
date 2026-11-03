import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/app_theme.dart';
import 'package:pideck_flutter/chat/chat_page.dart';
import 'package:pideck_flutter/host_channel.dart';

/// 快照到了就得画出来 —— 哪怕 `sessionId` 是空的。
///
/// 老实现里 `_applyJson()` 第一件事就是 `if (next.sessionId.isEmpty) return;`，
/// 主界面（还没选会话）的快照整份被丢掉：切会话、回主界面、改标题全都不刷新，
/// 页面永远停在上一次收到的那份内容上。这里锁两件事：
///   1. 空 `sessionId` 的快照也要渲染（主界面文案能画出来）；
///   2. 换了会话之后，列表里是新会话的消息（不是上一个会话的）。

Future<void> _pushChat(WidgetTester tester, Map<String, Object?> snapshot) async {
  await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
    hostChannel.name,
    const StandardMethodCodec().encodeMethodCall(
      MethodCall('setChat', jsonEncode(snapshot)),
    ),
    (ByteData? _) {},
  );
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 200));
}

Map<String, Object?> _snapshot({
  required String sessionId,
  required String title,
  required List<String> messages,
}) {
  return <String, Object?>{
    'sessionId': sessionId,
    'title': title,
    'status': 'idle',
    'emptyTitle': '开始使用你的 PiDeck',
    'emptySubtitle': '点左上角菜单选择会话，或者直接发消息开始新的对话',
    'history': <Object?>[
      for (int i = 0; i < messages.length; i += 1)
        <String, Object?>{'id': 'm$i', 'role': 'assistant', 'text': messages[i], 'time': '03:0$i'},
    ],
  };
}

Future<void> _mount(WidgetTester tester, Map<String, Object?> snapshot) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: buildPiTheme(Brightness.dark),
      home: ChatScreen(initialJson: jsonEncode(snapshot)),
    ),
  );
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 200));
}

Future<void> _pushSidebar(WidgetTester tester, Map<String, Object?> snapshot) async {
  await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
    hostChannel.name,
    const StandardMethodCodec().encodeMethodCall(
      MethodCall('setSidebar', jsonEncode(snapshot)),
    ),
    (ByteData? _) {},
  );
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 200));
}

/// 输入框里现在是什么（草稿要按会话搬，靠它断言）。
String _composerText(WidgetTester tester) =>
    tester.widget<TextField>(find.byType(TextField).first).controller!.text;

/// 侧栏：一条「最近会话」分段，两行会话。
Map<String, Object?> _sidebar() {
  Map<String, Object?> row(String id, String title, bool active) => <String, Object?>{
        'id': id,
        'title': title,
        'meta': '刚刚',
        'active': active,
        'running': false,
        'acp': false,
        'dsh': false,
      };
  return <String, Object?>{
    'tab': 'all',
    'tabs': <Object?>[],
    'query': '',
    'deviceCount': 0,
    'sections': <Object?>[
      <String, Object?>{
        'key': 'active-recent',
        'kind': 'flat',
        'title': '最近会话',
        'rows': <Object?>[row('s1', '会话一', true), row('s2', '会话二', false)],
        'hiddenCount': 0,
      },
    ],
  };
}

void main() {
  testWidgets('主界面（没有会话）的快照也会渲染', (WidgetTester tester) async {
    await _mount(tester, _snapshot(sessionId: '', title: 'PiDeck', messages: <String>[]));

    expect(find.text('开始使用你的 PiDeck'), findsOneWidget);

    // 同一个页面推一份空 sessionId 的快照（比如从会话返回主界面），文案也得跟着变
    await _pushChat(
      tester,
      _snapshot(sessionId: '', title: 'PiDeck', messages: <String>[]),
    );
    expect(find.text('开始使用你的 PiDeck'), findsOneWidget);
  });

  testWidgets('切会话后列表换成新会话的最新消息', (WidgetTester tester) async {
    await _mount(
      tester,
      _snapshot(sessionId: 's1', title: '会话一', messages: <String>['会话一的消息']),
    );
    expect(find.text('会话一的消息'), findsOneWidget);

    await _pushChat(
      tester,
      _snapshot(sessionId: 's2', title: '会话二', messages: <String>['会话二的开头', '会话二的最新一条']),
    );

    expect(find.text('会话一的消息'), findsNothing, reason: '切会话后还留着上一个会话的消息');
    expect(find.text('会话二的最新一条'), findsOneWidget, reason: '新会话的消息没画出来');
    expect(find.text('会话二'), findsWidgets, reason: '标题没跟着换');
  });

  testWidgets('点侧栏里的会话：页面立刻切过去，不等 Web 侧往返', (WidgetTester tester) async {
    await _mount(
      tester,
      _snapshot(sessionId: 's1', title: '会话一', messages: <String>['会话一的消息']),
    );
    await _pushSidebar(tester, _sidebar());
    expect(find.text('会话一的消息'), findsOneWidget);

    await tester.tap(find.byIcon(Icons.menu_rounded));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.tap(find.text('会话二'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    // 还没等 Web 侧回任何东西：旧会话的内容就不能再留在页面上
    expect(find.text('会话一的消息'), findsNothing, reason: '点了会话二还显示会话一的内容');
    expect(find.text('会话二'), findsWidgets, reason: '标题没先换成点中的那个会话');
  });

  testWidgets('草稿跟着会话走：切走不丢，切回来还在', (WidgetTester tester) async {
    await _mount(
      tester,
      _snapshot(sessionId: 's1', title: '会话一', messages: <String>['会话一的消息']),
    );
    await tester.enterText(find.byType(TextField).first, '没发完的话');
    await tester.pump();

    await _pushChat(
      tester,
      _snapshot(sessionId: 's2', title: '会话二', messages: <String>['会话二的消息']),
    );
    expect(_composerText(tester), isEmpty, reason: '上一个会话的草稿被带到新会话了');
    expect(find.text('会话二的消息'), findsOneWidget);

    await _pushChat(
      tester,
      _snapshot(sessionId: 's1', title: '会话一', messages: <String>['会话一的消息']),
    );
    expect(_composerText(tester), '没发完的话', reason: '切回原会话，草稿丢了');
  });

  testWidgets('点了会话之后，旧会话的推送不能把页面拽回去', (WidgetTester tester) async {
    await _mount(
      tester,
      _snapshot(sessionId: 's1', title: '会话一', messages: <String>['会话一的消息']),
    );
    await _pushSidebar(tester, _sidebar());
    await tester.tap(find.byIcon(Icons.menu_rounded));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    await tester.tap(find.text('会话二'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    // WebView 还没确认新会话，旧会话的收尾推送先到 —— 不能把页面拽回会话一
    await _pushChat(
      tester,
      _snapshot(sessionId: 's1', title: '会话一', messages: <String>['会话一的消息', '旧会话又推来一条']),
    );

    expect(find.text('旧会话又推来一条'), findsNothing, reason: '旧会话的推送把页面切回去了');
    expect(find.text('会话二'), findsWidgets, reason: '标题被旧会话的推送改回去了');
  });
}
