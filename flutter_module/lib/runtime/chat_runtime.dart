import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';

import 'api_client.dart';
import 'frames.dart';
import 'history_model.dart';
import 'prompt_payload.dart';
import 'sse.dart';
import 'state_store.dart';
import 'stream_pump.dart';

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
    this.onKickRunWatch,
  }) {
    store = StateStore(
      client: client,
      onStatus: (StoreStatus status) {
        _storeStatus = status;
        _notify();
      },
      onError: (Object error) => _reportStoreError(error),
    );
  }

  final PiDeckClient client;
  final String sessionId;

  /// 需要给用户看的提示（发送失败之类），由页面决定怎么显示。
  final void Function(String message)? onNotice;

  /// 发送成功后拉起后台看护（原生前台服务）。
  ///
  /// 为什么由页面注入：启动前台服务是 Android 通道的事，运行时这一层不碰平台 API。
  /// 时机很关键 —— 用户常常发完就上滑回桌面，等 SSE 把 running 推下来再起服务，
  /// 进程可能已经被冻结（`main.ts` 的 `kickRunWatch` 注释里记着同一个坑）。
  final void Function()? onKickRunWatch;

  /// 全局状态仓（会话标题 / 运行目标 / 待答请求的唯一来源）。
  ///
  /// 与 WebView 的 `StateStore` 同语义：`/api/events` 推快照，`/api/state` 对账。
  /// 页面里的侧栏快照也从它的 [StateStore.state] 算出来，不再等 WebView 推。
  late final StateStore store;

  /// 协议唯一状态机（与 `src/api/frames.ts` 的 `ChatReducer` 一一对应）。
  final ChatReducer reducer = ChatReducer();

  /// 服务端心跳 15s 一次；50s 没帧就是假死（与 `src/session.ts` 的 `stallMs` 同值）。
  static const Duration stallTimeout = Duration(seconds: 50);

  static const Duration _settleDelay = Duration(milliseconds: 400);

  /// 挂出提问后多久重拉历史（服务端要先把这条消息落盘）。
  static const Duration _promptEchoDelay = Duration(milliseconds: 650);

  List<Map<String, Object?>> _messages = <Map<String, Object?>>[];

  /// 乐观回显：自己刚发出去、但服务端历史里还没有的用户消息。
  ///
  /// Web 侧对应 `chatView.appendLocalUser()`。等 `_loadHistory` 把它落进历史就撤掉，
  /// 否则「刚发出去那一瞬间」屏幕上什么都不动，用户会以为没发出去。
  final List<Map<String, Object?>> _optimistic = <Map<String, Object?>>[];

  /// 当前那条帧流（回前台时要换掉它，见 [resume]）。
  StreamPump<SseEvent>? _framePump;

  PendingUiRequest? _pending;

  /// 正在提交（用于把状态显示成 running，否则发送到第一帧之间按钮是灰的）。
  bool _sending = false;

  /// 已投递成功、但还没收到第一帧 `start` 的时刻（见 [submit]）。
  DateTime? _awaitingTurn;
  StoreStatus _storeStatus = StoreStatus.idle;

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

  /// 历史读不到时的静默重试（当前只用于「会话文件还没写出来」，见 [_loadHistory]）。
  Timer? _historyRetryTimer;
  int _historyRetries = 0;
  bool _noticedMissingTranscript = false;

  /// 静默重试的间隔（测试会调小它，正式路径用 3 秒）。
  Duration historyRetryDelay = const Duration(seconds: 3);

  static const int _maxHistoryRetries = 5;

  final _Backoff _frameBackoff = _Backoff();

  /// 状态机可读字段（页面/测试用）。
  String get title => _title;
  bool get loading => _loading;
  bool get historyLoaded => _historyLoaded;
  bool get connected => _connected;
  PendingUiRequest? get pending => _pending;
  List<Map<String, Object?>> get historyItems => historyItemsOf(_messages);
  RunStatus get status => reducer.status;
  String? get error => _errorDetail ?? _errorTitle;

  /// 打开页面时调一次：起状态仓 + 拉历史 + 起帧流。重复调用无副作用。
  Future<void> start() async {
    if (_started || _disposed) return;
    _started = true;
    store.subscribe(_onStoreState);
    store.start();
    unawaited(_loadHistory());
    unawaited(_frameLoop());
  }

  /// 回到前台：把「后台里变成僵尸的连接」换掉，并把数据重新对账一遍。
  ///
  /// 为什么不能只等看门狗：App 被冻结时 TCP 连接大多是死的，但读操作还挂着、
  /// 没有人会抛错，用户只能盯着一个不动的界面等满 [stallTimeout]（50 秒）——
  /// 这就是「从后台回来内容不刷新」的主因。回前台必须自己动手：
  ///
  ///  1. `kick()` 收掉当前帧流 → `_frameLoop` 立刻重连（不退避）；
  ///  2. 状态仓对账（标题 / 待答卡片都靠它）+ 重拉历史（后台期间可能已经跑完）；
  ///  3. 后台期间跑完的那一轮，`finished` 帧永远收不到了：看状态仓里还有没有
  ///     这个会话的运行目标，没有就本地收口，否则按钮停在「停止」、计时器一直转
  ///     （与 TS 的 `settleStaleTurn()` 同义）。
  Future<void> resume() async {
    if (_disposed) return;
    _framePump?.kick();
    await store.noteForeground();
    if (_disposed) return;
    await _loadHistory(quiet: true);
    if (_disposed) return;
    if (store.ready &&
        reducer.status == RunStatus.running &&
        store.targetOf(sessionId) == null) {
      _applyFrame(doneFrame());
    }
  }

  /* ------------------------------- 写动作 ------------------------------- */

  /// 发送一轮提问（`main.ts` 的 `send()` 逐条搬过来）。
  ///
  /// 一次发送要处理的事情，少一件就会出可见的 bug：
  ///   1. **计划模式**：可见消息保原文，另塞带隐藏标记的 `agentMessage`，
  ///      且发完立刻关掉（一次性语义，否则后续提问一直被限制成只读）；
  ///   2. **排队 / 插话**：只在运行中带 `streamingBehavior`，不支持插话的后端降级成排队；
  ///   3. **reducer 归零**：上一轮的 `finished` 会让新帧走「追加」分支，屏幕上留下残块；
  ///   4. **乐观回显**：先把气泡上屏，不等服务端落盘；
  ///   5. **拒收回滚**：桌面端会回 `accepted:false`，以前完全没看返回值，
  ///      表现就是「气泡上屏了、什么都没发生、也没有任何提示」；
  ///   6. **拉起后台看护**：发完用户可能立刻回桌面，前台服务必须现在就开始守。
  ///
  /// 返回值：是否被服务端接受（`false` 时页面不应该清空输入框里的图）。
  Future<bool> submit(
    String text, {
    List<Map<String, Object?>> images = const <Map<String, Object?>>[],
    List<String> imageDataUrls = const <String>[],
    bool planMode = false,
    String sendMode = sendModeQueue,
    bool canSteer = false,
  }) async {
    final String trimmed = text.trim();
    // 只发图不写字时给服务端一句自然的话：/prompt 的 message 不能为空（400 messageRequired）。
    final String visible = trimmed.isEmpty && images.isNotEmpty ? '看下这张图' : trimmed;
    if (visible.isEmpty) return false;

    final PlanSubmission submission = buildPlanSubmission(visible, planMode);
    final bool running = reducer.status == RunStatus.running;
    // 运行中才带；不支持插话的后端一律降级成排队（与 `streamingBehaviorFor` 一致）。
    final String? behavior = streamingBehaviorFor(running, canSteer ? sendMode : sendModeQueue);

    // 新提问 = 上一轮结束。运行中（插话/排队）时不能归零，那会把正在流的内容清掉。
    if (!running) reducer.reset();
    _errorTitle = null;
    _errorDetail = null;
    if (!running) {
      _turnStart = DateTime.now();
      _turnEnd = null;
    }
    _loading = false;
    _sending = true;

    final Map<String, Object?> echo = <String, Object?>{
      'kind': 'message',
      'id': null,
      'role': 'user',
      'text': visible,
      'thinking': '',
      'time': _clockOf(DateTime.now()),
      'images': imageDataUrls,
      'toolName': '',
      'toolState': '',
      'toolDetail': '',
    };
    _optimistic.add(echo);
    // 服务端已经收下了，但第一帧 `start` 可能还要几十毫秒才到。这段空窗如果
    // 按 idle 画，发送键会重新变亮、状态行闪回「空闲」—— 用户看到的是
    // 「我点了发送，没反应」。先记一笔「等 start」，由 `_applyFrame` 收掉。
    if (!running) _awaitingTurn = DateTime.now();
    _notify();

    try {
      final SendPromptResult result = await client.sendPrompt(
        sessionId,
        requestId: _newRequestId(),
        message: submission.message,
        images: images,
        streamingBehavior: behavior,
        extra: submission.agentMessage == null
            ? const <String, Object?>{}
            : <String, Object?>{'agentMessage': submission.agentMessage},
      );
      if (!result.accepted) {
        final PromptRejection rejection = result.rejection ?? const PromptRejection();
        if (rejection.shouldRollback) _optimistic.remove(echo);
        // 回滚了就不存在「等 start」这回事；投递未知时保留气泡，状态照旧由帧决定。
        if (rejection.shouldRollback) _awaitingTurn = null;
        final String message = rejectionMessage(rejection);
        onNotice?.call(
          rejection.shouldRollback ? message : '$message\n（这条可能已经发出去了，稍后以历史为准）',
        );
        _notify();
        return false;
      }
      // 服务端收下了：立刻拉起后台看护（前台服务），再排一次历史对账。
      if (onKickRunWatch != null) {
        try {
          onKickRunWatch!.call();
        } on Object {
          // 起服务失败不该把一次成功的发送变成失败。
        }
      }
      _scheduleEchoCheck(visible);
      return true;
    } on PiDeckException catch (error) {
      _optimistic.remove(echo);
      _awaitingTurn = null;
      _errorTitle = '发送失败';
      _errorDetail = error.describe();
      _notify();
      onNotice?.call(error.describe());
      return false;
    } finally {
      _sending = false;
      _notify();
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

  /// 用户刚在 WebView 侧把消息发出去（回退路径用）。
  ///
  /// 服务端落盘之后历史里才有这条用户消息，而「这一轮结束」还要等很久；
  /// 这里排一次短延迟重拉，让刚发出去的提问尽快出现在列表里。
  void notifyPromptSent() => _scheduleEchoCheck('');

  /// 排一次短延迟历史对账：服务端要先把这条提问落盘。
  void _scheduleEchoCheck(String visible) {
    Timer(_promptEchoDelay, () {
      if (_disposed) return;
      unawaited(_reloadAndDropEcho(visible));
    });
  }

  Future<void> _reloadAndDropEcho(String visible) async {
    await _loadHistory();
    if (_disposed) return;
    // 历史里已经有这条（按文本比对最近几条用户消息），就可以把乐观气泡撤掉了；
    // 还没落盘就继续挂着，等下一轮 reload（settle 时还会拉一次）。
    if (_optimistic.isEmpty) return;
    final bool persisted = _messages.reversed
        .take(3)
        .any((Map<String, Object?> item) => '${item['role'] ?? ''}' == 'user' && _textOf(item) == visible);
    if (persisted || visible.isEmpty) {
      _optimistic.clear();
      _notify();
    }
  }

  /// 历史条目里的正文。
  ///
  /// 消息的 `content` 可能是字符串，也可能是块数组（`[{type:'text',text:…}]`）——
  /// 只认字符串会让对账永远失败，用户那句乐观气泡就再也撤不掉。
  String _textOf(Map<String, Object?> item) => messageText(item).trim();

  /* ------------------------------- 快照 ------------------------------- */

  /// 产出 `ChatSnapshot` 形状的 JSON。
  ///
  /// 只包含**数据面**字段；`messageMenus` / `header` / `picker` / `suggestions` /
  /// `sheet` / `workspace` / `themeMode` 由 WebView 那份快照合并进来（Dart 不猜）。
  Map<String, Object?> snapshotJson() {
    final String? messageId = reducer.messageId;
    // 从「点了发送」到「服务端推第一个 start 帧」之间没有 running 状态，
    // 按钮会一直是灰的；这里先按 running 画，帧到了自然接管。
    // 「刚发出去、start 帧还没到」也算运行中：否则发送键会闪回可点状态。
    final bool awaiting = _awaitingTurn != null && reducer.status == RunStatus.idle;
    final bool sendingIdle = (_sending || awaiting) && reducer.status != RunStatus.running;
    return <String, Object?>{
      'sessionId': sessionId,
      // 标题来自 `/api/events`；还没到时**不要**写这个键，否则会把
      // WebView 那边正确的标题盖成 "PiDeck"。
      if (_title.isNotEmpty) 'title': _title,
      'status': sendingIdle ? RunStatus.running.name : reducer.status.name,
      'statusText': sendingIdle ? _statusText(at: RunStatus.running) : _statusText(),
      'error': _errorTitle == null
          ? null
          : <String, Object?>{
              'title': _errorTitle!,
              if (_errorDetail != null && _errorDetail!.isNotEmpty) 'detail': _errorDetail,
            },
      'empty': null,
      'loading': _loading,
      // 乐观回显接在历史尾部：刚发出去那句必须立刻出现，不能等服务端落盘。
      'history': <Map<String, Object?>>[...historyItems, ..._optimistic],
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

  String _statusText({RunStatus? at}) {
    final RunStatus status = at ?? reducer.status;
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
        final StreamPump<SseEvent> pump = StreamPump<SseEvent>(
          open: (void Function() onActivity, Future<void> abort) => client.streamSse(
            '/api/sessions/${Uri.encodeComponent(sessionId)}/stream',
            onOpen: () {
              everOpened = true;
              _frameBackoff.reset();
              _connected = true;
              _notify();
            },
            onActivity: onActivity,
            abort: abort,
          ),
          // 看门狗：50s 一个字节都没收到 → 收掉这条流，循环到底部立刻重连
          // （被踢/假死的不等退避）。按字节而不是按事件算：ping 是注释行，
          // 它不会变成事件，按事件计时会在长工具调用时误杀健康连接。
          idleTimeout: stallTimeout,
        );
        _framePump = pump;
        await pump.run((SseEvent event) {
          if (_disposed) return;
          _handleFrameEvent(event);
        });
        _framePump = null;
        // 回前台主动换连接：不是网络坏了，不要退避。
        if (pump.kicked) {
          _connected = false;
          _notify();
          continue;
        }
      } on PiDeckException catch (error) {
        _framePump = null;
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
        _framePump = null;
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
    // 状态一旦离开 idle（start / error / finish），「等 start」就不成立了。
    if (reducer.status != RunStatus.idle) _awaitingTurn = null;
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
    _awaitingTurn = null;
    _notify();
  }

  /// 状态仓每次替换快照后回调：只挑这个会话关心的三样（标题 / 待答请求 / 运行目标）。
  void _onStoreState(Map<String, Object?> state) {
    bool changed = false;

    final Map<String, Object?>? session = store.session(sessionId);
    final String next = '${session?['title'] ?? ''}';
    if (next.isNotEmpty && next != _title) {
      _title = next;
      changed = true;
    }

    PendingUiRequest? nextPending;
    for (final Map<String, Object?> item in store.pendingFor(sessionId)) {
      final PendingUiRequest? parsed = PendingUiRequest.fromJson(item);
      if (parsed == null) continue;
      nextPending = parsed;
      break;
    }
    if (!_samePending(_pending, nextPending)) {
      _pending = nextPending;
      changed = true;
    }

    if (changed) _notify();
  }

  void _reportStoreError(Object error) {
    if (_disposed) return;
    if (error is PiDeckException && error.isAuth) {
      _authFailed = true;
      _errorTitle = '登录已失效';
      _errorDetail = error.describe();
      _notify();
    }
  }

  /// 状态仓的连接状态（页面可以据此提示「正在重连」）。
  StoreStatus get storeStatus => _storeStatus;

  Future<void> _refreshState() async {
    try {
      await store.refresh();
    } on PiDeckException catch (error) {
      if (!error.isAuth) return;
      _authFailed = true;
      _errorTitle = '登录已失效';
      _errorDetail = error.describe();
      _notify();
    }
  }

  Future<void> _loadHistory({bool quiet = false}) async {
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
      _historyRetries = 0;
      _historyRetryTimer?.cancel();
      _historyRetryTimer = null;
      _noticedMissingTranscript = false;
      _notify();
    } on PiDeckException catch (error) {
      if (_disposed) return;
      _loading = false;
      // 这一轮「读过了」——不管成没成，页面都得有一份数据可以画。
      // 留着 false 的话合并会退回 WebView 那份，而它这时通常停在 `loading: true`，
      // 用户看到的就是一直转圈（0.2.17 修）。
      _historyLoaded = true;
      if (error.isAuth) {
        _authFailed = true;
        _errorTitle = '登录已失效';
        _errorDetail = error.describe();
        _notify();
        return;
      }
      // 桌面端在会话文件还没写出来时对 `/messages` 回 500（新建会话、还没发第一条）。
      // 那不是「历史读坏了」，是「还没有历史」：当空历史处理，隔几秒再静默重试。
      if (_isMissingTranscript(error)) {
        _errorTitle = null;
        _errorDetail = null;
        if (!_noticedMissingTranscript && !quiet) {
          _noticedMissingTranscript = true;
          onNotice?.call('这个会话还没有历史（电脑端还没写出会话文件），可以先发消息');
        }
        _scheduleHistoryRetry();
        _notify();
        return;
      }
      // quiet（回前台的后台对账）失败时不把已经看得见的内容换成错误卡：
      // 刚回前台网络抖一下，满屏报错比不刷新还难受。
      if (!quiet || _messages.isEmpty) {
        _errorTitle = '历史加载失败';
        _errorDetail = error.describe();
      }
      _notify();
    }
  }
  /// 桌面端「会话文件还没写出来」时回的是 5xx（不是 404，也不是空数组）。
  ///
  /// 新建的会话要等第一条消息落盘才有 transcript 文件；在此之前
  /// `GET /api/sessions/:id/messages` 稳定回 500 `webError.internal`。
  /// 这里按「空历史」处理并安排一次重试 —— 用户点进新会话本来就该看到空态。
  bool _isMissingTranscript(PiDeckException error) {
    final int? status = error.status;
    return status != null && status >= 500;
  }

  void _scheduleHistoryRetry() {
    if (_disposed || _historyRetryTimer != null) return;
    if (_historyRetries >= _maxHistoryRetries) return;
    _historyRetries += 1;
    _historyRetryTimer = Timer(historyRetryDelay, () {
      _historyRetryTimer = null;
      if (_disposed) return;
      unawaited(_loadHistory());
    });
  }

  /* ------------------------------- 工具 ------------------------------- */

  /// 运行目标（`src/runtime/target.ts` 的 `findTarget`）：只在 `runtimes` 里。
  Map<String, Object?>? _target() {
    final Map<String, Object?>? target = store.targetOf(sessionId);
    if (target == null) return null;
    if (target['agentId'] == null || target['runtimeGeneration'] == null) return null;
    return target;
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
    _historyRetryTimer?.cancel();
    _historyRetryTimer = null;
    // 先把帧流收掉，否则它要等 50s 看门狗或者下一个字节才退。
    _framePump?.kick();
    _framePump = null;
    store.stop();
    // 两条流的取消靠「关掉客户端」：`streamSse` 的 async* 生成器在
    // HttpClient 关闭时抛 SocketException 并走 finally，循环随即退出。
    // 这个运行时独占自己的 client（页面不要复用给别的用途）。
    client.close();
    super.dispose();
  }
}

/// 「时:分」时钟（本地时区、24 小时制），与 `dom.ts` 的 `clockOf()` 同口径。
String _clockOf(DateTime time) =>
    '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';

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
