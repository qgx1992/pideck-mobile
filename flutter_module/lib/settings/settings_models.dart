/// 设置页的数据模型：把宿主（WebView）推过来的 JSON 解析成可直接渲染的对象。
///
/// 字段与 `app/src/ui/settings-model.ts` / `app/src/ui/settings-view.ts` 里的
/// `SettingsData` 一一对应 —— **那边是唯一的状态源**，这里只负责画。
/// 加字段时两边要一起改，否则 Dart 侧会静默忽略（解析器对缺字段是宽松的）。
library;

String str(Object? value) => value is String ? value : '';

bool boolOr(Object? value, bool fallback) => value is bool ? value : fallback;

int intOr(Object? value, int fallback) => value is int ? value : fallback;

List<Object?> listOf(Object? value) => value is List ? value : const <Object?>[];

Map<String, dynamic> mapOf(Object? value) {
  if (value is Map) {
    return <String, dynamic>{
      for (final entry in value.entries) '${entry.key}': entry.value,
    };
  }
  return const <String, dynamic>{};
}

class ThemeOption {
  const ThemeOption({required this.value, required this.label});

  final String value;
  final String label;

  factory ThemeOption.fromJson(Map<String, dynamic> json) =>
      ThemeOption(value: str(json['value']), label: str(json['label']));
}

class ThemeSettings {
  const ThemeSettings({required this.mode, required this.options});

  final String mode;
  final List<ThemeOption> options;

  factory ThemeSettings.fromJson(Map<String, dynamic> json) => ThemeSettings(
    mode: str(json['mode']),
    options: [
      for (final item in listOf(json['options']))
        ThemeOption.fromJson(mapOf(item)),
    ],
  );

  static const ThemeSettings empty = ThemeSettings(mode: '', options: []);
}

class ConnectionInfo {
  const ConnectionInfo({
    this.serviceUrl = '',
    this.token = '',
    this.status = '',
    this.version = '',
    this.sseNative,
  });

  final String serviceUrl;
  final String token;
  final String status;
  final String version;

  /// null = 老包/浏览器环境，没有这项
  final bool? sseNative;

  factory ConnectionInfo.fromJson(Map<String, dynamic> json) => ConnectionInfo(
    serviceUrl: str(json['serviceUrl']),
    token: str(json['token']),
    status: str(json['status']),
    version: str(json['version']),
    sseNative: json['sseNative'] is bool ? json['sseNative'] as bool : null,
  );
}

class DeviceRow {
  const DeviceRow({
    required this.id,
    required this.label,
    required this.url,
    required this.active,
    required this.running,
    required this.pending,
    required this.enabled,
  });

  final String id;
  final String label;
  final String url;
  final bool active;
  final int running;
  final int pending;
  final bool enabled;

  factory DeviceRow.fromJson(Map<String, dynamic> json) => DeviceRow(
    id: str(json['id']),
    label: str(json['label']),
    url: str(json['url']),
    active: boolOr(json['active'], false),
    running: intOr(json['running'], 0),
    pending: intOr(json['pending'], 0),
    enabled: boolOr(json['enabled'], true),
  );

  /// 与 Web 版同一套徽标文案（空项不显示）。
  String get badges => [
    if (active) '当前',
    if (running > 0) '$running 运行中',
    if (pending > 0) '$pending 待答',
    if (!enabled) '未参与同时连接',
  ].join(' · ');
}

class ModelRow {
  const ModelRow({
    required this.key,
    required this.name,
    required this.provider,
    required this.modelId,
  });

  final String key;
  final String name;
  final String provider;
  final String modelId;

  factory ModelRow.fromJson(Map<String, dynamic> json) => ModelRow(
    key: str(json['key']),
    name: str(json['name']),
    provider: str(json['provider']),
    modelId: str(json['modelId']),
  );
}

class SkillRow {
  const SkillRow({
    required this.key,
    required this.name,
    required this.sourceId,
    required this.sourceLabel,
    required this.description,
    required this.enabled,
    required this.valid,
    required this.userOnly,
    required this.warnings,
  });

  final String key;
  final String name;
  final String sourceId;
  final String sourceLabel;
  final String description;
  final bool enabled;

  /// 后端标了 valid=false 的技能点了也白点，UI 要置灰
  final bool valid;
  final bool userOnly;
  final List<String> warnings;

  factory SkillRow.fromJson(Map<String, dynamic> json) => SkillRow(
    key: str(json['key']),
    name: str(json['name']),
    sourceId: str(json['sourceId']),
    sourceLabel: str(json['sourceLabel']),
    description: str(json['description']),
    enabled: boolOr(json['enabled'], true),
    valid: boolOr(json['valid'], true),
    userOnly: boolOr(json['userOnly'], false),
    warnings: [
      for (final item in listOf(json['warnings'])) str(item),
    ].where((item) => item.isNotEmpty).toList(),
  );

  String get subtitle => [
    description,
    if (userOnly) '仅手动调用',
    if (!valid) '无效',
    ...warnings,
  ].where((item) => item.isNotEmpty).join(' · ');
}

class SkillSection {
  const SkillSection({
    required this.sourceId,
    required this.label,
    required this.rows,
  });

  final String sourceId;
  final String label;
  final List<SkillRow> rows;

  factory SkillSection.fromJson(Map<String, dynamic> json) => SkillSection(
    sourceId: str(json['sourceId']),
    label: str(json['label']),
    rows: [
      for (final item in listOf(json['rows'])) SkillRow.fromJson(mapOf(item)),
    ],
  );
}

