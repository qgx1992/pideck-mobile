/// 侧栏快照的 Dart 版：把 Web 侧三份纯逻辑逐字节搬到 Dart。
///
/// 规则来源（改规则请三处一起改，再重新生成 `test/sidebar_native_test.dart` 的对照表）：
///
///   * `app/src/platform/flutter-sidebar.ts` —— 快照组装与行渲染
///   * `app/src/ui/sidebar-model.ts` —— 项目分组 / 折叠 / 预览截断
///   * `app/src/ui/sidebar-tabs-model.ts` —— 活动 / 聊天 / 搜索 / 多设备聚合
///
/// 这是纯函数模块：不 import Flutter，也不碰 `dart:ui`，只吃 JSON、吐 JSON。
/// 键名、键序、省略规则与 TS 版一致，`jsonEncode()` 的结果逐字节可比。
///
/// 为什么处处都在「模仿 JS」：这份代码的价值全在等价，不在好看。TS 版依赖了一堆
/// 隐式行为（`||` 与 `??` 的差别、falsy 才展开的可选键、`Array#sort` 的稳定性、
/// 本地时区的时间格式化），任何一处「顺手写成 Dart 习惯」都会让抽屉内容和 Web 版分叉。
library;

/// JS `||` 的等价物：null / false / 0 / NaN / 空串算假，其余（含空 Map、空 List）算真。
///
/// TS 里 `session.title || "未命名会话"` 连空串都会替换，而 `projectLabel ?? ""`
/// 只替换 null —— 两者混用是这一层最容易抄错的地方，所以分成两个函数。
Object? _jsOr(Object? value, Object fallback) => _jsTruthy(value) ? value : fallback;

bool _jsTruthy(Object? value) {
  if (value == null || value == false) return false;
  if (value is num) return value != 0 && !value.isNaN;
  if (value is String) return value.isNotEmpty;
  return true;
}

/// `state.sessions` / `state.projects` 这类数组的统一读法（缺字段当空数组）。
List<Object?> _listOf(Object? value) => value is List ? value : const <Object?>[];

/// 把一个 JSON 对象读成 `Map`（其余值当空 Map，避免到处写 `is Map`）。
Map<String, Object?> _mapOf(Object? value) =>
    value is Map ? value.cast<String, Object?>() : const <String, Object?>{};

/// `[{...}, {...}]` → `List<Map>`，丢掉非对象元素（TS 里这些项在取值时自然变成 undefined）。
List<Map<String, Object?>> _mapListOf(Object? value) => <Map<String, Object?>>[
  for (final Object? item in _listOf(value)) _mapOf(item),
];

/// `typeof x === "string" ? x : ""`。
String _strOrEmpty(Object? value) => value is String ? value : '';

/// `updatedAt ?? 0`：缺字段、非数字、NaN 都算 0（JS 的 `??` 只挡 null/undefined，
/// 这里顺手挡掉 NaN —— 否则排序比较器会返回 NaN，Dart 的 sort 直接抛错）。
num _sortKey(Map<String, Object?> session) {
  final Object? value = session['updatedAt'];
  return value is num && !value.isNaN ? value : 0;
}

/// `Set<string>` 的入参读法：JSON 里是数组，Dart 调用方可能直接给 Set。
Set<Object?> _setOf(Object? value) {
  if (value is Set) return value.cast<Object?>();
  if (value is List) return value.toSet();
  if (value is Map) return value.keys.toSet();
  return <Object?>{};
}

/// `ReadonlyMap<string, boolean>` 的入参读法（JSON 里是对象）。
Map<String, Object?> _mapOrEmpty(Object? value) =>
    value is Map ? value.cast<String, Object?>() : const <String, Object?>{};

/// 与 `ui/dom.ts` 的 `fmtTime` 同口径：今天 → `HH:MM`，其余 → `M/D`。
///
/// 用**本地**时区（`DateTime.fromMillisecondsSinceEpoch` 默认就是本地），
/// 与 `new Date(ms)` 一致；空 / 0 时间戳返回空串（TS 是 `if (!ms) return ""`）。
String timeLabel(num? ms, [num? now]) {
  if (ms == null || ms == 0 || ms.isNaN) return '';
  final DateTime date = DateTime.fromMillisecondsSinceEpoch(ms.toInt());
  final num nowValue = now ?? DateTime.now().millisecondsSinceEpoch;
  final DateTime today = DateTime.fromMillisecondsSinceEpoch(nowValue.toInt());
  final bool sameDay =
      today.year == date.year && today.month == date.month && today.day == date.day;
  final String hh = date.hour.toString().padLeft(2, '0');
  final String mm = date.minute.toString().padLeft(2, '0');
  if (sameDay) return '$hh:$mm';
  return '${date.month}/${date.day}';
}

/* ------------------------------------------------------------------ */
/* 后端徽标                                                            */
/* ------------------------------------------------------------------ */

/// 认不出来的 backend 一律按 pi 处理（`backend-capabilities.ts` 的同名规则）。
String _normalizeBackend(Object? backend) {
  final String kind = backend is String ? backend.trim().toLowerCase() : '';
  if (kind == 'dsh' || kind == 'acp' || kind == 'imagegen') return kind;
  return 'pi';
}

