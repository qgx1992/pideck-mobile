/// 「会话文件还没写出来」时历史接口回 5xx 的容错自测。
///
/// 现场：桌面端新建的会话要等第一条消息落盘才有 transcript 文件；在那之前
/// `GET /api/sessions/:id/messages` 稳定回 500 `webError.internal`。
/// 页面如果把它当错误处理，用户点进新会话看到的是「一直空白加载 / 历史加载失败」；
/// 正确行为是**空历史 + 引导页**，并静默重试到文件出现。
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/runtime/api_client.dart';
import 'package:pideck_flutter/runtime/chat_runtime.dart';

const String _sessionId = 's1';

/// 服务端可变行为：`/messages` 先失败几次，再吐出历史。
class _Server {
  int failuresLeft = 0;
  int status = 500;
  int messageRequests = 0;
  final List<String> notices = <String>[];

  late HttpServer server;
  String get baseUrl => 'http://127.0.0.1:${server.port}';

  Future<void> start() async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((HttpRequest request) async {
      final String path = request.uri.path;
      if (path == '/api/state') {
        request.response.headers.contentType = ContentType.json;
        request.response.write(jsonEncode(<String, Object?>{
          'projects': <Object?>[
            <String, Object?>{'id': 'p1', 'name': 'PiDeck'},
          ],
          'sessions': <Object?>[
            <String, Object?>{
              'id': _sessionId,
              'projectId': 'p1',
              'title': '新会话',
              'status': 'idle',
              'createdAt': 1000,
              'updatedAt': 1000,
            },
          ],
          'runtimes': <Object?>[],
          'pendingUiRequests': <Object?>[],
        }));
        await request.response.close();
        return;
      }
      if (path == '/api/events') {
        request.response.headers.contentType =
            ContentType('text', 'event-stream', charset: 'utf-8');
        request.response.bufferOutput = false;
        request.response.write('event: state\ndata: ${jsonEncode(<String, Object?>{
          'projects': <Object?>[],
          'sessions': <Object?>[],
          'runtimes': <Object?>[],
          'pendingUiRequests': <Object?>[],
        })}\n\n');
        await request.response.flush();
        return;
      }
      if (path == '/api/sessions/$_sessionId/messages') {
        messageRequests += 1;
        if (failuresLeft > 0) {
          failuresLeft -= 1;
          request.response.statusCode = status;
          request.response.headers.contentType = ContentType.json;
          request.response.write(jsonEncode(<String, Object?>{
            'code': 'webError.internal',
            'error': 'The web service encountered an internal error',
          }));
          await request.response.close();
          return;
        }
        request.response.headers.contentType = ContentType.json;
        request.response.write(jsonEncode(<String, Object?>{
          'messages': <Object?>[
            <String, Object?>{
              'id': 'm1',
              'role': 'user',
              'content': <Object?>[
                <String, Object?>{'type': 'text', 'text': '第一条'},
              ],
            },
          ],
          'total': 1,
          'windowStart': 0,
          'truncated': false,
        }));
        await request.response.close();
        return;
      }
      if (path == '/api/sessions/$_sessionId/stream') {
        request.response.headers.contentType =
            ContentType('text', 'event-stream', charset: 'utf-8');
        request.response.bufferOutput = false;
        request.response.write(': ping\n\n');
        await request.response.flush();
        return;
      }
      request.response.statusCode = 404;
      await request.response.close();
    });
  }

  Future<void> stop() => server.close(force: true);
}

Future<bool> _waitFor(bool Function() predicate, {Duration? timeout}) async {
  final DateTime deadline =
      DateTime.now().add(timeout ?? const Duration(seconds: 5));
  while (DateTime.now().isBefore(deadline)) {
    if (predicate()) return true;
    await Future<void>.delayed(const Duration(milliseconds: 10));
  }
  return false;
}

void main() {
  late _Server server;
  late ChatRuntime runtime;

  setUp(() async {
    server = _Server();
    await server.start();
  });

  tearDown(() async {
    runtime.dispose();
    await server.stop();
  });

  ChatRuntime build() {
    final ChatRuntime created = ChatRuntime(
      client: PiDeckClient(baseUrl: server.baseUrl, token: 't'),
      sessionId: _sessionId,
      onNotice: server.notices.add,
    );
    created.historyRetryDelay = const Duration(milliseconds: 40);
    return created;
  }

  test('会话文件还没写出来：当空历史，先给引导页，再静默重试到有历史', () async {
    server.failuresLeft = 2;
    runtime = build();
    unawaited(runtime.start());

    // 第一次读失败之后：不能停在 loading，也不能弹错误卡。
    expect(await _waitFor(() => runtime.historyLoaded), isTrue);
    expect(runtime.loading, isFalse);
    expect(runtime.error, isNull);
    expect(runtime.historyItems, isEmpty);
    expect(server.notices.single, contains('还没有历史'));

    // 静默重试把历史补上（服务端前两次失败、第三次给正文）。
    expect(await _waitFor(() => runtime.historyItems.isNotEmpty), isTrue);
    expect(server.messageRequests, 3);
    expect(runtime.error, isNull);
  });

  test('非 5xx 的失败仍然按错误处理（有错误卡，不是空态）', () async {
    server.failuresLeft = 99;
    server.status = 403;
    runtime = build();
    unawaited(runtime.start());

    expect(await _waitFor(() => runtime.historyLoaded), isTrue);
    expect(runtime.loading, isFalse);
    expect(runtime.error, isNotNull);
    expect(server.notices, isEmpty);
    // 403 不该触发静默重试
    await Future<void>.delayed(const Duration(milliseconds: 200));
    expect(server.messageRequests, 1);
  });
}
