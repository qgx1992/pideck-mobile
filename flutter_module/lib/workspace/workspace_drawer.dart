import 'package:flutter/material.dart';

import 'workspace_models.dart';

/// 右侧「工作区」抽屉：Git 改动、文件浏览、待办历史。
///
/// 数据全在快照里（Web 侧算），这里只画 + 把点击变成动作发回去。
/// 抽屉宽度取「屏幕的 92%、最多 380」，因为手机上默认 304 会把文件路径截掉一半。
class WorkspaceDrawer extends StatelessWidget {
  const WorkspaceDrawer({
    super.key,
    required this.snapshot,
    required this.onAction,
    this.onClose,
  });

  final WorkspaceSnapshot snapshot;
  final void Function(Map<String, Object?> action) onAction;

  /// 关抽屉的本地入口。
  ///
  /// 不能只发 `workspaceClose`：聊天页那边有个「用户刚点的意图」，光发动作不改意图的话
  /// 快照回来（open=false）反而会和意图（open=true）打架，抽屉刚关又被打开（0.2.8 踩过：
  /// 右上角 X 怎么点都关不掉）。必须走 `_setWorkspaceOpen(false)` 把意图一起改掉。
  final VoidCallback? onClose;

  static const List<String> _tabs = <String>['git', 'files', 'todos'];
  static const List<String> _labels = <String>['改动', '文件', '待办'];

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    final double width = MediaQuery.sizeOf(context).width * 0.92;
    return Drawer(
      backgroundColor: scheme.surface,
      width: width > 380 ? 380 : width,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.horizontal(left: Radius.circular(24)),
      ),
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 8, 8),
              child: Row(
                children: <Widget>[
                  Expanded(
                    child: Text(
                      snapshot.title.isEmpty ? '工作区' : snapshot.title,
                      style: theme.textTheme.titleMedium,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  IconButton(
                    tooltip: '刷新',
                    onPressed: () => onAction(<String, Object?>{'type': 'workspaceRefresh'}),
                    icon: const Icon(Icons.refresh_rounded),
                  ),
                  IconButton(
                    tooltip: '关闭',
                    onPressed: onClose ??
                        () => onAction(<String, Object?>{'type': 'workspaceClose'}),
                    icon: const Icon(Icons.close_rounded),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: SegmentedButton<String>(
                showSelectedIcon: false,
                style: const ButtonStyle(visualDensity: VisualDensity.compact),
                segments: <ButtonSegment<String>>[
                  for (int i = 0; i < _tabs.length; i++)
                    ButtonSegment<String>(value: _tabs[i], label: Text(_labels[i])),
                ],
                selected: <String>{snapshot.tab},
                onSelectionChanged: (Set<String> value) {
                  onAction(<String, Object?>{'type': 'workspaceTab', 'tab': value.first});
                },
              ),
            ),
            if (snapshot.loading) const LinearProgressIndicator(minHeight: 2),
            const SizedBox(height: 4),
            Expanded(child: _body(context)),
          ],
        ),
      ),
    );
  }

  Widget _body(BuildContext context) {
    final WorkspacePreview? preview = snapshot.preview;
    if (preview != null) return _preview(context, preview);
    if (snapshot.loading && snapshot.empty.isEmpty) {
      return const Center(child: SizedBox.shrink());
    }
    if (snapshot.empty.isNotEmpty) {
      return _empty(context, snapshot.empty);
    }
    switch (snapshot.tab) {
      case 'files':
        return _files(context);
      case 'todos':
        return _todos(context);
      default:
        return _git(context);
    }
  }

  Widget _empty(BuildContext context, String text) {
    final ThemeData theme = Theme.of(context);
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 32),
        child: Text(
          text,
          textAlign: TextAlign.center,
          style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
      ),
    );
  }

  Widget _sectionTitle(BuildContext context, String text) {
    final ThemeData theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 6),
      child: Text(
        text,
        style: theme.textTheme.labelMedium?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
          letterSpacing: 0.6,
        ),
      ),
    );
  }

  Widget _git(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    final List<Widget> children = <Widget>[];

    children.add(
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 4),
        child: Row(
          children: <Widget>[
            Icon(Icons.account_tree_outlined, size: 18, color: scheme.onSurfaceVariant),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                snapshot.branch.isEmpty ? '（未知分支）' : snapshot.branch,
                style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            Text(
              '${snapshot.changedTotal} 个改动',
              style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );

    for (final String group in snapshot.changeGroups) {
      children.add(_sectionTitle(context, group));
      for (final WorkspaceChange change in snapshot.changes.where((WorkspaceChange c) => c.group == group)) {
        children.add(
          ListTile(
            dense: true,
            visualDensity: VisualDensity.compact,
            contentPadding: const EdgeInsets.symmetric(horizontal: 20),
            leading: _letterBadge(context, change.letter),
            title: Text(
              change.path,
              style: theme.textTheme.bodySmall,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            trailing: IconButton(
              tooltip: '引用到输入框',
              visualDensity: VisualDensity.compact,
              onPressed: () => onAction(<String, Object?>{'type': 'workspaceMention', 'path': change.path}),
              icon: const Icon(Icons.alternate_email_rounded, size: 18),
            ),
            onTap: () => onAction(<String, Object?>{'type': 'workspacePreview', 'path': change.path}),
          ),
        );
      }
    }

    if (snapshot.commits.isNotEmpty) {
      children.add(_sectionTitle(context, '最近提交'));
      for (final WorkspaceCommit commit in snapshot.commits) {
        children.add(
          ListTile(
            dense: true,
            visualDensity: VisualDensity.compact,
            contentPadding: const EdgeInsets.symmetric(horizontal: 20),
            leading: Text(
              commit.sha,
              style: theme.textTheme.labelSmall?.copyWith(
                color: scheme.primary,
                fontFeatures: const <FontFeature>[FontFeature.tabularFigures()],
              ),
            ),
            title: Text(commit.subject, style: theme.textTheme.bodySmall, maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text(commit.date, style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant)),
          ),
        );
      }
    }

    if (children.length == 1) children.add(_empty(context, '工作区干净，没有未提交的改动'));
    return ListView(children: children);
  }

  Widget _letterBadge(BuildContext context, String letter) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    final bool add = letter == 'A' || letter == 'U';
    final Color color = letter == 'D' ? scheme.error : (add ? scheme.primary : scheme.tertiary);
    return SizedBox(
      width: 20,
      child: Text(
        letter.isEmpty ? '?' : letter,
        style: theme.textTheme.labelMedium?.copyWith(color: color, fontWeight: FontWeight.w700),
      ),
    );
  }

  Widget _files(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        SizedBox(
          height: 44,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16),
            itemCount: snapshot.crumbs.length,
            separatorBuilder: (BuildContext context, int index) => Icon(
              Icons.chevron_right_rounded,
              size: 16,
              color: scheme.onSurfaceVariant,
            ),
            itemBuilder: (BuildContext context, int index) {
              final bool last = index == snapshot.crumbs.length - 1;
              return Center(
                child: ActionChip(
                  visualDensity: VisualDensity.compact,
                  label: Text(snapshot.crumbs[index]),
                  backgroundColor: last ? scheme.secondaryContainer : null,
                  onPressed: last
                      ? null
                      : () => onAction(<String, Object?>{
                            'type': 'workspaceCrumb',
                            'depth': index,
                          }),
                ),
              );
            },
          ),
        ),
        Expanded(
          child: ListView.builder(
            itemCount: snapshot.entries.length,
            itemBuilder: (BuildContext context, int index) {
              final WorkspaceEntry entry = snapshot.entries[index];
              return ListTile(
                dense: true,
                contentPadding: const EdgeInsets.only(left: 20, right: 8),
                leading: Icon(
                  entry.dir ? Icons.folder_outlined : Icons.description_outlined,
                  color: entry.dir ? scheme.primary : scheme.onSurfaceVariant,
                ),
                title: Text(
                  entry.name,
                  style: theme.textTheme.bodyMedium,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                trailing: entry.dir
                    ? null
                    : IconButton(
                        tooltip: '引用到输入框',
                        visualDensity: VisualDensity.compact,
                        onPressed: () => onAction(<String, Object?>{'type': 'workspaceMention', 'path': entry.path}),
                        icon: const Icon(Icons.alternate_email_rounded, size: 18),
                      ),
                onTap: () => onAction(<String, Object?>{
                  'type': entry.dir ? 'workspaceEnter' : 'workspacePreview',
                  'path': entry.path,
                }),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _todos(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    return ListView.builder(
      padding: const EdgeInsets.only(bottom: 24),
      itemCount: snapshot.todos.length,
      itemBuilder: (BuildContext context, int index) {
        final WorkspaceTodo todo = snapshot.todos[index];
        return Padding(
          padding: const EdgeInsets.fromLTRB(20, 10, 20, 10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(
                todo.text,
                style: theme.textTheme.bodySmall?.copyWith(
                  height: 1.6,
                  color: todo.finished ? scheme.onSurfaceVariant : scheme.onSurface,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                todo.at,
                style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant),
              ),
              const Divider(height: 20),
            ],
          ),
        );
      },
    );
  }

  Widget _preview(BuildContext context, WorkspacePreview preview) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        Padding(
          padding: const EdgeInsets.fromLTRB(8, 4, 16, 0),
          child: Row(
            children: <Widget>[
              IconButton(
                tooltip: '返回',
                onPressed: () => onAction(<String, Object?>{'type': 'workspacePreviewClose'}),
                icon: const Icon(Icons.arrow_back_rounded),
              ),
              Expanded(
                child: Text(
                  preview.path,
                  style: theme.textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              IconButton(
                tooltip: '引用到输入框',
                onPressed: () => onAction(<String, Object?>{'type': 'workspaceMention', 'path': preview.path}),
                icon: const Icon(Icons.alternate_email_rounded),
              ),
            ],
          ),
        ),
        if (preview.truncated)
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 6),
            child: Text(
              '文件太长，只显示开头部分',
              style: theme.textTheme.labelSmall?.copyWith(color: scheme.onSurfaceVariant),
            ),
          ),
        Expanded(
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
            child: SelectableText(
              preview.content,
              style: theme.textTheme.bodySmall?.copyWith(
                fontFamily: 'monospace',
                fontFamilyFallback: const <String>['Menlo', 'Courier New'],
                height: 1.5,
              ),
            ),
          ),
        ),
      ],
    );
  }
}
