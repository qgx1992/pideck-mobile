import 'dart:async';

import 'package:flutter/material.dart';

import '../app_theme.dart';

/// 弹层里的一行（对应 `app/src/platform/flutter-sheet.ts` 的 `SheetItemView`）。
class AppSheetItem {
  const AppSheetItem({
    required this.id,
    required this.label,
    this.description = '',
    this.group = '',
    this.danger = false,
    this.disabled = false,
    this.selected = false,
    this.actionable = false,
  });

  final String id;
  final String label;
  final String description;
  final String group;
  final bool danger;
  final bool disabled;
  final bool selected;
  final bool actionable;

  factory AppSheetItem.fromJson(Map<String, Object?> json) => AppSheetItem(
        id: json['id'] is String ? json['id'] as String : '',
        label: json['label'] is String ? json['label'] as String : '',
        description: json['description'] is String ? json['description'] as String : '',
        group: json['group'] is String ? json['group'] as String : '',
        danger: json['danger'] == true,
        disabled: json['disabled'] == true,
        selected: json['selected'] == true,
        actionable: json['actionable'] == true,
      );
}

/// 一份完整的弹层视图（`SheetView`）。
class AppSheet {
  const AppSheet({
    required this.kind,
    required this.title,
    this.note = '',
    this.items = const <AppSheetItem>[],
  });

  final String kind;
  final String title;
  final String note;
  final List<AppSheetItem> items;

  factory AppSheet.fromJson(Map<String, Object?> json) => AppSheet(
        kind: json['kind'] is String ? json['kind'] as String : '',
        title: json['title'] is String ? json['title'] as String : '',
        note: json['note'] is String ? json['note'] as String : '',
        items: (json['items'] is List ? json['items'] as List : const <Object?>[])
            .whereType<Map<Object?, Object?>>()
            .map((Map<Object?, Object?> raw) => AppSheetItem.fromJson(raw.cast<String, Object?>()))
            .toList(growable: false),
      );
}

/// 把快照里的 `sheet` 字段接到真实的底部弹层上（侧栏与聊天页共用一份）。
///
/// 为什么不让调用方各自 `showModalBottomSheet`：快照是持续推送的，
/// 「选后端」这种一操作就整份重算的视图如果每次关掉再打开，用户会看到面板闪一下。
/// 所以这里用一个 [ValueNotifier] 原地更新内容，只在「从无到有」和「从有到无」时开关。
class AppSheetHost {
  AppSheetHost({
    required this.sendPick,
    required this.sendClose,
  });

  /// 用户点了某一行：把 id 发回 WebView（由它决定下一步）
  final void Function(String id) sendPick;

  /// 面板被用户关掉（下拉 / 点遮罩）：告诉 WebView 清掉状态，否则下次进页面会立刻弹回来
  final void Function() sendClose;

  BuildContext? _context;
  ValueNotifier<AppSheet>? _view;
  bool _open = false;

  /// 每次快照到位时调用。`null` 表示「不该有面板」。
  void sync(BuildContext context, AppSheet? next) {
    _context = context;
    // 没有面板：把还开着的那个关掉（WebView 侧已经清状态了，这里只做界面收尾）
    if (next == null) {
      _view = null;
      if (_open) {
        _open = false;
        final NavigatorState? nav = Navigator.maybeOf(context);
        if (nav != null && nav.canPop()) nav.pop();
      }
      return;
    }
    if (_view == null) {
      _view = ValueNotifier<AppSheet>(next);
      unawaited(_present(context));
      return;
    }
    // 内容变了原地刷新，开关动画保持不动
    if (!identical(_view!.value, next)) _view!.value = next;
  }

  Future<void> _present(BuildContext context) async {
    _open = true;
    // `showDragHandle` + `bottomSheetTheme` 的形状就是 M3 的弹层外观，
    // 不用再自己画那条把手（手画的那条与 M3 的把手粗细、位置都对不上）。
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (BuildContext sheetContext) {
        final ValueNotifier<AppSheet>? view = _view;
        if (view == null) return const SizedBox.shrink();
        return ValueListenableBuilder<AppSheet>(
          valueListenable: view,
          builder: (BuildContext context, AppSheet sheet, Widget? _) =>
              _AppSheetBody(sheet: sheet, onPick: _pick),
        );
      },
    );
    // 用户把它关掉了（下拉 / 点遮罩 / 系统返回）
    if (_open) {
      _open = false;
      _view = null;
      sendClose();
    }
  }

  void _pick(String id) {
    final NavigatorState? nav = _context == null ? null : Navigator.maybeOf(_context!);
    if (nav != null && nav.canPop()) nav.pop();
    sendPick(id);
  }

  void dispose() {
    _view?.dispose();
    _view = null;
  }
}

class _AppSheetBody extends StatelessWidget {
  const _AppSheetBody({required this.sheet, required this.onPick});

  final AppSheet sheet;
  final void Function(String id) onPick;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    final List<Widget> children = <Widget>[];
    String lastGroup = '';

    for (final AppSheetItem item in sheet.items) {
      if (item.group.isNotEmpty && item.group != lastGroup) {
        lastGroup = item.group;
        children.add(
          Padding(
            padding: EdgeInsets.fromLTRB(24, children.isEmpty ? 0 : 12, 24, 4),
            child: Text(
              item.group,
              style: Theme.of(context).textTheme.labelMedium?.copyWith(color: c.accent),
            ),
          ),
        );
      }
      children.add(_row(context, c, item));
    }

    final ThemeData theme = Theme.of(context);
    return ConstrainedBox(
      constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.75),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 4, 24, 4),
            child: Text(sheet.title, style: theme.textTheme.titleLarge),
          ),
          if (sheet.note.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
              child: Text(sheet.note, style: theme.textTheme.bodySmall?.copyWith(color: c.textMuted)),
            ),
          Flexible(
            child: ListView(
              shrinkWrap: true,
              padding: const EdgeInsets.only(bottom: 16),
              children: children,
            ),
          ),
        ],
      ),
    );
  }

  Widget _row(BuildContext context, PiColors c, AppSheetItem item) {
    final ThemeData theme = Theme.of(context);
    final Color color = item.disabled
        ? c.textMuted.withValues(alpha: 0.6)
        : item.danger
            ? c.danger
            : c.text;
    // 纯信息行（目标 / 技能）不给点击：点了没反应比不画箭头更糟
    final VoidCallback? onTap = item.disabled || !item.actionable ? null : () => onPick(item.id);
    return ListTile(
      onTap: onTap,
      enabled: !item.disabled,
      selected: item.selected,
      contentPadding: const EdgeInsets.symmetric(horizontal: 24),
      title: Text(
        item.label,
        style: theme.textTheme.bodyLarge?.copyWith(color: color, fontWeight: FontWeight.w500),
      ),
      subtitle: item.description.isEmpty
          ? null
          : Text(
              item.description,
              style: theme.textTheme.bodySmall?.copyWith(color: c.textMuted),
            ),
      trailing: item.selected
          ? Icon(Icons.check_rounded, size: 20, color: c.accent)
          : (item.actionable ? Icon(Icons.chevron_right_rounded, size: 20, color: c.textMuted) : null),
    );
  }
}
