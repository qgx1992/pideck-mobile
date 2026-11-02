import 'dart:convert';

/// 右侧「工作区」抽屉的数据模型。
///
/// 内容全部由 Web 侧算好（`app/src/platform/flutter-workspace.ts`）：Git 分组、
/// 目录列表、待办历史、文件预览截断都在那边做，这里只做反序列化 + 渲染。
/// 之所以不让 Dart 自己去调 pi 的接口：协议版本一改就要改两处，而且手机端
/// 没有理由再学一遍 git 状态的解析规则。

String _str(Object? value) => value is String ? value : '';

Map<String, Object?> _map(Object? value) {
  if (value is Map<String, Object?>) return value;
  if (value is Map) {
    return value.map((Object? key, Object? item) => MapEntry<String, Object?>(key.toString(), item));
  }
  return const <String, Object?>{};
}

List<Object?> _list(Object? value) => value is List ? value : const <Object?>[];

class WorkspaceChange {
  const WorkspaceChange({this.letter = '', this.path = '', this.group = ''});

  factory WorkspaceChange.fromJson(Map<String, Object?> json) => WorkspaceChange(
        letter: _str(json['letter']),
        path: _str(json['path']),
        group: _str(json['group']),
      );

  final String letter;
  final String path;
  final String group;
}

class WorkspaceCommit {
  const WorkspaceCommit({this.sha = '', this.subject = '', this.date = ''});

  factory WorkspaceCommit.fromJson(Map<String, Object?> json) => WorkspaceCommit(
        sha: _str(json['sha']),
        subject: _str(json['subject']),
        date: _str(json['date']),
      );

  final String sha;
  final String subject;
  final String date;
}

class WorkspaceEntry {
  const WorkspaceEntry({this.name = '', this.path = '', this.dir = false});

  factory WorkspaceEntry.fromJson(Map<String, Object?> json) => WorkspaceEntry(
        name: _str(json['name']),
        path: _str(json['path']),
        dir: json['dir'] == true,
      );

  final String name;
  final String path;
  final bool dir;
}

class WorkspaceTodo {
  const WorkspaceTodo({this.text = '', this.status = '', this.at = ''});

  factory WorkspaceTodo.fromJson(Map<String, Object?> json) => WorkspaceTodo(
        text: _str(json['text']),
        status: _str(json['status']),
        at: _str(json['at']),
      );

  final String text;
  final String status;
  final String at;

  bool get finished => status == 'completed';
}

class WorkspacePreview {
  const WorkspacePreview({this.path = '', this.content = '', this.truncated = false});

  factory WorkspacePreview.fromJson(Map<String, Object?> json) => WorkspacePreview(
        path: _str(json['path']),
        content: _str(json['content']),
        truncated: json['truncated'] == true,
      );

  final String path;
  final String content;
  final bool truncated;
}

class WorkspaceSnapshot {
  const WorkspaceSnapshot({
    this.open = false,
    this.tab = 'git',
    this.title = '',
    this.loading = false,
    this.empty = '',
    this.branch = '',
    this.changedTotal = 0,
    this.changes = const <WorkspaceChange>[],
    this.commits = const <WorkspaceCommit>[],
    this.crumbs = const <String>[],
    this.entries = const <WorkspaceEntry>[],
    this.preview,
    this.todos = const <WorkspaceTodo>[],
  });

  factory WorkspaceSnapshot.fromJson(Map<String, Object?> json) {
    final Object? preview = json['preview'];
    return WorkspaceSnapshot(
      open: json['open'] == true,
      tab: _str(json['tab']).isEmpty ? 'git' : _str(json['tab']),
      title: _str(json['title']),
      loading: json['loading'] == true,
      empty: _str(json['empty']),
      branch: _str(json['branch']),
      changedTotal: json['changedTotal'] is num ? (json['changedTotal'] as num).toInt() : 0,
      changes: _list(json['changes'])
          .whereType<Map<Object?, Object?>>()
          .map((Map<Object?, Object?> item) => WorkspaceChange.fromJson(_map(item)))
          .toList(growable: false),
      commits: _list(json['commits'])
          .whereType<Map<Object?, Object?>>()
          .map((Map<Object?, Object?> item) => WorkspaceCommit.fromJson(_map(item)))
          .toList(growable: false),
      crumbs: _list(json['crumbs']).whereType<String>().toList(growable: false),
      entries: _list(json['entries'])
          .whereType<Map<Object?, Object?>>()
          .map((Map<Object?, Object?> item) => WorkspaceEntry.fromJson(_map(item)))
          .toList(growable: false),
      preview: preview is Map<Object?, Object?> ? WorkspacePreview.fromJson(_map(preview)) : null,
      todos: _list(json['todos'])
          .whereType<Map<Object?, Object?>>()
          .map((Map<Object?, Object?> item) => WorkspaceTodo.fromJson(_map(item)))
          .toList(growable: false),
    );
  }

  static WorkspaceSnapshot? parse(String json) {
    if (json.trim().isEmpty) return null;
    try {
      final Object? raw = jsonDecode(json);
      if (raw is! Map) return null;
      return WorkspaceSnapshot.fromJson(_map(raw));
    } catch (_) {
      return null;
    }
  }

  final bool open;
  /// `git` / `files` / `todos`
  final String tab;
  final String title;
  final bool loading;
  final String empty;
  final String branch;
  final int changedTotal;
  final List<WorkspaceChange> changes;
  final List<WorkspaceCommit> commits;
  final List<String> crumbs;
  final List<WorkspaceEntry> entries;
  final WorkspacePreview? preview;
  final List<WorkspaceTodo> todos;

  /// 变更按「分组标题」连续排列（Web 侧已排好，这里只保持顺序去重取组名）。
  List<String> get changeGroups {
    final List<String> groups = <String>[];
    for (final WorkspaceChange change in changes) {
      if (!groups.contains(change.group)) groups.add(change.group);
    }
    return groups;
  }
}
