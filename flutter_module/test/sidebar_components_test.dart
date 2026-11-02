import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/app_theme.dart';
import 'package:pideck_flutter/sidebar/sidebar_drawer.dart';
import 'package:pideck_flutter/sidebar/sidebar_models.dart';

/// 侧栏组件可用性：每一处点击都要真的发出对应动作。
///
/// 侧栏数据全部来自 Web 侧算好的 [SidebarSnapshot]，Dart 只画；
/// 所以「点了没反应」只可能是动作没发出去或动作名对不上。
class _Recorder {
  final List<Map<String, Object?>> actions = <Map<String, Object?>>[];
  int closes = 0;

  bool has(String type) => actions.any((Map<String, Object?> item) => '${item['type']}' == type);

  Map<String, Object?>? last(String type) {
    for (final Map<String, Object?> item in actions.reversed) {
      if ('${item['type']}' == type) return item;
    }
    return null;
  }
}

Future<_Recorder> _mount(WidgetTester tester, Map<String, Object?> snapshot) async {
  final _Recorder recorder = _Recorder();
  // 直接把抽屉当页面画：`Scaffold.drawer` 里的 widget 即使没打开也在树里，
  // 找到 ≠ 点得到，测出来的是假绿/假红。
  await tester.pumpWidget(
    MaterialApp(
      theme: buildPiTheme(Brightness.dark),
      home: Scaffold(
        body: SidebarDrawer(
          snapshot: SidebarSnapshot.fromJson(snapshot),
          onAction: recorder.actions.add,
          onClose: () => recorder.closes++,
        ),
      ),
    ),
  );
  await tester.pump();
  return recorder;
}

Map<String, Object?> _snapshot() => <String, Object?>{
      'tab': 'active',
      'tabs': <Object?>[
        <String, Object?>{'id': 'active', 'label': '活动', 'count': 3},
        <String, Object?>{'id': 'all', 'label': '全部', 'count': 9},
      ],
      'query': '',
      'deviceCount': 0,
      'sections': <Object?>[
        <String, Object?>{
          'key': 'flat:active',
          'kind': 'flat',
          'title': '活动',
          'hiddenCount': 0,
          'expanded': false,
          'rows': <Object?>[
            <String, Object?>{'id': 's1', 'title': '修登录页', 'active': true},
            <String, Object?>{'id': 's2', 'title': '重构侧栏'},
            <String, Object?>{'id': 's3', 'title': '第三个会话'},
          ],
        },
        <String, Object?>{
          'key': 'project:p9',
          'kind': 'group',
          'label': 'PiDeck-Mobile',
          'projectId': 'p9',
          'count': 1,
          'active': false,
          'collapsed': false,
          'hiddenCount': 0,
          'expanded': false,
          'rows': <Object?>[
            <String, Object?>{'id': 's4', 'title': '移动端会话'},
          ],
        },
      ],
    };

void main() {
  testWidgets('页签切换发 toggleTab', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _snapshot());

    await tester.tap(find.text('全部 9'));
    await tester.pump(const Duration(milliseconds: 300));
    expect(recorder.last('toggleTab')?['tab'], 'all', reason: '页签点了没发出 toggleTab');
  });

  testWidgets('搜索框输入发 search，清空也发', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _snapshot());

    await tester.enterText(find.byType(TextField), '登录');
    await tester.pump(const Duration(milliseconds: 200));
    expect(recorder.last('search')?['query'], '登录', reason: '搜索框没发出 search');

    await tester.tap(find.byTooltip('清空'));
    await tester.pump(const Duration(milliseconds: 200));
    expect(recorder.last('search')?['query'], '', reason: '清空没发 search(query: "")');
  });

  testWidgets('点会话行：先关抽屉再发 openSession', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _snapshot());

    await tester.tap(find.text('重构侧栏'));
    await tester.pump(const Duration(milliseconds: 300));
    expect(recorder.last('openSession')?['id'], 's2', reason: '会话行没发出 openSession');
    expect(recorder.closes, greaterThan(0), reason: '点会话后没关抽屉');
  });

  testWidgets('长按会话行弹本地菜单', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _snapshot());

    await tester.longPress(find.text('重构侧栏'));
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.text('复制会话'), findsOneWidget, reason: '长按没弹会话菜单');

    await tester.tap(find.text('复制会话'));
    await tester.pump(const Duration(milliseconds: 400));
    expect(recorder.last('sessionCopy')?['id'], 's2', reason: '菜单项没发出 sessionCopy');
  });

  testWidgets('项目分组头：点一下折叠、长按弹项目菜单', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _snapshot());

    await tester.tap(find.text('PiDeck-Mobile'));
    await tester.pump(const Duration(milliseconds: 300));
    expect(recorder.last('toggleProject')?['projectId'], 'p9', reason: '分组头点了没发出 toggleProject');

    await tester.longPress(find.text('PiDeck-Mobile'));
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.text('从列表移除项目'), findsOneWidget, reason: '长按没弹项目菜单');
  });

  testWidgets('底部三个按钮：新建会话 / 设置 / 返回网页版', (WidgetTester tester) async {
    final _Recorder recorder = await _mount(tester, _snapshot());

    await tester.tap(find.text('新建会话'));
    await tester.pump(const Duration(milliseconds: 300));
    expect(recorder.has('newSession'), isTrue, reason: '新建会话没发出动作');

    await tester.tap(find.text('设置'));
    await tester.pump(const Duration(milliseconds: 300));
    expect(recorder.has('openSettings'), isTrue, reason: '设置没发出动作');

    await tester.tap(find.text('返回网页版'));
    await tester.pump(const Duration(milliseconds: 300));
    expect(recorder.has('close'), isTrue, reason: '返回网页版没发出动作');
  });

  testWidgets('快照说「展开其余 N 个」时才给按钮', (WidgetTester tester) async {
    final Map<String, Object?> snapshot = _snapshot();
    final List<Object?> sections = snapshot['sections']! as List<Object?>;
    (sections.first! as Map<String, Object?>)['hiddenCount'] = 4;

    final _Recorder recorder = await _mount(tester, snapshot);

    expect(find.text('展开其余 4 个'), findsOneWidget);
    await tester.tap(find.text('展开其余 4 个'));
    await tester.pump(const Duration(milliseconds: 300));
    expect(recorder.last('toggleSection')?['key'], 'flat:active', reason: '展开按钮没发出 toggleSection');
  });
}
