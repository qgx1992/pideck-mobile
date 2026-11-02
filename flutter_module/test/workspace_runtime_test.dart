/// 工作区数据（Dart 接管 git / files / 预览）的端到端自测：真开 HTTP 服务端。
///
/// 盯的是接管之后最容易坏的四件事：
///   - `/api/files` 的参数名必须是 `dir`（写 `path` 会被服务端静默忽略，永远是根目录）
///   - 目录栈 / 面包屑 / 进入下层目录的换算
///   - git 状态解析到 `changes` 的相对路径、提交日期格式化
///   - 预览截断与错误文案（读不了要说清楚，不能画半截）
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/runtime/api_client.dart';
import 'package:pideck_flutter/runtime/state_store.dart';
import 'package:pideck_flutter/workspace/workspace_runtime.dart';

const String _sessionId = 's1';
const String _projectPath = r'D:\proj';

/// 服务端可变行为：这次 /api/files 返回什么、/api/file-content 有多长。
String _filesFailure = '';
bool _slowFiles = false;
String _previewContent = 'hello';

/// `/api/state` 与 `/api/events` 的同一份状态：一个项目 + 一个会话。
Map<String, Object?> _stateJson() => <String, Object?>{
      'projects': <Object?>[
        <String, Object?>{'id': 'p1', 'name': 'PiDeck', 'path': _projectPath},
      ],
      'sessions': <Object?>[
        <String, Object?>{
          'id': _sessionId,
          'projectId': 'p1',
          'title': '测试会话',
          'status': 'idle',
          'updatedAt': 1000,
        },
      ],
      'runtimes': <Object?>[],
      'pendingUiRequests': <Object?>[],
    };

Future<HttpServer> _startServer(List<String> dirRequests) async {
  final HttpServer server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  server.listen((HttpRequest request) async {
    final String path = request.uri.path;
    final Map<String, String> query = request.uri.queryParameters;
    void json(Object? body) {
      request.response.headers.contentType = ContentType.json;
      request.response.write(jsonEncode(body));
    }

    if (path == '/api/state') {
      json(_stateJson());
      await request.response.close();
      return;
    }
    if (path == '/api/events') {
      // 必须关掉输出缓冲：HttpResponse 默认会把 body 攒到 close 才发，
      // 流式连接永远不 close，客户端就一个字节都收不到。
      request.response.bufferOutput = false;
      request.response.headers.contentType = ContentType('text', 'event-stream', charset: 'utf-8');
      // 状态仓的 `ready` 只在收到一帧 `state` 事件后才会置真 —— 只发心跳不行。
      request.response.write('event: state\ndata: ${jsonEncode(_stateJson())}\n\n');
      await request.response.flush();
      return;
    }
    if (path == '/api/files') {
      dirRequests.add('${query['dir'] ?? ''}|${query['path'] ?? ''}');
      if (_slowFiles) await Future<void>.delayed(const Duration(milliseconds: 400));
      if (_filesFailure.isNotEmpty) {
        request.response.statusCode = 500;
        json(<String, Object?>{'error': _filesFailure});
        await request.response.close();
        return;
      }
      final List<Object?> nodes = query['dir'] == 'src'
          ? <Object?>[
              <String, Object?>{'name': 'b.ts', 'relativePath': 'src/b.ts', 'type': 'file'},
              <String, Object?>{'name': 'a.ts', 'relativePath': 'src/a.ts', 'type': 'file'},
            ]
          : <Object?>[
              <String, Object?>{'name': 'src', 'relativePath': 'src', 'type': 'directory'},
              <String, Object?>{'name': 'README.md', 'relativePath': 'README.md', 'type': 'file'},
              <String, Object?>{'name': 'file10.txt', 'relativePath': 'file10.txt', 'type': 'file'},
              <String, Object?>{'name': 'file2.txt', 'relativePath': 'file2.txt', 'type': 'file'},
            ];
      json(<String, Object?>{'nodes': nodes});
      await request.response.close();
      return;
    }
    if (path == '/api/file-content') {
      json(<String, Object?>{'content': _previewContent, 'size': _previewContent.length});
      await request.response.close();
      return;
    }
    if (path == '/api/git/status') {
      json(<String, Object?>{
        'repo': true,
        'branch': <String, Object?>{'current': 'master', 'branches': <Object?>['master']},
        'groups': <String, Object?>{
          'index': <Object?>[
            <String, Object?>{'path': '$_projectPath\\src\\a.ts', 'letter': 'M'},
          ],
          'untracked': <Object?>[
            <String, Object?>{'path': '$_projectPath\\new.txt', 'letter': '?'},
          ],
        },
      });
      await request.response.close();
      return;
    }
    if (path == '/api/git/log') {
      json(<String, Object?>{
        'commits': <Object?>[
          <String, Object?>{
            'hash': 'abcdef1234567',
            'message': '修工作区',
            'authorName': 'me',
            'authorDate': '2026-11-02T15:04:00',
          },
        ],
      });
      await request.response.close();
      return;
    }
    request.response.statusCode = HttpStatus.notFound;
    await request.response.close();
  });
  return server;
}

