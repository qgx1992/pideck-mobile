import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';

import 'api_client.dart';
import 'frames.dart';
import 'history_model.dart';
import 'sse.dart';

/// 聊天页的数据面运行时（Dart 侧自己跑，不再依赖 WebView 推快照）。
///
/// 为什么要有它：WebView 里的 `SessionChannel` 一旦被系统挂起就不再收帧，
/// 聊天页只能看到「卡住的最后一条」。原生这层守住三件事：
///
/// 1. **帧流**：`GET /api/sessions/:id/stream` 断线重连（800ms → 15s 退避），
///    50 秒没帧就当假死、立刻重连；帧本身只喂 [ChatReducer]（协议唯一定义处）。
/// 2. **状态面**：`GET /api/events` 拿会话标题 / 运行目标（`agentId` + `runtimeGeneration`）/
///    待答请求，`/api/state` 拉一次底。
/// 3. **历史**：`GET /api/sessions/:id/messages`，`settle` 时（finish 后 400ms）重拉一次，
///    否则流式块和已落盘的历史会双份渲染。
///
/// 它**只产出快照 JSON**（形状 = `ChatSnapshot`），不碰 widget：UI 全部走
/// `ChatSnapshot.fromJson`，与 WebView 推送的 JSON 完全同构，所以渲染层一行都不用改。
///
/// 边界（务必保持）：
/// - 这里不许判断「顶栏该显示哪些 chip」「菜单有哪些动作」——那是 WebView 的
///   `buildHeaderView()` / `actionsForRole()` 的活，搬过来就是两个真相源。
/// - 这里只做数据面：发送 / 停止 / 回答提问三个写动作也跟着下沉，因为它们和帧流
///   强耦合（发送要先把 reducer 归零）。其余动作仍然回传 WebView。
class ChatRuntime extends ChangeNotifier {
  ChatRuntime({
    required this.client,
    required this.sessionId,
    this.onNotice,
  });

  final PiDeckClient client;
  final String sessionId;

  /// 需要给用户看的提示（发送失败之类），由页面决定怎么显示。
  final void Function(String message)? onNotice;

  /// 协议唯一状态机（与 `src/api/frames.ts` 的 `ChatReducer` 一一对应）。
  final ChatReducer reducer = ChatReducer();

  /// 服务端心跳 15s 一次；50s 没帧就是假死（与 `src/session.ts` 的 `stallMs` 同值）。
  static const Duration stallTimeout = Duration(seconds: 50);

  static const Duration _settleDelay = Duration(milliseconds: 400);

  /// 挂出提问后多久重拉历史（服务端要先把这条消息落盘）。
  static const Duration _promptEchoDelay = Duration(milliseconds: 650);

  List<Map<String, Object?>> _messages = <Map<String, Object?>>[];
  Map<String, Object?> _state = <String, Object?>{};
  PendingUiRequest? _pending;

  bool _loading = true;

  /// 是否已经从服务端成功拉到过一次历史。
  ///
  /// 页面靠它决定「这份快照能不能盖到 WebView 那份上」：没拉到过历史时
  /// 这边全是空值，盖上去就是白屏。
  bool _historyLoaded = false;
  bool _connected = false;
  bool _authFailed = false;
  String? _errorTitle;
  String? _errorDetail;
  String _title = '';

  DateTime? _turnStart;
  DateTime? _turnEnd;

  bool _started = false;
  bool _disposed = false;
  int _settleStamp = 0;

  final _Backoff _frameBackoff = _Backoff();
  final _Backoff _stateBackoff = _Backoff();

  /// 状态机可读字段（页面/测试用）。
  String get title => _title;
  bool get loading => _loading;
  bool get historyLoaded => _historyLoaded;
  bool get connected => _connected;
  PendingUiRequest? get pending => _pending;
  List<Map<String, Object?>> get historyItems => historyItemsOf(_messages);
  RunStatus get status => reducer.status;
  String? get error => _errorDetail ?? _errorTitle;

  /// 打开页面时调一次：拉状态底 + 起两条流。重复调用无副作用。
  Future<void> start() async {
    if (_started || _disposed) return;
    _started = true;
    unawaited(_refreshState());
    unawaited(_loadHistory());
    unawaited(_frameLoop());
    unawaited(_stateLoop());
  }

