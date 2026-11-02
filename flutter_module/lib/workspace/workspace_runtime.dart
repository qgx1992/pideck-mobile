import 'dart:async';

import 'package:flutter/foundation.dart';

import '../runtime/api_client.dart';
import '../runtime/state_store.dart';
import 'workspace_native.dart';

/// 桌面端迟迟不回时的上限：超时当成一次失败，把错误文案画在抽屉里。
///
/// 与 Web 侧 `WORKSPACE_TIMEOUT_MS` 一致 —— 那边是 12s，手机上一模一样。
const Duration workspaceTimeout = Duration(milliseconds: 12000);

/// 工作区抽屉的数据来源（Dart 侧）。
///
/// 只接管 `git` 与 `files` 两个页签：它们的数据全在桌面端（`/api/git/*`、
/// `/api/files`、`/api/file-content`），WebView 被挂起时本来就得等它醒来。
/// `todos` 页签仍由 WebView 算 —— 待办历史存在 Web 侧的 localStorage 里，
/// 搬过来等于另起一套存储，收益低、风险高。
///
/// 对外只给一个东西：[snapshotJson]，形状与 `WorkspaceSnapshot` 完全一致，
/// 由聊天页合并进快照的 `workspace` 键，Dart 侧 UI 一行都不用改。
class WorkspaceRuntime extends ChangeNotifier {
  WorkspaceRuntime({
    required this.client,
    required this.store,
    required this.sessionId,
    this.onNotice,
    this.timeout = workspaceTimeout,
  }) : _snapshot = emptyWorkspaceSnapshotJson();

  final PiDeckClient client;
  final StateStore store;
  final String sessionId;
  final void Function(String message)? onNotice;

  /// 单次请求的上限（测试里调短，生产就是 12s）。
  final Duration timeout;

  Map<String, Object?> _snapshot;
  bool _open = false;
  String _tab = 'files';
  final List<String> _stack = <String>[];
  Map<String, Object?>? _preview;
  int _generation = 0;
  bool _disposed = false;

  /// 抽屉该不该开着（与快照里的 `open` 同义）。
  bool get isOpen => _open;
  String get tab => _tab;
  bool get loading => _snapshot['loading'] == true;

  /// 当前该画什么。键与 `WorkspaceSnapshot.fromJson` 一一对应。
  Map<String, Object?> snapshotJson() => _snapshot;

  /// 打开抽屉：没项目就提示，有项目就清空目录栈重算。
  Future<void> openDrawer() async {
    if (_projectId().isEmpty) {
      onNotice?.call('没有可用项目：请先在桌面端添加项目目录');
      return;
    }
    _open = true;
    _preview = null;
    _stack.clear();
    await _reload();
  }

  /// 收起：只清预览，保留 tab/stack —— 再打开时用户还想看原来那层目录。
  void close() {
    _open = false;
    _preview = null;
    _publish(<String, Object?>{..._snapshot, 'open': false, 'preview': null});
  }

  Future<void> setTab(String tab) async {
    _tab = tab == 'git' || tab == 'todos' ? tab : 'files';
    _stack.clear();
    _preview = null;
    await _reload();
  }

  Future<void> enter(String path) async {
    if (path.isNotEmpty) _stack.add(lastSegment(path));
    _preview = null;
    await _reload();
  }

  Future<void> crumb(int depth) async {
    if (depth < 0 || depth > _stack.length) return;
    _stack.removeRange(depth, _stack.length);
    _preview = null;
    await _reload();
  }

  Future<void> refresh() => _reload();

