/// 侧栏抽屉：会话列表 / 分段切换 / 搜索。
///
/// 所有数据来自 [SidebarSnapshot]（WebView 侧 `buildSidebarSnapshot()` 算好的），
/// 这里只画 + 把点击原样回传。分组规则、折叠、预览截断都不在这里判断 ——
/// 一旦在 Dart 里也判一遍，两边的「展开其余」就会对不上。
library;

import 'package:flutter/material.dart';

import '../app_theme.dart';
import '../common/app_sheet.dart';
import 'sidebar_models.dart';

typedef SidebarActionSink = void Function(Map<String, Object?> action);

class SidebarDrawer extends StatefulWidget {
  const SidebarDrawer({
    super.key,
    required this.snapshot,
    required this.onAction,
    required this.onClose,
  });

  final SidebarSnapshot snapshot;
  final SidebarActionSink onAction;

  /// 关掉抽屉本身（点会话、点「返回网页版」后）
  final VoidCallback onClose;

  @override
  State<SidebarDrawer> createState() => _SidebarDrawerState();
}

class _SidebarDrawerState extends State<SidebarDrawer> {
  /// 搜索框必须用常驻 controller：宿主每来一份新快照都会重建这个抽屉，
  /// 每次现建 controller 会把光标和输入法组合状态冲掉（聊天页提问卡踩过同一个坑）。
  final TextEditingController _search = TextEditingController();
  final FocusNode _searchFocus = FocusNode();

  /// 底部弹层（新建会话 / 切换后端）：内容随快照原地更新，只在有无之间开关。
  late final AppSheetHost _sheetHost = AppSheetHost(
    sendPick: (String id) => _send('sheetPick', <String, Object?>{'id': id}),
    sendClose: () => _send('sheetClose'),
  );