String _backendBadge(Object? backend) {
  switch (_normalizeBackend(backend)) {
    case 'dsh':
      return 'DSH';
    case 'acp':
      return 'ACP';
    case 'imagegen':
      return '生图';
    default:
      return '';
  }
}

/// 从 `acpTools`（`{id,name}[]`）里找某个 `acpToolId` 的显示名。
///
/// 取不到返回 null —— 不要回退成 id，那串用户不认识。
/// 注意：**第一个 id 命中的条目就返回**，即使它的 name 是空串也不再往后找。
String? _acpToolNameOf(Object? tools, Object? toolId) {
  if (tools is! List) return null;
  final String wanted = toolId is String ? toolId.trim() : '';
  if (wanted.isEmpty) return null;
  for (final Object? item in tools) {
    if (item is! Map) continue;
    final String id = _strOrEmpty(item['id']).trim();
    if (id != wanted) continue;
    final String name = _strOrEmpty(item['name']).trim();
    return name.isEmpty ? null : name;
  }
  return null;
}

/* ------------------------------------------------------------------ */
/* 项目分组（sidebar-model.ts）                                        */
/* ------------------------------------------------------------------ */

/// 每个项目默认显示最近几个会话（与 `SESSIONS_PREVIEW` 一致）。
const int _sessionsPreview = 6;

/// 项目名：内置 Chat 就叫 Chat，其余优先 name，其次路径末段，最后退回 id。
String? _projectLabel(Map<String, Object?>? project) {
  if (project == null) return null;
  if (project['kind'] == 'chat') return 'Chat';
  final Object? rawName = project['name'];
  final String name = rawName is String ? rawName.trim() : '';
  if (name.isNotEmpty) return name;
  final Object? rawPath = project['path'];
  final String raw = rawPath is String ? rawPath : '';
  // 与 `/[\\\/]+$/` 等价：先剥掉尾部的一个或多个分隔符，再按分隔符切段
  final String path = raw.replaceFirst(RegExp(r'[\\/]+$'), '');
  final List<String> segments = path
      .split(RegExp(r'[\\/]'))
      .where((String part) => part.isNotEmpty)
      .toList();
  if (segments.isNotEmpty) return segments.last;
  final Object? id = project['id'];
  return id is String ? id : null;
}

/// `Array#sort` 的稳定版：键相同的元素保持原顺序。
///
/// ES2019 起 `Array.prototype.sort` 是稳定排序，而 Dart 的 `List#sort` **不保证**，
/// 所以这里显式把下标当第二排序键。侧栏里「同一秒更新的两个会话」全靠它才不乱跳。
List<T> _stableSortByDesc<T>(List<T> items, num Function(T item) key) {
  final List<({int index, T item})> indexed = <({int index, T item})>[
    for (int i = 0; i < items.length; i += 1) (index: i, item: items[i]),
  ];
  indexed.sort((({int index, T item}) left, ({int index, T item}) right) {
    final int byKey = key(right.item).compareTo(key(left.item));
    if (byKey != 0) return byKey;
    return left.index.compareTo(right.index);
  });
  return <T>[for (final ({int index, T item}) entry in indexed) entry.item];
}

/// 生成侧栏分组：每个项目一组、按 `projects` 原顺序，组内按 `updatedAt` 倒序。
///
/// 搜索时（`query` 非空）跨项目过滤、空组隐藏、且强制展开；快照里调用时恒为空串，
/// 但这段照样搬过来 —— 少一条分支就少一条等价性。
List<Map<String, Object?>> _buildSidebarGroups(
  List<Object?> projects,
  List<Object?> sessions,
  Set<Object?> collapsed, {
  String query = '',
}) {
  final String needle = query.trim().toLowerCase();
  final List<Map<String, Object?>> groups = <Map<String, Object?>>[];

  for (final Object? rawProject in projects) {
    final Map<String, Object?> project = _mapOf(rawProject);
    final List<Map<String, Object?>> own = <Map<String, Object?>>[];
    for (final Object? rawSession in sessions) {
      final Map<String, Object?> session = _mapOf(rawSession);
      if (session['projectId'] == project['id']) own.add(session);
    }
    final List<Map<String, Object?>> matched = needle.isEmpty
        ? List<Map<String, Object?>>.of(own)
        : own
              .where(
                (Map<String, Object?> session) =>
                    _strOrEmpty(session['title']).toLowerCase().contains(needle),
              )
              .toList();
    if (needle.isNotEmpty && matched.isEmpty) continue;

    groups.add(<String, Object?>{
      'project': project,
      'label': _projectLabel(project) ?? _strOrEmpty(project['id']),
      'sessions': _stableSortByDesc(matched, _sortKey),
      'collapsed': needle.isNotEmpty ? false : collapsed.contains(project['id']),
      'total': own.length,
    });
  }
  return groups;
}