  /// 读文件预览。`kind != text` 时画说明文字，绝不展示半截内容。
  Future<void> preview(String path) async {
    final String projectId = _projectId();
    if (projectId.isEmpty || path.isEmpty) return;
    final Map<String, Object?> base = <String, Object?>{
      ..._snapshot,
      'loading': true,
      'empty': '',
    };
    _publish(base);
    try {
      final FileContent file = await _deadline(
        client.fileContent(projectId, path),
        '读取文件',
      );
      final Map<String, Object?> cut = truncatePreview(
        previewTextOf(kind: file.kind, size: file.size, content: file.content),
      );
      _preview = <String, Object?>{
        'path': path,
        'content': cut['content'],
        'truncated': cut['truncated'],
      };
      _publish(<String, Object?>{
        ...base,
        'loading': false,
        'preview': _preview,
      });
    } catch (error) {
      _preview = null;
      _publish(<String, Object?>{
        ...base,
        'loading': false,
        'preview': null,
        'empty': '读不了这个文件：${_describe(error)}',
      });
    }
  }

  void closePreview() {
    _preview = null;
    _publish(<String, Object?>{..._snapshot, 'preview': null});
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }

  /// 重算整份快照：先推「加载中」，再推结果（抽屉里不会一片空白）。
  Future<void> _reload() async {
    final int generation = ++_generation;
    final String projectId = _projectId();
    final Map<String, Object?>? project = _projectOf(projectId);
    final String title = project == null ? '' : _projectLabel(project);
    final String projectPath = project == null ? '' : _str(project['path']);

    final Map<String, Object?> base = <String, Object?>{
      ...emptyWorkspaceSnapshotJson(),
      'open': true,
      'tab': _tab,
      'title': title,
      'loading': true,
      'crumbs': <Object?>[title.isEmpty ? '项目' : title, ..._stack],
      'preview': _preview,
    };
    _publish(base);

    if (projectId.isEmpty) {
      _publishIfCurrent(generation, <String, Object?>{
        ...base,
        'loading': false,
        'empty': '没有可用项目',
      });
      return;
    }
    // 内置 Chat 项目没有目录：别拿空路径去问桌面端要文件列表（那边会一直不回）
    if (projectPath.isEmpty && _tab != 'todos') {
      _publishIfCurrent(generation, <String, Object?>{
        ...base,
        'loading': false,
        'empty': '这个项目没有目录',
      });
      return;
    }

    Map<String, Object?> next = <String, Object?>{...base, 'loading': false};
    try {
      switch (_tab) {
        case 'todos':
          // 待办历史归 WebView 算；Dart 接管不了就别装懂，留空态。
          next['empty'] = '这个会话还没有待办清单';
        case 'git':
          next = <String, Object?>{
            ...next,
            ...(await _loadGit(projectId, projectPath)),
          };
        default:
          next = <String, Object?>{
            ...next,
            ...(await _loadFiles(projectId)),
          };
      }
    } catch (error) {
      next = <String, Object?>{...next, 'empty': '读取失败：${_describe(error)}'};
    }
    _publishIfCurrent(generation, next);
  }

  Future<Map<String, Object?>> _loadGit(String projectId, String projectPath) async {
    final Future<Object?> statusFuture = _deadline(client.gitStatus(projectId), '读取 Git 状态');
    // 日志挂了不影响状态显示：Web 侧也是 `.catch(() => null)`。
    final Future<Object?> logFuture = client.gitLog(projectId, limit: 20).then<Object?>(
      (Object? value) => value,
      onError: (Object _) => null,
    );
    final List<Object?> both = await Future.wait<Object?>(<Future<Object?>>[statusFuture, logFuture]);
    final Map<String, Object?> status = parseGitStatus(both[0], projectPath);
    if (status['isRepo'] != true) {
      return <String, Object?>{'empty': '这个项目不是 Git 仓库'};
    }
    final List<Object?> groups = status['groups'] is List ? status['groups']! as List<Object?> : const <Object?>[];
    final List<Object?> changes = <Object?>[];
    for (final Object? group in groups) {
      if (group is! Map) continue;
      final Map<String, Object?> g = Map<String, Object?>.from(group);
      final Object? files = g['files'];
      if (files is! List) continue;
      for (final Object? file in files) {
        if (file is! Map) continue;
        final Map<String, Object?> f = Map<String, Object?>.from(file);
        changes.add(<String, Object?>{
          'letter': f['letter'] ?? '',
          'path': f['relativePath'] ?? '',
          'group': g['title'] ?? '',
        });
      }
    }
    final List<Object?> commits = <Object?>[
      for (final Map<String, Object?> commit in parseGitLog(both[1]))
        <String, Object?>{
          'sha': commit['shortHash'],
          'subject': commit['message'],
          'date': formatCommitDate(_str(commit['authorDate'])),
        },
    ];
    return <String, Object?>{
      'branch': status['branch'],
      'changedTotal': status['total'],
      'changes': changes,
      'commits': commits,
    };
  }

