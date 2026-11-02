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
  late List<Map<String, Object?>> bodies;
  late List<String> notices;
  late List<Map<String, Object?>> forwards;
  late Map<String, Object?> stateJson;

  setUp(() async {
    requests = <String>[];
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

  SessionActions actionsOf({String activeSessionId = 's1'}) => SessionActions(
        client: client,
        store: store,
        activeSessionId: activeSessionId,
        onNotice: notices.add,
        onForward: forwards.add,
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
