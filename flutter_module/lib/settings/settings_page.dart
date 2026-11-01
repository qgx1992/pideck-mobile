import 'dart:convert';

import 'package:flutter/material.dart';

import '../app_theme.dart';
import '../host_channel.dart';
import 'settings_models.dart';

/// 设置页（Flutter 版）。
///
/// 定位：**纯视图**。数据由 WebView 侧组装好推过来（`settingsData()`），
/// 用户在这里点的每一个动作都原样回传给 WebView 执行 —— 主题、通知、设备切换
/// 这些状态本来就在 WebView 里，Flutter 不复制一份，避免两套状态源打架。
///
/// 为什么这么做（而不是让 Flutter 自己打 HTTP）：
/// 设置页里一半是本地设置（主题/通知/设备列表），Flutter 自己拿不到；
/// 一半是服务端概况（模型/技能/扩展/DSH），自己请求就等于重写一遍协议。
/// 先按「视图迁移」走完，之后要独立再逐块把数据源搬过来。
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, this.initialJson = '', this.onThemeModeChanged});

  /// 宿主在打开本页之前 push 的那份数据。非空时首帧就是完整内容，
  /// 不用等一次 `getSettings` 往返（避免先闪一下「正在读取设置…」）。
  final String initialJson;

  /// 主题变化回调。设置页只负责把 `theme.mode` 报上去，
  /// 真正换肤的是 `PiDeckFlutterApp` 里的 `MaterialApp.themeMode`。
  final ValueChanged<ThemeMode>? onThemeModeChanged;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  SettingsData _data = const SettingsData();
  bool _gotData = false;

  @override
  void initState() {
    super.initState();
    bindHostChannel(onSettings: _applyJson, onNotice: _showNotice);
    if (!_seed(widget.initialJson)) _load();
  }

  /// 用宿主给的初始数据打底；解析不出来就返回 false，交给 `_load()` 去拉。
  bool _seed(String raw) {
    if (raw.trim().isEmpty) return false;
    try {
      _data = SettingsData.fromJson(mapOf(jsonDecode(raw)));
      _gotData = true;
      widget.onThemeModeChanged?.call(themeModeFrom(_data.theme.mode));
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<void> _load() async {
    final raw = await loadInitialSettings();
    if (!mounted) return;
    _applyJson(raw);
  }

  void _applyJson(String raw) {
    try {
      final decoded = jsonDecode(raw);
      final data = SettingsData.fromJson(mapOf(decoded));
      if (!mounted) return;
      setState(() {
        _data = data;
        _gotData = true;
      });
      // Web 侧改完主题会重新 push 一份数据过来，这里跟着换肤。
      widget.onThemeModeChanged?.call(themeModeFrom(data.theme.mode));
    } catch (error) {
      // 解析失败不能白屏：保留上一份数据，只提示一句
      _showNotice('设置数据解析失败：$error');
    }
  }

  void _showNotice(String text) {
    if (!mounted || text.isEmpty) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(content: Text(text), duration: const Duration(seconds: 3)),
      );
  }

  void _act(String type, [Map<String, Object?> extra = const {}]) {
    sendSettingsAction(<String, Object?>{'type': type, ...extra});
  }

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    return Scaffold(
      backgroundColor: c.background,
      appBar: AppBar(
        backgroundColor: c.background,
        surfaceTintColor: Colors.transparent,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, size: 20),
          tooltip: '返回',
          // 直接让宿主 finish（走 `close` 方法，不经过 WebView 转一圈）：
          // 返回是最不能失败的操作，少一跳就少一个会断的环节。
          // WebView 侧的 `flutterSettingsOpen` 由 `pageVisibility(false)` 清掉。
          onPressed: closeHost,
        ),
        title: const Text('设置', style: TextStyle(fontSize: 16)),
        actions: [
          TextButton(
            onPressed: () => _act('refresh'),
            child: const Text('刷新', style: TextStyle(fontSize: 13)),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(14, 4, 14, 28),
        children: [
          if (!_gotData) const _Hint('正在读取设置…'),
          _appearanceSection(),
          _notifySection(),
          _devicesSection(),
          _overviewSection(),
          _skillsSection(),
          _extensionsSection(),
          _DshSection(data: _data, act: _act),
          _aboutSection(),
        ],
      ),
    );
  }

  // ── 外观 ──

  Widget _appearanceSection() {
    final options = _data.theme.options;
    return _Section(
      title: '外观',
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(2, 2, 2, 4),
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final option in options)
                _ChoiceButton(
                  label: option.label,
                  active: option.value == _data.theme.mode,
                  onTap: () => _act('theme', {'value': option.value}),
                ),
            ],
          ),
        ),
      ],
    );
  }

  // ── 通知 ──

  Widget _notifySection() {
    return _Section(
      title: '通知',
      children: [
        _SwitchRow(
          label: '任务完成 / AI 提问提醒',
          sub: '锁屏后由原生前台服务负责；多设备时只覆盖一台',
          value: _data.notifyEnabled,
          onChanged: (next) => _act('notify', {'enabled': next}),
        ),
      ],
    );
  }

  // ── 连接与设备 ──

  Widget _devicesSection() {
    final devices = _data.devices;
    return _Section(
      title: '连接与设备（${devices.length}）',
      children: [
        if (devices.isEmpty) const _Hint('还没有配对的设备'),
        for (final device in devices)
          _Row(
            label: device.label,
            value: device.active ? '使用中' : '切换',
            sub: device.badges.isEmpty
                ? device.url
                : '${device.url} · ${device.badges}',
            highlight: device.active,
            onTap: () => _act('deviceActivate', {'id': device.id}),
          ),
        Padding(
          padding: const EdgeInsets.only(top: 10),
          child: Row(
            children: [
              Expanded(
                child: _PillButton(
                  label: '添加设备',
                  onTap: () => _act('addDevice'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _PillButton(
                  label: '断开全部连接',
                  danger: true,
                  onTap: () => _act('disconnect'),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  // ── pi 概况 ──

  Widget _overviewSection() {
    final models = _data.models;
    return _Section(
      title: 'pi 概况',
      children: [
        const _Hint(desktopOnlyNote),
        if (_data.loading) const _Hint('正在读取模型 / 技能 / 扩展…'),
        if (!_data.loading && _data.loadError.isNotEmpty)
          _Hint(_data.loadError, error: true),
        _Row(label: '可用模型', value: '${models.length} 个'),
        if (models.isNotEmpty)
          _Fold(
            title: '模型列表',
            count: models.length,
            children: [
              for (final model in models)
                _Row(
                  label: model.name,
                  value: model.provider.isEmpty ? '—' : model.provider,
                  sub: model.modelId == model.name ? '' : model.modelId,
                ),
            ],
          ),
        _Row(
          label: '供应商额度',
          value: '查询',
          sub: '与桌面端同源，未开启时会给引导',
          onTap: () => _act('openUsage'),
        ),
      ],
    );
  }

  // ── 技能 ──

  Widget _skillsSection() {
    final skills = _data.skills;
    return _Section(
      title: '技能',
      children: [
        if (skills.isEmpty && !_data.loading) const _Hint('这台 PiDeck 没有返回技能列表'),
        for (final group in skills)
          _Fold(
            title: group.label,
            count: group.rows.length,
            children: [
              for (final skill in group.rows)
                _SwitchRow(
                  label: skill.name,
                  sub: skill.subtitle,
                  value: skill.enabled,
                  disabled: !skill.valid,
                  onChanged: (next) => _act('toggleSkill', {
                    'sourceId': group.sourceId,
                    'name': skill.name,
                    'enabled': next,
                  }),
                ),
            ],
          ),
      ],
    );
  }

  // ── 扩展 ──

  Widget _extensionsSection() {
    final extensions = _data.extensions;
    return _Section(
      title: '扩展',
      children: [
        if (extensions.isEmpty && !_data.loading) const _Hint('没有返回扩展列表'),
        if (extensions.isNotEmpty)
          _Fold(
            title: '扩展列表',
            count: extensions.length,
            children: [
              for (final extension in extensions)
                _SwitchRow(
                  label: extension.name,
                  sub: extension.subtitle,
                  value: extension.enabled,
                  onChanged: (next) =>
                      _act('toggleExtension', {'key': extension.key, 'enabled': next}),
                ),
            ],
          ),
      ],
    );
  }

  // ── 关于 ──

  Widget _aboutSection() {
    final connection = _data.connection;
    final sseNative = connection.sseNative;
    return _Section(
      title: '关于',
      children: [
        _Row(label: '客户端版本', value: connection.version),
        _Row(label: '服务地址', value: connection.serviceUrl),
        _Row(
          label: '访问令牌',
          value: maskToken(connection.token),
          sub: '明文存在本机，上架前需换原生加密存储',
        ),
        _Row(label: '连接状态', value: connection.status),
        if (sseNative != null)
          _Row(
            label: '数据流通道',
            value: sseNative ? '原生 SSE' : 'WebView',
            sub: sseNative ? '' : 'WebView 受后台冻结影响，息屏后可能断流',
          ),
        // 调试入口：验证 Dart 直连流式可行性的探针页（见 docs/flutter-migration.md）
        Padding(
          padding: const EdgeInsets.fromLTRB(2, 8, 2, 2),
          child: Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              onPressed: () => _act('sseProbe'),
              style: TextButton.styleFrom(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                minimumSize: const Size(0, 32),
              ),
              child: const Text('流式探针（调试）', style: TextStyle(fontSize: 12.5)),
            ),
          ),
        ),
      ],
    );
  }
}

/* ------------------------------------------------------------------ */
/* DSH 插件                                                            */
/* ------------------------------------------------------------------ */

/// DSH 插件区块。
///
/// 动态插件（Cordis）能启停/卸载；静态 Loader 条目只展示 —— 服务端没有对应路由。
/// 安装只要三个字段，`hostCode`/`clientCode` 这种写代码的活在手机上做不了。
class _DshSection extends StatefulWidget {
  const _DshSection({required this.data, required this.act});

  final SettingsData data;
  final void Function(String type, [Map<String, Object?> extra]) act;

  @override
  State<_DshSection> createState() => _DshSectionState();
}

class _DshSectionState extends State<_DshSection> {
  bool _formOpen = false;
  String _formError = '';
  final TextEditingController _prefix = TextEditingController();
  final TextEditingController _name = TextEditingController();
  final TextEditingController _purpose = TextEditingController();

  @override
  void dispose() {
    _prefix.dispose();
    _name.dispose();
    _purpose.dispose();
    super.dispose();
  }

  void _submit() {
    final idPrefix = _prefix.text.trim().toLowerCase();
    final pluginName = _name.text.trim();
    final usage = _purpose.text.trim();
    if (!RegExp(r'^[a-z]{3,6}$').hasMatch(idPrefix)) {
      setState(() => _formError = 'ID 前缀要 3-6 个小写字母');
      return;
    }
    if (pluginName.isEmpty) {
      setState(() => _formError = '填个名称');
      return;
    }
    if (widget.data.dshTarget.isEmpty) {
      setState(() => _formError = '先在手机上打开一个 DSH 会话');
      return;
    }
    setState(() {
      _formError = '';
      _formOpen = false;
    });
    widget.act('dshInstall', {
      'idPrefix': idPrefix,
      'name': pluginName,
      'purpose': usage,
    });
    _prefix.clear();
    _name.clear();
    _purpose.clear();
  }

  @override
  Widget build(BuildContext context) {
    final data = widget.data;
    final plugins = data.dshPlugins;
    final target = data.dshTarget;
    return _Section(
      title: 'DSH 插件',
      children: [
        if (plugins.isEmpty)
          _Row(
            label: '插件',
            value: data.dshError.isNotEmpty ? '读取失败' : '无',
            sub: data.dshError.isNotEmpty
                ? data.dshError
                : target.isNotEmpty
                ? '$target 还没有动态插件'
                : '先在手机上打开一个 DSH 会话，再回来管理插件',
          ),
        for (final plugin in plugins)
          _DshPluginTile(plugin: plugin, act: widget.act),
        _Row(
          label: '安装插件',
          value: '＋',
          sub: target.isEmpty ? '需要先打开一个 DSH 会话' : target,
          onTap: () => setState(() {
            _formOpen = !_formOpen;
            _formError = '';
          }),
        ),
        if (_formOpen) ...[
          _TextField(controller: _prefix, hint: 'ID 前缀：3-6 个小写字母，如 note'),
          _TextField(controller: _name, hint: '插件名称'),
          _TextField(controller: _purpose, hint: '用途（一句话）'),
          if (_formError.isNotEmpty) _Hint(_formError, error: true),
          Row(
            children: [
              Expanded(
                child: _PillButton(label: '安装', primary: true, onTap: _submit),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _PillButton(
                  label: '取消',
                  onTap: () => setState(() {
                    _formOpen = false;
                    _formError = '';
                  }),
                ),
              ),
            ],
          ),
        ],
      ],
    );
  }
}

class _DshPluginTile extends StatefulWidget {
  const _DshPluginTile({required this.plugin, required this.act});

  final DshPluginRow plugin;
  final void Function(String type, [Map<String, Object?> extra]) act;

  @override
  State<_DshPluginTile> createState() => _DshPluginTileState();
}

class _DshPluginTileState extends State<_DshPluginTile> {
  bool _confirmUninstall = false;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    final plugin = widget.plugin;
    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 10),
      decoration: BoxDecoration(
        color: c.surfaceAlt,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      plugin.name,
                      style: const TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600),
                    ),
                    if (plugin.subtitle.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 3),
                        child: Text(
                          plugin.subtitle,
                          style: TextStyle(
                            fontSize: 11.5,
                            height: 1.45,
                            color: c.textMuted,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              Text(
                plugin.valueLabel,
                style: TextStyle(fontSize: 12, color: c.textMuted),
              ),
            ],
          ),
          if (plugin.actionable)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Row(
                children: [
                  _MiniButton(
                    label: plugin.running ? '停止' : '运行',
                    onTap: () => widget.act('dshAction', {
                      'id': plugin.id,
                      'action': plugin.running ? 'stop' : 'run',
                    }),
                  ),
                  const SizedBox(width: 8),
                  _MiniButton(
                    label: _confirmUninstall ? '确认卸载' : '卸载',
                    danger: true,
                    onTap: () {
                      if (!_confirmUninstall) {
                        setState(() => _confirmUninstall = true);
                        return;
                      }
                      widget.act('dshAction', {
                        'id': plugin.id,
                        'action': 'uninstall',
                      });
                    },
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/* ------------------------------------------------------------------ */
/* 基础控件                                                            */
/* ------------------------------------------------------------------ */

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    return Container(
      margin: const EdgeInsets.only(top: 12),
      padding: const EdgeInsets.fromLTRB(12, 12, 12, 10),
      decoration: BoxDecoration(
        color: c.surface,
        borderRadius: BorderRadius.circular(14),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 2, bottom: 8),
            child: Text(
              title,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                letterSpacing: 0.3,
                color: c.textMuted,
              ),
            ),
          ),
          ...children,
        ],
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({
    required this.label,
    required this.value,
    this.sub = '',
    this.onTap,
    this.highlight = false,
  });

  final String label;
  final String value;
  final String sub;
  final VoidCallback? onTap;
  final bool highlight;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    final content = Padding(
      padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: TextStyle(
                    fontSize: 13.5,
                    color: highlight ? c.accent : c.text,
                  ),
                ),
                if (sub.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 3),
                    child: Text(
                      sub,
                      style: TextStyle(
                        fontSize: 11.5,
                        height: 1.45,
                        color: c.textMuted,
                      ),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          Text(
            value,
            style: TextStyle(
              fontSize: 12.5,
              color: highlight ? c.accent : c.textMuted,
            ),
          ),
        ],
      ),
    );
    if (onTap == null) return content;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: content,
    );
  }
}

class _SwitchRow extends StatelessWidget {
  const _SwitchRow({
    required this.label,
    required this.value,
    required this.onChanged,
    this.sub = '',
    this.disabled = false,
  });

  final String label;
  final String sub;
  final bool value;
  final bool disabled;
  final ValueChanged<bool> onChanged;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    return InkWell(
      onTap: disabled ? null : () => onChanged(!value),
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 2),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: TextStyle(
                      fontSize: 13.5,
                      color: disabled ? c.textMuted : c.text,
                    ),
                  ),
                  if (sub.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 3),
                      child: Text(
                        sub,
                        style: TextStyle(
                          fontSize: 11.5,
                          height: 1.45,
                          color: c.textMuted,
                        ),
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Switch(
              value: value,
              onChanged: disabled ? null : onChanged,
              activeThumbColor: c.accent,
            ),
          ],
        ),
      ),
    );
  }
}

