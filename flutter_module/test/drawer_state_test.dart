/// 抽屉的用户意图：折叠 / 展开 / 页签 / 搜索。
///
/// 这些状态原来只活在 WebView 里，现在 Dart 也要有一份（点一下立刻重算快照），
/// 所以两边语义必须一致 —— 尤其 [DrawerState.toggleSection] 的前缀分派：
/// `project:<id>` 走「展开全部会话」，其余走「展开其余」，两个集合互不相干。
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/sidebar/drawer_state.dart';
import 'package:pideck_flutter/sidebar/sidebar_native.dart';

void main() {
  test('页签：只认三个已知值', () {
    final DrawerState state = DrawerState();
    expect(state.tab, sidebarTabActive);
    state.toggleTab(sidebarTabProjects);
    expect(state.tab, sidebarTabProjects);
    state.toggleTab('bogus');
    expect(state.tab, sidebarTabProjects);
  });

  test('折叠项目：点一次收起，再点一次展开', () {
    final DrawerState state = DrawerState();
    state.toggleProject('p1');
    expect(state.collapsedProjects, <String>{'p1'});
    state.toggleProject('p1');
    expect(state.collapsedProjects, isEmpty);
  });

  test('分段展开：project: 前缀走「展开全部会话」，其余走「展开其余」', () {
    final DrawerState state = DrawerState();
    state.toggleSection('project:p1');
    expect(state.expandedSessions, <String, bool>{'p1': true});
    // 两个集合不能串：切扁平段不该动项目的展开意图
    state.toggleSection('active');
    expect(state.flatExpanded, <String>{'active'});
    expect(state.expandedSessions, <String, bool>{'p1': true});
    // 再点一次是收起（false 要**记下来**：它表示用户明确收起，规则上要压过临时展开）
    state.toggleSection('project:p1');
    expect(state.expandedSessions, <String, bool>{'p1': false});
    state.toggleSection('active');
    expect(state.flatExpanded, isEmpty);
  });

  test('空 key 不产生任何意图', () {
    final DrawerState state = DrawerState();
    state.toggleSection('');
    state.toggleSection('project:');
    state.toggleProject('');
    expect(state.expandedSessions, isEmpty);
    expect(state.collapsedProjects, isEmpty);
  });

  test('seedFrom：只捞「收起」的项目，不凭空造展开意图', () {
    final DrawerState state = DrawerState();
    state.seedFrom(
      tab: sidebarTabChats,
      query: '登录',
      collapsed: <String, bool>{'p1': true, 'p2': false},
      expandedSessions: const <String, bool>{},
      flatExpanded: <String>{'chats'},
    );
    expect(state.tab, sidebarTabChats);
    expect(state.query, '登录');
    expect(state.collapsedProjects, <String>{'p1'});
    expect(state.flatExpanded, <String>{'chats'});
  });

  test('toInput 的键名与 TS 的 SidebarSnapshotInput 一致', () {
    final DrawerState state = DrawerState()
      ..setQuery('q')
      ..toggleProject('p1')
      ..toggleSection('project:p2')
      ..toggleSection('active');
    final Map<String, Object?> input = state.toInput(
      state: <String, Object?>{'projects': <Object?>[], 'sessions': <Object?>[], 'runtimes': <Object?>[]},
      endpointId: 'native',
      endpointLabel: '',
      activeSessionId: 's1',
      activeProjectId: 'p2',
      sheet: null,
    );
    expect(input.keys.toList(), <String>[
      'tab',
      'query',
      'endpoints',
      'activeEndpointId',
      'activeSessionId',
      'activeProjectId',
      'collapsedProjects',
      'expandedSessions',
      'flatExpanded',
      'sheet',
    ]);
    expect(input['query'], 'q');
    expect(input['collapsedProjects'], <String>['p1']);
    expect(input['expandedSessions'], <String, Object?>{'p2': true});
    expect(input['flatExpanded'], <String>['active']);
    final List<Object?> endpoints = input['endpoints']! as List<Object?>;
    final Map<String, Object?> endpoint = endpoints.single! as Map<String, Object?>;
    expect(endpoint['id'], 'native');
    expect(endpoint['state'], isA<Map<String, Object?>>());
  });

  test('本地意图能直接喂给 buildSidebarSnapshot（端到端串起来）', () {
    // 项目分组只在「项目」页签里出现（活动页签是扁平的运行/最近列表）
    final DrawerState state = DrawerState()..tab = sidebarTabProjects;
    state.toggleProject('p1');
    final Map<String, Object?> snapshot = buildSidebarSnapshot(
      state.toInput(
        state: <String, Object?>{
          'projects': <Object?>[
            <String, Object?>{'id': 'p1', 'name': 'PiDeck'},
          ],
          'sessions': <Object?>[
            <String, Object?>{
              'id': 's1',
              'projectId': 'p1',
              'title': '测试会话',
              'status': 'idle',
              'updatedAt': 1000,
            },
          ],
          'runtimes': <Object?>[],
        },
        endpointId: 'native',
        endpointLabel: '',
        activeSessionId: 's1',
        activeProjectId: 'p1',
        sheet: null,
      ),
    );
    final List<Object?> sections = snapshot['sections']! as List<Object?>;
    // 默认页签是「活动」，所以第一段是扁平的运行/最近列表，项目段在后面
    final Map<String, Object?> group = sections
        .cast<Map<String, Object?>>()
        .firstWhere((Map<String, Object?> section) => section['kind'] == 'group');
    expect(group['kind'], 'group');
    // 用户明确收起 → 分组是折叠的，行被收起来（不是「展开其余 N 个」那种截断）
    expect(group['collapsed'], isTrue);
  });
}
