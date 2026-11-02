/// SSE 增量解析 —— `src/api/sse.ts` 的 Dart 移植（逐行对齐，含测试用例）。
///
/// 为什么不直接复用 `probe/sse_probe.dart`：探针版只服务于统计，丢了 `id` / `retry`、
/// BOM、孤立 `\r` 这几条协议语义。聊天流要和桌面端 / 网页版走同一份协议解释，
/// 差一个换行判定就会出现「网页版正常、原生页卡住」这种最难查的漂移。
///
/// 帧协议本身很朴素：
///   - 状态流：`event: state` + 一行 data（整份 JSON 快照），15s 一次 `: ping`
///   - 会话流：只有 `data: {...}` 帧，结束时 `data: [DONE]`
/// 但网络分片会把一行切断，所以必须按流增量解析，不能假设 chunk 边界。
library;

import 'dart:convert';

/// 一个完整的 SSE 事件（到空行为止）。
class SseEvent {
  const SseEvent({required this.event, required this.data, this.id, this.retry});

  /// 缺省为 `message`。
  final String event;

  /// 多行 `data:` 用换行拼起来。
  final String data;

  /// 只有出现过 `id:` 字段才有值；跨事件保留（与 SSE 规范一致）。
  final String? id;

  /// `retry:` 字段，仅当前事件有效。
  final int? retry;

  /// 解析后的 JSON 载荷；不是 JSON 时返回 null。
  Object? get json {
    final String trimmed = data.trim();
    if (trimmed.isEmpty) return null;
    try {
      return jsonDecode(trimmed);
    } catch (_) {
      return null;
    }
  }

  @override
  String toString() => 'SseEvent($event, ${data.length} chars)';
}

/// 增量 SSE 解析器：一次 `push` 可能产出 0..n 个完整事件。
class SseParser {
  String _buffer = '';
  final List<String> _dataLines = <String>[];
  String _eventName = '';
  String? _lastId;
  int? _retry;
  bool _started = false;

  /// 喂入一段已解码的字符流，返回这段里收口的完整事件。
  List<SseEvent> push(String chunk) {
    if (!_started) {
      _started = true;
      // BOM：只可能出现在整条流的最前面
      if (chunk.isNotEmpty && chunk.codeUnitAt(0) == 0xFEFF) chunk = chunk.substring(1);
    }
    _buffer += chunk;

    final List<SseEvent> events = <SseEvent>[];
    while (true) {
      final List<int>? sep = _nextSeparator();
      if (sep == null) break;
      final int index = sep[0];
      final int width = sep[1];
      final String line = _buffer.substring(0, index);
      _buffer = _buffer.substring(index + width);
      final SseEvent? event = _consumeLine(line);
      if (event != null) events.add(event);
    }
    return events;
  }

  /// 连接正常结束但没有尾随空行时，把残留数据吐出来。
  List<SseEvent> flush() {
    final List<SseEvent> events = <SseEvent>[];
    if (_buffer.isNotEmpty) {
      final String line = _buffer;
      _buffer = '';
      final SseEvent? event = _consumeLine(line);
      if (event != null) events.add(event);
    }
    final SseEvent? tail = _dispatch();
    if (tail != null) events.add(tail);
    return events;
  }

  /// 找下一个换行符：`\r\n` / `\r` / `\n` 都算，返回 `[下标, 宽度]`。
  ///
  /// 末尾孤立的 `\r` 可能只是被切开的 CRLF 的前半截 —— 等下一个分片再判，
  /// 提前当换行会把下一行首字符吃掉。
  List<int>? _nextSeparator() {
    for (int i = 0; i < _buffer.length; i++) {
      final int code = _buffer.codeUnitAt(i);
      if (code == 0x0A) return <int>[i, 1];
      if (code == 0x0D) {
        if (i + 1 < _buffer.length) return <int>[i, _buffer.codeUnitAt(i + 1) == 0x0A ? 2 : 1];
        return null;
      }
    }
    return null;
  }

  SseEvent? _consumeLine(String line) {
    if (line.isEmpty) return _dispatch();
    if (line.startsWith(':')) return null; // 注释 / 心跳

    final int colon = line.indexOf(':');
    final String field = colon == -1 ? line : line.substring(0, colon);
    String value = colon == -1 ? '' : line.substring(colon + 1);
    if (value.startsWith(' ')) value = value.substring(1);

    switch (field) {
      case 'data':
        _dataLines.add(value);
      case 'event':
        _eventName = value;
      case 'id':
        if (!value.contains('\u0000')) _lastId = value;
      case 'retry':
        final int? parsed = int.tryParse(value);
        if (parsed != null) _retry = parsed;
      default:
        break;
    }
    return null;
  }

  SseEvent? _dispatch() {
    if (_dataLines.isEmpty) {
      _eventName = '';
      _retry = null;
      return null;
    }
    final String data = _dataLines.join('\n');
    final SseEvent event = SseEvent(
      event: _eventName.isEmpty ? 'message' : _eventName,
      data: data,
      id: _lastId,
      retry: _retry,
    );
    _dataLines.clear();
    _eventName = '';
    _retry = null;
    return event;
  }
}

/// 便利函数：一次性文本转事件（测试与回放用）。
List<SseEvent> parseSseText(String text) {
  final SseParser parser = SseParser();
  return <SseEvent>[...parser.push(text), ...parser.flush()];
}

/// AI SDK 风格的流结束标记。
const String sseDoneMarker = '[DONE]';
