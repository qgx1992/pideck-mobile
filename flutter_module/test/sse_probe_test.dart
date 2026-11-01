import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/probe/sse_probe.dart';

void main() {
  group('SseEventParser', () {
    test('一帧被切成两块时也能拼回来', () {
      final parser = SseEventParser();
      expect(parser.feed('data: hel'), isEmpty);
      final events = parser.feed('lo\n\n');
      expect(events, hasLength(1));
      expect(events.first.data, 'hello');
    });

    test('一个 chunk 里有多帧时全部吐出来', () {
      final parser = SseEventParser();
      final events = parser.feed('data: a\n\ndata: b\n\ndata: c\n\n');
      expect(events.map((e) => e.data).toList(), ['a', 'b', 'c']);
    });

    test('CRLF 与多行 data 都按规范处理', () {
      final parser = SseEventParser();
      final events = parser.feed('event: state\r\ndata: 第一行\r\ndata: 第二行\r\n\r\n');
      expect(events, hasLength(1));
      expect(events.first.event, 'state');
      expect(events.first.data, '第一行\n第二行');
    });

    test('注释行（心跳）不产出事件', () {
      final parser = SseEventParser();
      expect(parser.feed(': keep-alive\n\n'), isEmpty);
      expect(parser.feed('data: x\n\n').single.data, 'x');
    });

    test('字段值前的一个空格被吃掉，后面不动', () {
      final parser = SseEventParser();
      expect(parser.feed('data:  两个空格\n\n').single.data, ' 两个空格');
    });
  });

  group('ProbeRecorder', () {
    test('间隔统计与帧类型分布', () {
      final recorder = ProbeRecorder();
      recorder.record(100, const SseEvent(data: '{"type":"text_delta"}'));
      recorder.record(150, const SseEvent(data: '{"type":"text_delta"}'));
      recorder.record(400, const SseEvent(data: '{"type":"finish"}'));
      recorder.record(450, const SseEvent(data: '[DONE]'));

      expect(recorder.eventCount, 4);
      expect(recorder.firstMs, 100);
      expect(recorder.gaps, [50, 250, 50]);
      expect(recorder.maxGapMs, 250);
      expect(recorder.avgGapMs, 117);
      // 中位数按排序后取中间那个：50 50 250 → 50
      expect(recorder.medianGapMs, 50);
      expect(recorder.finishMs, 400);
      expect(recorder.types['text_delta'], 2);
      expect(recorder.types['finish'], 1);
      expect(recorder.types['done'], 1);
      expect(recorder.chars, greaterThan(0));
    });

    test('非 JSON 帧按 event 名归类（加 event: 前缀，和帧类型分开）', () {
      final recorder = ProbeRecorder();
      recorder.record(10, const SseEvent(event: 'state', data: 'not-json'));
      expect(recorder.types['event:state'], 1);
    });

    test('后台期间的事件单独计数', () {
      final recorder = ProbeRecorder();
      recorder.record(10, const SseEvent(data: '{"type":"text_delta"}'));
      recorder.markBackground(true);
      recorder.record(20, const SseEvent(data: '{"type":"text_delta"}'));
      recorder.record(30, const SseEvent(data: '{"type":"text_delta"}'));
      recorder.markBackground(false);
      recorder.record(40, const SseEvent(data: '{"type":"text_delta"}'));
      expect(recorder.eventsWhileBackground, 2);
    });

    test('没有事件时统计不炸', () {
      final recorder = ProbeRecorder();
      expect(recorder.firstMs, isNull);
      expect(recorder.medianGapMs, isNull);
      expect(recorder.maxGapMs, isNull);
      expect(recorder.typeSummary, '（无帧）');
    });
  });

  test('fmtMs 按量级切换单位', () {
    expect(fmtMs(null), '—');
    expect(fmtMs(340), '340ms');
    expect(fmtMs(1234), '1.23s');
  });
}
