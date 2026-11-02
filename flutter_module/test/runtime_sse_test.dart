import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/runtime/sse.dart';

/// `tests/sse.test.ts` 的 Dart 对应用例：两边解析结果必须一致，
/// 否则同一段网络字节在网页版和原生页会解析成不同的事件流。
void main() {
  test('解析单条 data 帧并忽略心跳注释', () {
    final List<SseEvent> events = parseSseText(': ping\n\ndata: {"type":"finish"}\n\n');
    expect(events.length, 1);
    expect(events[0].event, 'message');
    expect(events[0].data, '{"type":"finish"}');
  });

  test('识别 event 字段与多行 data', () {
    final List<SseEvent> events = parseSseText('event: state\ndata: {"a":1,\ndata: "b":2}\n\n');
    expect(events.length, 1);
    expect(events[0].event, 'state');
    expect(events[0].data, '{"a":1,\n"b":2}');
  });

  test('一个分片里包含多条帧', () {
    final List<SseEvent> events = parseSseText('data: 1\n\ndata: 2\n\ndata: [DONE]\n\n');
    expect(events.map((SseEvent e) => e.data).toList(), <String>['1', '2', '[DONE]']);
  });

  test('被切断的帧在下一个分片续上', () {
    final SseParser parser = SseParser();
    expect(parser.push('data: {"ty'), isEmpty);
    expect(parser.push('pe":"fin'), isEmpty);
    final List<SseEvent> events = parser.push('ish"}\n\n');
    expect(events.length, 1);
    expect(events[0].data, '{"type":"finish"}');
  });

  test('CRLF 与单独 CR 都能分行', () {
    expect(parseSseText('data: a\r\n\r\n').length, 1);
    expect(parseSseText('data: a\r\r').length, 1);
  });

  test('末尾孤立 CR 不误判为换行', () {
    final SseParser parser = SseParser();
    expect(parser.push('data: a\r'), isEmpty);
    final List<SseEvent> events = parser.push('\n\n');
    expect(events.length, 1);
    expect(events[0].data, 'a');
  });

  test('flush 吐出没有尾随空行的残留数据', () {
    final SseParser parser = SseParser();
    parser.push('data: tail');
    final List<SseEvent> events = parser.flush();
    expect(events.length, 1);
    expect(events[0].data, 'tail');
  });

  test('id / retry 字段被保留，注释不产生事件', () {
    final List<SseEvent> events = parseSseText('id: 7\nretry: 3000\n: comment\ndata: x\n\n');
    expect(events.length, 1);
    expect(events[0].id, '7');
    expect(events[0].retry, 3000);
  });

  test('BOM 被剥离', () {
    final List<SseEvent> events = parseSseText('\uFEFFdata: x\n\n');
    expect(events[0].data, 'x');
  });

  test('空 data 行等价于空载荷（保留事件）', () {
    final List<SseEvent> events = parseSseText('data:\n\n');
    expect(events.length, 1);
    expect(events[0].data, '');
  });

  test('UTF-8 多字节字符被分片切断仍能正确解码', () async {
    // Dart 侧不自己写解码器：`utf8.decoder` 是有状态的 StreamTransformer，
    // 这里把「逐字节喂入」这条最坏情况的路径固定成用例。
    final List<int> bytes = utf8.encode('你好好好');
    final StreamController<List<int>> controller = StreamController<List<int>>();
    final Future<String> joined = utf8.decoder.bind(controller.stream).join();
    for (final int byte in bytes) {
      controller.add(<int>[byte]);
    }
    await controller.close();
    expect(await joined, '你好好好');
  });
}