  /* ------------------------------- 写动作 ------------------------------- */

  /// 发送一轮提问。
  ///
  /// 顺序照 `src/session.ts:167-207`：**先把 reducer 归零**再发（否则上一轮的
  /// `finished` 状态会让新帧走「追加」分支，屏幕上会留下上轮残块）。
  Future<SendPromptResult> send(
    String text, {
    List<Map<String, Object?>> images = const <Map<String, Object?>>[],
    String? streamingBehavior,
  }) async {
    final String trimmed = text.trim();
    if (trimmed.isEmpty) {
      return const SendPromptResult(ok: false, message: '内容为空');
    }
    if (reducer.status != RunStatus.running) reducer.reset();
    _errorTitle = null;
    _errorDetail = null;
    _turnStart = DateTime.now();
    _turnEnd = null;
    _loading = false;
    _notify();
    try {
      final SendPromptResult result = await client.sendPrompt(
        sessionId,
        requestId: _newRequestId(),
        message: trimmed,
        images: images,
        streamingBehavior: streamingBehavior,
      );
      if (!result.ok) {
        _errorTitle = '发送失败';
        _errorDetail = result.message.isEmpty ? '服务端拒绝了这次发送' : result.message;
        _notify();
      }
      return result;
    } on PiDeckException catch (error) {
      _errorTitle = '发送失败';
      _errorDetail = error.describe();
      _notify();
      rethrow;
    }
  }

  /// 停止本轮（服务端动作名是 `abort`，但界面叫「停止」）。
  Future<void> stop() async {
    final Map<String, Object?>? target = _target();
    if (target == null) {
      // 本地还没有这个会话的运行目标：先刷一次状态，别拿空 target 去撞 409。
      await _refreshState();
      final Map<String, Object?>? retryTarget = _target();
      if (retryTarget == null) {
        throw PiDeckException(
          PiDeckErrorKind.runtime,
          '这个会话还没有运行中的任务',
        );
      }
      await client.runtimeAction(sessionId, 'abort', target: retryTarget);
    } else {
      try {
        await client.runtimeAction(sessionId, 'abort', target: target);
      } on PiDeckException catch (error) {
        if (!error.isStale) rethrow;
        // 409 = 运行时换代了，按新目标重发一次（与 Web 侧同口径）。
        await _refreshState();
        final Map<String, Object?>? next = _target();
        if (next == null) rethrow;
        await client.runtimeAction(sessionId, 'abort', target: next);
      }
    }
    reducer.markAborted();
    _turnEnd = DateTime.now();
    _notify();
  }

  /// 回答 AI 的提问卡。
  ///
  /// 注意 `options` 的取值：快照里给 Dart 的是**标签**（`ChatPending.options`
  /// 是 `List<String>`，UI 直接拿来当选项文字），而服务端要的是**选项值**。
  /// 两者不总是相同，所以这里把标签映射回 value 再发。
  Future<void> respond(Map<String, Object?> response) async {
    final PendingUiRequest? pending = _pending;
    if (pending == null) return;
    final Map<String, Object?> payload = _mapResponseValue(pending, response);
    await client.respondUi(pending, payload);
    if (identical(_pending, pending)) _pending = null;
    _notify();
  }

  /// 重拉历史（错误卡上的「重试」、以及 settle 时用）。
  Future<void> reloadHistory() => _loadHistory();

  /// 用户刚在 WebView 侧把消息发出去（写路径还在 WebView）。
  ///
  /// 服务端落盘之后历史里才有这条用户消息，而「这一轮结束」还要等很久；
  /// 这里排一次短延迟重拉，让刚发出去的提问尽快出现在列表里。
  void notifyPromptSent() {
    Timer(_promptEchoDelay, () {
      if (!_disposed) unawaited(reloadHistory());
    });
  }

  /* ------------------------------- 快照 ------------------------------- */

