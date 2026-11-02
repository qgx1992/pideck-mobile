/// Dart 运行时的端到端自测：真开一个 HTTP 服务端，走一遍「建流 → 收帧 → 收口」。
///
/// 单元测试盯的是 reducer / parser 的语义，这里盯的是**接起来之后还能不能跑**：
/// 分片切在帧中间、SSE 里混着心跳注释、`[DONE]` 结尾、token 走 query。
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/runtime/api_client.dart';
import 'package:pideck_flutter/runtime/frames.dart';

const String _token = 'tok-123';

/// 服务端收到的请求，用来在测试里断言（不能直接 expect，handler 里抛会被吞掉）。
final List<String> _seen = <String>[];
final List<String> _authHeaders = <String>[];
final List<String> _accepts = <String>[];

Future<HttpServer> _startServer() async {
  final HttpServer server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
  server.listen((HttpRequest request) async {
    _seen.add('${request.uri.path}?${request.uri.query}');
    _authHeaders.add(request.headers.value('authorization') ?? '');
    _accepts.add(request.headers.value('accept') ?? '');
    if (request.uri.path == '/api/sessions/s1/messages') {
      request.response.headers.contentType = ContentType.json;
      request.response.write(jsonEncode(<String, Object?>{
        'messages': <Object?>[
          <String, Object?>{'id': 'm1', 'role': 'user', 'text': '你好'},
        ],
        'total': 1,
        'windowStart': 0,
        'truncated': false,
      }));
      await request.response.close();
      return;
    }
    if (request.uri.path != '/api/sessions/s1/stream') {
      request.response.statusCode = HttpStatus.notFound;
      await request.response.close();
      return;
    }
    request.response.headers.contentType =
        ContentType('text', 'event-stream', charset: 'utf-8');
    final List<String> chunks = <String>[
      ': ping\n\n',
      'data: {"type":"start","messageId":"m9"}\n\n',
      // 故意把一个帧切成两片写出去（真实网络里完全可能出现）
      'data: {"type":"text-start","id":"t1"}\n',
      '\ndata: {"type":"text-delta","id":"t1","delta":"你"}\n\n',
      'data: {"type":"text-delta","id":"t1","delta":"好"}\n\n',
      'data: {"type":"tool-input-start","toolCallId":"c1","toolName":"bash"}\n\n',
      'data: {"type":"tool-output-available","toolCallId":"c1","output":"ok"}\n\n',
      'data: {"type":"finish"}\n\n',
      'data: [DONE]\n\n',
    ];
    for (final String chunk in chunks) {
      request.response.write(chunk);
      await request.response.flush();
      await Future<void>.delayed(const Duration(milliseconds: 5));
    }
    await request.response.close();
  });
  return server;
}

void main() {
  late HttpServer server;
  late PiDeckClient client;

  setUp(() async {
    _seen.clear();
    _authHeaders.clear();
    _accepts.clear();
    server = await _startServer();
    client = PiDeckClient(baseUrl: 'http://127.0.0.1:${server.port}', token: _token);
  });

  tearDown(() async {
    client.close();
    await server.close(force: true);
  });

  test('帧流：分片穿过帧边界也能收全，[DONE] 收口成 finish', () async {
    final ChatReducer reducer = ChatReducer();
    final List<Map<String, Object?>> frames = <Map<String, Object?>>[];
    await for (final Map<String, Object?> frame in client.sessionFrames('s1')) {
      frames.add(frame);
      reducer.apply(frame);
    }

    expect(frames.map((Map<String, Object?> f) => f['type']).toList(),
        <String>['start', 'text-start', 'text-delta', 'text-delta', 'tool-input-start',
          'tool-output-available', 'finish',
          // 服务端是「finish 帧 + data: [DONE]」两个都发，[DONE] 在两边都映射成 finish 帧
          // （src/api/client.ts:587 同样如此），所以这里会多收一个，reducer 上是幂等的
          'finish']);
    expect(reducer.status, RunStatus.finished);
    expect(reducer.messageId, 'm9');
    expect(blocksToPlainText(reducer.blocks), '你好\n[工具 bash · 完成]');
    // token 必须走 query（GET 不带 Authorization，和 TS 侧同一套规则）
    expect(_seen.single, contains('token=$_token'));
    // GET 不带 Authorization：带上去浏览器就要先发 CORS 预检
    expect(_authHeaders.single, isEmpty);
    expect(_accepts.single, contains('text/event-stream'));
  });

  test('历史：getMessages 能读回消息窗口', () async {
    final MessageWindow window = await client.getMessages('s1');
    expect(window.total, 1);
    expect(window.messages.length, 1);
    expect((window.messages.first as Map<String, Object?>)['role'], 'user');
    expect(window.truncated, isFalse);
  });

  test('鉴权失败映射成 auth：401 → isAuth', () async {
    final HttpServer closed = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    closed.listen((HttpRequest request) async {
      request.response.statusCode = HttpStatus.unauthorized;
      request.response.write(jsonEncode(<String, Object?>{'error': 'bad token'}));
      await request.response.close();
    });
    final PiDeckClient bad = PiDeckClient(baseUrl: 'http://127.0.0.1:${closed.port}', token: 'x');
    try {
      await bad.getMessages('s1');
      fail('应当抛 PiDeckException');
    } on PiDeckException catch (error) {
      expect(error.isAuth, isTrue);
      expect(error.status, 401);
    } finally {
      bad.close();
      await closed.close(force: true);
    }
  });
}
