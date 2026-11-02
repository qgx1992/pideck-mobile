import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/chat/chat_models.dart';
import 'package:pideck_flutter/runtime/history_model.dart';

/// `app/tests/flutter-chat.test.ts` 里 `historyItemsOf` 那组用例的 Dart 对照，
/// 外加 Dart 侧的边界用例（2000 字截断、图片去重、JSON 形状回环）。
///
/// 两份实现（TS + Dart）必须同语义：任何一边改规则，这里的用例要先红。
void main() {
  test('用户消息取正文、id 与补零的 24 小时钟点', () {
    // 用本地时间构造时间戳，这样断言不随测试机时区变化
    final int ts = DateTime(2026, 1, 2, 3, 4).millisecondsSinceEpoch;

    final List<Map<String, Object?>> items = historyItemsOf(<Object?>[
      <String, Object?>{
        'id': 'm1',
        'role': 'user',
        'parts': <Object?>[
          <String, Object?>{'type': 'text', 'text': '你好'},
        ],
        'timestamp': ts,
      },
    ]);

    expect(items.length, 1);
    expect(items.single['kind'], 'message');
    expect(items.single['id'], 'm1');
    expect(items.single['role'], 'user');
    expect(items.single['text'], '你好');
    expect(items.single['thinking'], '');
    expect(items.single['time'], '03:04');
    expect(items.single['images'], isEmpty);
    expect(items.single['toolName'], '');
    expect(items.single['toolState'], '');
    expect(items.single['toolDetail'], '');
  });

  test('assistant 的 <thinking> 标签从正文里剥出来（大小写不敏感）', () {
    final List<Map<String, Object?>> items = historyItemsOf(<Object?>[
      <String, Object?>{
        'id': 'm2',
        'role': 'assistant',
        'parts': <Object?>[
          <String, Object?>{'type': 'text', 'text': '<THINKING>先想想</thinking>答案'},
        ],
      },
    ]);

    expect(items.single['text'], '答案');
    expect(items.single['thinking'], '先想想');
  });

  test('独立 thinking 字段与标签内容都保留，按空行拼起来', () {
    final List<Map<String, Object?>> items = historyItemsOf(<Object?>[
      <String, Object?>{
        'id': 'm3',
        'role': 'assistant',
        'thinking': '字段里的',
        'text': '<thinking>标签里的</thinking>正文',
      },
    ]);

    expect(items.single['thinking'], '字段里的\n\n标签里的');
    expect(items.single['text'], '正文');
  });

  test('工具消息三种 state：running / ok / error（isError 优先于 status）', () {
    final List<Map<String, Object?>> items = historyItemsOf(<Object?>[
      <String, Object?>{
        'id': 't-ok',
        'role': 'tool',
        'parts': <Object?>[
          <String, Object?>{'type': 'text', 'text': '命令输出'},
        ],
        'meta': <String, Object?>{'toolName': 'bash', 'status': 'ok', 'detailText': 'ls -la'},
      },
      <String, Object?>{
        'id': 't-running',
        'role': 'tool',
        'meta': <String, Object?>{'toolName': 'grep', 'status': 'running'},
      },
      <String, Object?>{
        'id': 't-error',
        'role': 'tool',
        'parts': <Object?>[
          <String, Object?>{'type': 'text', 'text': 'errorText: 权限不足'},
        ],
        'meta': <String, Object?>{
          'toolName': 'read',
          'status': 'ok',
          'isError': true,
          'detailText': 'cat /root/x',
        },
      },
      <String, Object?>{
        'id': 't-status-error',
        'role': 'tool',
        'meta': <String, Object?>{'status': 'error'},
      },
    ]);

    expect(items.length, 4);

    expect(items[0]['role'], 'tool');
    expect(items[0]['text'], '');
    expect(items[0]['thinking'], '');
    expect(items[0]['images'], isEmpty);
    expect(items[0]['toolName'], 'bash');
    expect(items[0]['toolState'], 'ok');
    expect(items[0]['toolDetail'], 'ls -la\n\n命令输出');

    expect(items[1]['toolName'], 'grep');
    expect(items[1]['toolState'], 'running');
    // detailText 缺失时只留正文；正文也没有就整体是空串
    expect(items[1]['toolDetail'], '');

    // isError === true 优先，即使 status 是 ok
    expect(items[2]['toolState'], 'error');
    expect(items[2]['toolDetail'], 'cat /root/x\n\nerrorText: 权限不足');

    // 没有 toolName 时退回 "tool"；status === "error" 也判成 error
    expect(items[3]['toolName'], 'tool');
    expect(items[3]['toolState'], 'error');
  });

  test('工具详情超过 2000 字按字符数截断', () {
    final List<Map<String, Object?>> items = historyItemsOf(<Object?>[
      <String, Object?>{
        'id': 't4',
        'role': 'tool',
        'meta': <String, Object?>{'toolName': 'bash', 'status': 'ok', 'detailText': 'x' * 5000},
      },
    ]);

    final String detail = items.single['toolDetail']! as String;
    expect(detail.length, 2000);
    expect(detail, 'x' * 2000);
  });

  test('正文/思考/图片全空的条目被跳过，role=system 也被跳过', () {
    final List<Map<String, Object?>> items = historyItemsOf(<Object?>[
      <String, Object?>{
        'id': 's',
        'role': 'system',
        'parts': <Object?>[
          <String, Object?>{'type': 'text', 'text': '系统提示'},
        ],
      },
      // parts 是空数组
      <String, Object?>{'id': 'e', 'role': 'assistant', 'parts': <Object?>[]},
      // 字段全缺
      <String, Object?>{'id': 'e2', 'role': 'assistant'},
      // 只有空白
      <String, Object?>{'id': 'blank', 'role': 'assistant', 'text': '   ', 'thinking': '  '},
      // 只有一张图片、没有正文：要保留
      <String, Object?>{
        'id': 'img',
        'role': 'user',
        'images': <Object?>[
          <String, Object?>{'type': 'image', 'data': 'data:image/png;base64,AAAA'},
        ],
      },
      <String, Object?>{
        'id': 'ok',
        'role': 'assistant',
        'parts': <Object?>[
          <String, Object?>{'type': 'text', 'text': '有内容'},
        ],
      },
    ]);

    expect(items.map((Map<String, Object?> item) => item['id']), <String>['img', 'ok']);
  });

  test('没有 id 时退回 entryId，都没有时为 null', () {
    final List<Map<String, Object?>> items = historyItemsOf(<Object?>[
      <String, Object?>{
        'entryId': 'e1',
        'role': 'assistant',
        'parts': <Object?>[
          <String, Object?>{'type': 'text', 'text': 'x'},
        ],
      },
      <String, Object?>{
        'id': '',
        'entryId': 'e2',
        'role': 'assistant',
        'parts': <Object?>[
          <String, Object?>{'type': 'text', 'text': 'y'},
        ],
      },
      <String, Object?>{
        'role': 'assistant',
        'parts': <Object?>[
          <String, Object?>{'type': 'text', 'text': 'z'},
        ],
      },
    ]);

    expect(items[0]['id'], 'e1');
    expect(items[1]['id'], 'e2');
    expect(items[2]['id'], isNull);
  });

  test('多张图片 sources 收集：parts + images、裸 base64 补前缀、保序去重', () {
    const String png1 = 'data:image/png;base64,AAAA';
    const String png2 = 'data:image/jpeg;base64,BBBB';
    final String bare = 'A' * 80; // > 64 个字符的裸 base64
    final String bareUrl = 'data:image/png;base64,$bare';

    final List<Map<String, Object?>> items = historyItemsOf(<Object?>[
      <String, Object?>{
        'id': 'img',
        'role': 'user',
        'parts': <Object?>[
          <String, Object?>{'type': 'image', 'data': png1},
          <String, Object?>{'type': 'image', 'data': png2},
          // 同一张重复出现，去重后只留一份
          <String, Object?>{'type': 'image', 'data': png1},
        ],
        'images': <Object?>[
          <String, Object?>{'type': 'image', 'data': bare, 'mimeType': 'image/png'},
          // 与 parts 里重复
          <String, Object?>{'data': png2},
        ],
      },
    ]);

    expect(items.single['text'], '');
    expect(items.single['images'], <String>[png1, png2, bareUrl]);
  });

  test('空列表 → 空结果（非 List 输入同样返回空）', () {
    expect(historyItemsOf(const <Object?>[]), isEmpty);
    expect(historyItemsOf(null), isEmpty);
  });

  test('时间戳缺失 / 为 0 / 非数字时 time 为空串', () {
    final List<Map<String, Object?>> items = historyItemsOf(<Object?>[
      <String, Object?>{
        'id': 'a',
        'role': 'user',
        'text': 'a',
      },
      <String, Object?>{'id': 'b', 'role': 'user', 'text': 'b', 'timestamp': 0},
      <String, Object?>{'id': 'c', 'role': 'user', 'text': 'c', 'timestamp': '1700000000000'},
    ]);

    for (final Map<String, Object?> item in items) {
      expect(item['time'], '');
    }
  });

  test('输出形状能被 ChatHistoryItem.fromJson 解析（JSON 往返）', () {
    final int ts = DateTime(2026, 1, 2, 3, 4).millisecondsSinceEpoch;
    final List<Map<String, Object?>> items = historyItemsOf(<Object?>[
      <String, Object?>{
        'id': 'm1',
        'role': 'user',
        'parts': <Object?>[
          <String, Object?>{'type': 'text', 'text': '你好'},
        ],
        'timestamp': ts,
      },
      <String, Object?>{
        'id': 't1',
        'role': 'tool',
        'parts': <Object?>[
          <String, Object?>{'type': 'text', 'text': '输出'},
        ],
        'meta': <String, Object?>{'toolName': 'bash', 'status': 'error', 'detailText': 'ls'},
      },
      <String, Object?>{
        'entryId': 'e1',
        'role': 'assistant',
        'thinking': '想',
        'text': '答案',
        'images': <Object?>[
          <String, Object?>{'data': 'data:image/png;base64,AAAA'},
        ],
      },
    ]);

    final String encoded = jsonEncode(items);
    final List<Object?> decoded = jsonDecode(encoded) as List<Object?>;
    final List<ChatHistoryItem> parsed = decoded
        .map((Object? item) => ChatHistoryItem.fromJson(item! as Map<String, Object?>))
        .toList();

    expect(parsed.length, 3);
    expect(parsed[0].id, 'm1');
    expect(parsed[0].isUser, isTrue);
    expect(parsed[0].time, '03:04');
    expect(parsed[1].isTool, isTrue);
    expect(parsed[1].toolName, 'bash');
    expect(parsed[1].toolState, 'error');
    expect(parsed[1].toolDetail, 'ls\n\n输出');
    expect(parsed[2].id, 'e1');
    expect(parsed[2].thinking, '想');
    expect(parsed[2].text, '答案');
    expect(parsed[2].images.single, 'data:image/png;base64,AAAA');
  });
}