/// 某次渲染时应该「展开全部会话」的项目集合。
///
/// 优先级：显式收起（`false`，连「当前会话被截断」也不展开）> 显式展开（`true`）>
/// 当前会话排到第 `preview` 个之后时临时展开（否则看不到自己点开的会话）。
/// 临时展开**不能**写回 override，否则进过一次会话后就永久全展开。
Set<Object?> _resolveExpandedProjects(
  Map<String, Object?> state,
  Map<String, Object?> override,
  String? activeSessionId, {
  int preview = _sessionsPreview,
}) {
  final Set<Object?> result = <Object?>{};
  override.forEach((String projectId, Object? expanded) {
    if (_jsTruthy(expanded)) result.add(projectId);
  });

  if (activeSessionId == null || activeSessionId.isEmpty) return result;

  Map<String, Object?>? active;
  for (final Object? rawSession in _listOf(state['sessions'])) {
    final Map<String, Object?> session = _mapOf(rawSession);
    if (session['id'] == activeSessionId) {
      active = session;
      break;
    }
  }
  if (active == null) return result;

  final Object? activeProjectId = active['projectId'];
  // 用户已经明确收起该项目 → 尊重选择，不自动展开
  if (activeProjectId is String && override[activeProjectId] == false) return result;

  final List<Map<String, Object?>> siblings = <Map<String, Object?>>[
    for (final Object? rawSession in _listOf(state['sessions']))
      if (_mapOf(rawSession)['projectId'] == activeProjectId) _mapOf(rawSession),
  ];
  final List<Map<String, Object?>> sorted = _stableSortByDesc(siblings, _sortKey);
  int position = -1;
  for (int i = 0; i < sorted.length; i += 1) {
    if (sorted[i]['id'] == activeSessionId) {
      position = i;
      break;
    }
  }
  if (position >= preview) result.add(activeProjectId);
  return result;
}

/* ------------------------------------------------------------------ */
/* 活动 / 聊天 / 搜索（sidebar-tabs-model.ts）                         */
/* ------------------------------------------------------------------ */

/// 「最近会话」最多放几条（与 `ACTIVITY_RECENT_LIMIT` 一致）。
const int _activityRecentLimit = 10;

/// 侧栏顶部三个分段（顺序与 `SIDEBAR_TABS` 一致）。
const List<({String id, String label})> _sidebarTabs = <({String id, String label})>[
  (id: 'active', label: '活动'),
  (id: 'chats', label: '聊天'),
  (id: 'projects', label: '项目'),
];

/// 这些状态的运行时已经不算「活动」了（小写后比较）。
const Set<String> _inactiveRuntimeStatus = <String>{
  'stopped',
  'closed',
  'exited',
  'error',
  'aborted',
};

/// 跨设备排序时未来时间戳的压缩上限：时钟差几分钟不该让某台机器永远霸榜。
const int _clockSkewMs = 60000;

/// `Number.MAX_SAFE_INTEGER`：多设备聚合时先按「全都要」取，最后再统一截断。
const int _maxSafeInteger = 9007199254740991;

typedef _ActivityRow = ({Map<String, Object?> session, String projectLabel, bool running});
typedef _ActivityView = ({List<_ActivityRow> live, List<_ActivityRow> recent});

/// `projectLabel(projects.find(...)) ?? ""`。
String _labelFor(List<Object?> projects, Map<String, Object?> session) {
  for (final Object? rawProject in projects) {
    final Map<String, Object?> project = _mapOf(rawProject);
    if (project['id'] == session['projectId']) {
      return _projectLabel(project) ?? '';
    }
  }
  return '';
}

/// 活动分段：上半区是「运行时还活着」的会话，下半区是其余最近会话。
///
/// 判活看 runtime 状态是否不在 `_inactiveRuntimeStatus` 里；只有 `status == "running"`
/// 才带脉冲点（挂着的运行时也算活动，但没在跑）。
_ActivityView _buildActivityView(
  Map<String, Object?> state, {
  int limit = _activityRecentLimit,
}) {
  final Set<Object?> liveIds = <Object?>{};
  final Set<Object?> runningIds = <Object?>{};
  for (final Object? rawRuntime in _listOf(state['runtimes'])) {
    if (rawRuntime is! Map) continue;
    // 注意这里 toLowerCase() 之后才比：桌面端会下发 "Running" 这种大小写混排
    final Object? rawStatus = rawRuntime['status'];
    final String status = rawStatus is String ? rawStatus.toLowerCase() : '';
    if (_inactiveRuntimeStatus.contains(status)) continue;
    liveIds.add(rawRuntime['sessionId']);
    if (status == 'running') runningIds.add(rawRuntime['sessionId']);
  }

  final List<Object?> projects = _listOf(state['projects']);
  final List<Map<String, Object?>> sorted = _stableSortByDesc(
    _mapListOf(state['sessions']),
    _sortKey,
  );

  _ActivityRow toRow(Map<String, Object?> session) => (
    session: session,
    projectLabel: _labelFor(projects, session),
    running: runningIds.contains(session['id']),
  );

  final List<_ActivityRow> live = <_ActivityRow>[
    for (final Map<String, Object?> session in sorted)
      if (liveIds.contains(session['id'])) toRow(session),
  ];
  final List<Map<String, Object?>> rest = <Map<String, Object?>>[
    for (final Map<String, Object?> session in sorted)
      if (!liveIds.contains(session['id'])) session,
  ];
  final int take = limit < 0 ? 0 : limit;
  final List<_ActivityRow> recent = <_ActivityRow>[
    for (final Map<String, Object?> session in rest.take(take)) toRow(session),
  ];
  return (live: live, recent: recent);
}

