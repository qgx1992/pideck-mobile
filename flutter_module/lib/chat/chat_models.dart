/// 聊天页视图模型：对应 `app/src/platform/flutter-chat.ts` 的 `ChatSnapshot`。
///
/// 这份数据的**唯一来源是 WebView**：`ChatReducer`（`src/api/frames.ts`）是协议核心，
/// 桌面端与手机端共用，所以在 Flutter 里再写一份状态机只会两套真相源打架。
/// 这里只做解析与展示，不推断任何协议语义。
///
/// 字段改名时必须同步改 `ChatSnapshot`（TS），否则 Dart 侧会静默拿到 null。
library;

import 'dart:convert';

/// 一条已落盘的历史消息（工具条目也是这个形状，用 [toolName] 区分）。
class ChatHistoryItem {
  const ChatHistoryItem({
    this.id = '',
    this.role = 'assistant',
    this.text = '',
    this.thinking = '',
    this.time = '',
    this.images = const <String>[],
    this.toolName = '',
    this.toolState = '',
    this.toolDetail = '',
  });

  final String id;
  final String role;
  final String text;
  final String thinking;
  final String time;
  final List<String> images;
  final String toolName;

  /// `running` / `ok` / `error`，非工具条目为空串。
  final String toolState;
  final String toolDetail;

  bool get isTool => role == 'tool';
  bool get isUser => role == 'user';

  factory ChatHistoryItem.fromJson(Map<String, Object?> json) {
    return ChatHistoryItem(
      id: str(json['id']),
      role: str(json['role'], 'assistant'),
      text: str(json['text']),
      thinking: str(json['thinking']),
      time: str(json['time']),
      images: listOf(json['images']).map(str).where((String item) => item.isNotEmpty).toList(growable: false),
      toolName: str(json['toolName']),
      toolState: str(json['toolState']),
      toolDetail: str(json['toolDetail']),
    );
  }
}

/// 当前轮的一个流式块（text / reasoning / tool）。
class ChatBlockView {
  const ChatBlockView({
    required this.kind,
    this.id = '',
    this.toolCallId = '',
    this.toolName = '',
    this.text = '',
    this.done = false,
    this.state = '',
    this.input,
    this.output,
    this.errorText = '',
  });

  /// `text` / `reasoning` / `tool`
  final String kind;
  final String id;
  final String toolCallId;
  final String toolName;
  final String text;
  final bool done;
  final String state;
  final Object? input;
  final Object? output;
  final String errorText;

  /// 与 Web 侧 `ChatView.blockKey()` 同口径：工具按 callId，其余按 id。
  /// 用它当 widget key，流式更新时才能复用已有节点（否则滚动位置每帧被清零）。
  String get key => kind == 'tool' ? 'tool:$toolCallId' : '$kind:$id';

  bool get isText => kind == 'text';
  bool get isReasoning => kind == 'reasoning';
  bool get isTool => kind == 'tool';

  factory ChatBlockView.fromJson(Map<String, Object?> json) {
    final String kind = str(json['kind']);
    if (kind == 'tool') {
      return ChatBlockView(
        kind: kind,
        toolCallId: str(json['toolCallId']),
        toolName: str(json['toolName'], 'tool'),
        state: str(json['state'], 'running'),
        input: json['input'],
        output: json['output'],
        errorText: str(json['errorText']),
      );
    }
    return ChatBlockView(
      kind: kind.isEmpty ? 'text' : kind,
      id: str(json['id']),
      text: str(json['text']),
      done: json['done'] == true,
    );
  }
}

/// 当前轮：一条 assistant 消息 + 它的块序列。
class ChatLive {
  const ChatLive({this.messageId = '', this.blocks = const <ChatBlockView>[]});

  final String messageId;
  final List<ChatBlockView> blocks;

  factory ChatLive.fromJson(Map<String, Object?> json) {
    return ChatLive(
      messageId: str(json['messageId']),
      blocks: listOf(json['blocks'])
          .whereType<Map<Object?, Object?>>()
          .map((Map<Object?, Object?> item) => ChatBlockView.fromJson(mapOf(item)))
          .toList(growable: false),
    );
  }
}

/// AI 提问卡（`PendingUiRequest` 的展示子集）。
class ChatPending {
  const ChatPending({
    this.requestId = '',
    this.method = '',
    this.title = '',
    this.options = const <String>[],
    this.placeholder = '',
    this.prefill = '',
    this.allowOther = false,
    this.multiple = false,
    this.batchQuestions = const <ChatBatchQuestion>[],
    this.batchReview = false,
  });

  final String requestId;

  /// `select` / `confirm` / `input` / `editor` / `batch_ask`
  final String method;
  final String title;
  final List<String> options;
  final String placeholder;
  final String prefill;
  final bool allowOther;
  final bool multiple;
  final List<ChatBatchQuestion> batchQuestions;
  final bool batchReview;

