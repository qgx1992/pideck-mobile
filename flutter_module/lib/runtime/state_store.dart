/// 全局状态仓（`src/state/store.ts` 的 Dart 版）：`/api/events` 推快照，
/// 不可流式时退化为 `/api/state` 轮询。
///
/// 设计要点（与 TS 版逐条对齐，改之前先看那份注释）：
///   - 服务端每 400ms 去抖推一次快照，另有 5s 兜底 tick，客户端不需要高频轮询；
///   - SSE 断线没有 Last-Event-ID，重连后必须重新拉 `/api/state` 对账；
///   - 401 是终态：停止重连，交给 UI 引导重新配对；
///   - **会话列表变化不在 revision 判据里**（新建/删除/改名），所以即使流是好的，
///     也要留一个低频兜底轮询（前台 30s）。
library;

import 'dart:async';

import 'api_client.dart';
import 'backoff.dart';
import 'stream_pump.dart';

/// 状态仓的连接状态（与 TS 的 `StoreStatus` 同字符串）。
enum StoreStatus {
  idle('idle'),
  connecting('connecting'),
  live('live'),
  polling('polling'),
  reconnecting('reconnecting'),
  authRequired('auth-required'),
  closed('closed');

  const StoreStatus(this.wire);

  /// 过桥/日志用的字符串值。
  final String wire;
}

/// 空状态：服务端字段缺失时的兜底（`emptyState()`）。
Map<String, Object?> emptyStateJson() => <String, Object?>{
      'projects': <Object?>[],
      'sessions': <Object?>[],
      'runtimes': <Object?>[],
      'pendingUiRequests': <Object?>[],
    };

/// 防御性归一：缺字段补空数组，security/acpTools 有就原样透传。
Map<String, Object?> normalizeStateJson(Object? raw) {
  if (raw is! Map) return emptyStateJson();
  final Map<String, Object?> out = <String, Object?>{
    'projects': _asObjectList(raw['projects']),
    'sessions': _asObjectList(raw['sessions']),
    'runtimes': _asObjectList(raw['runtimes']),
    'pendingUiRequests': _asObjectList(raw['pendingUiRequests']),
  };
  final Object? security = raw['security'];
  if (security is Map) out['security'] = security;
  if (raw['acpTools'] is List) out['acpTools'] = raw['acpTools'];
  return out;
}

List<Map<String, Object?>> _asObjectList(Object? value) {
  if (value is! List) return <Map<String, Object?>>[];
  return <Map<String, Object?>>[
    for (final Object? item in value)
      if (item is Map) Map<String, Object?>.from(item),
  ];
}

class StateStore {
  StateStore({
    required this.client,
    this.pollMs = 5000,
    this.fallbackPollMs = 30000,
    this.onStatus,
    this.onError,
    this.onReady,
  });

  final PiDeckClient client;

  /// 不支持 SSE 时的轮询间隔。
  final int pollMs;

  /// SSE 正常时的兜底轮询间隔（会话列表变化只能靠它推下来）。
  final int fallbackPollMs;

  /// 空闲多久没收到字节就判定这条状态流已经死了。
  ///
  /// 比聊天流（50s）短一点：状态流是全局的，断掉会让侧栏/运行目标一起变旧。
  /// 服务端 15s 一个 ping，这里留三倍余量；ping 是注释行，按**字节**续命才有意义。
  static const Duration streamIdleTimeout = Duration(seconds: 45);

  final void Function(StoreStatus status)? onStatus;
  final void Function(Object error)? onError;

  /// 首次快照到达。
  final void Function(Map<String, Object?> state)? onReady;

  final List<void Function(Map<String, Object?> state)> _listeners =
      <void Function(Map<String, Object?> state)>[];

  Map<String, Object?> _state = emptyStateJson();
  String? _etag;
  bool _running = false;
  bool _firstReady = false;
  StoreStatus _status = StoreStatus.idle;
  Future<void>? _refreshing;
  StreamPump<Map<String, Object?>>? _statePump;
  Timer? _pollTimer;
  int _pollIntervalMs = 0;