/// 聊天分段：内置 Chat 项目下的会话，按 `updatedAt` 倒序。
List<Map<String, Object?>> _chatSessions(Map<String, Object?> state) {
  final List<Object?> projects = _listOf(state['projects']);
  Object? chatProjectId;
  bool found = false;
  for (final Object? rawProject in projects) {
    final Map<String, Object?> project = _mapOf(rawProject);
    if (project['kind'] != 'chat') continue;
    chatProjectId = project['id'];
    found = true;
    break;
  }
  if (!found) return <Map<String, Object?>>[];

  final List<Map<String, Object?>> rows = <Map<String, Object?>>[
    for (final Object? rawSession in _listOf(state['sessions']))
      if (_mapOf(rawSession)['projectId'] == chatProjectId) _mapOf(rawSession),
  ];
  return _stableSortByDesc(rows, _sortKey);
}

typedef _FlatRow = ({Map<String, Object?> session, String projectLabel});

/// 搜索：标题或项目名命中，按 `updatedAt` 倒序（稳定）。
List<_FlatRow> _searchSessions(Map<String, Object?> state, String query) {
  final String needle = query.trim().toLowerCase();
  if (needle.isEmpty) return <_FlatRow>[];
  final List<Object?> projects = _listOf(state['projects']);
  final List<Map<String, Object?>> matched = <Map<String, Object?>>[];
  for (final Object? rawSession in _listOf(state['sessions'])) {
    final Map<String, Object?> session = _mapOf(rawSession);
    final String title = _strOrEmpty(session['title']).toLowerCase();
    final String label = _labelFor(projects, session).toLowerCase();
    if (title.contains(needle) || label.contains(needle)) matched.add(session);
  }
  final List<Map<String, Object?>> sorted = _stableSortByDesc(matched, _sortKey);
  return <_FlatRow>[
    for (final Map<String, Object?> session in sorted)
      (session: session, projectLabel: _labelFor(projects, session)),
  ];
}

/// 分段按钮上的计数（让用户不点进去也知道有没有东西）。
({int active, int chats, int projects}) _tabCounts(Map<String, Object?> state) {
  final _ActivityView activity = _buildActivityView(state);
  int workspaces = 0;
  for (final Object? rawProject in _listOf(state['projects'])) {
    if (_mapOf(rawProject)['kind'] != 'chat') workspaces += 1;
  }
  return (
    active: activity.live.length + activity.recent.length,
    chats: _chatSessions(state).length,
    projects: workspaces,
  );
}

/* ------------------------------------------------------------------ */
/* 多端点聚合                                                          */
/* ------------------------------------------------------------------ */

typedef _Endpoint = ({String id, String label, Map<String, Object?> state});

typedef _Catalog = ({
  String endpointId,
  String endpointLabel,
  List<Object?> projects,
  List<Object?> sessions,
  int running,
});

typedef _MultiActivityRow = ({
  Map<String, Object?> session,
  String projectLabel,
  bool running,
  String endpointId,
  String endpointLabel,
});

typedef _MultiChatRow = ({
  Map<String, Object?> session,
  String endpointId,
  String endpointLabel,
});

typedef _MultiFlatRow = ({
  Map<String, Object?> session,
  String projectLabel,
  String endpointId,
  String endpointLabel,
});

/// 读端点数组。`id` / `label` 非字符串时按空串处理（TS 里是 undefined）。
List<_Endpoint> _endpointsOf(Object? value) => <_Endpoint>[
  for (final Map<String, Object?> endpoint in _mapListOf(value))
    (
      id: _strOrEmpty(endpoint['id']),
      label: _strOrEmpty(endpoint['label']),
      state: _mapOf(endpoint['state']),
    ),
];

/// `endpoints.find(item => item.id === id)?.state ?? fallback`。
///
/// 找不到、或找到了但 `state` 不是对象时返回 null，让调用方回退到主端点 ——
/// 与 `?.state ?? active.state` 的语义一致。
Map<String, Object?>? _stateOf(Object? endpointsRaw, Object? endpointId) {
  for (final Object? rawEndpoint in _listOf(endpointsRaw)) {
    final Map<String, Object?> endpoint = _mapOf(rawEndpoint);
    if (endpoint['id'] != endpointId) continue;
    final Object? state = endpoint['state'];
    return state is Map ? state.cast<String, Object?>() : null;
  }
  return null;
}

/// `Math.min(session.updatedAt ?? 0, now + CLOCK_SKEW_MS)`。
num _sortAt(Map<String, Object?> session, num now) {
  final num updatedAt = _sortKey(session);
  final num ceiling = now + _clockSkewMs;
  return updatedAt < ceiling ? updatedAt : ceiling;
}