  factory ChatPending.fromJson(Map<String, Object?> json) {
    return ChatPending(
      requestId: str(json['requestId']),
      method: str(json['method']),
      title: str(json['title']),
      options: listOf(json['options']).map(str).where((String item) => item.isNotEmpty).toList(growable: false),
      placeholder: str(json['placeholder']),
      prefill: str(json['prefill']),
      allowOther: json['allowOther'] == true,
      multiple: json['multiple'] == true,
      batchQuestions: listOf(json['batchQuestions'])
          .whereType<Map<Object?, Object?>>()
          .map((Map<Object?, Object?> item) => ChatBatchQuestion.fromJson(mapOf(item)))
          .toList(growable: false),
      batchReview: json['batchReview'] == true,
    );
  }
}

class ChatBatchQuestion {
  const ChatBatchQuestion({
    this.id = '',
    this.header = '',
    this.question = '',
    this.kind = 'select',
    this.options = const <ChatBatchOption>[],
    this.multiple = false,
    this.placeholder = '',
    this.prefill = '',
  });

  final String id;
  final String header;
  final String question;

  /// `select` / `multi_select` / `confirm` / `input` / `editor`
  final String kind;
  final List<ChatBatchOption> options;
  final bool multiple;
  final String placeholder;
  final String prefill;

  factory ChatBatchQuestion.fromJson(Map<String, Object?> json) {
    return ChatBatchQuestion(
      id: str(json['id']),
      header: str(json['header']),
      question: str(json['question']),
      kind: str(json['type'], str(json['kind'], 'select')),
      // 与 `app/src/ui/ask-model.ts` 同口径：具名 type 优先，再退到 multiple/multiSelect
      multiple: str(json['type']) == 'multi_select' || json['multiple'] == true || json['multiSelect'] == true,
      placeholder: str(json['placeholder']),
      prefill: str(json['prefill']),
      options: listOf(json['options'])
          .map((Object? item) {
            if (item is String) return ChatBatchOption(label: item, value: item);
            if (item is Map<Object?, Object?>) {
              final Map<String, Object?> option = mapOf(item);
              return ChatBatchOption(
                label: str(option['label'], str(option['value'])),
                value: str(option['value'], str(option['label'])),
                description: str(option['description']),
              );
            }
            return const ChatBatchOption();
          })
          .toList(growable: false),
    );
  }
}

class ChatBatchOption {
  const ChatBatchOption({this.label = '', this.value = '', this.description = ''});

  final String label;
  final String value;
  final String description;
}

/// 用量/吞吐（`PerfMetrics` 的展示子集）。来源是运行时状态轮询，不是 SSE。
class ChatPerf {
  const ChatPerf({
    this.ttftMs,
    this.streamingTps,
    this.averageTps,
    this.totalMs,
    this.outputTokens,
  });

  final int? ttftMs;
  final double? streamingTps;
  final double? averageTps;
  final int? totalMs;
  final int? outputTokens;

  factory ChatPerf.fromJson(Map<String, Object?> json) {
    return ChatPerf(
      ttftMs: intOrNull(json['ttftMs']),
      streamingTps: doubleOrNull(json['streamingTps']),
      averageTps: doubleOrNull(json['averageTps']),
      totalMs: intOrNull(json['totalMs']),
      outputTokens: intOrNull(json['outputTokens']),
    );
  }
}

class ChatErrorInfo {
  const ChatErrorInfo({this.title = '', this.detail = '', this.hint = ''});

  final String title;
  final String detail;
  final String hint;

  factory ChatErrorInfo.fromJson(Map<String, Object?> json) {
    return ChatErrorInfo(
      title: str(json['title']),
      detail: str(json['detail']),
      hint: str(json['hint']),
    );
  }
}

/// 整份快照。`null` 字段语义：`error`/`empty`/`live`/`pending`/`perf` 缺失就是「没有」。
class ChatSnapshot {
  const ChatSnapshot({
    this.sessionId = '',
    this.title = '',
    this.status = 'idle',
    this.statusText = '',
    this.error,
    this.empty,
    this.loading = false,
    this.history = const <ChatHistoryItem>[],
    this.live,
    this.pending,
    this.perf,
    this.messageMenus = const <String, List<ChatMenuItem>>{},
  });

  final String sessionId;
  final String title;

  /// `idle` / `running` / `finished` / `error` / `aborted`
  final String status;
  final String statusText;
  final ChatErrorInfo? error;
  final String? empty;
  final bool loading;
  final List<ChatHistoryItem> history;
  final ChatLive? live;
  final ChatPending? pending;
  final ChatPerf? perf;

  /// 长按菜单：`{'user': [...], 'assistant': [...]}`。
  ///
  /// 内容由 WebView 的 `actionsForRole()` 决定（ACP 会话只剩「复制」），
  /// 这里**不维护**一份自己的动作表 —— 后端能力一变两边就会分叉。
  final Map<String, List<ChatMenuItem>> messageMenus;

  /// 取某个角色能用的菜单项（工具条不给菜单）。
  List<ChatMenuItem> menusFor(String role) {
    if (role == 'tool') return const <ChatMenuItem>[];
    return messageMenus[role] ?? const <ChatMenuItem>[];
  }

  bool get isRunning => status == 'running';

