/// 会话历史条目生成 —— `app/src/platform/flutter-chat.ts` 的 Dart 移植。
///
/// 逐分支对应 TS 实现，不做任何协议推断、不新增字段：
///   * `historyItemsOf()`      ← `app/src/platform/flutter-chat.ts` `historyItemsOf()`
///   * `messageClock()`        ← `app/src/platform/flutter-chat.ts` `messageClock()`
///                               （内部调 `app/src/ui/dom.ts` 的 `clockOf()`）
///   * `messageText()`         ← `app/src/ui/chat-view.ts` `messageText()`
///   * `normalizeRole()`       ← `app/src/ui/chat-view.ts` `normalizeRole()`
///   * `splitThinking()`       ← `app/src/ui/thinking-tags.ts` `splitThinking()`
///   * `collectImageSources()` ← `app/src/ui/image-attach-model.ts`
///                               `collectImageSources()` / `pushSource()` / `isImageDataUrl()`
///
/// `historyItemsOf()` 返回的是与 TS **完全同形**的 JSON Map 列表
/// （kind / id / role / text / thinking / time / images / toolName / toolState /
/// toolDetail），上层直接 `jsonEncode` 就能被 `ChatHistoryItem.fromJson` 解析。
library;

/// 工具条目两行摘要的字符上限 —— 对应 `flutter-chat.ts` 的 `TOOL_DETAIL_LIMIT = 2_000`。
const int _toolDetailLimit = 2000;

/// `<thinking>` / `</thinking>` 字面量（TS 里 `OPEN_TAG` / `CLOSE_TAG`）。
const String _openTag = '<thinking>';

/// 对应 `thinking-tags.ts` 的 `THINKING_PATTERN = /<thinking>([\s\S]*?)<\/thinking>/gi`。
final RegExp _thinkingPattern =
    RegExp(r'<thinking>([\s\S]*?)</thinking>', caseSensitive: false);

/// 对应 `thinking-tags.ts` 里 `text.replace(/\n{3,}/g, "\n\n")`。
final RegExp _blankLinesPattern = RegExp(r'\n{3,}');

/// 对应 `image-attach-model.ts` 的 `isImageDataUrl()`：`/^data:image\/[a-z0-9.+-]+;base64,/i`。
///
/// 注：Dart 的 `^` 默认只匹配整串开头（未开 multiLine），与 JS 正则一致。
final RegExp _imageDataUrlPattern =
    RegExp(r'^data:image/[a-z0-9.+-]+;base64,', caseSensitive: false);

/// 把任意值当「消息对象」取字段。
///
/// TS 的这几个函数直接 `message.text` / `message.parts`：对象与原始值（字符串、数字）
/// 都只会读到 `undefined`，只有 null / undefined 会抛 TypeError。这里对非 Map 一律
/// 返回 null（等价于「什么都读不到」），比 TS 更宽松一点，见文件末尾说明。
Map<Object?, Object?>? _fieldsOf(Object? value) =>
    value is Map ? value.cast<Object?, Object?>() : null;

/// 对应 `chat-view.ts` 的 `normalizeRole()`：非字符串 / 未知值一律当 `assistant`。
String normalizeRole(Object? role) {
  if (role is! String) return 'assistant';
  final String value = role.toLowerCase();
  if (value == 'user' || value == 'assistant' || value == 'system' || value == 'tool') {
    return value;
  }
  return 'assistant';
}

/// 对应 `chat-view.ts` 的 `messageText()`。
///
/// 优先级：顶层 `text`（去掉首尾空白后非空就用它）→ `parts` 里能取到的文本 →
/// （一个都没取到时）`content`。桶之间用单个换行连接，最后整体 trim。
String messageText(Object? message) {
  final Map<Object?, Object?>? record = _fieldsOf(message);
  final Object? top = record?['text'];
  if (top is String && top.trim().isNotEmpty) return top.trim();

  final List<String> buckets = <String>[];

  // TS 里的 `collect()`：字符串直接入桶；数组递归；对象优先 text，其次 content，
  // content 是别的形状时继续递归进去。
  void collect(Object? value) {
    if (value is String) {
      buckets.add(value);
      return;
    }
    if (value is List) {
      for (final Object? item in value) {
        collect(item);
      }
      return;
    }
    if (value is Map) {
      final Object? text = value['text'];
      final Object? content = value['content'];
      if (text is String) {
        buckets.add(text);
      } else if (content is String) {
        buckets.add(content);
      } else if (content != null) {
        collect(content);
      }
    }
  }

  // TS 是 `if (message.parts !== undefined) collect(message.parts)`；collect(null) 是空操作，
  // 所以这里无条件调用与 TS 等价。
  collect(record?['parts']);
  if (buckets.isEmpty) collect(record?['content']);
  return buckets.join('\n').trim();
}