/// 跨设备的「活动」分段：各端先各自算（不截断），合并后再统一取最近 `limit` 条。
({List<_MultiActivityRow> live, List<_MultiActivityRow> recent}) _buildActivityViewMulti(
  List<_Endpoint> endpoints,
  int limit,
  num now,
) {
  final List<_MultiActivityRow> live = <_MultiActivityRow>[];
  final List<_MultiActivityRow> rest = <_MultiActivityRow>[];
  for (final _Endpoint endpoint in endpoints) {
    final _ActivityView view = _buildActivityView(endpoint.state, limit: _maxSafeInteger);
    for (final _ActivityRow row in view.live) {
      live.add((
        session: row.session,
        projectLabel: row.projectLabel,
        running: row.running,
        endpointId: endpoint.id,
        endpointLabel: endpoint.label,
      ));
    }
    for (final _ActivityRow row in view.recent) {
      rest.add((
        session: row.session,
        projectLabel: row.projectLabel,
        running: row.running,
        endpointId: endpoint.id,
        endpointLabel: endpoint.label,
      ));
    }
  }

  num key(_MultiActivityRow row) => _sortAt(row.session, now);
  final int take = limit < 0 ? 0 : limit;
  return (
    live: _stableSortByDesc(live, key),
    recent: _stableSortByDesc(rest, key).take(take).toList(),
  );
}

/// 跨设备的「聊天」分段：各端内置 Chat 项目的会话。
List<_MultiChatRow> _chatSessionsMulti(List<_Endpoint> endpoints, num now) {
  final List<_MultiChatRow> rows = <_MultiChatRow>[];
  for (final _Endpoint endpoint in endpoints) {
    for (final Map<String, Object?> session in _chatSessions(endpoint.state)) {
      rows.add((
        session: session,
        endpointId: endpoint.id,
        endpointLabel: endpoint.label,
      ));
    }
  }
  return _stableSortByDesc(rows, (_MultiChatRow row) => _sortAt(row.session, now));
}

/// 跨设备搜索：标题或项目名命中，行上带设备名与项目名。
List<_MultiFlatRow> _searchSessionsMulti(
  List<_Endpoint> endpoints,
  String query,
  num now,
) {
  final List<_MultiFlatRow> rows = <_MultiFlatRow>[];
  for (final _Endpoint endpoint in endpoints) {
    for (final _FlatRow row in _searchSessions(endpoint.state, query)) {
      rows.add((
        session: row.session,
        projectLabel: row.projectLabel,
        endpointId: endpoint.id,
        endpointLabel: endpoint.label,
      ));
    }
  }
  return _stableSortByDesc(rows, (_MultiFlatRow row) => _sortAt(row.session, now));
}

/// 「项目」分段：多设备时按设备分块，单设备时直接就是原来的一套。
///
/// 注意 `running` 这里用的是**精确** `status === "running"`（不 toLowerCase），
/// 与 `_buildActivityView` 的判活口径不同 —— TS 里就是这样，照抄。
List<_Catalog> _endpointCatalogs(List<_Endpoint> endpoints) => <_Catalog>[
  for (final _Endpoint endpoint in endpoints)
    (
      endpointId: endpoint.id,
      endpointLabel: endpoint.label,
      projects: <Object?>[
        for (final Object? rawProject in _listOf(endpoint.state['projects']))
          if (_mapOf(rawProject)['kind'] != 'chat') rawProject,
      ],
      sessions: _listOf(endpoint.state['sessions']),
      running: _runningIds(endpoint.state).length,
    ),
];

/// `new Set(state.runtimes.filter(status === "running").map(sessionId))`。
///
/// 注意这里是**精确**比较（不 toLowerCase），与 `_buildActivityView` 的判活口径
/// 不同 —— TS 里 `runningIdsOf` 与 `endpointCatalogs` 都这么写，照抄。
Set<Object?> _runningIds(Map<String, Object?> state) => <Object?>{
  for (final Object? rawRuntime in _listOf(state['runtimes']))
    if (rawRuntime is Map && rawRuntime['status'] == 'running') rawRuntime['sessionId'],
};

/* ------------------------------------------------------------------ */
/* 行与分段                                                            */
/* ------------------------------------------------------------------ */

/// 一行会话。可选键只在**真值**时展开：空串会让整个键消失（`JSON.stringify` 里
/// `{badge: ""}` 是存在的，但 TS 版压根不会放进去 —— 两者字节不同）。
Map<String, Object?> _row(
  Map<String, Object?> session, {
  required bool active,
  required bool running,
  String? projectLabel,
  String? deviceLabel,
  Object? acpTools,
  required num now,
}) {
  final String backend = _strOrEmpty(session['backend']);
  final String badge = _backendBadge(backend);
  final bool acp = _normalizeBackend(backend) == 'acp';
  final String meta = <String>[
    projectLabel ?? '',
    timeLabel(
      session['updatedAt'] is num ? session['updatedAt'] as num : null,
      now,
    ),
  ].where((String part) => part.isNotEmpty).join(' · ');

  final String? acpToolName = acp ? _acpToolNameOf(acpTools, session['acpToolId']) : null;

  return <String, Object?>{
    'id': session['id'],
    'title': _jsOr(session['title'], '未命名会话'),
    'meta': meta,
    if (badge.isNotEmpty) 'badge': badge,
    // badgeKind 是 backend 的**原始串**：`" imagegen "` 会带着空格进来，
    // `"DSH"` 也原样带出 —— 主题按 badgeKind 配色，转小写就配错了。
    if (badge.isNotEmpty) 'badgeKind': backend,
    // 空串时整个键省略（TS 是 `...(acpToolName ? { acpToolName } : {})`）
    'acpToolName': ?acpToolName,
    if (deviceLabel != null && deviceLabel.isNotEmpty) 'deviceLabel': deviceLabel,
    'active': active,
    'running': running,
    'acp': acp,
    // dsh 用的是原始比较：`"DSH"` 在这里是 false
    'dsh': backend == 'dsh',
  };
}

