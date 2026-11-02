/// 侧栏（Drawer）的数据模型。
///
/// 这份 JSON 由 WebView 侧 `app/src/platform/flutter-sidebar.ts` 的
/// `buildSidebarSnapshot()` 组装：分段、折叠、搜索、预览截断、多设备分块全在那边算完，
/// Dart 只负责画。字段名与 TS 侧一一对应，改名要两边一起改。
library;

import 'dart:convert';

import '../common/app_sheet.dart';

String _str(Object? value, [String fallback = '']) =>
    value is String ? value : (value == null ? fallback : '$value');

bool _bool(Object? value) => value == true;

int _int(Object? value) => value is int ? value : (value is num ? value.toInt() : 0);

List<Object?> _list(Object? value) => value is List ? value : const <Object?>[];

Map<String, Object?> _map(Object? value) =>
    value is Map ? value.map((Object? k, Object? v) => MapEntry('$k', v)) : const <String, Object?>{};

/// 侧栏一行会话。
class SidebarRow {
  const SidebarRow({
    required this.id,
    required this.title,
    required this.meta,
    required this.active,
    required this.running,
    required this.acp,
    required this.dsh,
    this.badge,
    this.badgeKind,
    this.acpToolName,
    this.deviceLabel,
  });

  final String id;
  final String title;
  final String meta;
  final bool active;
  final bool running;
  final bool acp;
  final bool dsh;

  /// 后端徽标（pi 后端为空）
  final String? badge;

  /// 徽标样式名：pi / dsh / acp
  final String? badgeKind;

  /// ACP 会话绑定的 CLI 显示名
  final String? acpToolName;

  /// 多设备时的设备名徽标
  final String? deviceLabel;

  factory SidebarRow.fromJson(Map<String, Object?> json) {
    final String? badge = json['badge'] is String && (json['badge'] as String).isNotEmpty
        ? json['badge'] as String
        : null;
    return SidebarRow(
      id: _str(json['id']),
      title: _str(json['title'], '未命名会话'),
      meta: _str(json['meta']),
      active: _bool(json['active']),
      running: _bool(json['running']),
      acp: _bool(json['acp']),
      dsh: _bool(json['dsh']),
      badge: badge,
      badgeKind: json['badgeKind'] is String ? json['badgeKind'] as String : null,
      acpToolName: json['acpToolName'] is String ? json['acpToolName'] as String : null,
      deviceLabel: json['deviceLabel'] is String ? json['deviceLabel'] as String : null,
    );
  }
}

/// 侧栏的一段：扁平列表（活动/聊天/搜索）、设备头、或项目分组。
class SidebarSection {
  const SidebarSection({
    required this.key,
    required this.kind,
    required this.rows,
    required this.hiddenCount,
    required this.expanded,
    this.title,
    this.label,
    this.collapsed = false,
    this.projectId,
    this.active = false,
    this.count = 0,
    this.runningCount = 0,
    this.deviceRunning = 0,
    this.empty,
  });

  final String key;

  /// `flat` / `device` / `group`
  final String kind;
  final List<SidebarRow> rows;

  /// 被预览截断的条数；0 表示没有「展开其余 N 个」
  final int hiddenCount;
  final bool expanded;
  final String? title;
  final String? label;
  final bool collapsed;
  final String? projectId;
  final bool active;
  final int count;
  final int runningCount;
  final int deviceRunning;
  final String? empty;

  bool get isFlat => kind == 'flat';
  bool get isDevice => kind == 'device';
  bool get isGroup => kind == 'group';

  factory SidebarSection.fromJson(Map<String, Object?> json) => SidebarSection(
        key: _str(json['key']),
        kind: _str(json['kind'], 'flat'),
        rows: _list(json['rows'])
            .whereType<Map<Object?, Object?>>()
            .map((Map<Object?, Object?> raw) => SidebarRow.fromJson(_map(raw)))
            .toList(growable: false),
        hiddenCount: _int(json['hiddenCount']),
        expanded: _bool(json['expanded']),
        title: json['title'] is String ? json['title'] as String : null,
        label: json['label'] is String ? json['label'] as String : null,
        collapsed: _bool(json['collapsed']),
        projectId: json['projectId'] is String ? json['projectId'] as String : null,
        active: _bool(json['active']),
        count: _int(json['count']),
        runningCount: _int(json['runningCount']),
        deviceRunning: _int(json['deviceRunning']),
        empty: json['empty'] is String ? json['empty'] as String : null,
      );
}

/// 分段按钮。
class SidebarTab {
  const SidebarTab({required this.id, required this.label, required this.count});

  final String id;
  final String label;
  final int count;

  factory SidebarTab.fromJson(Map<String, Object?> json) => SidebarTab(
        id: _str(json['id']),
        label: _str(json['label']),
        count: _int(json['count']),
      );
}

/// 整份侧栏快照。
class SidebarSnapshot {
  const SidebarSnapshot({
    required this.tab,
    required this.tabs,
    required this.query,
    required this.sections,
    required this.deviceCount,
    this.empty,
    this.sheet,
  });

  static const SidebarSnapshot none = SidebarSnapshot(
    tab: 'active',
    tabs: <SidebarTab>[],
    query: '',
    sections: <SidebarSection>[],
    deviceCount: 0,
  );

  final String tab;
  final List<SidebarTab> tabs;
  final String query;
  final List<SidebarSection> sections;
  final int deviceCount;
  final String? empty;

  /// 侧栏发起的底部弹层（新建会话 / 切换后端）；null 表示不显示
  final AppSheet? sheet;

  bool get isEmpty => sections.isEmpty;

  /// 解析宿主推来的 JSON；空串或坏数据返回 [none]（侧栏画空列表，不炸）。
  ///
  /// 注意：实例字段 `empty` 是「空态文案」，静态的 `none` 是「什么都没有的快照」，
  /// 两者不能同名（Dart 不允许静态成员与实例成员撞名）。
  static SidebarSnapshot parse(String json) {
    if (json.trim().isEmpty || json == '{}') return none;
    try {
      final Object? decoded = jsonDecode(json);
      if (decoded is! Map) return none;
      return SidebarSnapshot.fromJson(_map(decoded));
    } catch (_) {
      return none;
    }
  }

  factory SidebarSnapshot.fromJson(Map<String, Object?> json) => SidebarSnapshot(
        tab: _str(json['tab'], 'active'),
        tabs: _list(json['tabs'])
            .whereType<Map<Object?, Object?>>()
            .map((Map<Object?, Object?> raw) => SidebarTab.fromJson(_map(raw)))
            .toList(growable: false),
        query: _str(json['query']),
        sections: _list(json['sections'])
            .whereType<Map<Object?, Object?>>()
            .map((Map<Object?, Object?> raw) => SidebarSection.fromJson(_map(raw)))
            .toList(growable: false),
        deviceCount: _int(json['deviceCount']),
        empty: json['empty'] is String ? json['empty'] as String : null,
        sheet: json['sheet'] is Map<Object?, Object?> ? AppSheet.fromJson(_map(json['sheet'])) : null,
      );
}