  Future<Map<String, Object?>> _loadFiles(String projectId) async {
    final List<Map<String, Object?>> nodes = await _deadline(
      client.listFiles(projectId, _stack.join('/')),
      '读取目录',
    );
    final List<Map<String, Object?>> entries = sortWorkspaceEntries(<Map<String, Object?>>[
      for (final Map<String, Object?> node in nodes)
        <String, Object?>{
          'name': _str(node['name']),
          'path': _str(node['relativePath']),
          'dir': node['type'] == 'directory',
        },
    ]);
    return <String, Object?>{
      'entries': entries,
      if (entries.isEmpty) 'empty': '空目录',
    };
  }

  void _publishIfCurrent(int generation, Map<String, Object?> next) {
    if (_disposed) return;
    if (generation != _generation) return;
    // 期间用户可能已经关了抽屉或换了 Tab：过期的结果直接丢掉
    if (!_open || '${next['tab'] ?? ''}' != _tab) return;
    _publish(next);
  }

  void _publish(Map<String, Object?> next) {
    if (_disposed) return;
    _snapshot = next;
    notifyListeners();
  }

  Future<T> _deadline<T>(Future<T> task, String label) => task.timeout(
        timeout,
        onTimeout: () => throw PiDeckException(
          PiDeckErrorKind.offline,
          '$label超时（桌面端没有响应）',
        ),
      );

  String _describe(Object error) {
    if (error is PiDeckException) return error.message;
    if (error is TimeoutException) return '桌面端没有响应（超时）';
    return '$error';
  }

  /// 工作区跟着当前会话的项目走；没有会话就退回第一个**有目录**的项目。
  ///
  /// 别退回内置 Chat 项目 —— 它没有目录，抽屉里只会写「这个项目没有目录」。
  String _projectId() {
    final Map<String, Object?>? session = store.session(sessionId);
    final String fromSession = session == null ? '' : _str(session['projectId']);
    if (fromSession.isNotEmpty) return fromSession;
    final List<Map<String, Object?>> projects = store.projects();
    for (final Map<String, Object?> project in projects) {
      if (_str(project['path']).isNotEmpty) return _str(project['id']);
    }
    return projects.isEmpty ? '' : _str(projects.first['id']);
  }

  Map<String, Object?>? _projectOf(String projectId) {
    if (projectId.isEmpty) return null;
    for (final Map<String, Object?> project in store.projects()) {
      if (_str(project['id']) == projectId) return project;
    }
    return null;
  }

  /// `projectLabel()`（`app/src/ui/sidebar-model.ts:23-31`）的同款。
  String _projectLabel(Map<String, Object?> project) {
    if (_str(project['kind']) == 'chat') return 'Chat';
    final String name = _str(project['name']).trim();
    if (name.isNotEmpty) return name;
    final String rawPath = _str(project['path']).replaceAll(RegExp(r'[\\/]+$'), '');
    final List<String> segments =
        rawPath.split(RegExp(r'[\\/]')).where((String s) => s.isNotEmpty).toList();
    return segments.isEmpty ? _str(project['id']) : segments.last;
  }
}

String _str(Object? value) => value is String ? value : '';