/// 可折叠分组（技能按来源折，避免一屏几十行）。
class _Fold extends StatefulWidget {
  const _Fold({required this.title, required this.count, required this.children});

  final String title;
  final int count;
  final List<Widget> children;

  @override
  State<_Fold> createState() => _FoldState();
}

class _FoldState extends State<_Fold> {
  bool _open = false;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        InkWell(
          onTap: () => setState(() => _open = !_open),
          borderRadius: BorderRadius.circular(8),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 9, horizontal: 2),
            child: Row(
              children: [
                Icon(
                  _open ? Icons.expand_more : Icons.chevron_right,
                  size: 16,
                  color: c.textMuted,
                ),
                const SizedBox(width: 4),
                Expanded(
                  child: Text(widget.title, style: const TextStyle(fontSize: 13)),
                ),
                Text(
                  '${widget.count}',
                  style: TextStyle(fontSize: 11.5, color: c.textMuted),
                ),
              ],
            ),
          ),
        ),
        if (_open)
          Padding(
            padding: const EdgeInsets.only(left: 6),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: widget.children,
            ),
          ),
      ],
    );
  }
}

class _Hint extends StatelessWidget {
  const _Hint(this.text, {this.error = false});

  final String text;
  final bool error;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(2, 6, 2, 8),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 11.5,
          height: 1.5,
          color: error ? c.danger : c.textMuted,
        ),
      ),
    );
  }
}

