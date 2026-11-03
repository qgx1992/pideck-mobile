/// 「从后台回来内容不刷新」的回归自测（0.2.18）。
///
/// 现场：手机锁屏放一会儿再回来，聊天页停在进后台前那一屏 —— 那一轮其实早跑完了，
/// 帧却永远收不到（App 被冻结时 SSE 连接是死的但没人报错）。
///
/// 正确行为是回前台**自己动手**，三件事一起做：
///   1. 换一条帧流（旧的已经僵死，不能等 50s 看门狗）；
///   2. 重拉历史（后台期间可能跑完了、落了盘）；
///   3. 状态仓里已经没有这个会话的运行目标 → 本地收口，别停在「停止」+ 转个不停的计时器。
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/runtime/api_client.dart';
import 'package:pideck_flutter/runtime/chat_runtime.dart';
import 'package:pideck_flutter/runtime/frames.dart';
import 'package:pideck_flutter/runtime/state_store.dart';

const String _sessionId = 's1';

class _Server {
  late HttpServer server;

  /// `/api/state` 里这个会话还有没有运行目标（= 服务端认为它还在跑）。
  bool runningTarget = true;

  /// 历史请求的次数与状态码（用来验证「回前台重拉了一次」）。
  int messageRequests = 0;
  int messageStatus = 200;

  /// 帧流连接次数（收起来的 response 用来往页面推帧）。
  int streamConnections = 0;
  final List<HttpResponse> _streams = <HttpResponse>[];

  String get baseUrl => 'http://127.0.0.1:${server.port}';

  Future<void> start() async {
    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((HttpRequest request) async {
      final String path = request.uri.path;
      if (path == '/api/state') {
        _json(request, <String, Object?>{
          'projects': <Object?>[
            <String, Object?>{'id': 'p1', 'name': 'PiDeck'},
          ],
          'sessions': <Object?>[
            <String, Object?>{
              'id': _sessionId,
              'projectId': 'p1',
              'title': '被封住的会话',
              'status': runningTarget ? 'running' : 'idle',
              'createdAt': 1000,
              'updatedAt': 2000,
            },
          ],
          'runtimes': <Object?>[
            if (runningTarget)
              <String, Object?>{
                'sessionId': _sessionId,
                'agentId': 'a1',
                'runtimeGeneration': 3,
              },
          ],
          'pendingUiRequests': <Object?>[],
        });
        return;
      }
      if (path == '/api/events') {
        request.response.headers.contentType =
            ContentType('text', 'event-stream', charset: 'utf-8');
        // 必须关掉攒包：流式连接永远不 close，攒着就一帧都收不到。
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
        if (messageStatus != 200) {
          request.response.statusCode = messageStatus;
          request.response.headers.contentType = ContentType.json;
          request.response.write(jsonEncode(<String, Object?>{
            'code': 'webError.forbidden',
            'error': 'forbidden',
          }));
          await request.response.close();
          return;
        }
        _json(request, <String, Object?>{
          'messages': <Object?>[
            <String, Object?>{
              'id': 'm1',
              'role': 'user',
              'content': <Object?>[
                <String, Object?>{'type': 'text', 'text': '后台跑完的那一轮'},
              ],
            },
          ],
          'total': 1,
          'windowStart': 0,
          'truncated': false,
        });
        return;
      }
      if (path == '/api/sessions/$_sessionId/stream') {
        streamConnections += 1;
        request.response.headers.contentType =
            ContentType('text', 'event-stream', charset: 'utf-8');
        request.response.bufferOutput = false;
        request.response.write(': ping\n\n');
        await request.response.flush();
        _streams.add(request.response);
        return;
      }
      request.response.statusCode = 404;
      await request.response.close();
    });
  }

  void _json(HttpRequest request, Map<String, Object?> body) {
    request.response.headers.contentType = ContentType.json;
    request.response.write(jsonEncode(body));
    unawaited(request.response.close());
  }

  /// 往所有活着的帧流推一帧（模拟服务端发货）。
  Future<void> pushFrame(Map<String, Object?> frame) async {
    for (final HttpResponse response in List<HttpResponse>.from(_streams)) {
      try {
        response.write('data: ${jsonEncode(frame)}\n\n');
        await response.flush();
      } on Object {
        _streams.remove(response);
      }
    }
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
    return ChatRuntime(
      client: PiDeckClient(baseUrl: server.baseUrl, token: 't'),
      sessionId: _sessionId,
    );
  }

  test('回前台：换帧流 + 重拉历史 + 把已经跑完的那一轮收口', () async {
    runtime = build();
    unawaited(runtime.start());
    expect(await _waitFor(() => runtime.historyLoaded), isTrue);
    expect(await _waitFor(() => runtime.storeStatus == StoreStatus.live), isTrue);

    // 服务端推一帧 start，本地进入 running（这就是用户离开前那一屏）。
    await server.pushFrame(<String, Object?>{'type': 'start', 'sessionId': _sessionId});
    expect(await _waitFor(() => runtime.status == RunStatus.running), isTrue);

    final int messagesBefore = server.messageRequests;
    final int streamsBefore = server.streamConnections;

    // 后台期间那一轮跑完了：服务端已经没有这个会话的运行目标，
    // 但 finished 帧早就丢了（连接死了，没人报错）。
    server.runningTarget = false;
    await runtime.resume();

    // 收口后与「真的收到 DONE」完全一致（`finished`），关键是不能再停在 running：
    // 停在 running 的表现就是按钮一直是「停止」、计时器一直转。
    expect(runtime.status, isNot(RunStatus.running), reason: '状态仓说没在跑，本地必须收口');
    expect(server.messageRequests, greaterThan(messagesBefore), reason: '要重拉历史');
    expect(
      await _waitFor(() => server.streamConnections > streamsBefore),
      isTrue,
      reason: '旧连接是僵尸，必须主动换一条（不能等 50s 看门狗）',
    );
  });

  test('回前台时历史请求失败：保留已有内容，不把屏幕换成错误卡', () async {
    runtime = build();
    unawaited(runtime.start());
    expect(await _waitFor(() => runtime.historyItems.isNotEmpty), isTrue);

    server.messageStatus = 403;
    await runtime.resume();

    expect(runtime.historyItems, isNotEmpty, reason: '看得见的内容不能被一次失败清掉');
    expect(runtime.error, isNull, reason: '刚回前台网络抖一下不该满屏报错');
  });
}
