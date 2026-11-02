import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:pideck_flutter/host_channel.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/app_theme.dart';
import 'package:pideck_flutter/chat/chat_page.dart';
import 'package:pideck_flutter/workspace/workspace_drawer.dart';

/// 右侧工作区抽屉：开合必须由快照里的 `workspace.open` 驱动。
///
/// 这里锁两件事：`open: true` 的推送到来后抽屉真的会开；推 `open: false` 时不会自己弹。
String _snapshot({required bool open, String tab = 'git'}) {
  return jsonEncode(<String, Object?>{
    'sessionId': 's1',
    'title': '测试会话',
    'status': 'idle',
    'workspace': <String, Object?>{
      'open': open,
      'tab': tab,
      'title': 'PiDeck',
      'branch': 'master',
      'changedTotal': 1,
      'changes': <Object?>[
        <String, Object?>{'letter': 'M', 'path': 'app/src/main.ts', 'group': '已修改'},
      ],
      'commits': <Object?>[
        <String, Object?>{'sha': 'abc1234', 'subject': 'feat: x', 'date': '11-02 18:00'},
      ],
    },
  });
}

List<String> _sent = const <String>[];

bool _endDrawerOpen(WidgetTester tester) =>
    tester.state<ScaffoldState>(find.byType(Scaffold).first).isEndDrawerOpen;

void main() {
  testWidgets('快照说开着，抽屉就开（不靠用户点）', (WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: buildPiTheme(Brightness.dark),
        home: ChatScreen(initialJson: _snapshot(open: true)),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(find.text('改动'), findsOneWidget);
    expect(find.text('已修改'), findsOneWidget);
    expect(find.text('app/src/main.ts'), findsOneWidget);

    // 切到文件页：表格里的行要跟着快照换
    await tester.tap(find.text('文件'));
    await tester.pump(const Duration(milliseconds: 300));
  });

  testWidgets('点顶栏按钮立刻开抽屉（数据还没到也不许「点了没反应」）', (WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: buildPiTheme(Brightness.dark),
        home: ChatScreen(initialJson: _snapshot(open: false)),
      ),
    );
    await tester.pump();

    await tester.tap(find.byIcon(Icons.view_sidebar_outlined));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    // 快照里说关着，但用户已经点了：抽屉要开着，内容等下一份快照
    expect(find.byIcon(Icons.view_sidebar_rounded), findsOneWidget, reason: '图标没切换 = 点击没生效');
    expect(find.byType(WorkspaceDrawer), findsOneWidget);
  });

  testWidgets('快照说关着，抽屉不弹', (WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: buildPiTheme(Brightness.dark),
        home: ChatScreen(initialJson: _snapshot(open: false)),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    expect(find.text('改动'), findsNothing);
  });

  testWidgets('点右上角 X 能关掉抽屉，不会被「刚点的意图」拉回来', (WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: buildPiTheme(Brightness.dark),
        home: ChatScreen(initialJson: _snapshot(open: true)),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byType(WorkspaceDrawer), findsOneWidget);

    await tester.tap(find.byIcon(Icons.close_rounded));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    // 关不掉的老病根：X 只发了 workspaceClose，没改本地意图，
    // 下一帧 openEndDrawer() 又把抽屉打开了。
    expect(_endDrawerOpen(tester), isFalse, reason: '点 X 之后抽屉还开着 = 关闭意图没生效');
  });

  testWidgets('点 X 之后快照接着来，页面不能被一起弹掉（黑屏）', (WidgetTester tester) async {
    final List<String> sent = <String>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(hostChannel,
        (MethodCall call) async {
      final Object? args = call.arguments;
      final Object? json = args is Map ? args['json'] : args;
      if (json is String) {
        try {
          final Object? decoded = jsonDecode(json);
          if (decoded is Map && decoded['type'] != null) {
            sent.add('${decoded['type']}');
          }
        } catch (_) {}
      }
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(hostChannel, null));
    _sent = sent;
    await tester.pumpWidget(
      MaterialApp(
        theme: buildPiTheme(Brightness.dark),
        home: ChatScreen(initialJson: _snapshot(open: true)),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    expect(find.byType(WorkspaceDrawer), findsOneWidget);

    await tester.tap(find.byIcon(Icons.close_rounded));
    // 同一帧里 Web 侧的快照也到了（80ms 一次推送，和点击撞在同一帧完全正常）
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
      hostChannel.name,
      const StandardMethodCodec().encodeMethodCall(
        MethodCall('setChat', _snapshot(open: false)),
      ),
      (ByteData? _) {},
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));

    // 老实现这里会再 pop 一次 —— 抽屉的 local history 已经没了，于是把整页路由弹掉，
    // 用户看到黑屏、再按返回就退回网页版。
    expect(find.byType(ChatScreen), findsOneWidget, reason: '页面被弹掉了（黑屏）');
    expect(find.byIcon(Icons.menu_rounded), findsOneWidget);
    expect(_endDrawerOpen(tester), isFalse);
    // 关抽屉绝不该顺带把 App 退掉（老实现 pop 到空路由时会走到 exitApp）
    expect(_sent, isNot(contains('exitApp')), reason: '关抽屉把 App 退了：$_sent');
  });
}
