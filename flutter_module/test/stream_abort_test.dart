import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/runtime/api_client.dart';
import 'package:pideck_flutter/runtime/sse.dart';
import 'package:pideck_flutter/runtime/stream_pump.dart';

/// 回归：`async*` 生成器挂在 `await for` 等字节时，`Subscription.cancel()`
/// 的 Future 永远不完成（生成器只在 yield 点处理取消）。没有 `abort` 的话
/// `kick()` 是假动作：`run()` 永不返回、连接也没断，回前台就永远换不上新连接。
void main() {
  test('kick 能收掉一条「连上但一个字节都不发」的 SSE 流', () async {
    final HttpServer server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    int opened = 0;
    server.listen((HttpRequest request) async {
      if (request.uri.path != '/s') {
        request.response.statusCode = 404;
        await request.response.close();
        return;
      }
      opened += 1;
      request.response.headers.contentType = ContentType('text', 'event-stream', charset: 'utf-8');
      // 关键：先 flush 一个字节再吊住 —— 服务端要像真的 SSE 那样不 close。
      request.response.bufferOutput = false;
      request.response.write(': ping\n\n');
      await request.response.flush();
    });

    final PiDeckClient client =
        PiDeckClient(baseUrl: 'http://127.0.0.1:${server.port}', token: 't');
    final StreamPump<SseEvent> pump = StreamPump<SseEvent>(
      open: (void Function() onActivity, Future<void> abort) =>
          client.streamSse('/s', onActivity: onActivity, abort: abort),
      idleTimeout: const Duration(seconds: 30),
    );
    bool returned = false;
    final Future<void> done = pump.run((SseEvent event) {}).then((_) => returned = true);
    await Future<void>.delayed(const Duration(milliseconds: 200));
    expect(opened, 1);

    pump.kick();
    await done.timeout(const Duration(seconds: 3), onTimeout: () {});
    expect(returned, isTrue, reason: 'kick 之后 run() 必须返回，否则重连不了');
    expect(pump.kicked, isTrue);

    client.close();
    await server.close(force: true);
  });
}