  /// 历史为空、当前也没有流式内容 —— 用来决定要不要显示空态。
  bool get hasContent => history.isNotEmpty || (live?.blocks.isNotEmpty ?? false);

  factory ChatSnapshot.fromJson(Map<String, Object?> json) {
    final Object? error = json['error'];
    final Object? live = json['live'];
    final Object? pending = json['pending'];
    final Object? perf = json['perf'];
    final Object? empty = json['empty'];
    return ChatSnapshot(
      sessionId: str(json['sessionId']),
      title: str(json['title']),
      status: str(json['status'], 'idle'),
      statusText: str(json['statusText']),
      error: error is Map<Object?, Object?> ? ChatErrorInfo.fromJson(mapOf(error)) : null,
      empty: empty is String && empty.isNotEmpty ? empty : null,
      loading: json['loading'] == true,
      history: listOf(json['history'])
          .whereType<Map<Object?, Object?>>()
          .map((Map<Object?, Object?> item) => ChatHistoryItem.fromJson(mapOf(item)))
          .toList(growable: false),
      live: live is Map<Object?, Object?> ? ChatLive.fromJson(mapOf(live)) : null,
      pending: pending is Map<Object?, Object?> ? ChatPending.fromJson(mapOf(pending)) : null,
      perf: perf is Map<Object?, Object?> ? ChatPerf.fromJson(mapOf(perf)) : null,
      messageMenus: _menusOf(json['messageMenus']),
    );
  }
}

Map<String, List<ChatMenuItem>> _menusOf(Object? raw) {
  if (raw is! Map) return const <String, List<ChatMenuItem>>{};
  final Map<String, List<ChatMenuItem>> menus = <String, List<ChatMenuItem>>{};
  for (final MapEntry<Object?, Object?> entry in raw.entries) {
    menus['${entry.key}'] = listOf(entry.value)
        .whereType<Map<Object?, Object?>>()
        .map((Map<Object?, Object?> item) => ChatMenuItem.fromJson(mapOf(item)))
        .toList(growable: false);
  }
  return menus;
}

/// 长按菜单里的一项。
class ChatMenuItem {
  const ChatMenuItem({required this.id, required this.label, this.danger = false});

  /// `copy` / `edit` / `resend` / `fork` / `delete`
  final String id;
  final String label;

  /// 危险操作（删除）：菜单里标红，点下去先二次确认
  final bool danger;

  factory ChatMenuItem.fromJson(Map<String, Object?> json) => ChatMenuItem(
        id: str(json['id']),
        label: str(json['label']),
        danger: json['danger'] == true,
      );
}

/* ------------------------------ 解析小工具 ------------------------------ */

String str(Object? value, [String fallback = '']) {
  if (value is String) return value;
  if (value == null) return fallback;
  return value.toString();
}

List<Object?> listOf(Object? value) => value is List ? value : const <Object?>[];

Map<String, Object?> mapOf(Map<Object?, Object?> value) {
  return value.map((Object? key, Object? item) => MapEntry<String, Object?>(str(key), item));
}

int? intOrNull(Object? value) {
  if (value is int) return value;
  if (value is double) return value.round();
  if (value is String) return int.tryParse(value);
  return null;
}

double? doubleOrNull(Object? value) {
  if (value is double) return value;
  if (value is int) return value.toDouble();
  if (value is String) return double.tryParse(value);
  return null;
}

/// 工具入参/输出的摘要：字符串原样，其它走 JSON，太长就截断。
///
/// 与 Web 侧 `preview()` 的差别：那边塞进 `<details>` 里可以很长，这里要进手机屏幕，
/// 所以截断得更早。
String previewOf(Object? value, {int limit = 600}) {
  if (value == null) return '';
  String text;
  if (value is String) {
    text = value;
  } else {
    try {
      text = jsonEncode(value);
    } catch (_) {
      text = value.toString();
    }
  }
  if (text.length <= limit) return text;
  return '${text.substring(0, limit)}…';
}

/// 一次问多题的提交格式，与 `app/src/ui/ask-model.ts` 的 `serializeBatchAnswers()` 一致：
/// `{"answers":[{"id","type","value","label","wasCustom"}]}`，整体再当 `value` 字符串回传。
///
/// `value` 为 `null` 表示「没碰过这题」，空字符串表示「主动留空」—— 这两种在
/// PiDeck 侧含义不同，不能合并。
String batchAnswerPayload(List<ChatBatchQuestion> questions, Map<String, Object?> answers) {
  final List<Map<String, Object?>> items = questions.map((ChatBatchQuestion question) {
    final Object? value = answers[question.id];
    return <String, Object?>{
      'id': question.id,
      'type': question.kind,
      'value': value,
      'label': batchAnswerLabel(value),
      'wasCustom': false,
    };
  }).toList(growable: false);
  return jsonEncode(<String, Object?>{'answers': items});
}

/// 与 `ask-model.ts` 的 `batchAnswerLabel()` 同口径。
String batchAnswerLabel(Object? value) {
  if (value == null) return '';
  if (value is bool) return value ? '是' : '否';
  if (value is List) return value.map(str).join('、');
  return str(value);
}