  Map<String, Object?> get state => _state;

  StoreStatus get status => _status;

  /// 是否已经成功拿到过一份完整状态。
  ///
  /// 侧栏在它为 false 时**不能**接管：空状态算出来的快照是「一条会话都没有」，
  /// 用户会看到抽屉整个空了。这种情况继续画 WebView 推来的那份。
  bool get ready => _firstReady;

  /// 订阅状态变化；返回取消函数。
  void Function() subscribe(void Function(Map<String, Object?> state) listener) {
    _listeners.add(listener);
    return () => _listeners.remove(listener);
  }

  /* ---------------------------- 选择器 ---------------------------- */

  List<Map<String, Object?>> projects() => _asObjectList(_state['projects']);

  List<Map<String, Object?>> sessionsOf(String projectId) {
    final List<Map<String, Object?>> list = <Map<String, Object?>>[
      for (final Map<String, Object?> session in _asObjectList(_state['sessions']))
        if ('${session['projectId'] ?? ''}' == projectId) session,
    ];
    list.sort((Map<String, Object?> a, Map<String, Object?> b) {
      final num left = _numOf(b['updatedAt']);
      final num right = _numOf(a['updatedAt']);
      return left.compareTo(right);
    });
    return list;
  }

  Map<String, Object?>? session(String sessionId) {
    for (final Map<String, Object?> session in _asObjectList(_state['sessions'])) {
      if ('${session['id'] ?? ''}' == sessionId) return session;
    }
    return null;
  }

  /// `findTarget(state, sessionId)`：运行目标只在 `runtimes` 里。
  Map<String, Object?>? targetOf(String sessionId) {
    for (final Map<String, Object?> item in _asObjectList(_state['runtimes'])) {
      if ('${item['sessionId'] ?? ''}' == sessionId) {
        return <String, Object?>{
          'sessionId': item['sessionId'],
          'agentId': item['agentId'],
          'runtimeGeneration': item['runtimeGeneration'],
        };
      }
    }
    return null;
  }

  List<Map<String, Object?>> pendingFor(String sessionId) {
    return <Map<String, Object?>>[
      for (final Map<String, Object?> item in _asObjectList(_state['pendingUiRequests']))
        if ('${item['sessionId'] ?? ''}' == sessionId) item,
    ];
  }

  /* ---------------------------- 拉取 ---------------------------- */

  /// 同一时刻只发一个 `/api/state` 请求（后台恢复时可能被多处同时触发）。
  Future<void> refresh() {
    final Future<void>? inflight = _refreshing;
    if (inflight != null) return inflight;
    final Future<void> next = _doRefresh().whenComplete(() => _refreshing = null);
    _refreshing = next;
    return next;
  }

  Future<void> _doRefresh() async {
    final StateSnapshot snapshot = await client.getState(etag: _etag);
    if (snapshot.notModified) return;
    _etag = snapshot.etag;
    _replace(normalizeStateJson(snapshot.json));
  }

  void _replace(Map<String, Object?> next) {
    _state = next;
    for (final void Function(Map<String, Object?> state) listener
        in List<void Function(Map<String, Object?> state)>.from(_listeners)) {
      try {
        listener(next);
      } on Object catch (error) {
        onError?.call(error);
      }
    }
  }

  void _setStatus(StoreStatus next) {
    if (_status == next) return;
    _status = next;
    onStatus?.call(next);
  }

  /* ---------------------------- 生命周期 ---------------------------- */

  void start() {
    if (_running) return;
    _running = true;
    unawaited(_loop());
  }