  @override
  void didUpdateWidget(covariant SidebarDrawer oldWidget) {
    super.didUpdateWidget(oldWidget);
    // 只在「内容真的不同」时同步：否则用户正在打字，快照一回流光标就跳走
    // 弹层的开合要等这一帧画完：build/didUpdateWidget 期间不能弹路由
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _sheetHost.sync(context, widget.snapshot.sheet);
    });

    final String next = widget.snapshot.query;
    if (next != _search.text && !_searchFocus.hasFocus) {
      _search.text = next;
      _search.selection = TextSelection.collapsed(offset: next.length);
    }
  }

  @override
  void dispose() {
    _sheetHost.dispose();
    _search.dispose();
    _searchFocus.dispose();
    super.dispose();
  }

  void _send(String type, [Map<String, Object?> extra = const <String, Object?>{}]) {
    widget.onAction(<String, Object?>{'type': type, ...extra});
  }

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    final SidebarSnapshot snapshot = widget.snapshot;

    return Drawer(
      backgroundColor: c.surface,
      width: 320,
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            _header(c),
            _searchField(c),
            if (snapshot.tabs.isNotEmpty) _tabs(c, snapshot),
            Expanded(child: _body(c, snapshot)),
            _footer(c),
          ],
        ),
      ),
    );
  }

  Widget _header(PiColors c) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 10, 8, 6),
      child: Row(
        children: <Widget>[
          Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(color: c.accent, shape: BoxShape.circle),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'PiDeck',
              style: TextStyle(color: c.text, fontSize: 15, fontWeight: FontWeight.w600),
            ),
          ),
          IconButton(
            tooltip: '设置',
            icon: Icon(Icons.settings_outlined, size: 20, color: c.textSecondary),
            onPressed: () {
              widget.onClose();
              _send('openSettings');
            },
          ),
          IconButton(
            tooltip: '新建会话（先选工作区）',
            icon: Icon(Icons.add, size: 22, color: c.textSecondary),
            onPressed: () {
              widget.onClose();
              _send('newSession');
            },
          ),
        ],
      ),
    );
  }

  Widget _searchField(PiColors c) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
      child: TextField(
        controller: _search,
        focusNode: _searchFocus,
        style: TextStyle(color: c.text, fontSize: 13.5),
        decoration: InputDecoration(
          isDense: true,
          hintText: '搜索会话…',
          hintStyle: TextStyle(color: c.textMuted, fontSize: 13.5),
          prefixIcon: Icon(Icons.search, size: 17, color: c.textMuted),
          prefixIconConstraints: const BoxConstraints(minWidth: 34),
          suffixIcon: _search.text.isEmpty
              ? null
              : IconButton(
                  tooltip: '清空',
                  icon: Icon(Icons.close, size: 15, color: c.textMuted),
                  onPressed: () {
                    _search.clear();
                    _send('search', <String, Object?>{'query': ''});
                  },
                ),
          filled: true,
          fillColor: c.surfaceAlt,
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(9),
            borderSide: BorderSide.none,
          ),
          contentPadding: const EdgeInsets.symmetric(vertical: 9),
        ),
        onChanged: (String value) => _send('search', <String, Object?>{'query': value}),
      ),
    );
  }

  Widget _tabs(PiColors c, SidebarSnapshot snapshot) {
    if (snapshot.tabs.isEmpty) return const SizedBox.shrink();
    // M3 的页签就该是 SegmentedButton：整条连在一起、选中项一块填充色，
    // 自己拿 Row + Container 拼出来的是"网页的 tag 栏"。
    final Set<String> ids = snapshot.tabs.map((SidebarTab tab) => tab.id).toSet();
    final String selected = ids.contains(snapshot.tab) ? snapshot.tab : snapshot.tabs.first.id;
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
      child: SegmentedButton<String>(
        segments: <ButtonSegment<String>>[
          for (final SidebarTab tab in snapshot.tabs)
            ButtonSegment<String>(
              value: tab.id,
              label: Text(tab.count > 0 ? '${tab.label} ${tab.count}' : tab.label),
            ),
        ],
        selected: <String>{selected},
        showSelectedIcon: false,
        onSelectionChanged: (Set<String> value) =>
            _send('toggleTab', <String, Object?>{'tab': value.first}),
        style: SegmentedButton.styleFrom(
          visualDensity: VisualDensity.compact,
          textStyle: const TextStyle(fontSize: 12.5),
          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
        ),
      ),
    );
  }

  Widget _body(PiColors c, SidebarSnapshot snapshot) {
    if (snapshot.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(
            snapshot.empty ?? '还没有会话',
            textAlign: TextAlign.center,
            style: TextStyle(color: c.textMuted, fontSize: 13),
          ),
        ),
      );
    }

    final List<Widget> children = <Widget>[];
    for (final SidebarSection section in snapshot.sections) {
      children.add(_section(c, section));
    }

    return ListView(
      padding: const EdgeInsets.only(bottom: 12),
      children: children,
    );
  }

  Widget _section(PiColors c, SidebarSection section) {
    if (section.isDevice) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 4),
        child: Row(
          children: <Widget>[
            Text(
              section.label ?? '',
              style: TextStyle(
                color: section.active ? c.accent : c.textMuted,
                fontSize: 11.5,
                fontWeight: FontWeight.w600,
                letterSpacing: 0.3,
              ),
            ),
            const SizedBox(width: 6),
            if ((section.deviceRunning) > 0) _pulse(c),
            const Spacer(),
            Text(
              '${section.rows.length}',
              style: TextStyle(color: c.textMuted, fontSize: 11),
            ),
          ],
        ),
      );
    }

    if (section.isGroup) return _group(c, section);

    /* ------------------------------ 扁平段 ------------------------------ */
    final List<Widget> children = <Widget>[];
    if (section.title != null) {
      children.add(
        Padding(
          padding: const EdgeInsets.fromLTRB(14, 10, 14, 4),
          child: Text(
            section.title!,
            style: TextStyle(
              color: c.textMuted,
              fontSize: 11.5,
              fontWeight: FontWeight.w600,
              letterSpacing: 0.3,
            ),
          ),
        ),
      );
    }

    if (section.rows.isEmpty) {
      children.add(
        Padding(
          padding: const EdgeInsets.fromLTRB(14, 4, 14, 10),
          child: Text(
            section.empty ?? '暂无会话',
            style: TextStyle(color: c.textMuted, fontSize: 12.5),
          ),
        ),
      );
    } else {
      for (final SidebarRow row in section.rows) {
        children.add(_row(c, row));
      }
      if (section.hiddenCount > 0) children.add(_moreButton(c, section));
    }

    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children);
  }

  Widget _group(PiColors c, SidebarSection section) {
    final bool collapsed = section.collapsed;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        InkWell(
          onTap: () => _send('toggleProject', <String, Object?>{'projectId': section.projectId ?? ''}),
          onLongPress: () => _projectMenu(section),
          child: Container(
            color: section.active ? c.surfaceAlt : Colors.transparent,
            padding: const EdgeInsets.fromLTRB(12, 8, 6, 8),
            child: Row(
              children: <Widget>[
                Icon(
                  collapsed ? Icons.chevron_right : Icons.expand_more,
                  size: 16,
                  color: c.textMuted,
                ),
                const SizedBox(width: 2),
                Flexible(
                  child: Text(
                    section.label ?? '',
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: section.active ? c.accent : c.text,
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                if (collapsed && section.runningCount > 0) ...<Widget>[
                  const SizedBox(width: 6),
                  _pulse(c),
                ],
                const SizedBox(width: 6),
                Text(
                  '${section.count}',
                  style: TextStyle(color: c.textMuted, fontSize: 11.5),
                ),
                IconButton(
                  tooltip: '在此项目新建会话',
                  visualDensity: VisualDensity.compact,
                  icon: Icon(Icons.add, size: 17, color: c.textSecondary),
                  onPressed: () =>
                      _send('createSession', <String, Object?>{'projectId': section.projectId ?? ''}),
                ),
              ],
            ),
          ),
        ),
        if (!collapsed) ...<Widget>[
          if (section.rows.isEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(30, 2, 14, 8),
              child: Text(
                section.empty ?? '暂无会话',
                style: TextStyle(color: c.textMuted, fontSize: 12.5),
              ),
            )
          else
            for (final SidebarRow row in section.rows) _row(c, row, indented: true),
          if (section.hiddenCount > 0) _moreButton(c, section, indented: true),
        ],
      ],
    );
  }

  Widget _moreButton(PiColors c, SidebarSection section, {bool indented = false}) {
    final String text = section.expanded ? '收起' : '展开其余 ${section.hiddenCount} 个';
    return Align(
      alignment: Alignment.centerLeft,
      child: Padding(
        padding: EdgeInsets.only(left: indented ? 24 : 6),
        child: TextButton.icon(
          onPressed: () => _send('toggleSection', <String, Object?>{'key': section.key}),
          icon: Icon(
            section.expanded ? Icons.expand_less : Icons.expand_more,
            size: 14,
            color: c.textMuted,
          ),
          label: Text(text, style: TextStyle(color: c.textMuted, fontSize: 12)),
          style: TextButton.styleFrom(
            padding: const EdgeInsets.symmetric(horizontal: 8),
            minimumSize: const Size(0, 28),
            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
          ),
        ),
      ),
    );
  }

  Widget _row(PiColors c, SidebarRow row, {bool indented = false}) {
    return InkWell(
      onTap: () {
        widget.onClose();
        _send('openSession', <String, Object?>{'id': row.id});
      },
      onLongPress: () => _sessionMenu(row),
      child: Container(
        color: row.active ? c.surfaceAlt : Colors.transparent,
        padding: EdgeInsets.fromLTRB(indented ? 22 : 12, 7, 12, 7),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Row(
              children: <Widget>[
                Flexible(
                  child: Text(
                    row.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: row.active ? c.accent : c.text,
                      fontSize: 13,
                      fontWeight: row.active ? FontWeight.w600 : FontWeight.w400,
                    ),
                  ),
                ),
                if (row.deviceLabel != null) ...<Widget>[
                  const SizedBox(width: 5),
                  _badge(c, row.deviceLabel!, c.textMuted),
                ],
                if (row.badge != null) ...<Widget>[
                  const SizedBox(width: 5),
                  _badge(c, row.badge!, _badgeColor(c, row.badgeKind)),
                ],
              ],
            ),
            if (row.meta.isNotEmpty || row.running || row.acpToolName != null)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Row(
                  children: <Widget>[
                    if (row.running) ...<Widget>[_pulse(c), const SizedBox(width: 5)],
                    Flexible(
                      child: Text(
                        row.meta,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(color: c.textMuted, fontSize: 11.5),
                      ),
                    ),
                    if (row.acpToolName != null) ...<Widget>[
                      const SizedBox(width: 6),
                      Text(
                        row.acpToolName!,
                        style: TextStyle(color: c.textMuted, fontSize: 11),
                      ),
                    ],
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _badge(PiColors c, String text, Color color) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(text, style: TextStyle(color: color, fontSize: 9.5, height: 1.3)),
    );
  }

  Color _badgeColor(PiColors c, String? kind) {
    switch (kind) {
      case 'acp':
        return const Color(0xFF4FBF9F);
      case 'dsh':
        return const Color(0xFFB58BE8);
      default:
        return c.textMuted;
    }
  }

  Widget _pulse(PiColors c) {
    return Container(
      width: 6,
      height: 6,
      decoration: BoxDecoration(color: c.accent, shape: BoxShape.circle),
    );
  }

  Widget _footer(PiColors c) {
    return Container(
      decoration: BoxDecoration(
        border: Border(top: BorderSide(color: c.divider)),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      child: Row(
        children: <Widget>[
          Expanded(
            child: TextButton(
              onPressed: () {
                widget.onClose();
                _send('newSession');
              },
              child: Text('新建会话', style: TextStyle(color: c.textSecondary, fontSize: 12.5)),
            ),
          ),
          Expanded(
            child: TextButton(
              onPressed: () {
                widget.onClose();
                _send('openSettings');
              },
              child: Text('设置', style: TextStyle(color: c.textSecondary, fontSize: 12.5)),
            ),
          ),
          Expanded(
            child: TextButton(
              onPressed: () {
                widget.onClose();
                _send('close');
              },
              child: Text('返回网页版', style: TextStyle(color: c.textMuted, fontSize: 12.5)),
            ),
          ),
        ],
      ),
    );
  }

  /// 长按会话：菜单项在 Flutter 里重做（菜单弹在 WebView 的 DOM 上会被 Flutter 页盖住看不见）。
  Future<void> _sessionMenu(SidebarRow row) async {
    final String? choice = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      backgroundColor: PiColors.of(context).surface,
      builder: (BuildContext sheetContext) {
        final PiColors c = PiColors.of(sheetContext);
        Widget item(String id, String label, {bool danger = false}) => ListTile(
              dense: true,
              title: Text(
                label,
                style: TextStyle(color: danger ? c.danger : c.text, fontSize: 14),
              ),
              onTap: () => Navigator.of(sheetContext).pop(id),
            );
        return SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 4),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    row.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(color: c.textMuted, fontSize: 12),
                  ),
                ),
              ),
              item('rename', '重命名'),
              item('copy', '复制会话'),
              item('export', '导出 HTML'),
              item('backend', '切换后端'),
              item('delete', '删除会话', danger: true),
            ],
          ),
        );
      },
    );

    if (!mounted || choice == null) return;
    switch (choice) {
      case 'rename':
        await _rename(row);
      case 'copy':
        _send('sessionCopy', <String, Object?>{'id': row.id});
      case 'export':
        _send('sessionExport', <String, Object?>{'id': row.id});
      case 'delete':
        final bool ok = await _confirmDelete(row.title);
        if (ok) _send('sessionDelete', <String, Object?>{'id': row.id});
      case 'backend':
        _send('sessionBackend', <String, Object?>{'id': row.id});
    }
  }

  /// 项目长按菜单。
  ///
  /// 以前这里 `leaveFlutterThen` 回网页版弹层（抽屉盖着，弹层在背后看不见）。
  /// 现在动作都由侧栏通道走，菜单在本地画。
  ///
  /// 这里**故意**没有「内置 Chat 项目不能移除」那条分支：Web 版那条分支其实到不了 ——
  /// Chat 项目在侧栏是「聊天」段的平铺行，永远进不了项目分组，所以分组头不会被长按到它。
  /// 搬一条到不了的分支过来只是多一处需要维护的假逻辑。
  Future<void> _projectMenu(SidebarSection section) async {
    final String projectId = section.projectId ?? '';
    if (projectId.isEmpty) return;
    final String? choice = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      backgroundColor: PiColors.of(context).surface,
      builder: (BuildContext sheetContext) {
        final PiColors c = PiColors.of(sheetContext);
        Widget item(String id, String label, {bool danger = false}) => ListTile(
              dense: true,
              title: Text(
                label,
                style: TextStyle(color: danger ? c.danger : c.text, fontSize: 14),
              ),
              onTap: () => Navigator.of(sheetContext).pop(id),
            );
        return SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 4),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    section.label ?? '项目',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(color: c.textMuted, fontSize: 12),
                  ),
                ),
              ),
              item('create', '在此项目新建会话'),
              item('toggle', section.collapsed ? '展开该项目' : '收起该项目'),
              item('remove', '从列表移除项目', danger: true),
            ],
          ),
        );
      },
    );

    if (!mounted || choice == null) return;
    switch (choice) {
      case 'create':
        widget.onClose();
        _send('createSession', <String, Object?>{'projectId': projectId});
      case 'toggle':
        _send('toggleProject', <String, Object?>{'projectId': projectId});
      case 'remove':
        final bool ok = await _confirmRemoveProject(section.label ?? '这个项目');
        if (ok) _send('projectRemove', <String, Object?>{'projectId': projectId});
    }
  }

  /// 二次确认：只从列表移除，不动磁盘上的文件（Web 版的提示文字照抄）。
  Future<bool> _confirmRemoveProject(String label) async {
    final bool? ok = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) {
        final PiColors c = PiColors.of(dialogContext);
        return AlertDialog(
          backgroundColor: c.surface,
          title: Text('移除项目？', style: TextStyle(color: c.text, fontSize: 15)),
          content: Text(
            '只把「$label」从 PiDeck 的列表里移除，不会删除磁盘上的项目文件。',
            style: TextStyle(color: c.textSecondary, fontSize: 13, height: 1.5),
          ),
          actions: <Widget>[
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: Text('取消', style: TextStyle(color: c.textMuted)),
            ),
            FilledButton(
              style: FilledButton.styleFrom(backgroundColor: c.danger),
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('确认移除'),
            ),
          ],
        );
      },
    );
    return ok == true;
  }

  Future<void> _rename(SidebarRow row) async {
    final TextEditingController controller = TextEditingController(text: row.title);
    final String? title = await showDialog<String>(
      context: context,
      builder: (BuildContext dialogContext) {
        final PiColors c = PiColors.of(dialogContext);
        return AlertDialog(
          backgroundColor: c.surface,
          title: Text('重命名会话', style: TextStyle(color: c.text, fontSize: 15)),
          content: TextField(
            controller: controller,
            autofocus: true,
            style: TextStyle(color: c.text, fontSize: 14),
            decoration: InputDecoration(hintText: '输入新标题', hintStyle: TextStyle(color: c.textMuted)),
            onSubmitted: (String value) => Navigator.of(dialogContext).pop(value.trim()),
          ),
          actions: <Widget>[
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: Text('取消', style: TextStyle(color: c.textMuted)),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(controller.text.trim()),
              child: const Text('保存'),
            ),
          ],
        );
      },
    );
    controller.dispose();
    if (!mounted || title == null) return;
    if (title.isEmpty) {
      _notice('标题不能为空');
      return;
    }
    _send('sessionRename', <String, Object?>{'id': row.id, 'title': title});
  }

  Future<bool> _confirmDelete(String title) async {
    final bool? ok = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) {
        final PiColors c = PiColors.of(dialogContext);
        return AlertDialog(
          backgroundColor: c.surface,
          title: Text('删除会话？', style: TextStyle(color: c.text, fontSize: 15)),
          content: Text(
            '「$title」的记录与会话文件都会被删除，不可恢复。',
            style: TextStyle(color: c.textSecondary, fontSize: 13),
          ),
          actions: <Widget>[
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: Text('取消', style: TextStyle(color: c.textMuted)),
            ),
            FilledButton(
              style: FilledButton.styleFrom(backgroundColor: c.danger),
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('确认删除'),
            ),
          ],
        );
      },
    );
    return ok == true;
  }

  void _notice(String text) {
    if (!mounted) return;
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(text)));
  }
}