Future<void> _waitFor(bool Function() condition, {Duration timeout = const Duration(seconds: 5)}) async {
  final DateTime deadline = DateTime.now().add(timeout);
  while (!condition()) {
    if (DateTime.now().isAfter(deadline)) throw StateError('等待超时');
    await Future<void>.delayed(const Duration(milliseconds: 20));
  }
}

void main() {
  late HttpServer server;
  late PiDeckClient client;
  late StateStore store;
  late WorkspaceRuntime workspace;
  late List<String> dirRequests;
  late List<String> notices;

  setUp(() async {
    _filesFailure = '';
    _slowFiles = false;
    _previewContent = 'hello';
    dirRequests = <String>[];
    notices = <String>[];
    server = await _startServer(dirRequests);
    client = PiDeckClient(baseUrl: 'http://127.0.0.1:${server.port}', token: 't');
    store = StateStore(client: client);
    store.start();
    await _waitFor(() => store.ready);
    workspace = WorkspaceRuntime(
      client: client,
      store: store,
      sessionId: _sessionId,
      onNotice: notices.add,
      timeout: const Duration(milliseconds: 200),
    );
  });

  tearDown(() async {
    workspace.dispose();
    store.stop();
    client.close();
    await server.close(force: true);
  });

  test('打开抽屉：默认文件页签，目录在前、其余按名称', () async {
    await workspace.openDrawer();
    await _waitFor(() => workspace.snapshotJson()['loading'] == false);
    final Map<String, Object?> snapshot = workspace.snapshotJson();
    expect(snapshot['open'], isTrue);
    expect(snapshot['tab'], 'files');
    expect(snapshot['title'], 'PiDeck');
    expect(snapshot['crumbs'], <Object?>['PiDeck']);
    expect(snapshot['empty'], '');
    final List<Object?> entries = snapshot['entries']! as List<Object?>;
    expect(
      entries.map((Object? row) => (row! as Map<String, Object?>)['name']).toList(),
      <Object?>['src', 'file2.txt', 'file10.txt', 'README.md'],
    );
    // 根目录不该带 dir 参数
    expect(dirRequests, <String>['|']);
  });

  test('进入目录：dir 参数走相对路径，面包屑跟着加一段', () async {
    await workspace.openDrawer();
    await _waitFor(() => workspace.snapshotJson()['loading'] == false);
    await workspace.enter(r'src\ui');
    await _waitFor(() => workspace.snapshotJson()['loading'] == false);
    final Map<String, Object?> snapshot = workspace.snapshotJson();
    expect(snapshot['crumbs'], <Object?>['PiDeck', 'ui']);
    expect(dirRequests.last, 'ui|');
    // 再点面包屑第一段（depth 是「保留几段 stack」）：0 = 回到项目根
    await workspace.crumb(0);
    await _waitFor(() => (workspace.snapshotJson()['crumbs']! as List<Object?>).length == 1);
    expect(workspace.snapshotJson()['crumbs'], <Object?>['PiDeck']);
    expect(dirRequests.last, '|');
  });

  test('git 页签：分支 / 相对路径 / 提交日期', () async {
    await workspace.openDrawer();
    await _waitFor(() => workspace.snapshotJson()['loading'] == false);
    await workspace.setTab('git');
    await _waitFor(() => workspace.snapshotJson()['loading'] == false);
    final Map<String, Object?> snapshot = workspace.snapshotJson();
    expect(snapshot['tab'], 'git');
    expect(snapshot['branch'], 'master');
    expect(snapshot['changedTotal'], 2);
    final List<Object?> changes = snapshot['changes']! as List<Object?>;
    expect(
      changes.map((Object? row) => (row! as Map<String, Object?>)['path']).toList(),
      <Object?>['src/a.ts', 'new.txt'],
    );
    expect(
      changes.map((Object? row) => (row! as Map<String, Object?>)['group']).toList(),
      <Object?>['已暂存', '未跟踪'],
    );
    final List<Object?> commits = snapshot['commits']! as List<Object?>;
    expect((commits.first! as Map<String, Object?>)['sha'], 'abcdef1');
    expect((commits.first! as Map<String, Object?>)['subject'], '修工作区');
    expect((commits.first! as Map<String, Object?>)['date'], '11-02 15:04');
  });

  test('预览：超长内容截断，短内容原样带 truncated:false', () async {
    await workspace.openDrawer();
    await _waitFor(() => workspace.snapshotJson()['loading'] == false);
    await workspace.preview('README.md');
    await _waitFor(() => workspace.snapshotJson()['loading'] == false);
    Map<String, Object?> preview = workspace.snapshotJson()['preview']! as Map<String, Object?>;
    expect(preview['path'], 'README.md');
    expect(preview['content'], 'hello');
    expect(preview['truncated'], isFalse);

    _previewContent = 'x' * 8001;
    await workspace.preview('big.txt');
    await _waitFor(() => (workspace.snapshotJson()['preview']! as Map<String, Object?>)['path'] == 'big.txt');
    preview = workspace.snapshotJson()['preview']! as Map<String, Object?>;
    expect((preview['content']! as String).length, 8000);
    expect(preview['truncated'], isTrue);

    workspace.closePreview();
    expect(workspace.snapshotJson()['preview'], isNull);
  });

  test('读取失败：错误文案落到 empty，不画半截', () async {
    _filesFailure = '目录不存在';
    await workspace.openDrawer();
    await _waitFor(() => workspace.snapshotJson()['loading'] == false);
    expect('${workspace.snapshotJson()['empty']}', contains('读取失败'));
    expect(workspace.snapshotJson()['entries'], isEmpty);
  });

  test('超时：桌面端不回就报「超时」，不要一直转圈', () async {
    _slowFiles = true;
    await workspace.openDrawer();
    await _waitFor(() => workspace.snapshotJson()['loading'] == false);
    expect('${workspace.snapshotJson()['empty']}', contains('超时'));
  });

  test('没有项目：提示一句，不画空目录', () async {
    store.stop();
    final List<String> emptyDirRequests = <String>[];
    final HttpServer other = await _startEmptyServer(emptyDirRequests);
    final PiDeckClient otherClient =
        PiDeckClient(baseUrl: 'http://127.0.0.1:${other.port}', token: 't');
    final StateStore otherStore = StateStore(client: otherClient);
    final List<String> otherNotices = <String>[];
    final WorkspaceRuntime otherWorkspace = WorkspaceRuntime(
      client: otherClient,
      store: otherStore,
      sessionId: 'none',
      onNotice: otherNotices.add,
    );
    await otherWorkspace.openDrawer();
    expect(otherNotices, <String>['没有可用项目：请先在桌面端添加项目目录']);
    otherWorkspace.dispose();
    otherClient.close();
    await other.close(force: true);
  });
}

Future<HttpServer> _startEmptyServer(List<String> unused) async {
  final HttpServer server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  server.listen((HttpRequest request) async {
    request.response.headers.contentType = ContentType.json;
    request.response.write(jsonEncode(<String, Object?>{
      'projects': <Object?>[],
      'sessions': <Object?>[],
      'runtimes': <Object?>[],
      'pendingUiRequests': <Object?>[],
    }));
    await request.response.close();
  });
  return server;
}
