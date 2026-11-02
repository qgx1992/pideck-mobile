/// 抽屉自己的「用户意图」：当前页签 / 搜索词 / 收起与展开。
///
/// 这些状态原来只存在于 WebView（`main.ts` 的 `sidebarTab` / `sidebarQuery` /
/// `collapsedProjects` / `expandedSessions` / `flatExpanded`），抽屉每点一下都要
/// 绕一圈回 WebView 重算再推回来。接管之后由 Dart 本地先改、立刻重算快照，
/// 同时照旧把动作转给 WebView（那边是回退路径，状态不能漂）。
///
/// 只做**用户意图**的增删，不做任何展示规则：分组、折叠、截断、空态全在
/// [buildSidebarSnapshot]（`sidebar_native.dart`，`buildSidebarSnapshot()` 的移植）里。
library;

/// 页签 id（与 `sidebar-tabs-model.ts` 的 `SidebarTab` 同字符串）。
const String sidebarTabActive = 'active';
const String sidebarTabChats = 'chats';
const String sidebarTabProjects = 'projects';

class DrawerState {
  DrawerState({this.tab = sidebarTabActive});

  String tab;
  String query = '';

  /// 用户显式收起的项目 id。
  final Set<String> collapsedProjects = <String>{};

  /// 项目内「展开全部会话」的显式意图（true 展开 / false 收起）。
  ///
  /// 注意它**只**存用户点过的项目：当前会话被截断时的「临时展开」由渲染规则算，
  /// 不能写回这里 —— 否则进过一次会话后就永久全展开。
  final Map<String, bool> expandedSessions = <String, bool>{};

  /// 扁平段（活动 / 聊天）的「展开其余 N 个」。
  final Set<String> flatExpanded = <String>{};

  /// 从 WebView 推来的快照里把用户意图捞出来（只在第一次用）。
  ///
  /// 为什么需要：抽屉的页签与折叠是**持久**偏好在 WebView 的 localStorage 里，
  /// Dart 这边一上来是空的，直接接管会出现「重启后折叠全丢了」。
  void seedFrom({
    required String tab,
    required String query,
    required Map<String, bool> collapsed,
    required Map<String, bool> expandedSessions,
    required Set<String> flatExpanded,
  }) {
    if (tab.isNotEmpty) this.tab = tab;
    this.query = query;
    collapsedProjects
      ..clear()
      ..addAll(<String>[
        for (final MapEntry<String, bool> entry in collapsed.entries)
          if (entry.value) entry.key,
      ]);
    this.expandedSessions
      ..clear()
      ..addAll(expandedSessions);
    this.flatExpanded
      ..clear()
      ..addAll(flatExpanded);
  }

  void toggleTab(String next) {
    if (next != sidebarTabActive && next != sidebarTabChats && next != sidebarTabProjects) return;
    tab = next;
  }

  void setQuery(String next) => query = next;

  /// 切项目折叠：已经收起 → 展开；否则收起。
  void toggleProject(String projectId) {
    if (projectId.isEmpty) return;
    if (!collapsedProjects.remove(projectId)) collapsedProjects.add(projectId);
  }

  /// 切分段展开。项目组走「展开全部会话」，扁平段走「展开其余」——
  /// 两个集合互不相干（`dispatchFlutterSidebarAction` 里就是这么分派的）。
  void toggleSection(String key) {
    if (key.isEmpty) return;
    const String prefix = 'project:';
    if (key.startsWith(prefix)) {
      final String projectId = key.substring(prefix.length);
      if (projectId.isEmpty) return;
      final bool next = !(expandedSessions[projectId] ?? false);
      expandedSessions[projectId] = next;
      return;
    }
    if (!flatExpanded.remove(key)) flatExpanded.add(key);
  }

  /// 拼出 `buildSidebarSnapshot()` 的输入（键名与 TS 的 `SidebarSnapshotInput` 一致）。
  Map<String, Object?> toInput({
    required Map<String, Object?> state,
    required String endpointId,
    required String endpointLabel,
    required String? activeSessionId,
    required String? activeProjectId,
    Object? sheet,
  }) {
    return <String, Object?>{
      'tab': tab,
      'query': query,
      'endpoints': <Object?>[
        <String, Object?>{
          'id': endpointId,
          'label': endpointLabel,
          'state': state,
        },
      ],
      'activeEndpointId': endpointId,
      'activeSessionId': activeSessionId,
      'activeProjectId': activeProjectId,
      'collapsedProjects': collapsedProjects.toList(growable: false),
      'expandedSessions': <String, Object?>{
        for (final MapEntry<String, bool> entry in expandedSessions.entries) entry.key: entry.value,
      },
      'flatExpanded': flatExpanded.toList(growable: false),
      'sheet': sheet,
    };
  }
}
