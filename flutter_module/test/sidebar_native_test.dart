// 侧栏快照 Dart 移植版的对照测试（`lib/sidebar/sidebar_native.dart`）。
//
// 期望值不是手写的：`tool/sidebar_fixture_gen.mjs` 用 node 直接 import 仓库里的 TS 真身
// （`app/src/platform/flutter-sidebar.ts`，node v24 的 amaro 负责剥类型），调
// `buildSidebarSnapshot()` 后把 `JSON.stringify()` 的结果原样嵌成下面的字符串常量。
// 每条用例都用 `jsonEncode()` 和它逐字符比较 —— 键序、键的省略规则、数字格式一起锁死。
//
// 时区无关性：fixture 的时间戳一律取「某天 12:00 UTC」，且都不落在 `now`（2026-03-05）那天，
// 于是 `timeLabel()` 只可能走 `M/D` 分支（UTC±11 内日期不变），本地 UTC+8 与 CI 的 UTC 都能过。
// 同日 `HH:MM` 那条分支按时区变，下面用本地构造的 `DateTime` 单独测（做法同
// `test/runtime_history_test.dart`）。
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/sidebar/sidebar_native.dart';

/// fixture 里的输入是 JSON 文本（Set/Map 在生成侧已展平成数组/对象），还原成函数要的形状。
Map<String, Object?> _input(String source) =>
    (jsonDecode(source) as Map<String, dynamic>).cast<String, Object?>();

/// 按 key 取分段，取不到直接失败（比 `firstWhere` 的 StateError 好读）。
Map<String, Object?> _section(Map<String, Object?> snapshot, String key) {
  for (final Object? raw in snapshot['sections']! as List<Object?>) {
    final Map<String, Object?> section = (raw! as Map<String, dynamic>)
        .cast<String, Object?>();
    if (section['key'] == key) return section;
  }
  fail('快照里没有 key=$key 的分段：${snapshot['sections']}');
}

List<String> _rowIds(Map<String, Object?> section) => <String>[
  for (final Object? raw in section['rows']! as List<Object?>)
    ((raw! as Map<String, dynamic>)['id'] as String?) ?? '',
];

Map<String, Object?> _snapshot({
  String tab = 'projects',
  int now = 1772000000000,
  String? query,
  Object? activeSessionId,
  Object? activeProjectId,
  List<Object?>? endpoints,
  Object? collapsedProjects,
  Object? expandedSessions,
  Object? flatExpanded,
  Object? sheet,
}) => <String, Object?>{
  'tab': tab,
  'now': now,
  'query': ?query,
  'activeEndpointId': null,
  'activeSessionId': activeSessionId,
  'activeProjectId': activeProjectId,
  'endpoints': endpoints ?? <Object?>[],
  'collapsedProjects': ?collapsedProjects,
  'expandedSessions': ?expandedSessions,
  'flatExpanded': ?flatExpanded,
  'sheet': ?sheet,
};

Map<String, Object?> _endpoint({
  String id = 'ep1',
  String label = '本机',
  List<Object?> projects = const <Object?>[],
  List<Object?> sessions = const <Object?>[],
  List<Object?> runtimes = const <Object?>[],
  List<Object?> acpTools = const <Object?>[],
}) => <String, Object?>{
  'id': id,
  'label': label,
  'state': <String, Object?>{
    'projects': projects,
    'sessions': sessions,
    'runtimes': runtimes,
    'acpTools': acpTools,
  },
};

Map<String, Object?> _session(
  String id,
  String projectId,
  String? title,
  int? updatedAt, [
  Map<String, Object?> extra = const <String, Object?>{},
]) => <String, Object?>{
  'id': id,
  'projectId': projectId,
  'title': ?title,
  'updatedAt': ?updatedAt,
  ...extra,
};

