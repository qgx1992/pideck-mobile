/// SSE 探针的纯逻辑：解析 + 统计。
///
/// 抽出来是为了能脱离网络直接单测 —— 聊天页将来要重写这套流式读取，
/// 这里的解析器和统计就是「Dart 侧能不能扛住」的基线。
library;

/// 一个完整的 SSE 事件（到空行为止）。
class SseEvent {
  const SseEvent({this.event, required this.data});

  /// `event:` 字段，没有就是 null（默认 message）。
  final String? event;

  /// 多行 `data:` 用换行拼起来。
  final String data;

  @override
  String toString() => 'SseEvent(${event ?? "message"}, ${data.length} chars)';
}

/// 增量 SSE 解析器。
///
/// 必须能处理「一帧被切成两块」和「一个 chunk 里有多帧」两种情况 ——
/// 原生 `SsePlugin` 是按 16ms 窗口合并后过桥的，块边界完全随机。
class SseEventParser {
  String _pending = '';
  String? _event;
  final List<String> _data = <String>[];

  List<SseEvent> feed(String chunk) {
    final List<SseEvent> out = <SseEvent>[];
    _pending += chunk;
    while (true) {
      final int index = _pending.indexOf('\n');
      if (index < 0) break;
      String line = _pending.substring(0, index);
      _pending = _pending.substring(index + 1);
      if (line.endsWith('\r')) line = line.substring(0, line.length - 1);
      _handleLine(line, out);
    }
    return out;
  }

  void _handleLine(String line, List<SseEvent> out) {
    if (line.isEmpty) {
      if (_data.isNotEmpty || _event != null) {
        out.add(SseEvent(event: _event, data: _data.join('\n')));
      }
      _event = null;
      _data.clear();
      return;
    }
    // 注释行（`:` 开头）是心跳，不产出事件
    if (line.startsWith(':')) return;
    final int colon = line.indexOf(':');
    final String field = colon < 0 ? line : line.substring(0, colon);
    String value = colon < 0 ? '' : line.substring(colon + 1);
    if (value.startsWith(' ')) value = value.substring(1);
    switch (field) {
      case 'event':
        _event = value;
      case 'data':
        _data.add(value);
      default:
        // id / retry 等字段这里用不上，忽略
        break;
    }
  }
}

/// 探针采集到的东西。
class ProbeRecorder {
  ProbeRecorder();

  /// 每个事件相对起点的时间（毫秒），按到达顺序。
  final List<int> timesMs = <int>[];

  /// 事件里 `type` 字段的分布（`[DONE]` 记成 `done`）。
  final Map<String, int> types = <String, int>{};

  /// 收到的字符数（不是字节；HTTP 层已解码成 String）。
  int chars = 0;

  /// 后台期间收到的事件数。
  int eventsWhileBackground = 0;

  bool _background = false;

  /// 收到 `finish` 或 `[DONE]` 的时间（毫秒），用来算整轮耗时。
  int? finishMs;

  void markBackground(bool value) => _background = value;

  void record(int ms, SseEvent event) {
    timesMs.add(ms);
    chars += event.data.length;
    if (_background) eventsWhileBackground += 1;

    final String data = event.data;
    if (data == '[DONE]') {
      types['done'] = (types['done'] ?? 0) + 1;
      finishMs ??= ms;
      return;
    }
    final String? type = _typeOf(data);
    if (type != null) {
      types[type] = (types[type] ?? 0) + 1;
      if (type == 'finish') finishMs ??= ms;
    } else {
      final String key = event.event ?? 'message';
      types['event:$key'] = (types['event:$key'] ?? 0) + 1;
    }
  }

  /// 只从 JSON 帧里取 `type`，取不到就当普通事件。
  String? _typeOf(String data) {
    final String trimmed = data.trimLeft();
    if (!trimmed.startsWith('{')) return null;
    final RegExpMatch? match = RegExp(r'"type"\s*:\s*"([a-zA-Z_]+)"').firstMatch(trimmed);
    return match?.group(1);
  }

  int get eventCount => timesMs.length;

  /// 首帧延迟（毫秒）。
  int? get firstMs => timesMs.isEmpty ? null : timesMs.first;

  /// 相邻事件的间隔（毫秒），跳过第 0 个。
  List<int> get gaps {
    final List<int> out = <int>[];
    for (int i = 1; i < timesMs.length; i++) {
      out.add(timesMs[i] - timesMs[i - 1]);
    }
    return out;
  }

  int? get maxGapMs {
    final List<int> list = gaps;
    if (list.isEmpty) return null;
    return list.reduce((a, b) => a > b ? a : b);
  }

  int? get avgGapMs {
    final List<int> list = gaps;
    if (list.isEmpty) return null;
    return (list.reduce((a, b) => a + b) / list.length).round();
  }

  /// 中位间隔比平均值更能反映「卡不卡」（一个长间隔就能把平均值拉飞）。
  int? get medianGapMs {
    final List<int> list = gaps.toList()..sort();
    if (list.isEmpty) return null;
    return list[list.length ~/ 2];
  }

  String get typeSummary {
    if (types.isEmpty) return '（无帧）';
    final List<String> keys = types.keys.toList()..sort();
    return keys.map((k) => '$k×${types[k]}').join('  ');
  }
}

/// 把毫秒数写成 `1.2s` / `340ms`。
String fmtMs(int? ms) {
  if (ms == null) return '—';
  if (ms < 1000) return '${ms}ms';
  return '${(ms / 1000).toStringAsFixed(2)}s';
}