  /// 产出 `ChatSnapshot` 形状的 JSON。
  ///
  /// 只包含**数据面**字段；`messageMenus` / `header` / `picker` / `suggestions` /
  /// `sheet` / `workspace` / `themeMode` 由 WebView 那份快照合并进来（Dart 不猜）。
  Map<String, Object?> snapshotJson() {
    final String? messageId = reducer.messageId;
    return <String, Object?>{
      'sessionId': sessionId,
      // 标题来自 `/api/events`；还没到时**不要**写这个键，否则会把
      // WebView 那边正确的标题盖成 "PiDeck"。
      if (_title.isNotEmpty) 'title': _title,
      'status': reducer.status.name,
      'statusText': _statusText(),
      'error': _errorTitle == null
          ? null
          : <String, Object?>{
              'title': _errorTitle!,
              if (_errorDetail != null && _errorDetail!.isNotEmpty) 'detail': _errorDetail,
            },
      'empty': null,
      'loading': _loading,
      'history': historyItems,
      'live': <String, Object?>{
        'messageId': messageId == null || messageId.isEmpty ? null : messageId,
        'blocks': <Map<String, Object?>>[
          for (final ChatBlock block in reducer.blocks) block.toJson(),
        ],
      },
      'pending': _pendingJson(),
      'perf': null,
    };
  }

  Map<String, Object?>? _pendingJson() {
    final PendingUiRequest? pending = _pending;
    if (pending == null) return null;
    // `ChatPending.options` 是 List<String>（纯文字），有 label 用 label，
    // 没有就退回 value —— 服务端只给字符串时两者本来就一样。
    final List<String> labels = <String>[
      for (final PendingUiOption option in pending.options)
        option.label.isNotEmpty ? option.label : option.value,
    ];
    return <String, Object?>{
      'requestId': pending.requestId,
      'method': pending.method,
      'title': pending.title,
      'options': labels,
      'placeholder': pending.placeholder,
      'prefill': pending.prefill,
      'allowOther': pending.allowOther,
      'multiple': pending.multiple,
      'batchQuestions': pending.batchQuestions,
      'batchReview': pending.batchReview,
    };
  }

  String _statusText() {
    final RunStatus status = reducer.status;
    if (status == RunStatus.running) {
      final DateTime start = _turnStart ?? DateTime.now();
      return _formatElapsed(DateTime.now().difference(start));
    }
    final DateTime? start = _turnStart;
    if (start == null) {
      return switch (status) {
        RunStatus.error => '本轮失败',
        RunStatus.aborted => '已停止',
        _ => '',
      };
    }
    final DateTime end = _turnEnd ?? DateTime.now();
    final String head = '用时 ${_formatElapsed(end.difference(start))}';
    return switch (status) {
      RunStatus.error => '$head · 本轮失败',
      RunStatus.aborted => '$head · 已停止',
      _ => '$head 完成',
    };
  }

  /* ------------------------------- 流循环 ------------------------------- */

  Future<void> _frameLoop() async {
    while (!_disposed && !_authFailed) {
      final DateTime startedAt = DateTime.now();
      bool everOpened = false;
      try {
        final Stream<SseEvent> stream = client.streamSse(
          '/api/sessions/${Uri.encodeComponent(sessionId)}/stream',
          onOpen: () {
            everOpened = true;
            _frameBackoff.reset();
            _connected = true;
            _notify();
          },
        );
        // `timeout` 在这里就是看门狗：50s 一个事件都没来 → 收掉这条流，
        // 循环到底部按「假死」立刻重连（退避归零，不等 15s）。
        await for (final SseEvent event
            in stream.timeout(stallTimeout, onTimeout: (EventSink<SseEvent> sink) => sink.close())) {
          if (_disposed) return;
          _handleFrameEvent(event);
        }
      } on PiDeckException catch (error) {
        if (_disposed) return;
        if (error.isAuth) {
          _authFailed = true;
          _errorTitle = '登录已失效';
          _errorDetail = error.describe();
          _notify();
          return;
        }
        if (!everOpened) {
          _errorTitle ??= '连不上会话';
          _errorDetail = error.describe();
        }
      } catch (_) {
        // 流被主动 cancel 也会走到这里，不当错误处理。
      }
      if (_disposed || _authFailed) return;
      _connected = false;
      _notify();
      final Duration stall = DateTime.now().difference(startedAt);
      if (everOpened && stall < stallTimeout) {
        // 连上过又秒断：多半是服务端重启/换端点，退避保护一下。
        await Future<void>.delayed(_frameBackoff.nextDelay());
      } else if (!everOpened) {
        await Future<void>.delayed(_frameBackoff.nextDelay());
      }
      // 假死（连了很久才断）不退避，立刻重连。
    }
  }

