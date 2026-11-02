import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/runtime/frames.dart';

/// `tests/frames.test.ts` 的 Dart 对应用例。
///
/// 这份状态机现在是两份实现（TS + Dart），所以每一条语义都要有成对的用例；
/// 将来任何一边改语义，另一边的测试必须先红。
void main() {
  test('文本流按 delta 累积，finish 收口', () {
    final ChatReducer reducer = ChatReducer();
    reducer.apply(<String, Object?>{'type': 'start', 'messageId': 'm1'});
    expect(reducer.status, RunStatus.running);

    reducer.apply(<String, Object?>{'type': 'text-start', 'id': 't1'});
    reducer.apply(<String, Object?>{'type': 'text-delta', 'id': 't1', 'delta': '你'});
    reducer.apply(<String, Object?>{'type': 'text-delta', 'id': 't1', 'delta': '好'});

    expect(reducer.blocks.length, 1);
    expect(blocksToPlainText(reducer.blocks), '你好');

    final FrameApplyResult result = reducer.apply(<String, Object?>{'type': 'finish'});
    expect(result.finished, isTrue);
    expect(reducer.status, RunStatus.finished);
    final ChatBlock text = reducer.blocks[0];
    expect(text, isA<TextBlock>());
    expect((text as TextBlock).done, isTrue);
  });

  test('思考块与正文块互不干扰', () {
    final ChatReducer reducer = ChatReducer();
    reducer.apply(<String, Object?>{'type': 'start', 'messageId': 'm1'});
    reducer.apply(<String, Object?>{'type': 'reasoning-start', 'id': 'r1'});
    reducer.apply(<String, Object?>{'type': 'reasoning-delta', 'id': 'r1', 'delta': '先想一下'});
    reducer.apply(<String, Object?>{'type': 'reasoning-end', 'id': 'r1'});
    reducer.apply(<String, Object?>{'type': 'text-start', 'id': 't1'});
    reducer.apply(<String, Object?>{'type': 'text-delta', 'id': 't1', 'delta': '答案'});
    reducer.apply(<String, Object?>{'type': 'finish'});

    expect(reducer.blocks.length, 2);
    expect(blocksToPlainText(reducer.blocks), '答案');
    expect(blocksToPlainText(reducer.blocks, includeReasoning: true), '[思考] 先想一下\n答案');
  });

  test('工具块经历 running → ok', () {
    final ChatReducer reducer = ChatReducer();
    reducer.apply(<String, Object?>{'type': 'tool-input-start', 'toolCallId': 'c1', 'toolName': 'bash'});
    reducer.apply(<String, Object?>{
      'type': 'tool-input-available',
      'toolCallId': 'c1',
      'toolName': 'bash',
      'input': <String, Object?>{'cmd': 'ls'},
    });
    final ToolBlock tool = reducer.blocks[0] as ToolBlock;
    expect(tool.state, 'running');

    reducer.apply(<String, Object?>{'type': 'tool-output-available', 'toolCallId': 'c1', 'output': 'ok'});
    final ToolBlock updated = reducer.blocks[0] as ToolBlock;
    expect(updated.state, 'ok');
    expect(updated.input, <String, Object?>{'cmd': 'ls'});
  });

  test('工具失败标记 errorText', () {
    final ChatReducer reducer = ChatReducer();
    reducer.apply(<String, Object?>{'type': 'tool-input-start', 'toolCallId': 'c2', 'toolName': 'read'});
    reducer.apply(<String, Object?>{'type': 'tool-output-error', 'toolCallId': 'c2', 'errorText': 'ENOENT'});
    final ToolBlock tool = reducer.blocks[0] as ToolBlock;
    expect(tool.state, 'error');
    expect(tool.errorText, 'ENOENT');
  });

  test('error 帧把整体置为失败并收口未结束的块', () {
    final ChatReducer reducer = ChatReducer();
    reducer.apply(<String, Object?>{'type': 'start', 'messageId': 'm1'});
    reducer.apply(<String, Object?>{'type': 'text-start', 'id': 't1'});
    reducer.apply(<String, Object?>{'type': 'text-delta', 'id': 't1', 'delta': '半截'});
    reducer.apply(<String, Object?>{'type': 'error', 'errorText': '模型超时'});
    reducer.apply(<String, Object?>{'type': 'finish'});

    expect(reducer.status, RunStatus.error);
    expect(reducer.error, '模型超时');
    final ChatBlock block = reducer.blocks[0];
    expect(block, isA<TextBlock>());
    expect((block as TextBlock).done, isTrue);
  });

  test('markAborted 把运行中状态置为 aborted', () {
    final ChatReducer reducer = ChatReducer();
    reducer.apply(<String, Object?>{'type': 'start', 'messageId': 'm1'});
    reducer.markAborted();
    expect(reducer.status, RunStatus.aborted);
  });

  test('新一轮 start 会清空上一轮的块', () {
    final ChatReducer reducer = ChatReducer();
    reducer.apply(<String, Object?>{'type': 'start', 'messageId': 'm1'});
    reducer.apply(<String, Object?>{'type': 'text-start', 'id': 't1'});
    reducer.apply(<String, Object?>{'type': 'text-delta', 'id': 't1', 'delta': '旧'});
    reducer.apply(<String, Object?>{'type': 'finish'});

    reducer.apply(<String, Object?>{'type': 'start', 'messageId': 'm2'});
    expect(reducer.blocks.length, 0);
    expect(reducer.messageId, 'm2');
  });

  test('未知帧不抛异常，原样上报', () {
    final ChatReducer reducer = ChatReducer();
    final FrameApplyResult result =
        reducer.apply(<String, Object?>{'type': 'quantum-entangle', 'payload': 1});
    expect(result.changed, isFalse);
    expect(result.unknownFrame?['type'], 'quantum-entangle');
  });

  test('safeParseFrame 拒绝坏 JSON 与无 type 对象', () {
    expect(safeParseFrame('not json'), isNull);
    expect(safeParseFrame('{}'), isNull);
    expect(safeParseFrame('{"type":"finish"}')?['type'], 'finish');
  });

  test('块序列可 JSON 化：形状与 TS 的 ChatBlock 一致', () {
    final ChatReducer reducer = ChatReducer();
    reducer.apply(<String, Object?>{'type': 'start', 'messageId': 'm1'});
    reducer.apply(<String, Object?>{'type': 'text-start', 'id': 't1'});
    reducer.apply(<String, Object?>{'type': 'text-delta', 'id': 't1', 'delta': 'hi'});
    reducer.apply(<String, Object?>{'type': 'reasoning-start', 'id': 'r1'});
    reducer.apply(<String, Object?>{'type': 'tool-input-start', 'toolCallId': 'c1', 'toolName': 'bash'});
    reducer.apply(<String, Object?>{'type': 'tool-output-available', 'toolCallId': 'c1', 'output': 'ok'});

    final List<Map<String, Object?>> json =
        reducer.blocks.map((ChatBlock block) => block.toJson()).toList();
    expect(json[0]['kind'], 'text');
    expect(json[0]['id'], 't1');
    expect(json[0]['text'], 'hi');
    expect(json[1]['kind'], 'reasoning');
    expect(json[2]['kind'], 'tool');
    expect(json[2]['state'], 'ok');
    expect(json[2]['output'], 'ok');
  });

  test('clearLive 只清流式块：历史里已经有这一轮了，状态保留 finished', () {
    final ChatReducer reducer = ChatReducer();
    reducer.apply(<String, Object?>{'type': 'start', 'messageId': 'm1'});
    reducer.apply(<String, Object?>{'type': 'text-start', 'id': 't1'});
    reducer.apply(<String, Object?>{'type': 'text-delta', 'id': 't1', 'delta': 'hi'});
    reducer.apply(<String, Object?>{'type': 'finish'});
    expect(reducer.status, RunStatus.finished);
    expect(reducer.blocks, isNotEmpty);

    reducer.clearLive();
    expect(reducer.blocks, isEmpty);
    // 状态 / messageId 不动：下一次 start 才 reset
    expect(reducer.status, RunStatus.finished);
    expect(reducer.messageId, 'm1');
  });
}
