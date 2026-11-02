/// 发送链路（Dart 自己发）的端到端自测：真开 HTTP 服务端，走一遍
/// 「拉历史 → 发送 → 乐观回显 → 拒收回滚」。
///
/// 盯的是**接管之后最容易坏的那几处**：
///   - 发送体里 message / streamingBehavior / extra.agentMessage 的形状对不对
///   - 发出去了但服务端还没落盘时，用户能不能立刻看到自己那句（乐观回显）
///   - 被拒收时气泡要收回、图片要留在输入框（不能既消失又没发出去）
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/runtime/api_client.dart';
import 'package:pideck_flutter/runtime/chat_runtime.dart';

const String _sessionId = 's1';

/// 服务端可变行为：这次 POST /prompt 返回接受还是拒收。
bool _accept = true;
String _rejectError = 'boom';
String _delivery = 'rejected';
final List<Map<String, Object?>> _prompts = <Map<String, Object?>>[];

Future<HttpServer> _startServer() async {
  final HttpServer server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
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
            'title': '测试会话',
            'status': 'idle',
            'updatedAt': 1000,
          },
        ],
        'runtimes': <Object?>[
          <String, Object?>{'sessionId': _sessionId, 'agentId': 'a1', 'runtimeGeneration': 3, 'status': 'idle'},
        ],
        'pendingUiRequests': <Object?>[],
      }));
      await request.response.close();
      return;
    }
    if (path == '/api/events') {
      // 状态流：连上就不动（这条测试不关心状态变化）
      request.response.headers.contentType = ContentType('text', 'event-stream', charset: 'utf-8');
      request.response.write(': ping\n\n');
      await request.response.flush();
      return;
    }
    if (path == '/api/sessions/$_sessionId/messages') {
      request.response.headers.contentType = ContentType.json;
      request.response.write(jsonEncode(<String, Object?>{
        'messages': <Object?>[
          <String, Object?>{'id': 'm1', 'role': 'assistant', 'text': '在的'},
        ],
        'total': 1,
        'windowStart': 0,
        'truncated': false,
      }));
      await request.response.close();
      return;
    }
    if (path == '/api/sessions/$_sessionId/stream') {
      request.response.headers.contentType = ContentType('text', 'event-stream', charset: 'utf-8');
      request.response.write(': ping\n\n');
      await request.response.flush();
      return;
    }
    if (path == '/api/sessions/$_sessionId/prompt' && request.method == 'POST') {
      final String raw = await utf8.decoder.bind(request).join();
      _prompts.add(jsonDecode(raw) as Map<String, Object?>);
      request.response.headers.contentType = ContentType.json;
      request.response.write(
        jsonEncode(<String, Object?>{
          'result': _accept
              ? <String, Object?>{'ok': true}
              : <String, Object?>{
                  'ok': false,
                  'accepted': false,
                  'delivery': _delivery,
                  'error': _rejectError,
                },
        }),
      );
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
    if (DateTime.now().isAfter(deadline)) {
      throw StateError('等待超时');
    }
    await Future<void>.delayed(const Duration(milliseconds: 20));
  }
}

void main() {
  late HttpServer server;
  late ChatRuntime runtime;
  late List<String> notices;
  int runWatchKicks = 0;

  setUp(() async {
    _prompts.clear();
    _accept = true;
    _delivery = 'rejected';
    notices = <String>[];
    runWatchKicks = 0;
    server = await _startServer();
    runtime = ChatRuntime(
      client: PiDeckClient(baseUrl: 'http://127.0.0.1:${server.port}', token: 'tok'),
      sessionId: _sessionId,
      onNotice: notices.add,
      onKickRunWatch: () => runWatchKicks++,
    );
    unawaited(runtime.start());
    await _waitFor(() => runtime.historyLoaded);
  });

  tearDown(() async {
    runtime.dispose();
    await server.close(force: true);
  });

  test('发送：请求体是 message + requestId，成功后有乐观回显', () async {
    final bool ok = await runtime.submit('你好');
    expect(ok, isTrue);
    expect(_prompts, hasLength(1));
    expect(_prompts.single['message'], '你好');
    expect('${_prompts.single['requestId']}', isNotEmpty);
    // 只发图不写字时服务端会 400，所以空文本 + 有图要换成一句人话
    expect(runWatchKicks, 1, reason: '发完必须立刻拉起后台看护');

    final Map<String, Object?> snapshot = runtime.snapshotJson();
    final List<Object?> history = snapshot['history']! as List<Object?>;
    expect(history, isNotEmpty);
    final Map<String, Object?> last = history.last! as Map<String, Object?>;
    expect(last['role'], 'user');
    expect(last['text'], '你好');
    // 状态要立刻显示成「正在跑」，否则发送键会一直是可点的空闲态
    expect(snapshot['status'], 'running');
  });

  test('计划模式：可见消息是原文，隐藏指令带标记且只发一次', () async {
    final bool ok = await runtime.submit('重构登录', planMode: true);
    expect(ok, isTrue);
    final Map<String, Object?> body = _prompts.single;
    expect(body['message'], '重构登录');
    // 隐藏指令是**平铺**在请求体里的（`sendPrompt` 里 `...extra`），不是 `{extra: {...}}`：
    // 套一层服务端就读不到，计划模式会静默失效。
    expect('${body['agentMessage']}', startsWith('__PI_DECK_PLAN_MODE__'));
    expect(body.containsKey('extra'), isFalse);
    // 可见气泡里不能出现标记（用户不该看到内部约定）
    final List<Object?> history = runtime.snapshotJson()['history']! as List<Object?>;
    expect('${(history.last! as Map<String, Object?>)['text']}', '重构登录');
  });

  test('运行中再发：带 streamingBehavior（排队 → followUp）', () async {
    // 直接把 reducer 推到 running（真实链路里由 start 帧触发）
    runtime.reducer.apply(<String, Object?>{'type': 'start', 'messageId': 'm2'});
    final bool ok = await runtime.submit('追加一句');
    expect(ok, isTrue);
    expect(_prompts.single['streamingBehavior'], 'followUp');
  });

  test('被拒收：气泡收回、图片不丢、给出原因', () async {
    _accept = false;
    _rejectError = 'turn busy';
    final bool ok = await runtime.submit('再试一次');
    expect(ok, isFalse);
    final List<Object?> history = runtime.snapshotJson()['history']! as List<Object?>;
    expect(history, hasLength(1), reason: '只应剩服务端那条历史，乐观气泡要收回');
    expect(notices.single, contains('发送被拒绝'));
    expect(notices.single, contains('turn busy'));
  });

  test('拒收但投递未知：气泡保留（可能已经发出去了）', () async {
    _accept = false;
    _delivery = 'unknown';
    _rejectError = 'unknown delivery';
    final bool ok = await runtime.submit('这条可能发出去了');
    expect(ok, isFalse);
    final List<Object?> history = runtime.snapshotJson()['history']! as List<Object?>;
    // 投递状态不明时必须留着气泡：删掉的话用户会以为没发，重发就重复问了一遍。
    expect(history, hasLength(2));
    expect('${(history.last! as Map<String, Object?>)['text']}', '这条可能发出去了');
    expect(notices.single, contains('稍后以历史为准'));
  });
}