  Future<void> _stateLoop() async {
    while (!_disposed && !_authFailed) {
      bool opened = false;
      try {
        final Stream<SseEvent> stream = client.streamSse(
          '/api/events',
          onOpen: () {
            opened = true;
            _stateBackoff.reset();
          },
        );
        await for (final SseEvent event
            in stream.timeout(stallTimeout, onTimeout: (EventSink<SseEvent> sink) => sink.close())) {
          if (_disposed) return;
          _handleStateEvent(event);
        }
      } on PiDeckException catch (error) {
        if (_disposed) return;
        if (error.isAuth) {
          _authFailed = true;
          return;
        }
      } catch (_) {
        /* 主动 cancel */
      }
      if (_disposed || _authFailed) return;
      if (!opened) await _refreshState();
      await Future<void>.delayed(_stateBackoff.nextDelay());
    }
  }

  void _handleFrameEvent(SseEvent event) {
    if (event.data == sseDoneMarker) {
      // 走 api_client 里同一个 `doneFrame()`，别在这里再写一份字面量
      // （`app/tests/flutter-actions.test.ts` 会把帧类型误判成动作名）。
      _applyFrame(doneFrame());
      return;
    }
    final Map<String, Object?>? frame = safeParseFrame(event.data);
    if (frame == null) return;
    _applyFrame(frame);
  }

  void _applyFrame(Map<String, Object?> frame) {
    final RunStatus before = reducer.status;
    final FrameApplyResult result = reducer.apply(frame);
    if (frame['type'] == 'start' && before != RunStatus.running) {
      _turnStart = DateTime.now();
      _turnEnd = null;
      _errorTitle = null;
      _errorDetail = null;
    }
    if (frame['type'] == 'error') _turnEnd = DateTime.now();
    if (result.changed || reducer.status != before) _notify();
    if (result.finished) {
      _turnEnd ??= DateTime.now();
      unawaited(_settle());
    }
  }

  /// finish 之后：等 400ms（给服务端落盘）→ 重拉历史 → 清掉流式块。
  ///
  /// 少任何一步都会出现「同一段回答出现两次」或「历史里还没有这一轮」。
  Future<void> _settle() async {
    final int stamp = ++_settleStamp;
    await Future<void>.delayed(_settleDelay);
    if (_disposed || stamp != _settleStamp) return;
    await _loadHistory();
    if (_disposed || stamp != _settleStamp) return;
    reducer.clearLive();
    _notify();
  }

  void _handleStateEvent(SseEvent event) {
    if (event.data == sseDoneMarker) return;
    final Object? decoded = _tryDecode(event.data);
    if (decoded is! Map) return;
    _applyState(Map<String, Object?>.from(decoded));
  }

  Future<void> _refreshState() async {
    try {
      final StateSnapshot snapshot = await client.getState();
      final Map<String, Object?>? body = snapshot.json;
      if (snapshot.notModified || body == null) return;
      _applyState(body);
    } on PiDeckException catch (error) {
      if (!error.isAuth) return;
      _authFailed = true;
      _errorTitle = '登录已失效';
      _errorDetail = error.describe();
      _notify();
    }
  }

  void _applyState(Map<String, Object?> state) {
    _state = state;
    bool changed = false;

    final Object? sessions = state['sessions'];
    if (sessions is List) {
      for (final Object? item in sessions) {
        if (item is! Map) continue;
        if ('${item['id']}' != sessionId) continue;
        final String next = '${item['title'] ?? ''}';
        if (next.isNotEmpty && next != _title) {
          _title = next;
          changed = true;
        }
        break;
      }
    }

    PendingUiRequest? nextPending;
    final Object? pendingList = state['pendingUiRequests'];
    if (pendingList is List) {
      for (final Object? item in pendingList) {
        if (item is! Map) continue;
        final PendingUiRequest? parsed = PendingUiRequest.fromJson(Map<String, Object?>.from(item));
        if (parsed == null || parsed.sessionId != sessionId) continue;
        nextPending = parsed;
        break;
      }
    }
    if (!_samePending(_pending, nextPending)) {
      _pending = nextPending;
      changed = true;
    }

    if (changed) _notify();
  }