/// 按 `preview` 截断并算出隐藏条数。
///
/// 展开时 `rows` 是全量、`hiddenCount` 仍然报出被折叠的条数 —— 否则 Dart 侧
/// 拿不到「收起」这个动作的入口。
({List<Map<String, Object?>> rows, int hiddenCount}) _clipped(
  List<Map<String, Object?>> rows,
  int? preview,
  bool expanded,
) {
  if (preview == null || rows.length <= preview) {
    return (rows: rows, hiddenCount: 0);
  }
  return (
    rows: expanded ? rows : rows.sublist(0, preview),
    hiddenCount: rows.length - preview,
  );
}

Map<String, Object?> _flatSection(
  String key,
  List<Map<String, Object?>> rows, {
  String? title,
  String? empty,
  required int preview,
  required bool expanded,
}) {
  final ({List<Map<String, Object?>> rows, int hiddenCount}) clipped = _clipped(
    rows,
    preview,
    expanded,
  );
  return <String, Object?>{
    'key': key,
    'kind': 'flat',
    if (title != null && title.isNotEmpty) 'title': title,
    'rows': clipped.rows,
    'hiddenCount': clipped.hiddenCount,
    'expanded': expanded,
    // 空态文案只在「这条分段本来就没有行」时出现，且自身为空串也省略
    if (rows.isEmpty && empty != null && empty.isNotEmpty) 'empty': empty,
  };
}

Map<String, Object?> _groupSection(
  Map<String, Object?> group, {
  required String? activeSessionId,
  required String? activeProjectId,
  required bool expanded,
  required Set<Object?> runningIds,
  required Object? acpTools,
  required num now,
}) {
  final List<Object?> sessions = _listOf(group['sessions']);
  final Map<String, Object?> project = _mapOf(group['project']);

  int runningCount = 0;
  final List<Map<String, Object?>> rows = <Map<String, Object?>>[];
  for (final Object? rawSession in sessions) {
    final Map<String, Object?> session = _mapOf(rawSession);
    final bool running = runningIds.contains(session['id']);
    if (running) runningCount += 1;
    rows.add(
      _row(
        session,
        active: session['id'] == activeSessionId,
        running: running,
        // 项目分段里不再重复项目名（组头已经写了）
        acpTools: acpTools,
        now: now,
      ),
    );
  }
  final ({List<Map<String, Object?>> rows, int hiddenCount}) clipped = _clipped(
    rows,
    _sessionsPreview,
    expanded,
  );
  final bool collapsed = group['collapsed'] == true;

  return <String, Object?>{
    'key': 'project:${project['id']}',
    'kind': 'group',
    'label': group['label'],
    'projectId': project['id'],
    'collapsed': collapsed,
    'active': project['id'] == activeProjectId,
    'count': sessions.length,
    'runningCount': runningCount,
    // 折叠时一行都不给，但 hiddenCount 仍按全量算
    'rows': collapsed ? <Map<String, Object?>>[] : clipped.rows,
    'hiddenCount': clipped.hiddenCount,
    'expanded': expanded,
    if (sessions.isEmpty)
      'empty': group['total'] == 0 ? '暂无会话' : '无匹配',
  };
}

/* ------------------------------------------------------------------ */
/* 入口                                                                */
/* ------------------------------------------------------------------ */