class ExtensionRow {
  const ExtensionRow({
    required this.key,
    required this.source,
    required this.name,
    required this.scope,
    required this.enabled,
    required this.description,
    this.projectId,
  });

  final String key;
  final String source;
  final String name;
  final String scope;
  final bool enabled;
  final String description;
  final String? projectId;

  factory ExtensionRow.fromJson(Map<String, dynamic> json) => ExtensionRow(
    key: str(json['key']),
    source: str(json['source']),
    name: str(json['name']),
    scope: str(json['scope']),
    enabled: boolOr(json['enabled'], true),
    description: str(json['description']),
    projectId: str(json['projectId']).isEmpty ? null : str(json['projectId']),
  );

  String get subtitle => [
    if (scope == 'project') '项目级',
    description,
  ].where((item) => item.isNotEmpty).join(' · ');
}

class DshPluginRow {
  const DshPluginRow({
    required this.id,
    required this.name,
    required this.version,
    required this.running,
    required this.description,
    required this.kind,
    required this.packageId,
    required this.status,
  });

  final String id;
  final String name;
  final String version;
  final bool running;
  final String description;

  /// dynamic = 动态 Cordis 插件（可启停/卸载）；static = 静态 Loader 条目（只读）
  final String kind;
  final String packageId;
  final String status;

  bool get actionable => kind == 'dynamic';

  String get valueLabel => running
      ? '运行中'
      : kind == 'static'
      ? '未启用'
      : '已停止';

  String get subtitle => [
    description,
    if (kind == 'static') '静态条目（只读）',
  ].where((item) => item.isNotEmpty).join(' · ');

  factory DshPluginRow.fromJson(Map<String, dynamic> json) => DshPluginRow(
    id: str(json['id']),
    name: str(json['name']),
    version: str(json['version']),
    running: boolOr(json['running'], false),
    description: str(json['description']),
    kind: str(json['kind']),
    packageId: str(json['packageId']),
    status: str(json['status']),
  );
}

/// 整页数据。缺字段一律按「空」处理，不要让一个旧宿主把整页打崩。
class SettingsData {
  /// 主界面形态开关：缺字段 / 老版本推送时按「原生界面」处理（默认形态）。
  static bool _shellNative(Map<String, dynamic> json) {
    final Object? shell = json['shell'];
    if (shell is! Map) return true;
    return mapOf(shell)['native'] != false;
  }

  const SettingsData({
    this.theme = ThemeSettings.empty,
    this.notifyEnabled = true,
    this.shellNative = true,
    this.connection = const ConnectionInfo(),
    this.devices = const [],
    this.models = const [],
    this.skills = const [],
    this.extensions = const [],
    this.dshPlugins = const [],
    this.dshTarget = '',
    this.dshError = '',
    this.loading = false,
    this.loadError = '',
  });

  final ThemeSettings theme;
  final bool notifyEnabled;

  /// 主界面形态：true = 原生界面，false = 网页版（两种都能改，被卡住时要能自救）。
  final bool shellNative;
  final ConnectionInfo connection;
  final List<DeviceRow> devices;
  final List<ModelRow> models;
  final List<SkillSection> skills;
  final List<ExtensionRow> extensions;
  final List<DshPluginRow> dshPlugins;

  /// 当前可用于 DSH 插件操作的会话描述（空串 = 没有）
  final String dshTarget;
  final String dshError;
  final bool loading;
  final String loadError;

  factory SettingsData.fromJson(Map<String, dynamic> json) => SettingsData(
    theme: ThemeSettings.fromJson(mapOf(json['theme'])),
    shellNative: _shellNative(json),
    notifyEnabled: boolOr(mapOf(json['notify'])['enabled'], true),
    connection: ConnectionInfo.fromJson(mapOf(json['connection'])),
    devices: [
      for (final item in listOf(json['devices'])) DeviceRow.fromJson(mapOf(item)),
    ],
    models: [
      for (final item in listOf(json['models'])) ModelRow.fromJson(mapOf(item)),
    ],
    skills: [
      for (final item in listOf(json['skills']))
        SkillSection.fromJson(mapOf(item)),
    ],
    extensions: [
      for (final item in listOf(json['extensions']))
        ExtensionRow.fromJson(mapOf(item)),
    ],
    dshPlugins: [
      for (final item in listOf(json['dshPlugins']))
        DshPluginRow.fromJson(mapOf(item)),
    ],
    dshTarget: str(json['dshTarget']),
    dshError: str(json['dshError']),
    loading: boolOr(json['loading'], false),
    loadError: str(json['loadError']),
  );
}

/// 与 Web 版 `DESKTOP_ONLY_NOTE` 同一句话（两边都要说清「这里只能看」）。
const String desktopOnlyNote =
    '模型供应商密钥、模型目录、Git/终端/代理/备份等配置走桌面端本地配置，Web 服务没有开放写接口；'
    '这里只做只读展示与有接口的开关。';

/// 令牌脱敏：只显示前后各 4 位，避免截屏泄露（与 Web 版 maskToken 一致）。
String maskToken(String token) {
  final value = token.trim();
  if (value.isEmpty) return '（未设置）';
  if (value.length <= 8) return '*' * value.length;
  return '${value.substring(0, 4)}…${value.substring(value.length - 4)}';
}