  Future<void> _loadHistory() async {
    try {
      final MessageWindow window = await client.getMessages(sessionId);
      if (_disposed) return;
      _messages = <Map<String, Object?>>[
        for (final Object? item in window.messages)
          if (item is Map) Map<String, Object?>.from(item),
      ];
      _loading = false;
      _historyLoaded = true;
      _errorTitle = null;
      _errorDetail = null;
      _notify();
    } on PiDeckException catch (error) {
      if (_disposed) return;
      _loading = false;
      if (error.isAuth) {
        _authFailed = true;
        _errorTitle = '登录已失效';
      } else {
        _errorTitle = '历史加载失败';
      }
      _errorDetail = error.describe();
      _notify();
    }
  }

  /* ------------------------------- 工具 ------------------------------- */

  Map<String, Object?>? _target() {
    final Object? runtimes = _state['runtimes'];
    if (runtimes is! List) return null;
    for (final Object? item in runtimes) {
      if (item is! Map) continue;
      if ('${item['sessionId']}' != sessionId) continue;
      final Object? agentId = item['agentId'];
      final Object? generation = item['runtimeGeneration'];
      if (agentId == null || generation == null) return null;
      return <String, Object?>{
        'sessionId': sessionId,
        'agentId': agentId,
        'runtimeGeneration': generation,
      };
    }
    return null;
  }

  Map<String, Object?> _mapResponseValue(
    PendingUiRequest pending,
    Map<String, Object?> response,
  ) {
    final Object? value = response['value'];
    if (value is! String || value.isEmpty) return response;
    for (final PendingUiOption option in pending.options) {
      final String label = option.label.isNotEmpty ? option.label : option.value;
      if (label == value && option.value != value) {
        return <String, Object?>{...response, 'value': option.value};
      }
    }
    return response;
  }

  bool _samePending(PendingUiRequest? a, PendingUiRequest? b) {
    if (a == null || b == null) return a == b;
    if (a.requestId != b.requestId) return false;
    if (a.method != b.method || a.title != b.title) return false;
    if (a.options.length != b.options.length) return false;
    for (int i = 0; i < a.options.length; i++) {
      if (a.options[i].value != b.options[i].value) return false;
      if (a.options[i].label != b.options[i].label) return false;
    }
    return true;
  }

  String _newRequestId() {
    final Random random = Random();
    final int stamp = DateTime.now().microsecondsSinceEpoch;
    final String tail = random.nextInt(1 << 32).toRadixString(36).padLeft(7, '0');
    return 'req_${stamp.toRadixString(36)}_$tail';
  }

  void _notify() {
    if (_disposed) return;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    // 两条流的取消靠「关掉客户端」：`streamSse` 的 async* 生成器在
    // HttpClient 关闭时抛 SocketException 并走 finally，循环随即退出。
    // 这个运行时独占自己的 client（页面不要复用给别的用途）。
    client.close();
    super.dispose();
  }
}

Object? _tryDecode(String raw) {
  try {
    return jsonDecode(raw);
  } catch (_) {
    return null;
  }
}

String _formatElapsed(Duration elapsed) {
  final int ms = elapsed.isNegative ? 0 : elapsed.inMilliseconds;
  if (ms < 60_000) return '${(ms / 1000).toStringAsFixed(1)}s';
  final int totalSeconds = ms ~/ 1000;
  final int minutes = totalSeconds ~/ 60;
  final String seconds = (totalSeconds % 60).toString().padLeft(2, '0');
  return '${minutes}m${seconds}s';
}

/// 指数退避（`src/api/backoff.ts` 同口径：800ms 起，15s 封顶，带轻微抖动）。
class _Backoff {
  static const int baseMs = 800;
  static const int maxMs = 15_000;

  int _attempt = 0;
  final Random _random = Random();

  void reset() => _attempt = 0;

  Duration nextDelay() {
    final int raw = baseMs * (1 << min(_attempt, 16));
    _attempt++;
    final int capped = min(raw, maxMs);
    // ±10% 抖动，避免多设备同时重连撞在一起
    final int jitter = ((capped * 0.2) * (_random.nextDouble() - 0.5)).round();
    return Duration(milliseconds: max(baseMs ~/ 2, capped + jitter));
  }
}