  /// 回到前台立即对账一次（SSE 在后台通常已被系统挂起）。
  ///
  /// 顺序很重要：**先踢流，再对账**。后台期间那条连接多半已经死了但读操作还挂着，
  /// 不踢掉它就只能等 [streamIdleTimeout] 的超时，而用户这时正看着屏幕。
  /// 踢掉之后 `_loop` 会立刻重连（被踢的不退避），重连前自己也会再对账一次，
  /// 两边的 `refresh()` 由 inflight 去重合并成一个请求。
  Future<void> noteForeground() async {
    if (!_running) return;
    _statePump?.kick();
    try {
      await refresh();
    } on Object catch (error) {
      onError?.call(error);
    }
  }

  Future<void> _loop() async {
    final Backoff backoff = Backoff();
    int consecutiveFailures = 0;

    while (_running) {
      _setStatus(backoff.attempts == 0 ? StoreStatus.connecting : StoreStatus.reconnecting);
      // 每次（重）连之前先对账，避免断线期间漏掉的变更。
      try {
        await refresh();
      } on PiDeckException catch (error) {
        if (error.isAuth) {
          _setStatus(StoreStatus.authRequired);
          onError?.call(error);
          return;
        }
        onError?.call(error);
      }

      try {
        final StreamPump<Map<String, Object?>> pump = StreamPump<Map<String, Object?>>(
          open: (void Function() onActivity, Future<void> abort) => client.stateEvents(
            onOpen: () {
              _setStatus(StoreStatus.live);
              // SSE 只推「运行时/pendingUi」相关变化，会话列表得靠轮询兜底。
              _startPolling(fallbackPollMs);
            },
            onActivity: onActivity,
            abort: abort,
          ),
          idleTimeout: streamIdleTimeout,
        );
        _statePump = pump;
        await pump.run((Map<String, Object?> snapshot) {
          if (!_running) return;
          _etag = null;
          _replace(normalizeStateJson(snapshot));
          if (!_firstReady) {
            _firstReady = true;
            onReady?.call(_state);
          }
          _setStatus(StoreStatus.live);
          consecutiveFailures = 0;
          backoff.reset();
          _startPolling(fallbackPollMs);
        });
        _statePump = null;
        // 被主动踢掉（回前台换连接）：立刻重连，不进退避。
        if (pump.kicked) continue;
        // 服务端正常收尾：退避后重连。
        consecutiveFailures = 0;
      } on PiDeckException catch (error) {
        if (!_running) break;
        if (error.isAuth) {
          _setStatus(StoreStatus.authRequired);
          onError?.call(error);
          break;
        }
        consecutiveFailures += 1;
        onError?.call(error);
        // 环境根本不支持流式：退化轮询。
        if (error.kind == PiDeckErrorKind.protocol || consecutiveFailures >= 3) {
          _startPolling(pollMs);
          _setStatus(StoreStatus.polling);
          return;
        }
      } on Object catch (error) {
        if (!_running) break;
        consecutiveFailures += 1;
        onError?.call(error);
        if (consecutiveFailures >= 3) {
          _startPolling(pollMs);
          _setStatus(StoreStatus.polling);
          return;
        }
      }

      if (!_running) break;
      await Future<void>.delayed(Duration(milliseconds: backoff.nextDelay()));
    }
    _setStatus(_status == StoreStatus.authRequired ? StoreStatus.authRequired : StoreStatus.closed);
  }

  void _startPolling(int intervalMs) {
    if (_pollTimer != null && _pollIntervalMs == intervalMs) return;
    _stopPolling();
    _pollIntervalMs = intervalMs;
    _pollTimer = Timer.periodic(Duration(milliseconds: intervalMs), (Timer timer) {
      if (!_running) return;
      unawaited(refresh().catchError((Object error) {
        onError?.call(error);
      }));
    });
  }

  void _stopPolling() {
    _pollTimer?.cancel();
    _pollTimer = null;
    _pollIntervalMs = 0;
  }

  void stop() {
    _running = false;
    _statePump?.kick();
    _statePump = null;
    _stopPolling();
    _setStatus(StoreStatus.closed);
  }
}

num _numOf(Object? value) {
  if (value is num) return value;
  if (value is String) return num.tryParse(value) ?? 0;
  return 0;
}
