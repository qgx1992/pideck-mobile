import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/runtime/api_client.dart';
import 'package:pideck_flutter/runtime/state_store.dart';
import 'package:pideck_flutter/sidebar/session_actions.dart';

/// 侧栏数据动作（改名 / 复制 / 删除 / 移除项目 / 新建会话）的接口契约。
///
/// 这些测试盯的是「谁调接口、调了几次、body 长什么样」——
/// 因为它们已经有一次副作用，回传给 WebView 的那一份只许重画，不许再调。
void main() {
  late HttpServer server;
  late PiDeckClient client;
  late StateStore store;
  late List<String> requests;
  late List<String> auths;
  late List<Map<String, Object?>> bodies;
  late List<String> notices;
  late List<Map<String, Object?>> forwards;
  late Map<String, Object?> stateJson;

  setUp(() async {
    requests = <String>[];
    auths = <String>[];
    bodies = <Map<String, Object?>>[];
    notices = <String>[];
    forwards = <Map<String, Object?>>[];
    stateJson = <String, Object?>{
      'projects': <Object?>[
        <String, Object?>{'id': 'p1', 'name': 'PiDeck', 'path': '/repo'},
      ],
      'sessions': <Object?>[
        <String, Object?>{'id': 's1', 'projectId': 'p1', 'title': '第一个', 'status': 'idle', 'updatedAt': 2},
        <String, Object?>{'id': 's2', 'projectId': 'p1', 'title': '第二个', 'status': 'idle', 'updatedAt': 1},
      ],
      'runtimes': <Object?>[],
      'pendingUiRequests': <Object?>[],
    };
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((HttpRequest request) async {
      final String path = request.uri.path;
      requests.add(path);
      auths.add(request.headers.value('authorization') ?? '');
      final String raw = await utf8.decoder.bind(request).join();
      bodies.add(raw.isEmpty ? <String, Object?>{} : Map<String, Object?>.from(jsonDecode(raw) as Map));
      final HttpResponse response = request.response;
      if (path == '/api/events') {
        response.bufferOutput = false;
        response.headers.contentType = ContentType('text', 'event-stream', charset: 'utf-8');
        response.write('event: state\ndata: ${jsonEncode(stateJson)}\n\n');
        await response.flush();
        return;
      }
      if (path.endsWith('/export-html')) {
        response.headers.contentType = ContentType('text', 'html', charset: 'utf-8');
        response.write('<html><body>导出正文</body></html>');
        await response.close();
        return;
      }
      response.headers.contentType = ContentType.json;
      if (path == '/api/state') {
        response.write(jsonEncode(stateJson));
      } else if (path == '/api/sessions') {
        response.write(jsonEncode(<String, Object?>{
          'session': <String, Object?>{'id': 's9', 'projectId': 'p1', 'title': '新会话'},
        }));
      } else if (path == '/api/projects/p1/delete') {
        response.write(jsonEncode(<String, Object?>{'deleted': true}));
      } else {
        response.write(jsonEncode(<String, Object?>{'ok': true}));
      }
      await response.close();
    });
    client = PiDeckClient(baseUrl: 'http://127.0.0.1:${server.port}', token: 't');
    store = StateStore(client: client);
    store.start();
    await _waitFor(() => store.ready);
  });

  tearDown(() async {
    store.stop();
    client.close();
    await server.close(force: true);
  });

  SessionActions actionsOf({String activeSessionId = 's1', SaveTextFileFn? saveFile}) => SessionActions(
        client: client,
        store: store,
        activeSessionId: activeSessionId,
        onNotice: notices.add,
        onForward: forwards.add,
        saveFile: saveFile,
      );

  test('改名：标题去空白后提交，成功后回传 native 只重画', () async {
    await actionsOf().rename('s2', '  新名字  ');
    expect(requests, contains('/api/sessions/s2/update'));
    expect(bodies[requests.indexOf('/api/sessions/s2/update')], <String, Object?>{'title': '新名字'});
    expect(notices, <String>['已重命名']);
    expect(forwards.single, <String, Object?>{
      'type': 'sessionRename',
      'id': 's2',
      'title': '新名字',
      'native': true,
    });
  });

  test('改名：空标题只提示，不发请求', () async {
    await actionsOf().rename('s2', '   ');
    expect(notices, <String>['标题不能为空']);
    expect(requests.where((String path) => path.contains('/update')), isEmpty);
    expect(forwards, isEmpty);
  });

  test('删除：删的是当前会话就不接手（要 WebView 收尾）', () async {
    final SessionActions actions = actionsOf();
    expect(actions.canHandleDelete('s1'), isFalse);
    await actions.delete('s1');
    expect(requests.where((String path) => path == '/api/sessions/s1/delete'), isEmpty);
    expect(forwards, isEmpty);
  });

  test('删除：别人的会话直接删，回传 native', () async {
    await actionsOf().delete('s2');
    expect(requests, contains('/api/sessions/s2/delete'));
    expect(notices, <String>['已删除会话']);
    expect(forwards.single['type'], 'sessionDelete');
    expect(forwards.single['native'], isTrue);
  });

  test('复制：打 copy 接口', () async {
    await actionsOf().copy('s2');
    expect(requests, contains('/api/sessions/s2/copy'));
    expect(notices, <String>['已复制会话']);
  });

  test('移除项目：调 delete 端点并回传', () async {
    await actionsOf().removeProject('p1');
    expect(requests, contains('/api/projects/p1/delete'));
    expect(notices, <String>['已移除项目']);
    expect(forwards.single['type'], 'projectRemove');
  });

  test('新建会话：默认用当前会话的项目，成功后回传新 id', () async {
    final String? created = await actionsOf().create();
    expect(created, 's9');
    final int index = requests.indexOf('/api/sessions');
    expect(bodies[index], <String, Object?>{'projectId': 'p1'});
    expect(forwards.single, <String, Object?>{
      'type': 'createSession',
      'native': true,
      'projectId': 'p1',
      'id': 's9',
    });
  });

  test('导出：带 Authorization 取 HTML，文件名去掉非法字符后保存', () async {
    String? savedName;
    String? savedText;
    String? savedMime;
    final SessionActions actions = actionsOf(
      saveFile: ({required String name, required String text, required String mime}) async {
        savedName = name;
        savedText = text;
        savedMime = mime;
        return '/sdcard/Download/PiDeck/$name';
      },
    );
    await actions.export('s2');
    final int index = requests.indexOf('/api/sessions/s2/export-html');
    expect(index, greaterThanOrEqualTo(0));
    // 导出整段 HTML 不能把 token 拼进 query（会落到下载记录 / 系统 Intent 里）。
    expect(auths[index], 'Bearer t');
    expect(savedText, '<html><body>导出正文</body></html>');
    expect(savedMime, 'text/html');
    expect(
      RegExp(r'^PiDeck-第二个-\d{4}-\d{2}-\d{2}-\d{2}-\d{2}\.html$').hasMatch(savedName!),
      isTrue,
      reason: '实际文件名：$savedName',
    );
    expect(notices.first, '正在导出 HTML…');
    expect(notices.last, contains('已保存到'));
    expect(forwards.single, <String, Object?>{'type': 'sessionExport', 'native': true, 'id': 's2'});
  });

  test('导出：落盘通道没响应时提示失败，但仍回传（不要重来一次）', () async {
    final SessionActions actions = actionsOf(
      saveFile: ({required String name, required String text, required String mime}) async => null,
    );
    await actions.export('s2');
    expect(notices.last, contains('保存失败'));
    expect(forwards.single['type'], 'sessionExport');
  });

  test('导出：空 id 不发请求、不提示', () async {
    final SessionActions actions = actionsOf(
      saveFile: ({required String name, required String text, required String mime}) async => '/x/$name',
    );
    await actions.export('');
    expect(requests.where((String path) => path.contains('/export-html')), isEmpty);
    expect(notices, isEmpty);
    expect(forwards, isEmpty);
  });

  test('文件名清洗与时间戳：与 Web 的 safeFileStem 同一套规则', () {
    expect(safeFileStem('  a/b:c*d?e"f<g>h|i  '), 'a-b-c-d-e-f-g-h-i');
    expect(safeFileStem('   '), 'session');
    expect(safeFileStem('x' * 60).length, 40);
    expect(exportStamp(DateTime.utc(2026, 11, 2, 15, 24, 41)), '2026-11-02-15-24');
  });

  test('新建会话：一个项目都没有时只提示', () async {
    stateJson = <String, Object?>{
      'projects': <Object?>[],
      'sessions': <Object?>[],
      'runtimes': <Object?>[],
      'pendingUiRequests': <Object?>[],
    };
    await store.refresh();
    final String? created = await actionsOf(activeSessionId: '').create();
    expect(created, isNull);
    expect(notices, <String>['没有可用项目：请先在桌面端添加项目目录']);
    expect(forwards, isEmpty);
  });
}

Future<void> _waitFor(bool Function() condition, {Duration timeout = const Duration(seconds: 5)}) async {
  final DateTime deadline = DateTime.now().add(timeout);
  while (!condition()) {
    if (DateTime.now().isAfter(deadline)) throw StateError('等待超时');
    await Future<void>.delayed(const Duration(milliseconds: 10));
  }
}