/// 对应 `thinking-tags.ts` 的 `splitThinking()`。
///
/// 返回去掉思考块后的正文、抽出来的思考（多段用空行连接），以及是否找到思考内容。
/// 未闭合的 `<thinking>` 会把尾部整段归进思考里。
({String thinking, String text, bool found}) splitThinking(String raw) {
  if (raw.isEmpty || !raw.toLowerCase().contains(_openTag)) {
    return (thinking: '', text: raw, found: false);
  }

  final List<String> parts = <String>[];
  String text = raw.replaceAllMapped(_thinkingPattern, (Match match) {
    final String trimmed = (match.group(1) ?? '').trim();
    if (trimmed.isNotEmpty) parts.add(trimmed);
    return '';
  });

  // 未闭合的思考（截断的历史 / 流式中途）：整段尾部归到思考里，
  // 否则正文会留下一个裸露的 <thinking> 标签。
  final int unclosed = text.toLowerCase().indexOf(_openTag);
  if (unclosed >= 0) {
    final String tail = text.substring(unclosed + _openTag.length).trim();
    if (tail.isNotEmpty) parts.add(tail);
    text = text.substring(0, unclosed);
  }

  return (
    thinking: parts.join('\n\n').trim(),
    // 去掉块后可能留下多余空行，压缩一下但不破坏正常段落
    text: text.replaceAll(_blankLinesPattern, '\n\n').trim(),
    found: parts.isNotEmpty,
  );
}

/// 对应 `image-attach-model.ts` 的 `pushSource()`：只收 data URL，
/// 以及「够长 + 旁边有 image/* mediaType」的裸 base64（补成 data URL）。
void _pushSource(List<String> out, Object? value, [String? mimeHint]) {
  if (value is String && _imageDataUrlPattern.hasMatch(value)) {
    out.add(value);
    return;
  }
  // 裸 base64 + 旁边的 mediaType。`value.length` 在 Dart 与 JS 里都是 UTF-16 码元数。
  if (value is String &&
      value.length > 64 &&
      (mimeHint?.startsWith('image/') ?? false) &&
      !value.startsWith('data:')) {
    out.add('data:$mimeHint;base64,$value');
  }
}

/// 对应 `image-attach-model.ts` 的 `mime` 取值：`mediaType || mimeType || mime`（空串算假）。
String _mimeOf(Map<dynamic, dynamic> record) {
  for (final String key in const <String>['mediaType', 'mimeType', 'mime']) {
    final Object? value = record[key];
    if (value is String && value.isNotEmpty) return value;
  }
  return '';
}

/// 对应 `image-attach-model.ts` 的 `collectImageSources()`。
///
/// 覆盖 `parts` / `images` / `content` 里的三种形状，只对「看起来是图片」的节点取载荷
/// （避免把 tool input 里的普通字符串当图片），最后按出现顺序去重。
List<String> collectImageSources(Object? message) {
  final List<String> out = <String>[];
  final Map<Object?, Object?>? record = _fieldsOf(message);

  void visit(Object? value, int depth) {
    if (depth > 4 || value == null) return;
    if (value is String) {
      _pushSource(out, value);
      return;
    }
    if (value is List) {
      for (final Object? item in value) {
        visit(item, depth + 1);
      }
      return;
    }
    if (value is! Map) return;

    final Object? typeValue = value['type'];
    final String type = typeValue is String ? typeValue : '';
    final String mime = _mimeOf(value);
    final bool looksImage =
        type == 'image' || type == 'file' || type.isEmpty || mime.startsWith('image/');

    if (looksImage) {
      _pushSource(out, value['url'], mime);
      _pushSource(out, value['data'], mime);
      _pushSource(out, value['image'], mime);
      // TS 这里会把 source 本身也过一遍 pushSource，它是对象时自然什么都不推。
      _pushSource(out, value['source'], mime);
      final Object? source = value['source'];
      if (source is Map) {
        final Object? mediaType = source['mediaType'];
        _pushSource(out, source['data'], mediaType is String ? mediaType : mime);
        _pushSource(out, source['url'], mime);
      }
    }

    for (final String key in const <String>['parts', 'content', 'images', 'attachments']) {
      if (value.containsKey(key)) visit(value[key], depth + 1);
    }
  }

  visit(record?['parts'], 0);
  visit(record?['images'], 0);
  // TS 只在前面一张都没挖到时才看 content
  if (out.isEmpty) visit(record?['content'], 0);
  // `[...new Set(out)]`：保序去重
  return out.toSet().toList(growable: false);
}

