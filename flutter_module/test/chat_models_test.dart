import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/chat/chat_models.dart';

void main() {
  group('ChatSnapshot.fromJson', () {
    test('解析完整快照', () {
      final ChatSnapshot snapshot = ChatSnapshot.fromJson(<String, Object?>{
        'sessionId': 's1',
        'title': '调试 SSE',
        'status': 'running',
        'statusText': '正在生成',
        'loading': false,
        'history': <Object?>[
          <String, Object?>{'kind': 'message', 'id': 'm1', 'role': 'user', 'text': '你好', 'time': '12:03'},
          <String, Object?>{
            'kind': 'message',
            'id': 'm2',
            'role': 'tool',
            'text': '',
            'toolName': 'bash',
            'toolState': 'ok',
            'toolDetail': 'ls',
          },
        ],
        'live': <String, Object?>{
          'messageId': 'live1',
          'blocks': <Object?>[
            <String, Object?>{'kind': 'reasoning', 'id': 'r1', 'text': '想一下', 'done': true},
            <String, Object?>{'kind': 'text', 'id': 't1', 'text': '答', 'done': false},
            <String, Object?>{
              'kind': 'tool',
              'toolCallId': 'c1',
              'toolName': 'read',
              'state': 'running',
              'input': <String, Object?>{'path': 'a.ts'},
            },
          ],
        },
        'pending': <String, Object?>{
          'requestId': 'q1',
          'method': 'select',
          'title': '选一个',
          'options': <Object?>['a', 'b'],
          'allowOther': true,
          'multiple': false,
        },
        'perf': <String, Object?>{'ttftMs': 420, 'streamingTps': 12.5, 'outputTokens': 88},
      });

      expect(snapshot.sessionId, 's1');
      expect(snapshot.title, '调试 SSE');
      expect(snapshot.isRunning, isTrue);
      expect(snapshot.hasContent, isTrue);
      expect(snapshot.history, hasLength(2));
      expect(snapshot.history[0].isUser, isTrue);
      expect(snapshot.history[1].isTool, isTrue);
      expect(snapshot.history[1].toolState, 'ok');

      expect(snapshot.live!.blocks, hasLength(3));
      expect(snapshot.live!.blocks[0].isReasoning, isTrue);
      expect(snapshot.live!.blocks[0].done, isTrue);
      expect(snapshot.live!.blocks[1].done, isFalse);
      expect(snapshot.live!.blocks[2].isTool, isTrue);
      expect(snapshot.live!.blocks[2].state, 'running');

      expect(snapshot.pending!.requestId, 'q1');
      expect(snapshot.pending!.options, <String>['a', 'b']);
      expect(snapshot.pending!.allowOther, isTrue);
      expect(snapshot.pending!.multiple, isFalse);

      expect(snapshot.perf!.ttftMs, 420);
      expect(snapshot.perf!.streamingTps, 12.5);
      expect(snapshot.perf!.outputTokens, 88);
      expect(snapshot.perf!.averageTps, isNull);
    });

    test('缺字段时给默认值，不抛', () {
      final ChatSnapshot snapshot = ChatSnapshot.fromJson(<String, Object?>{});
      expect(snapshot.sessionId, '');
      expect(snapshot.status, 'idle');
      expect(snapshot.isRunning, isFalse);
      expect(snapshot.hasContent, isFalse);
      expect(snapshot.history, isEmpty);
      expect(snapshot.live, isNull);
      expect(snapshot.pending, isNull);
      expect(snapshot.perf, isNull);
      expect(snapshot.error, isNull);
    });

    test('块 key：工具按 callId，其余按 id', () {
      const ChatBlockView tool = ChatBlockView(kind: 'tool', toolCallId: 'c9');
      const ChatBlockView text = ChatBlockView(kind: 'text', id: 't9');
      expect(tool.key, 'tool:c9');
      expect(text.key, 'text:t9');
    });

    test('perf 的字符串数字也能解析', () {
      final ChatPerf perf = ChatPerf.fromJson(<String, Object?>{
        'ttftMs': '1200',
        'streamingTps': '9.75',
        'totalMs': 3000.4,
      });
      expect(perf.ttftMs, 1200);
      expect(perf.streamingTps, 9.75);
      expect(perf.totalMs, 3000);
    });
  });

  group('batch_ask 提交格式', () {
    final List<ChatBatchQuestion> questions = <ChatBatchQuestion>[
      ChatBatchQuestion.fromJson(<String, Object?>{
        'id': 'q1',
        'question': '用哪个数据库',
        'type': 'select',
        'options': <Object?>['pg', 'sqlite'],
      }),
      ChatBatchQuestion.fromJson(<String, Object?>{
        'id': 'q2',
        'question': '要多选',
        'type': 'multi_select',
        'options': <Object?>['a', 'b'],
      }),
      ChatBatchQuestion.fromJson(<String, Object?>{
        'id': 'q3',
        'question': '确认吗',
        'type': 'confirm',
      }),
    ];

    test('multi_select 的 type 能识别成多选', () {
      expect(questions[0].multiple, isFalse);
      expect(questions[1].multiple, isTrue);
      expect(questions[2].kind, 'confirm');
    });

    test('序列化成与 Web 相同的 {answers:[...]}', () {
      final String payload = batchAnswerPayload(questions, <String, Object?>{
        'q1': 'pg',
        'q2': <String>['a', 'b'],
        'q3': true,
      });
      expect(
        payload,
        '{"answers":['
        '{"id":"q1","type":"select","value":"pg","label":"pg","wasCustom":false},'
        '{"id":"q2","type":"multi_select","value":["a","b"],"label":"a、b","wasCustom":false},'
        '{"id":"q3","type":"confirm","value":true,"label":"是","wasCustom":false}]}',
      );
    });

    test('未回答的题 value 是 null，留空是空串（两者不能合并）', () {
      final String payload = batchAnswerPayload(questions, <String, Object?>{'q1': ''});
      expect(payload.contains('{"id":"q1","type":"select","value":"","label":"","wasCustom":false}'), isTrue);
      expect(payload.contains('{"id":"q2","type":"multi_select","value":null,"label":"","wasCustom":false}'), isTrue);
    });
  });

  group('previewOf', () {
    test('字符串原样，对象走 JSON，超长截断', () {
      expect(previewOf('hello'), 'hello');
      expect(previewOf(<String, Object?>{'a': 1}), '{"a":1}');
      expect(previewOf('x' * 20, limit: 5), 'xxxxx…');
      expect(previewOf(null), '');
    });
  });

  group('长按菜单', () {
    test('解析 messageMenus，并按角色取用', () {
      final ChatSnapshot snapshot = ChatSnapshot.fromJson(<String, Object?>{
        'sessionId': 's1',
        'messageMenus': <String, Object?>{
          'user': <Object?>[
            <String, Object?>{'id': 'copy', 'label': '复制文本'},
            <String, Object?>{'id': 'edit', 'label': '编辑后重发'},
            <String, Object?>{'id': 'delete', 'label': '删除', 'danger': true},
          ],
          'assistant': <Object?>[
            <String, Object?>{'id': 'copy', 'label': '复制文本'},
          ],
        },
      });
      expect(snapshot.menusFor('user').length, 3);
      expect(snapshot.menusFor('user').last.danger, isTrue);
      expect(snapshot.menusFor('assistant').single.id, 'copy');
    });

    test('工具条不给菜单，缺字段回空列表', () {
      final ChatSnapshot snapshot = ChatSnapshot.fromJson(<String, Object?>{
        'sessionId': 's1',
        'messageMenus': <String, Object?>{
          'user': <Object?>[
            <String, Object?>{'id': 'copy', 'label': '复制文本'},
          ],
        },
      });
      expect(snapshot.menusFor('tool'), isEmpty);
      expect(snapshot.menusFor('assistant'), isEmpty);
      expect(ChatSnapshot.fromJson(<String, Object?>{}).menusFor('user'), isEmpty);
    });
  });
}