/// 组装侧栏快照。
///
/// 分段规则与 `app/src/platform/flutter-sidebar.ts` 的 `buildSidebarSnapshot()`
/// 严格一一对应；单测 `test/sidebar_native_test.dart` 里那张对照表是用真 TS 生成的。
Map<String, Object?> buildSidebarSnapshot(Map<String, Object?> input) {
  final Object? rawNow = input['now'];
  final num now = rawNow is num ? rawNow : DateTime.now().millisecondsSinceEpoch;
  final List<_Endpoint> endpoints = _endpointsOf(input['endpoints']);
  final bool multi = endpoints.length > 1;

  _Endpoint? matched;
  for (final _Endpoint endpoint in endpoints) {
    if (endpoint.id == _strOrEmpty(input['activeEndpointId'])) {
      matched = endpoint;
      break;
    }
  }
  final _Endpoint? resolved =
      matched ?? (endpoints.isEmpty ? null : endpoints.first);
  if (resolved == null) {
    // 还没连上任何端点：这里的分支连键序都和下面不同（sections 在 deviceCount 之前）
    return <String, Object?>{
      'tab': input['tab'],
      'tabs': <Object?>[],
      'query': input['query'],
      'sections': <Object?>[],
      'deviceCount': 0,
      'sheet': input['sheet'],
    };
  }
  final _Endpoint active = resolved;

  // 多设备时先算一次跨端活动视图：计数要用它，活动分段还要再算一次（TS 里两次都是现算）
  final ({List<_MultiActivityRow> live, List<_MultiActivityRow> recent})? multiActivity =
      multi ? _buildActivityViewMulti(endpoints, _activityRecentLimit, now) : null;

  final Map<String, int> counts;
  if (multiActivity != null) {
    int projectCount = 0;
    for (final _Catalog catalog in _endpointCatalogs(endpoints)) {
      projectCount += catalog.projects.length;
    }
    counts = <String, int>{
      'active': multiActivity.live.length + multiActivity.recent.length,
      'chats': _chatSessionsMulti(endpoints, now).length,
      'projects': projectCount,
    };
  } else {
    final ({int active, int chats, int projects}) single = _tabCounts(active.state);
    counts = <String, int>{
      'active': single.active,
      'chats': single.chats,
      'projects': single.projects,
    };
  }

  final List<Object?> tabs = <Object?>[
    for (final ({String id, String label}) tab in _sidebarTabs)
      <String, Object?>{
        'id': tab.id,
        'label': tab.label,
        'count': counts[tab.id] ?? 0,
      },
  ];
  final String query = _strOrEmpty(input['query']).trim();
  final Object? rawActiveSessionId = input['activeSessionId'];
  final String? activeSessionId = rawActiveSessionId is String
      ? rawActiveSessionId
      : null;
  final Object? rawActiveProjectId = input['activeProjectId'];
  final String? activeProjectId = rawActiveProjectId is String
      ? rawActiveProjectId
      : null;
  final Set<Object?> flatExpanded = _setOf(input['flatExpanded']);
  final Set<Object?> collapsedProjects = _setOf(input['collapsedProjects']);
  final Map<String, Object?> expandedSessions = _mapOrEmpty(
    input['expandedSessions'],
  );

  /* ---------------------------- 搜索 ---------------------------- */
  if (query.isNotEmpty) {
    final List<Map<String, Object?>> rows;
    if (multi) {
      rows = <Map<String, Object?>>[
        for (final _MultiFlatRow row in _searchSessionsMulti(
          endpoints,
          query,
          now,
        ))
          _row(
            row.session,
            active: row.session['id'] == rawActiveSessionId,
            running: _runningIds(
              _stateOf(input['endpoints'], row.endpointId) ?? active.state,
            ).contains(row.session['id']),
            projectLabel: row.projectLabel,
            deviceLabel: row.endpointLabel,
            acpTools: active.state['acpTools'],
            now: now,
          ),
      ];
    } else {
      rows = <Map<String, Object?>>[
        for (final _FlatRow row in _searchSessions(active.state, query))
          _row(
            row.session,
            active: row.session['id'] == rawActiveSessionId,
            running: _runningIds(active.state).contains(row.session['id']),
            projectLabel: row.projectLabel,
            acpTools: active.state['acpTools'],
            now: now,
          ),
      ];
    }

    return <String, Object?>{
      'tab': input['tab'],
      'tabs': tabs,
      // 分支用的是 trim 后的串，回给 Dart 的仍是**原始**输入（输入框里那串）
      'query': input['query'],
      'deviceCount': endpoints.length,
      'sheet': input['sheet'],
      'sections': <Object?>[
        _flatSection(
          'search',
          rows,
          // 搜索结果不截断：preview 就是自身长度，`expanded` 恒 true
          preview: rows.length,
          expanded: true,
          empty: '没有匹配的会话',
        ),
      ],
    };
  }

  /* ---------------------------- 活动 ---------------------------- */
  if (input['tab'] == 'active') {
    final List<_MultiActivityRow> liveRows;
    final List<_MultiActivityRow> recentRows;
    if (multi) {
      final ({List<_MultiActivityRow> live, List<_MultiActivityRow> recent})
      view = _buildActivityViewMulti(endpoints, _activityRecentLimit, now);
      liveRows = view.live;
      recentRows = view.recent;
    } else {
      final _ActivityView view = _buildActivityView(active.state);
      // 单设备没有设备名：endpointLabel 留空，_row 会把它省略掉
      liveRows = <_MultiActivityRow>[
        for (final _ActivityRow row in view.live)
          (
            session: row.session,
            projectLabel: row.projectLabel,
            running: row.running,
            endpointId: active.id,
            endpointLabel: '',
          ),
      ];
      recentRows = <_MultiActivityRow>[
        for (final _ActivityRow row in view.recent)
          (
            session: row.session,
            projectLabel: row.projectLabel,
            running: row.running,
            endpointId: active.id,
            endpointLabel: '',
          ),
      ];
    }

    Map<String, Object?> toRow(_MultiActivityRow row) => _row(
      row.session,
      active: row.session['id'] == rawActiveSessionId,
      running: row.running,
      projectLabel: row.projectLabel,
      deviceLabel: row.endpointLabel,
      acpTools: active.state['acpTools'],
      now: now,
    );

    final List<Map<String, Object?>> sections = <Map<String, Object?>>[];
    final Map<String, Object?> live = _flatSection(
      'active-live',
      <Map<String, Object?>>[for (final _MultiActivityRow row in liveRows) toRow(row)],
      title: '运行中',
      // 运行中只露 6 条（与 TS 里写死的 6 一致，不是 SESSIONS_PREVIEW 常量复用）
      preview: 6,
      expanded: flatExpanded.contains('active-live'),
    );
    // 没有活着的运行时就连分段都不给（否则抽屉里挂着个空标题）
    if (liveRows.isNotEmpty) sections.add(live);
    sections.add(
      _flatSection(
        'active-recent',
        <Map<String, Object?>>[
          for (final _MultiActivityRow row in recentRows) toRow(row),
        ],
        title: '最近会话',
        // 上游已经 slice 到 10 条，这里的 preview 只影响「展开」按钮，永远截不到
        preview: _activityRecentLimit,
        expanded: flatExpanded.contains('active-recent'),
        empty: '还没有会话；发一条消息就会出现在这里',
      ),
    );

    return <String, Object?>{
      'tab': input['tab'],
      'tabs': tabs,
      'query': input['query'],
      'deviceCount': endpoints.length,
      'sheet': input['sheet'],
      'sections': sections,
    };
  }

  /* ---------------------------- 聊天 ---------------------------- */
  if (input['tab'] == 'chats') {
    final List<_MultiChatRow> rows = _chatSessionsMulti(
      multi ? endpoints : <_Endpoint>[active],
      now,
    );
    final List<Map<String, Object?>> mapped = <Map<String, Object?>>[
      for (final _MultiChatRow row in rows)
        _row(
          row.session,
          active: row.session['id'] == rawActiveSessionId,
          running: _runningIds(
            _stateOf(input['endpoints'], row.endpointId) ?? active.state,
          ).contains(row.session['id']),
          // 聊天行不带项目名（都是内置 Chat 项目），只带设备名
          deviceLabel: multi ? row.endpointLabel : null,
          acpTools: active.state['acpTools'],
          now: now,
        ),
    ];

    return <String, Object?>{
      'tab': input['tab'],
      'tabs': tabs,
      'query': input['query'],
      'deviceCount': endpoints.length,
      'sheet': input['sheet'],
      'sections': <Object?>[
        _flatSection(
          'chats',
          mapped,
          preview: 12,
          expanded: flatExpanded.contains('chats'),
          empty: '没有聊天会话（桌面端没有启用内置 Chat 项目）',
        ),
      ],
    };
  }

  /* ---------------------------- 项目 ---------------------------- */
  final List<_Catalog> catalogs;
  if (multi) {
    catalogs = _endpointCatalogs(endpoints);
  } else {
    final List<_Catalog> single = _endpointCatalogs(<_Endpoint>[active]);
    catalogs = <_Catalog>[
      (
        endpointId: active.id,
        endpointLabel: active.label,
        projects: single.isEmpty ? <Object?>[] : single.first.projects,
        sessions: _listOf(active.state['sessions']),
        running: _runningIds(active.state).length,
      ),
    ];
  }

  final List<Map<String, Object?>> sections = <Map<String, Object?>>[];
  int groupCount = 0;
  for (final _Catalog catalog in catalogs) {
    final Map<String, Object?> endpointState =
        _stateOf(input['endpoints'], catalog.endpointId) ?? active.state;
    final List<Map<String, Object?>> groups = _buildSidebarGroups(
      catalog.projects,
      catalog.sessions,
      collapsedProjects,
    );
    if (groups.isEmpty) continue;

    // 用**原始**的 activeEndpointId 比较：activeEndpointId 指不到任何端点时，
    // 所有分段都算「非当前设备」，当前会话也就不会被当成高亮项。
    final bool onActiveEndpoint = catalog.endpointId == input['activeEndpointId'];

    if (multi) {
      sections.add(<String, Object?>{
        'key': 'device:${catalog.endpointId}',
        'kind': 'device',
        'label': catalog.endpointLabel,
        'active': onActiveEndpoint,
        'deviceRunning': catalog.running,
        'rows': <Object?>[],
        'hiddenCount': 0,
        'expanded': true,
      });
    }

    final Set<Object?> expandedProjects = _resolveExpandedProjects(
      endpointState,
      expandedSessions,
      onActiveEndpoint ? activeSessionId : null,
    );

    for (final Map<String, Object?> group in groups) {
      groupCount += 1;
      sections.add(
        _groupSection(
          group,
          activeSessionId: onActiveEndpoint ? activeSessionId : null,
          activeProjectId: onActiveEndpoint ? activeProjectId : null,
          expanded: expandedProjects.contains(_mapOf(group['project'])['id']),
          runningIds: _runningIds(endpointState),
          acpTools: endpointState['acpTools'],
          now: now,
        ),
      );
    }
  }

  return <String, Object?>{
    'tab': input['tab'],
    'tabs': tabs,
    'query': input['query'],
    'deviceCount': endpoints.length,
    'sheet': input['sheet'],
    'sections': sections,
    if (groupCount == 0) 'empty': '还没有项目，请先在桌面端添加目录',
  };
}