/// 对应 `flutter-chat.ts` 的 `messageClock()` + `dom.ts` 的 `clockOf()`。
///
/// 24 小时制、补零的「时:分」，用本地时区（`DateTime.fromMillisecondsSinceEpoch`
/// 默认就是本地时间，等价于 `new Date(ms).getHours()`）。非法/非正值返回空串。
String _messageClock(Object? timestamp) {
  if (timestamp is! num) return '';
  if (!timestamp.isFinite || timestamp <= 0) return '';
  try {
    final DateTime date = DateTime.fromMillisecondsSinceEpoch(timestamp.truncate());
    return '${date.hour.toString().padLeft(2, '0')}:'
        '${date.minute.toString().padLeft(2, '0')}';
  } catch (_) {
    // JS 的 `new Date(超界值)` 是 Invalid Date，clockOf() 会拼出字面量 "NaN:NaN"，
    // Dart 这里直接抛 RangeError；为了与 TS 的输出对齐，返回同样的字符串。
    return 'NaN:NaN';
  }
}

/// 对应 TS 的 `String.prototype.slice(0, limit)`：按 UTF-16 码元截断（Dart 的 `length`
/// 与 `substring` 同样是码元口径，所以超长时的行为与 TS 一致，不会抛越界）。
String _slice(String value, int limit) =>
    value.length <= limit ? value : value.substring(0, limit);

/// 对应 `flutter-chat.ts` 的 `historyItemsOf()`。
///
/// 与 Web 版 `ChatView.setHistory()` 的差别（TS 注释原文）：Web 版把连续的「思考 + 工具」
/// 收进同一个 `<details>` 分组，这里**不平铺分组**，而是每条消息各自成项、思考挂在
/// 消息自己身上。
///
/// 返回的 Map 里 `id` 可能是 null（TS 里就是 `null`），`ChatHistoryItem.fromJson`
/// 会把 null 读成空串。
List<Map<String, Object?>> historyItemsOf(Object? messages) {
  final List<Map<String, Object?>> items = <Map<String, Object?>>[];
  if (messages is! List) return items;

  for (final Object? message in messages) {
    final Map<Object?, Object?>? record = _fieldsOf(message);
    final String role = normalizeRole(record?['role']);
    // role === "system" 直接跳过（TS 里唯一被丢掉的角色）
    if (role == 'system') continue;

    // id 取 message.id，空串视为没有；否则退回 entryId（entryId 是空串时保留空串）
    final Object? rawId = record?['id'];
    final String? id;
    if (rawId is String && rawId.isNotEmpty) {
      id = rawId;
    } else {
      final Object? entryId = record?['entryId'];
      id = entryId is String ? entryId : null;
    }

    if (role == 'tool') {
      final Map<Object?, Object?> meta =
          _fieldsOf(record?['meta']) ?? const <Object?, Object?>{};
      final Object? toolNameValue = meta['toolName'];
      final String toolName =
          toolNameValue is String && toolNameValue.isNotEmpty ? toolNameValue : 'tool';
      final Object? statusValue = meta['status'];
      final String status = statusValue is String ? statusValue : '';
      final bool isError = meta['isError'] == true || status == 'error';

      // 先各取一段，丢掉空串，再用空行拼起来（TS：filter(Boolean).join("\n\n")）
      final Object? detailText = meta['detailText'];
      final String detail = <String>[
        detailText is String ? detailText : '',
        messageText(message),
      ].where((String part) => part.isNotEmpty).join('\n\n');

      items.add(<String, Object?>{
        'kind': 'message',
        'id': id,
        'role': 'tool',
        'text': '',
        'thinking': '',
        'time': _messageClock(record?['timestamp']),
        'images': <String>[],
        'toolName': toolName,
        // 判断顺序与 TS 一致：isError/status=error 优先，其次 running，其余 ok
        'toolState': isError ? 'error' : status == 'running' ? 'running' : 'ok',
        'toolDetail': _slice(detail, _toolDetailLimit),
      });
      continue;
    }

    // 思考可能来自独立字段，也可能以 <thinking> 标签嵌在正文里
    final String rawText = messageText(message);
    final ({String thinking, String text, bool found}) split = splitThinking(rawText);
    final Object? thinkingField = record?['thinking'];
    final String fieldThinking = thinkingField is String ? thinkingField.trim() : '';
    final String thinking =
        <String>[fieldThinking, split.thinking].where((String part) => part.isNotEmpty).join('\n\n').trim();
    final List<String> images = collectImageSources(message);

    // 正文、思考、图片全空 → 不渲染（TS：!split.text && !thinking && images.length === 0）
    if (split.text.isEmpty && thinking.isEmpty && images.isEmpty) continue;

    items.add(<String, Object?>{
      'kind': 'message',
      'id': id,
      // 只剩 user / assistant 两种（tool 已在上面的分支里 continue，system 已跳过）
      'role': role == 'user' ? 'user' : 'assistant',
      'text': split.text,
      'thinking': thinking,
      'time': _messageClock(record?['timestamp']),
      'images': images,
      'toolName': '',
      'toolState': '',
      'toolDetail': '',
    });
  }

  return items;
}