const Map<String, Object?> _alpha = <String, Object?>{
  'id': 'p1',
  'name': 'alpha',
};
// 由 TS 实现生成的对照表（flutter_module/tool/sidebar_fixture_gen.mjs）。
// input / expected 都是一行 JSON，逐字节比较；不要手改。
const List<({String name, String note, String input, String expected})> sidebarFixtureCases =
    <({String name, String note, String input, String expected})>[
  (
    name: 'empty-endpoints',
    note: '一个端点都没连上：空快照提前返回（tabs 与 sections 都是空数组）',
    input: r'''{"tab":"active","query":"","endpoints":[],"activeEndpointId":"ep1","activeSessionId":null,"activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"active","tabs":[],"query":"","sections":[],"deviceCount":0,"sheet":null}''',
  ),
  (
    name: 'empty-endpoint-state',
    note: '端点连上了但什么都没有：活动段只剩「最近会话」的空态文案',
    input: r'''{"tab":"active","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[],"sessions":[],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":null,"activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"active","tabs":[{"id":"active","label":"活动","count":0},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":0}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"active-recent","kind":"flat","title":"最近会话","rows":[],"hiddenCount":0,"expanded":false,"empty":"还没有会话；发一条消息就会出现在这里"}]}''',
  ),
  (
    name: 'single-project-single-session',
    note: '单项目单会话：行上带项目名，当前会话行 active=true',
    input: r'''{"tab":"active","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-chat","kind":"chat","name":"Chat"},{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"s1","projectId":"p-alpha","title":"你好","updatedAt":1772366400000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":"s1","activeProjectId":"p-alpha","collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"active","tabs":[{"id":"active","label":"活动","count":1},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":1}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"active-recent","kind":"flat","title":"最近会话","rows":[{"id":"s1","title":"你好","meta":"alpha · 3/1","active":true,"running":false,"acp":false,"dsh":false}],"hiddenCount":0,"expanded":false}]}''',
  ),
  (
    name: 'projects-grouping-labels',
    note: '项目分段：项目顺序原样、Chat 项目排除、空分组带「暂无会话」、缺 title/updatedAt 的兜底',
    input: r'''{"tab":"projects","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-chat","kind":"chat","name":"Chat"},{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"},{"id":"p-beta","name":"beta","path":"D:/work/beta/"},{"id":"p-noname","path":"/srv/repos/gamma"},{"id":"p-idonly"}],"sessions":[{"id":"s-title","projectId":"p-alpha","title":"写代码","updatedAt":1772539200000},{"id":"s-empty-title","projectId":"p-alpha","title":"","updatedAt":1772452800000},{"id":"s-no-title","projectId":"p-beta","updatedAt":1772366400000},{"id":"s-null-time","projectId":"p-beta","title":"无时间","updatedAt":null},{"id":"s-ghost","projectId":"p-ghost","title":"幽灵项目","updatedAt":1772625600000},{"id":"s-chat","projectId":"p-chat","title":"内置聊天","updatedAt":1772625600000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":"s-title","activeProjectId":"p-alpha","collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"projects","tabs":[{"id":"active","label":"活动","count":6},{"id":"chats","label":"聊天","count":1},{"id":"projects","label":"项目","count":4}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"project:p-alpha","kind":"group","label":"alpha","projectId":"p-alpha","collapsed":false,"active":true,"count":2,"runningCount":0,"rows":[{"id":"s-title","title":"写代码","meta":"3/3","active":true,"running":false,"acp":false,"dsh":false},{"id":"s-empty-title","title":"未命名会话","meta":"3/2","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":0,"expanded":false},{"key":"project:p-beta","kind":"group","label":"beta","projectId":"p-beta","collapsed":false,"active":false,"count":2,"runningCount":0,"rows":[{"id":"s-no-title","title":"未命名会话","meta":"3/1","active":false,"running":false,"acp":false,"dsh":false},{"id":"s-null-time","title":"无时间","meta":"","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":0,"expanded":false},{"key":"project:p-noname","kind":"group","label":"gamma","projectId":"p-noname","collapsed":false,"active":false,"count":0,"runningCount":0,"rows":[],"hiddenCount":0,"expanded":false,"empty":"暂无会话"},{"key":"project:p-idonly","kind":"group","label":"p-idonly","projectId":"p-idonly","collapsed":false,"active":false,"count":0,"runningCount":0,"rows":[],"hiddenCount":0,"expanded":false,"empty":"暂无会话"}]}''',
  ),
  (
    name: 'preview-truncation',
    note: '组内 9 条超过预览 6 条：只给前 6 行，hiddenCount=3',
    input: r'''{"tab":"projects","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"a1","projectId":"p-alpha","title":"会话 1","updatedAt":1772625600000},{"id":"a2","projectId":"p-alpha","title":"会话 2","updatedAt":1772539200000},{"id":"a3","projectId":"p-alpha","title":"会话 3","updatedAt":1772452800000},{"id":"a4","projectId":"p-alpha","title":"会话 4","updatedAt":1772366400000},{"id":"a5","projectId":"p-alpha","title":"会话 5","updatedAt":1772280000000},{"id":"a6","projectId":"p-alpha","title":"会话 6","updatedAt":1772193600000},{"id":"a7","projectId":"p-alpha","title":"会话 7","updatedAt":1772107200000},{"id":"a8","projectId":"p-alpha","title":"会话 8","updatedAt":1772020800000},{"id":"a9","projectId":"p-alpha","title":"会话 9","updatedAt":1771934400000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":null,"activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"projects","tabs":[{"id":"active","label":"活动","count":9},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":1}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"project:p-alpha","kind":"group","label":"alpha","projectId":"p-alpha","collapsed":false,"active":false,"count":9,"runningCount":0,"rows":[{"id":"a1","title":"会话 1","meta":"3/4","active":false,"running":false,"acp":false,"dsh":false},{"id":"a2","title":"会话 2","meta":"3/3","active":false,"running":false,"acp":false,"dsh":false},{"id":"a3","title":"会话 3","meta":"3/2","active":false,"running":false,"acp":false,"dsh":false},{"id":"a4","title":"会话 4","meta":"3/1","active":false,"running":false,"acp":false,"dsh":false},{"id":"a5","title":"会话 5","meta":"2/28","active":false,"running":false,"acp":false,"dsh":false},{"id":"a6","title":"会话 6","meta":"2/27","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":3,"expanded":false}]}''',
  ),
  (
    name: 'expanded-sessions',
    note: 'expandedSessions 显式展开同一组：9 行全出，hiddenCount 仍然报 3（收起的入口）',
    input: r'''{"tab":"projects","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"a1","projectId":"p-alpha","title":"会话 1","updatedAt":1772625600000},{"id":"a2","projectId":"p-alpha","title":"会话 2","updatedAt":1772539200000},{"id":"a3","projectId":"p-alpha","title":"会话 3","updatedAt":1772452800000},{"id":"a4","projectId":"p-alpha","title":"会话 4","updatedAt":1772366400000},{"id":"a5","projectId":"p-alpha","title":"会话 5","updatedAt":1772280000000},{"id":"a6","projectId":"p-alpha","title":"会话 6","updatedAt":1772193600000},{"id":"a7","projectId":"p-alpha","title":"会话 7","updatedAt":1772107200000},{"id":"a8","projectId":"p-alpha","title":"会话 8","updatedAt":1772020800000},{"id":"a9","projectId":"p-alpha","title":"会话 9","updatedAt":1771934400000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":null,"activeProjectId":null,"collapsedProjects":[],"expandedSessions":{"p-alpha":true},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"projects","tabs":[{"id":"active","label":"活动","count":9},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":1}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"project:p-alpha","kind":"group","label":"alpha","projectId":"p-alpha","collapsed":false,"active":false,"count":9,"runningCount":0,"rows":[{"id":"a1","title":"会话 1","meta":"3/4","active":false,"running":false,"acp":false,"dsh":false},{"id":"a2","title":"会话 2","meta":"3/3","active":false,"running":false,"acp":false,"dsh":false},{"id":"a3","title":"会话 3","meta":"3/2","active":false,"running":false,"acp":false,"dsh":false},{"id":"a4","title":"会话 4","meta":"3/1","active":false,"running":false,"acp":false,"dsh":false},{"id":"a5","title":"会话 5","meta":"2/28","active":false,"running":false,"acp":false,"dsh":false},{"id":"a6","title":"会话 6","meta":"2/27","active":false,"running":false,"acp":false,"dsh":false},{"id":"a7","title":"会话 7","meta":"2/26","active":false,"running":false,"acp":false,"dsh":false},{"id":"a8","title":"会话 8","meta":"2/25","active":false,"running":false,"acp":false,"dsh":false},{"id":"a9","title":"会话 9","meta":"2/24","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":3,"expanded":true}]}''',
  ),
  (
    name: 'collapsed-project',
    note: '折叠分组：rows 为空但 hiddenCount/expanded 照报；collapsed 只在项目分段出现',
    input: r'''{"tab":"projects","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"a1","projectId":"p-alpha","title":"会话 1","updatedAt":1772625600000},{"id":"a2","projectId":"p-alpha","title":"会话 2","updatedAt":1772539200000},{"id":"a3","projectId":"p-alpha","title":"会话 3","updatedAt":1772452800000},{"id":"a4","projectId":"p-alpha","title":"会话 4","updatedAt":1772366400000},{"id":"a5","projectId":"p-alpha","title":"会话 5","updatedAt":1772280000000},{"id":"a6","projectId":"p-alpha","title":"会话 6","updatedAt":1772193600000},{"id":"a7","projectId":"p-alpha","title":"会话 7","updatedAt":1772107200000},{"id":"a8","projectId":"p-alpha","title":"会话 8","updatedAt":1772020800000},{"id":"a9","projectId":"p-alpha","title":"会话 9","updatedAt":1771934400000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":null,"activeProjectId":null,"collapsedProjects":["p-alpha"],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"projects","tabs":[{"id":"active","label":"活动","count":9},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":1}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"project:p-alpha","kind":"group","label":"alpha","projectId":"p-alpha","collapsed":true,"active":false,"count":9,"runningCount":0,"rows":[],"hiddenCount":3,"expanded":false}]}''',
  ),
  (
    name: 'active-session-auto-expand',
    note: '当前会话排在第 8 位（≥预览 6）：临时展开，但不写回 expandedSessions',
    input: r'''{"tab":"projects","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"a1","projectId":"p-alpha","title":"会话 1","updatedAt":1772625600000},{"id":"a2","projectId":"p-alpha","title":"会话 2","updatedAt":1772539200000},{"id":"a3","projectId":"p-alpha","title":"会话 3","updatedAt":1772452800000},{"id":"a4","projectId":"p-alpha","title":"会话 4","updatedAt":1772366400000},{"id":"a5","projectId":"p-alpha","title":"会话 5","updatedAt":1772280000000},{"id":"a6","projectId":"p-alpha","title":"会话 6","updatedAt":1772193600000},{"id":"a7","projectId":"p-alpha","title":"会话 7","updatedAt":1772107200000},{"id":"a8","projectId":"p-alpha","title":"会话 8","updatedAt":1772020800000},{"id":"a9","projectId":"p-alpha","title":"会话 9","updatedAt":1771934400000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":"a8","activeProjectId":"p-alpha","collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"projects","tabs":[{"id":"active","label":"活动","count":9},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":1}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"project:p-alpha","kind":"group","label":"alpha","projectId":"p-alpha","collapsed":false,"active":true,"count":9,"runningCount":0,"rows":[{"id":"a1","title":"会话 1","meta":"3/4","active":false,"running":false,"acp":false,"dsh":false},{"id":"a2","title":"会话 2","meta":"3/3","active":false,"running":false,"acp":false,"dsh":false},{"id":"a3","title":"会话 3","meta":"3/2","active":false,"running":false,"acp":false,"dsh":false},{"id":"a4","title":"会话 4","meta":"3/1","active":false,"running":false,"acp":false,"dsh":false},{"id":"a5","title":"会话 5","meta":"2/28","active":false,"running":false,"acp":false,"dsh":false},{"id":"a6","title":"会话 6","meta":"2/27","active":false,"running":false,"acp":false,"dsh":false},{"id":"a7","title":"会话 7","meta":"2/26","active":false,"running":false,"acp":false,"dsh":false},{"id":"a8","title":"会话 8","meta":"2/25","active":true,"running":false,"acp":false,"dsh":false},{"id":"a9","title":"会话 9","meta":"2/24","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":3,"expanded":true}]}''',
  ),
  (
    name: 'active-session-explicit-collapse',
    note: '用户显式收起该项目：即使当前会话被预览截断也不再自动展开（会话被藏起来）',
    input: r'''{"tab":"projects","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"a1","projectId":"p-alpha","title":"会话 1","updatedAt":1772625600000},{"id":"a2","projectId":"p-alpha","title":"会话 2","updatedAt":1772539200000},{"id":"a3","projectId":"p-alpha","title":"会话 3","updatedAt":1772452800000},{"id":"a4","projectId":"p-alpha","title":"会话 4","updatedAt":1772366400000},{"id":"a5","projectId":"p-alpha","title":"会话 5","updatedAt":1772280000000},{"id":"a6","projectId":"p-alpha","title":"会话 6","updatedAt":1772193600000},{"id":"a7","projectId":"p-alpha","title":"会话 7","updatedAt":1772107200000},{"id":"a8","projectId":"p-alpha","title":"会话 8","updatedAt":1772020800000},{"id":"a9","projectId":"p-alpha","title":"会话 9","updatedAt":1771934400000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":"a8","activeProjectId":"p-alpha","collapsedProjects":[],"expandedSessions":{"p-alpha":false},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"projects","tabs":[{"id":"active","label":"活动","count":9},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":1}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"project:p-alpha","kind":"group","label":"alpha","projectId":"p-alpha","collapsed":false,"active":true,"count":9,"runningCount":0,"rows":[{"id":"a1","title":"会话 1","meta":"3/4","active":false,"running":false,"acp":false,"dsh":false},{"id":"a2","title":"会话 2","meta":"3/3","active":false,"running":false,"acp":false,"dsh":false},{"id":"a3","title":"会话 3","meta":"3/2","active":false,"running":false,"acp":false,"dsh":false},{"id":"a4","title":"会话 4","meta":"3/1","active":false,"running":false,"acp":false,"dsh":false},{"id":"a5","title":"会话 5","meta":"2/28","active":false,"running":false,"acp":false,"dsh":false},{"id":"a6","title":"会话 6","meta":"2/27","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":3,"expanded":false}]}''',
  ),
  (
    name: 'search-by-title-and-project',
    note: '搜索走扁平段：title 命中与项目名命中各一行，未命中的被滤掉',
    input: r'''{"tab":"projects","query":"beta","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-chat","kind":"chat","name":"Chat"},{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"},{"id":"p-beta","name":"beta","path":"D:/work/beta/"}],"sessions":[{"id":"s1","projectId":"p-alpha","title":"beta 讨论","updatedAt":1772539200000},{"id":"s2","projectId":"p-beta","title":"无关标题","updatedAt":1772452800000},{"id":"s3","projectId":"p-alpha","title":"gamma 相关","updatedAt":1772366400000},{"id":"s4","projectId":"p-chat","title":"beta 聊天","updatedAt":1772625600000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":"s1","activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"projects","tabs":[{"id":"active","label":"活动","count":4},{"id":"chats","label":"聊天","count":1},{"id":"projects","label":"项目","count":2}],"query":"beta","deviceCount":1,"sheet":null,"sections":[{"key":"search","kind":"flat","rows":[{"id":"s4","title":"beta 聊天","meta":"Chat · 3/4","active":false,"running":false,"acp":false,"dsh":false},{"id":"s1","title":"beta 讨论","meta":"alpha · 3/3","active":true,"running":false,"acp":false,"dsh":false},{"id":"s2","title":"无关标题","meta":"beta · 3/2","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":0,"expanded":true}]}''',
  ),
  (
    name: 'search-miss',
    note: '搜索没命中：仍是搜索段，rows 空 + 「没有匹配的会话」',
    input: r'''{"tab":"active","query":"不存在的关键字","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"s1","projectId":"p-alpha","title":"你好","updatedAt":1772366400000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":null,"activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"active","tabs":[{"id":"active","label":"活动","count":1},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":1}],"query":"不存在的关键字","deviceCount":1,"sheet":null,"sections":[{"key":"search","kind":"flat","rows":[],"hiddenCount":0,"expanded":true,"empty":"没有匹配的会话"}]}''',
  ),
  (
    name: 'query-whitespace-only',
    note: '只输入空格：trim 后为空 → 不走搜索分支，但 snapshot.query 保留原串',
    input: r'''{"tab":"projects","query":"   ","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"a1","projectId":"p-alpha","title":"会话 1","updatedAt":1772625600000},{"id":"a2","projectId":"p-alpha","title":"会话 2","updatedAt":1772539200000},{"id":"a3","projectId":"p-alpha","title":"会话 3","updatedAt":1772452800000},{"id":"a4","projectId":"p-alpha","title":"会话 4","updatedAt":1772366400000},{"id":"a5","projectId":"p-alpha","title":"会话 5","updatedAt":1772280000000},{"id":"a6","projectId":"p-alpha","title":"会话 6","updatedAt":1772193600000},{"id":"a7","projectId":"p-alpha","title":"会话 7","updatedAt":1772107200000},{"id":"a8","projectId":"p-alpha","title":"会话 8","updatedAt":1772020800000},{"id":"a9","projectId":"p-alpha","title":"会话 9","updatedAt":1771934400000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":null,"activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"projects","tabs":[{"id":"active","label":"活动","count":9},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":1}],"query":"   ","deviceCount":1,"sheet":null,"sections":[{"key":"project:p-alpha","kind":"group","label":"alpha","projectId":"p-alpha","collapsed":false,"active":false,"count":9,"runningCount":0,"rows":[{"id":"a1","title":"会话 1","meta":"3/4","active":false,"running":false,"acp":false,"dsh":false},{"id":"a2","title":"会话 2","meta":"3/3","active":false,"running":false,"acp":false,"dsh":false},{"id":"a3","title":"会话 3","meta":"3/2","active":false,"running":false,"acp":false,"dsh":false},{"id":"a4","title":"会话 4","meta":"3/1","active":false,"running":false,"acp":false,"dsh":false},{"id":"a5","title":"会话 5","meta":"2/28","active":false,"running":false,"acp":false,"dsh":false},{"id":"a6","title":"会话 6","meta":"2/27","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":3,"expanded":false}]}''',
  ),
  (
    name: 'active-live-truncation',
    note: '活动段：运行中 7 条（含大写 Running）截断到 6，STOPPED/stopped 落到最近会话',
    input: r'''{"tab":"active","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"a1","projectId":"p-alpha","title":"会话 1","updatedAt":1772625600000},{"id":"a2","projectId":"p-alpha","title":"会话 2","updatedAt":1772539200000},{"id":"a3","projectId":"p-alpha","title":"会话 3","updatedAt":1772452800000},{"id":"a4","projectId":"p-alpha","title":"会话 4","updatedAt":1772366400000},{"id":"a5","projectId":"p-alpha","title":"会话 5","updatedAt":1772280000000},{"id":"a6","projectId":"p-alpha","title":"会话 6","updatedAt":1772193600000},{"id":"a7","projectId":"p-alpha","title":"会话 7","updatedAt":1772107200000},{"id":"a8","projectId":"p-alpha","title":"会话 8","updatedAt":1772020800000},{"id":"a9","projectId":"p-alpha","title":"会话 9","updatedAt":1771934400000},{"id":"b1","projectId":"p-alpha","title":"被停掉的","updatedAt":1771848000000}],"runtimes":[{"sessionId":"a1","status":"running"},{"sessionId":"a2","status":"running"},{"sessionId":"a3","status":"running"},{"sessionId":"a4","status":"running"},{"sessionId":"a5","status":"running"},{"sessionId":"a6","status":"running"},{"sessionId":"a7","status":"Running"},{"sessionId":"a8","status":"STOPPED"},{"sessionId":"a9","status":"stopped"}]}}],"activeEndpointId":"ep1","activeSessionId":null,"activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"active","tabs":[{"id":"active","label":"活动","count":10},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":1}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"active-live","kind":"flat","title":"运行中","rows":[{"id":"a1","title":"会话 1","meta":"alpha · 3/4","active":false,"running":true,"acp":false,"dsh":false},{"id":"a2","title":"会话 2","meta":"alpha · 3/3","active":false,"running":true,"acp":false,"dsh":false},{"id":"a3","title":"会话 3","meta":"alpha · 3/2","active":false,"running":true,"acp":false,"dsh":false},{"id":"a4","title":"会话 4","meta":"alpha · 3/1","active":false,"running":true,"acp":false,"dsh":false},{"id":"a5","title":"会话 5","meta":"alpha · 2/28","active":false,"running":true,"acp":false,"dsh":false},{"id":"a6","title":"会话 6","meta":"alpha · 2/27","active":false,"running":true,"acp":false,"dsh":false}],"hiddenCount":1,"expanded":false},{"key":"active-recent","kind":"flat","title":"最近会话","rows":[{"id":"a8","title":"会话 8","meta":"alpha · 2/25","active":false,"running":false,"acp":false,"dsh":false},{"id":"a9","title":"会话 9","meta":"alpha · 2/24","active":false,"running":false,"acp":false,"dsh":false},{"id":"b1","title":"被停掉的","meta":"alpha · 2/23","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":0,"expanded":false}]}''',
  ),
  (
    name: 'active-flat-expanded',
    note: 'flatExpanded 同时展开 active-live / active-recent：行全出，expanded=true',
    input: r'''{"tab":"active","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"a1","projectId":"p-alpha","title":"会话 1","updatedAt":1772625600000},{"id":"a2","projectId":"p-alpha","title":"会话 2","updatedAt":1772539200000},{"id":"a3","projectId":"p-alpha","title":"会话 3","updatedAt":1772452800000},{"id":"a4","projectId":"p-alpha","title":"会话 4","updatedAt":1772366400000},{"id":"a5","projectId":"p-alpha","title":"会话 5","updatedAt":1772280000000},{"id":"a6","projectId":"p-alpha","title":"会话 6","updatedAt":1772193600000},{"id":"a7","projectId":"p-alpha","title":"会话 7","updatedAt":1772107200000},{"id":"a8","projectId":"p-alpha","title":"会话 8","updatedAt":1772020800000},{"id":"a9","projectId":"p-alpha","title":"会话 9","updatedAt":1771934400000},{"id":"b1","projectId":"p-alpha","title":"被停掉的","updatedAt":1771848000000}],"runtimes":[{"sessionId":"a1","status":"running"},{"sessionId":"a2","status":"running"},{"sessionId":"a3","status":"running"},{"sessionId":"a4","status":"running"},{"sessionId":"a5","status":"running"},{"sessionId":"a6","status":"running"},{"sessionId":"a7","status":"Running"},{"sessionId":"a8","status":"STOPPED"},{"sessionId":"a9","status":"stopped"}]}}],"activeEndpointId":"ep1","activeSessionId":null,"activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":["active-live","active-recent"],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"active","tabs":[{"id":"active","label":"活动","count":10},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":1}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"active-live","kind":"flat","title":"运行中","rows":[{"id":"a1","title":"会话 1","meta":"alpha · 3/4","active":false,"running":true,"acp":false,"dsh":false},{"id":"a2","title":"会话 2","meta":"alpha · 3/3","active":false,"running":true,"acp":false,"dsh":false},{"id":"a3","title":"会话 3","meta":"alpha · 3/2","active":false,"running":true,"acp":false,"dsh":false},{"id":"a4","title":"会话 4","meta":"alpha · 3/1","active":false,"running":true,"acp":false,"dsh":false},{"id":"a5","title":"会话 5","meta":"alpha · 2/28","active":false,"running":true,"acp":false,"dsh":false},{"id":"a6","title":"会话 6","meta":"alpha · 2/27","active":false,"running":true,"acp":false,"dsh":false},{"id":"a7","title":"会话 7","meta":"alpha · 2/26","active":false,"running":true,"acp":false,"dsh":false}],"hiddenCount":1,"expanded":true},{"key":"active-recent","kind":"flat","title":"最近会话","rows":[{"id":"a8","title":"会话 8","meta":"alpha · 2/25","active":false,"running":false,"acp":false,"dsh":false},{"id":"a9","title":"会话 9","meta":"alpha · 2/24","active":false,"running":false,"acp":false,"dsh":false},{"id":"b1","title":"被停掉的","meta":"alpha · 2/23","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":0,"expanded":true}]}''',
  ),
  (
    name: 'chats-backends',
    note: '聊天分段：13 条按 12 截断；徽标/ACP 工具名/dsh 精确比较/pendingUiRequests 不参与',
    input: r'''{"tab":"chats","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-chat","kind":"chat","name":"Chat"},{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"c1","projectId":"p-chat","title":"普通会话","updatedAt":1772625600000},{"id":"c2","projectId":"p-chat","title":"dsh 的活","updatedAt":1772539200000,"backend":"dsh"},{"id":"c3","projectId":"p-chat","title":"大写 DSH","updatedAt":1772452800000,"backend":"DSH"},{"id":"c4","projectId":"p-chat","title":"ACP 一","updatedAt":1772366400000,"backend":"acp","acpToolId":"t1"},{"id":"c5","projectId":"p-chat","title":"生图","updatedAt":1772280000000,"backend":" imagegen "},{"id":"c6","projectId":"p-chat","title":"ACP 二","updatedAt":1772193600000,"backend":"acp","acpToolId":"t-unknown"},{"id":"c7","projectId":"p-chat","title":"会话 7","updatedAt":1772107200000},{"id":"c8","projectId":"p-chat","title":"会话 8","updatedAt":1772020800000},{"id":"c9","projectId":"p-chat","title":"会话 9","updatedAt":1771934400000},{"id":"c10","projectId":"p-chat","title":"会话 10","updatedAt":1771848000000},{"id":"c11","projectId":"p-chat","title":"会话 11","updatedAt":1771761600000},{"id":"c12","projectId":"p-chat","title":"会话 12","updatedAt":1771675200000},{"id":"c13","projectId":"p-chat","title":"会话 13","updatedAt":1771588800000}],"runtimes":[{"sessionId":"c2","status":"running"}],"acpTools":[{"id":"t1","name":" Gemini "}],"pendingUiRequests":[{"id":"q1","kind":"select"},{"id":"q2","kind":"confirm"}]}}],"activeEndpointId":"ep1","activeSessionId":"c4","activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"chats","tabs":[{"id":"active","label":"活动","count":11},{"id":"chats","label":"聊天","count":13},{"id":"projects","label":"项目","count":1}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"chats","kind":"flat","rows":[{"id":"c1","title":"普通会话","meta":"3/4","active":false,"running":false,"acp":false,"dsh":false},{"id":"c2","title":"dsh 的活","meta":"3/3","badge":"DSH","badgeKind":"dsh","active":false,"running":true,"acp":false,"dsh":true},{"id":"c3","title":"大写 DSH","meta":"3/2","badge":"DSH","badgeKind":"DSH","active":false,"running":false,"acp":false,"dsh":false},{"id":"c4","title":"ACP 一","meta":"3/1","badge":"ACP","badgeKind":"acp","acpToolName":"Gemini","active":true,"running":false,"acp":true,"dsh":false},{"id":"c5","title":"生图","meta":"2/28","badge":"生图","badgeKind":" imagegen ","active":false,"running":false,"acp":false,"dsh":false},{"id":"c6","title":"ACP 二","meta":"2/27","badge":"ACP","badgeKind":"acp","active":false,"running":false,"acp":true,"dsh":false},{"id":"c7","title":"会话 7","meta":"2/26","active":false,"running":false,"acp":false,"dsh":false},{"id":"c8","title":"会话 8","meta":"2/25","active":false,"running":false,"acp":false,"dsh":false},{"id":"c9","title":"会话 9","meta":"2/24","active":false,"running":false,"acp":false,"dsh":false},{"id":"c10","title":"会话 10","meta":"2/23","active":false,"running":false,"acp":false,"dsh":false},{"id":"c11","title":"会话 11","meta":"2/22","active":false,"running":false,"acp":false,"dsh":false},{"id":"c12","title":"会话 12","meta":"2/21","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":1,"expanded":false}]}''',
  ),
  (
    name: 'chats-flat-expanded',
    note: '聊天分段 flatExpanded：13 行全出，hiddenCount 仍为 1',
    input: r'''{"tab":"chats","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-chat","kind":"chat","name":"Chat"},{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"c1","projectId":"p-chat","title":"普通会话","updatedAt":1772625600000},{"id":"c2","projectId":"p-chat","title":"dsh 的活","updatedAt":1772539200000,"backend":"dsh"},{"id":"c3","projectId":"p-chat","title":"大写 DSH","updatedAt":1772452800000,"backend":"DSH"},{"id":"c4","projectId":"p-chat","title":"ACP 一","updatedAt":1772366400000,"backend":"acp","acpToolId":"t1"},{"id":"c5","projectId":"p-chat","title":"生图","updatedAt":1772280000000,"backend":" imagegen "},{"id":"c6","projectId":"p-chat","title":"ACP 二","updatedAt":1772193600000,"backend":"acp","acpToolId":"t-unknown"},{"id":"c7","projectId":"p-chat","title":"会话 7","updatedAt":1772107200000},{"id":"c8","projectId":"p-chat","title":"会话 8","updatedAt":1772020800000},{"id":"c9","projectId":"p-chat","title":"会话 9","updatedAt":1771934400000},{"id":"c10","projectId":"p-chat","title":"会话 10","updatedAt":1771848000000},{"id":"c11","projectId":"p-chat","title":"会话 11","updatedAt":1771761600000},{"id":"c12","projectId":"p-chat","title":"会话 12","updatedAt":1771675200000},{"id":"c13","projectId":"p-chat","title":"会话 13","updatedAt":1771588800000}],"runtimes":[{"sessionId":"c2","status":"running"}],"acpTools":[{"id":"t1","name":" Gemini "}],"pendingUiRequests":[{"id":"q1","kind":"select"},{"id":"q2","kind":"confirm"}]}}],"activeEndpointId":"ep1","activeSessionId":"c4","activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":["chats"],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"chats","tabs":[{"id":"active","label":"活动","count":11},{"id":"chats","label":"聊天","count":13},{"id":"projects","label":"项目","count":1}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"chats","kind":"flat","rows":[{"id":"c1","title":"普通会话","meta":"3/4","active":false,"running":false,"acp":false,"dsh":false},{"id":"c2","title":"dsh 的活","meta":"3/3","badge":"DSH","badgeKind":"dsh","active":false,"running":true,"acp":false,"dsh":true},{"id":"c3","title":"大写 DSH","meta":"3/2","badge":"DSH","badgeKind":"DSH","active":false,"running":false,"acp":false,"dsh":false},{"id":"c4","title":"ACP 一","meta":"3/1","badge":"ACP","badgeKind":"acp","acpToolName":"Gemini","active":true,"running":false,"acp":true,"dsh":false},{"id":"c5","title":"生图","meta":"2/28","badge":"生图","badgeKind":" imagegen ","active":false,"running":false,"acp":false,"dsh":false},{"id":"c6","title":"ACP 二","meta":"2/27","badge":"ACP","badgeKind":"acp","active":false,"running":false,"acp":true,"dsh":false},{"id":"c7","title":"会话 7","meta":"2/26","active":false,"running":false,"acp":false,"dsh":false},{"id":"c8","title":"会话 8","meta":"2/25","active":false,"running":false,"acp":false,"dsh":false},{"id":"c9","title":"会话 9","meta":"2/24","active":false,"running":false,"acp":false,"dsh":false},{"id":"c10","title":"会话 10","meta":"2/23","active":false,"running":false,"acp":false,"dsh":false},{"id":"c11","title":"会话 11","meta":"2/22","active":false,"running":false,"acp":false,"dsh":false},{"id":"c12","title":"会话 12","meta":"2/21","active":false,"running":false,"acp":false,"dsh":false},{"id":"c13","title":"会话 13","meta":"2/20","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":1,"expanded":true}]}''',
  ),
  (
    name: 'multi-device-projects',
    note: '两台设备：设备头 + 各自分组；项目行不带 deviceLabel（只有活动/聊天/搜索带）',
    input: r'''{"tab":"projects","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-chat","kind":"chat","name":"Chat"},{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"},{"id":"p-beta","name":"beta","path":"D:/work/beta/"}],"sessions":[{"id":"s1","projectId":"p-alpha","title":"写文档","updatedAt":1772625600000},{"id":"s2","projectId":"p-beta","title":"修 CI","updatedAt":1772539200000},{"id":"s5","projectId":"p-chat","title":"本机聊天","updatedAt":1772366400000}],"runtimes":[{"sessionId":"s1","status":"running"},{"sessionId":"s2","status":"running"}]}},{"id":"ep2","label":"办公室","state":{"projects":[{"id":"p-chat2","kind":"chat","name":"Chat"},{"id":"p-gamma","name":"gamma","path":"/srv/gamma"}],"sessions":[{"id":"s3","projectId":"p-gamma","title":"gamma 的活","updatedAt":1772193600000},{"id":"s4","projectId":"p-chat2","title":"另一台机器的聊天","updatedAt":1772452800000}],"runtimes":[{"sessionId":"s3","status":"running"}]}}],"activeEndpointId":"ep1","activeSessionId":"s1","activeProjectId":"p-alpha","collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"projects","tabs":[{"id":"active","label":"活动","count":5},{"id":"chats","label":"聊天","count":2},{"id":"projects","label":"项目","count":3}],"query":"","deviceCount":2,"sheet":null,"sections":[{"key":"device:ep1","kind":"device","label":"本机","active":true,"deviceRunning":2,"rows":[],"hiddenCount":0,"expanded":true},{"key":"project:p-alpha","kind":"group","label":"alpha","projectId":"p-alpha","collapsed":false,"active":true,"count":1,"runningCount":1,"rows":[{"id":"s1","title":"写文档","meta":"3/4","active":true,"running":true,"acp":false,"dsh":false}],"hiddenCount":0,"expanded":false},{"key":"project:p-beta","kind":"group","label":"beta","projectId":"p-beta","collapsed":false,"active":false,"count":1,"runningCount":1,"rows":[{"id":"s2","title":"修 CI","meta":"3/3","active":false,"running":true,"acp":false,"dsh":false}],"hiddenCount":0,"expanded":false},{"key":"device:ep2","kind":"device","label":"办公室","active":false,"deviceRunning":1,"rows":[],"hiddenCount":0,"expanded":true},{"key":"project:p-gamma","kind":"group","label":"gamma","projectId":"p-gamma","collapsed":false,"active":false,"count":1,"runningCount":1,"rows":[{"id":"s3","title":"gamma 的活","meta":"2/27","active":false,"running":true,"acp":false,"dsh":false}],"hiddenCount":0,"expanded":false}]}''',
  ),
  (
    name: 'multi-device-active',
    note: '多设备活动段：行带设备名，跨设备按时间合并，recent 只取前 10 条',
    input: r'''{"tab":"active","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"m1","projectId":"p-alpha","title":"本机 1","updatedAt":1772625600000},{"id":"m2","projectId":"p-alpha","title":"本机 2","updatedAt":1772452800000},{"id":"m3","projectId":"p-alpha","title":"本机 3","updatedAt":1772280000000},{"id":"m4","projectId":"p-alpha","title":"本机 4","updatedAt":1772107200000},{"id":"m5","projectId":"p-alpha","title":"本机 5","updatedAt":1771934400000},{"id":"m6","projectId":"p-alpha","title":"本机 6","updatedAt":1771761600000},{"id":"m7","projectId":"p-alpha","title":"本机 7","updatedAt":1771588800000},{"id":"m8","projectId":"p-alpha","title":"本机 8","updatedAt":1771416000000}],"runtimes":[{"sessionId":"m1","status":"running"}]}},{"id":"ep2","label":"办公室","state":{"projects":[{"id":"p-beta","name":"beta","path":"D:/work/beta/"}],"sessions":[{"id":"n1","projectId":"p-beta","title":"办公室 1","updatedAt":1772539200000},{"id":"n2","projectId":"p-beta","title":"办公室 2","updatedAt":1772366400000},{"id":"n3","projectId":"p-beta","title":"办公室 3","updatedAt":1772193600000},{"id":"n4","projectId":"p-beta","title":"办公室 4","updatedAt":1772020800000},{"id":"n5","projectId":"p-beta","title":"办公室 5","updatedAt":1771848000000},{"id":"n6","projectId":"p-beta","title":"办公室 6","updatedAt":1771675200000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":"m1","activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"active","tabs":[{"id":"active","label":"活动","count":11},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":2}],"query":"","deviceCount":2,"sheet":null,"sections":[{"key":"active-live","kind":"flat","title":"运行中","rows":[{"id":"m1","title":"本机 1","meta":"alpha · 3/4","deviceLabel":"本机","active":true,"running":true,"acp":false,"dsh":false}],"hiddenCount":0,"expanded":false},{"key":"active-recent","kind":"flat","title":"最近会话","rows":[{"id":"n1","title":"办公室 1","meta":"beta · 3/3","deviceLabel":"办公室","active":false,"running":false,"acp":false,"dsh":false},{"id":"m2","title":"本机 2","meta":"alpha · 3/2","deviceLabel":"本机","active":false,"running":false,"acp":false,"dsh":false},{"id":"n2","title":"办公室 2","meta":"beta · 3/1","deviceLabel":"办公室","active":false,"running":false,"acp":false,"dsh":false},{"id":"m3","title":"本机 3","meta":"alpha · 2/28","deviceLabel":"本机","active":false,"running":false,"acp":false,"dsh":false},{"id":"n3","title":"办公室 3","meta":"beta · 2/27","deviceLabel":"办公室","active":false,"running":false,"acp":false,"dsh":false},{"id":"m4","title":"本机 4","meta":"alpha · 2/26","deviceLabel":"本机","active":false,"running":false,"acp":false,"dsh":false},{"id":"n4","title":"办公室 4","meta":"beta · 2/25","deviceLabel":"办公室","active":false,"running":false,"acp":false,"dsh":false},{"id":"m5","title":"本机 5","meta":"alpha · 2/24","deviceLabel":"本机","active":false,"running":false,"acp":false,"dsh":false},{"id":"n5","title":"办公室 5","meta":"beta · 2/23","deviceLabel":"办公室","active":false,"running":false,"acp":false,"dsh":false},{"id":"m6","title":"本机 6","meta":"alpha · 2/22","deviceLabel":"本机","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":0,"expanded":false}]}''',
  ),
  (
    name: 'multi-device-clock-skew',
    note: '未来时间戳被压到 now+60s：两条未来时间并列 → 取设备内原顺序（排序稳定性）',
    input: r'''{"tab":"active","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"x1","projectId":"p-alpha","title":"未来 30 天","updatedAt":1775304000000},{"id":"x2","projectId":"p-alpha","title":"未来 60 天","updatedAt":1777896000000},{"id":"x3","projectId":"p-alpha","title":"更早","updatedAt":1772452800000}],"runtimes":[]}},{"id":"ep2","label":"办公室","state":{"projects":[{"id":"p-beta","name":"beta","path":"D:/work/beta/"}],"sessions":[{"id":"y1","projectId":"p-beta","title":"对面刚写的","updatedAt":1772539200000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":null,"activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"active","tabs":[{"id":"active","label":"活动","count":4},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":2}],"query":"","deviceCount":2,"sheet":null,"sections":[{"key":"active-recent","kind":"flat","title":"最近会话","rows":[{"id":"x2","title":"未来 60 天","meta":"alpha · 5/4","deviceLabel":"本机","active":false,"running":false,"acp":false,"dsh":false},{"id":"x1","title":"未来 30 天","meta":"alpha · 4/4","deviceLabel":"本机","active":false,"running":false,"acp":false,"dsh":false},{"id":"y1","title":"对面刚写的","meta":"beta · 3/3","deviceLabel":"办公室","active":false,"running":false,"acp":false,"dsh":false},{"id":"x3","title":"更早","meta":"alpha · 3/2","deviceLabel":"本机","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":0,"expanded":false}]}''',
  ),
  (
    name: 'sheet-passthrough',
    note: '侧栏弹层原样带出（键序 = 输入键序），没有弹层时固定是 null',
    input: r'''{"tab":"projects","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"}],"sessions":[{"id":"a1","projectId":"p-alpha","title":"会话 1","updatedAt":1772625600000},{"id":"a2","projectId":"p-alpha","title":"会话 2","updatedAt":1772539200000},{"id":"a3","projectId":"p-alpha","title":"会话 3","updatedAt":1772452800000},{"id":"a4","projectId":"p-alpha","title":"会话 4","updatedAt":1772366400000},{"id":"a5","projectId":"p-alpha","title":"会话 5","updatedAt":1772280000000},{"id":"a6","projectId":"p-alpha","title":"会话 6","updatedAt":1772193600000},{"id":"a7","projectId":"p-alpha","title":"会话 7","updatedAt":1772107200000},{"id":"a8","projectId":"p-alpha","title":"会话 8","updatedAt":1772020800000},{"id":"a9","projectId":"p-alpha","title":"会话 9","updatedAt":1771934400000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":null,"activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":{"kind":"newSession","title":"新建会话","note":"选择工作区","items":[{"id":"project:p-alpha","label":"alpha","selected":true,"actionable":true},{"id":"project:p-beta","label":"beta","description":"D:/work/beta","danger":true}]},"now":1772712000000}''',
    expected: r'''{"tab":"projects","tabs":[{"id":"active","label":"活动","count":9},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":1}],"query":"","deviceCount":1,"sheet":{"kind":"newSession","title":"新建会话","note":"选择工作区","items":[{"id":"project:p-alpha","label":"alpha","selected":true,"actionable":true},{"id":"project:p-beta","label":"beta","description":"D:/work/beta","danger":true}]},"sections":[{"key":"project:p-alpha","kind":"group","label":"alpha","projectId":"p-alpha","collapsed":false,"active":false,"count":9,"runningCount":0,"rows":[{"id":"a1","title":"会话 1","meta":"3/4","active":false,"running":false,"acp":false,"dsh":false},{"id":"a2","title":"会话 2","meta":"3/3","active":false,"running":false,"acp":false,"dsh":false},{"id":"a3","title":"会话 3","meta":"3/2","active":false,"running":false,"acp":false,"dsh":false},{"id":"a4","title":"会话 4","meta":"3/1","active":false,"running":false,"acp":false,"dsh":false},{"id":"a5","title":"会话 5","meta":"2/28","active":false,"running":false,"acp":false,"dsh":false},{"id":"a6","title":"会话 6","meta":"2/27","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":3,"expanded":false}]}''',
  ),
  (
    name: 'backend-normalization',
    note: 'backend 归一化只影响徽标/布尔：badgeKind 保留原始串，dsh 用原始比较；acpTools 首个 id 命中即返回（name 为空也不往后找）',
    input: r'''{"tab":"chats","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-chat","kind":"chat","name":"Chat"}],"sessions":[{"id":"n1","projectId":"p-chat","title":"带空格 pi","updatedAt":1772625600000,"backend":" Pi "},{"id":"n2","projectId":"p-chat","title":"带空格 acp","updatedAt":1772539200000,"backend":"ACP ","acpToolId":"dup"},{"id":"n3","projectId":"p-chat","title":"混排 dsh","updatedAt":1772452800000,"backend":"Dsh"},{"id":"n4","projectId":"p-chat","title":"空 name 的 acp","updatedAt":1772366400000,"backend":"acp","acpToolId":"blank"}],"runtimes":[],"acpTools":[{"id":"dup","name":"   "},{"id":"dup","name":"Gemini"},{"id":"blank","name":"  Claude  "}]}}],"activeEndpointId":"ep1","activeSessionId":null,"activeProjectId":null,"collapsedProjects":[],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"chats","tabs":[{"id":"active","label":"活动","count":4},{"id":"chats","label":"聊天","count":4},{"id":"projects","label":"项目","count":0}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"chats","kind":"flat","rows":[{"id":"n1","title":"带空格 pi","meta":"3/4","active":false,"running":false,"acp":false,"dsh":false},{"id":"n2","title":"带空格 acp","meta":"3/3","badge":"ACP","badgeKind":"ACP ","active":false,"running":false,"acp":true,"dsh":false},{"id":"n3","title":"混排 dsh","meta":"3/2","badge":"DSH","badgeKind":"Dsh","active":false,"running":false,"acp":false,"dsh":false},{"id":"n4","title":"空 name 的 acp","meta":"3/1","badge":"ACP","badgeKind":"acp","acpToolName":"Claude","active":false,"running":false,"acp":true,"dsh":false}],"hiddenCount":0,"expanded":false}]}''',
  ),
  (
    name: 'empty-project-group',
    note: '空项目分组照常出现：rows 为空、empty=「暂无会话」、hiddenCount 0',
    input: r'''{"tab":"projects","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"},{"id":"p-beta","name":"beta","path":"D:/work/beta/"}],"sessions":[{"id":"s1","projectId":"p-alpha","title":"有活的","updatedAt":1772625600000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":null,"activeProjectId":null,"collapsedProjects":["p-beta"],"expandedSessions":{},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"projects","tabs":[{"id":"active","label":"活动","count":1},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":2}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"project:p-alpha","kind":"group","label":"alpha","projectId":"p-alpha","collapsed":false,"active":false,"count":1,"runningCount":0,"rows":[{"id":"s1","title":"有活的","meta":"3/4","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":0,"expanded":false},{"key":"project:p-beta","kind":"group","label":"beta","projectId":"p-beta","collapsed":true,"active":false,"count":0,"runningCount":0,"rows":[],"hiddenCount":0,"expanded":false,"empty":"暂无会话"}]}''',
  ),
  (
    name: 'expanded-and-collapsed-overrides',
    note: '两个展开集合互不相干：alpha 显式展开、beta 显式收起且同时被标成展开 —— 收起时 rows 为空但 hiddenCount 仍按全量算',
    input: r'''{"tab":"projects","query":"","endpoints":[{"id":"ep1","label":"本机","state":{"projects":[{"id":"p-alpha","name":"alpha","path":"C:/work/alpha"},{"id":"p-beta","name":"beta","path":"D:/work/beta/"}],"sessions":[{"id":"a1","projectId":"p-alpha","title":"会话 1","updatedAt":1772625600000},{"id":"a2","projectId":"p-alpha","title":"会话 2","updatedAt":1772539200000},{"id":"a3","projectId":"p-alpha","title":"会话 3","updatedAt":1772452800000},{"id":"a4","projectId":"p-alpha","title":"会话 4","updatedAt":1772366400000},{"id":"a5","projectId":"p-alpha","title":"会话 5","updatedAt":1772280000000},{"id":"a6","projectId":"p-alpha","title":"会话 6","updatedAt":1772193600000},{"id":"a7","projectId":"p-alpha","title":"会话 7","updatedAt":1772107200000},{"id":"a8","projectId":"p-alpha","title":"会话 8","updatedAt":1772020800000},{"id":"a9","projectId":"p-alpha","title":"会话 9","updatedAt":1771934400000},{"id":"b1","projectId":"p-beta","title":"beta 1","updatedAt":1772625600000},{"id":"b2","projectId":"p-beta","title":"beta 2","updatedAt":1772539200000},{"id":"b3","projectId":"p-beta","title":"beta 3","updatedAt":1772452800000},{"id":"b4","projectId":"p-beta","title":"beta 4","updatedAt":1772366400000},{"id":"b5","projectId":"p-beta","title":"beta 5","updatedAt":1772280000000},{"id":"b6","projectId":"p-beta","title":"beta 6","updatedAt":1772193600000},{"id":"b7","projectId":"p-beta","title":"beta 7","updatedAt":1772107200000},{"id":"b8","projectId":"p-beta","title":"beta 8","updatedAt":1772020800000}],"runtimes":[]}}],"activeEndpointId":"ep1","activeSessionId":"a1","activeProjectId":"p-alpha","collapsedProjects":["p-beta"],"expandedSessions":{"p-alpha":true,"p-beta":true},"flatExpanded":[],"sheet":null,"now":1772712000000}''',
    expected: r'''{"tab":"projects","tabs":[{"id":"active","label":"活动","count":10},{"id":"chats","label":"聊天","count":0},{"id":"projects","label":"项目","count":2}],"query":"","deviceCount":1,"sheet":null,"sections":[{"key":"project:p-alpha","kind":"group","label":"alpha","projectId":"p-alpha","collapsed":false,"active":true,"count":9,"runningCount":0,"rows":[{"id":"a1","title":"会话 1","meta":"3/4","active":true,"running":false,"acp":false,"dsh":false},{"id":"a2","title":"会话 2","meta":"3/3","active":false,"running":false,"acp":false,"dsh":false},{"id":"a3","title":"会话 3","meta":"3/2","active":false,"running":false,"acp":false,"dsh":false},{"id":"a4","title":"会话 4","meta":"3/1","active":false,"running":false,"acp":false,"dsh":false},{"id":"a5","title":"会话 5","meta":"2/28","active":false,"running":false,"acp":false,"dsh":false},{"id":"a6","title":"会话 6","meta":"2/27","active":false,"running":false,"acp":false,"dsh":false},{"id":"a7","title":"会话 7","meta":"2/26","active":false,"running":false,"acp":false,"dsh":false},{"id":"a8","title":"会话 8","meta":"2/25","active":false,"running":false,"acp":false,"dsh":false},{"id":"a9","title":"会话 9","meta":"2/24","active":false,"running":false,"acp":false,"dsh":false}],"hiddenCount":3,"expanded":true},{"key":"project:p-beta","kind":"group","label":"beta","projectId":"p-beta","collapsed":true,"active":false,"count":8,"runningCount":0,"rows":[],"hiddenCount":2,"expanded":true}]}''',
  ),
];
void main() {
  group('buildSidebarSnapshot 与 TS 逐字节一致', () {
    for (final ({String name, String note, String input, String expected})
        fixture in sidebarFixtureCases) {
      test(fixture.name, () {
        final String actual = jsonEncode(
          buildSidebarSnapshot(_input(fixture.input)),
        );
        expect(actual, fixture.expected, reason: fixture.note);
      });
    }
  });

  group('timeLabel（本地时间，按时区无关的方式构造）', () {
    test('同一天用补零的 HH:MM', () {
      final int now = DateTime(2026, 1, 2, 8, 30).millisecondsSinceEpoch;
      expect(
        timeLabel(DateTime(2026, 1, 2, 3, 4).millisecondsSinceEpoch, now),
        '03:04',
      );
      expect(
        timeLabel(DateTime(2026, 1, 2, 23, 59).millisecondsSinceEpoch, now),
        '23:59',
      );
      expect(
        timeLabel(DateTime(2026, 1, 2, 0, 5).millisecondsSinceEpoch, now),
        '00:05',
      );
    });

    test('跨天用不补零的 M/D', () {
      final int now = DateTime(2026, 3, 1, 0, 0).millisecondsSinceEpoch;
      expect(
        timeLabel(DateTime(2026, 2, 28, 23, 59).millisecondsSinceEpoch, now),
        '2/28',
      );
      expect(
        timeLabel(DateTime(2025, 12, 31, 23, 59).millisecondsSinceEpoch, now),
        '12/31',
      );
      // 只看日历上的哪一天：2/28 23:59 与 3/1 00:00 差一分钟也是跨天
      expect(
        timeLabel(
          DateTime(2026, 3, 1, 0, 0).millisecondsSinceEpoch,
          DateTime(2026, 2, 28, 23, 59).millisecondsSinceEpoch,
        ),
        '3/1',
      );
    });

    test('没有时间戳 / 0 / NaN 都返回空串', () {
      expect(timeLabel(null), '');
      expect(timeLabel(0), '');
      expect(timeLabel(double.nan), '');
    });
  });

  group('排序稳定性（JS 的 sort 自 ES2019 起稳定）', () {
    test('updatedAt 全相同的一批会话保持输入顺序', () {
      final List<Object?> sessions = <Object?>[
        for (int i = 1; i <= 9; i++)
          _session('s$i', 'p1', 'S$i', 1767225600000),
      ];
      final Map<String, Object?> out = buildSidebarSnapshot(
        _snapshot(
          endpoints: <Object?>[
            _endpoint(projects: <Object?>[_alpha], sessions: sessions),
          ],
          expandedSessions: <String, Object?>{'p1': true},
        ),
      );
      expect(_rowIds(_section(out, 'project:p1')), <String>[
        's1',
        's2',
        's3',
        's4',
        's5',
        's6',
        's7',
        's8',
        's9',
      ]);
    });

    test('按 updatedAt 降序，同值时保持输入顺序', () {
      final Map<String, Object?> out = buildSidebarSnapshot(
        _snapshot(
          endpoints: <Object?>[
            _endpoint(
              projects: <Object?>[_alpha],
              sessions: <Object?>[
                _session('b', 'p1', 'B', 1000),
                _session('a', 'p1', 'A', 3000),
                _session('c', 'p1', 'C', 3000),
              ],
            ),
          ],
        ),
      );
      expect(_rowIds(_section(out, 'project:p1')), <String>['a', 'c', 'b']);
    });
  });

  test('不修改输入（纯函数）', () {
    final ({String name, String note, String input, String expected}) fixture =
        sidebarFixtureCases.firstWhere(
          (({String name, String note, String input, String expected}) entry) =>
              entry.name == 'multi-device-active',
        );
    final Map<String, Object?> input = _input(fixture.input);
    final String before = jsonEncode(input);
    buildSidebarSnapshot(input);
    expect(jsonEncode(input), before);
  });

  group('键序（JSON.stringify 的插入顺序也是契约的一部分）', () {
    test('端点为空时提前返回：sections 在 deviceCount 之前', () {
      final Map<String, Object?> out = buildSidebarSnapshot(
        _snapshot(tab: 'projects', query: 'abc'),
      );
      expect(out.keys.toList(), <String>[
        'tab',
        'tabs',
        'query',
        'sections',
        'deviceCount',
        'sheet',
      ]);
      expect(out['deviceCount'], 0);
      expect(out['tabs'], isEmpty);
      // query 原样带出（连空白都不 trim）
      expect(out['query'], 'abc');
    });

    test('项目分支一个分组都没有时，尾部追加 empty', () {
      final Map<String, Object?> out = buildSidebarSnapshot(
        _snapshot(endpoints: <Object?>[_endpoint()]),
      );
      expect(out.keys.toList(), <String>[
        'tab',
        'tabs',
        'query',
        'deviceCount',
        'sheet',
        'sections',
        'empty',
      ]);
      expect(out['empty'], isA<String>());
      expect(out['empty'] as String, isNotEmpty);
      expect(out['sections'], isEmpty);
    });

    test('分组段的键序，空分组把 empty 放在最后', () {
      final Map<String, Object?> out = buildSidebarSnapshot(
        _snapshot(endpoints: <Object?>[_endpoint(projects: <Object?>[_alpha])]),
      );
      expect(_section(out, 'project:p1').keys.toList(), <String>[
        'key',
        'kind',
        'label',
        'projectId',
        'collapsed',
        'active',
        'count',
        'runningCount',
        'rows',
        'hiddenCount',
        'expanded',
        'empty',
      ]);
    });

    test('分组有会话时 total>0，empty 不出现', () {
      final Map<String, Object?> out = buildSidebarSnapshot(
        _snapshot(
          endpoints: <Object?>[
            _endpoint(
              projects: <Object?>[_alpha],
              sessions: <Object?>[_session('s1', 'p1', '有活的', 1000)],
            ),
          ],
        ),
      );
      expect(
        _section(out, 'project:p1').containsKey('empty'),
        isFalse,
      );
    });
  });
}