class _ChoiceButton extends StatelessWidget {
  const _ChoiceButton({
    required this.label,
    required this.active,
    required this.onTap,
  });

  final String label;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(9),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
        decoration: BoxDecoration(
          color: active ? c.accent.withValues(alpha: 0.18) : c.surfaceAlt,
          borderRadius: BorderRadius.circular(9),
          border: Border.all(
            color: active ? c.accent : Colors.transparent,
            width: 1,
          ),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 12.5,
            color: active ? c.accent : c.textSecondary,
          ),
        ),
      ),
    );
  }
}

class _PillButton extends StatelessWidget {
  const _PillButton({
    required this.label,
    required this.onTap,
    this.danger = false,
    this.primary = false,
  });

  final String label;
  final VoidCallback onTap;
  final bool danger;
  final bool primary;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    final Color base = danger
        ? c.danger
        : primary
        ? c.accent
        : c.textMuted;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(9),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 9),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: base.withValues(alpha: 0.14),
          borderRadius: BorderRadius.circular(9),
          border: Border.all(color: base.withValues(alpha: 0.45)),
        ),
        child: Text(
          label,
          style: TextStyle(fontSize: 12.5, color: base == c.textMuted ? c.textSecondary : base),
        ),
      ),
    );
  }
}

class _MiniButton extends StatelessWidget {
  const _MiniButton({
    required this.label,
    required this.onTap,
    this.danger = false,
  });

  final String label;
  final VoidCallback onTap;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    final Color base = danger ? c.danger : c.textMuted;
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(7),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        decoration: BoxDecoration(
          color: base.withValues(alpha: 0.14),
          borderRadius: BorderRadius.circular(7),
        ),
        child: Text(
          label,
          style: TextStyle(
            fontSize: 11.5,
            color: danger ? c.danger : c.textSecondary,
          ),
        ),
      ),
    );
  }
}

class _TextField extends StatelessWidget {
  const _TextField({required this.controller, required this.hint});

  final TextEditingController controller;
  final String hint;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 6),
      child: TextField(
        controller: controller,
        style: const TextStyle(fontSize: 13),
        decoration: InputDecoration(
          hintText: hint,
          hintStyle: TextStyle(fontSize: 12, color: c.textMuted),
          filled: true,
          fillColor: c.surfaceAlt,
          isDense: true,
          contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(9),
            borderSide: BorderSide.none,
          ),
        ),
      ),
    );
  }
}
