/// 一条 SSE 流的「泵」：把 `await for` 换成可中断、可按字节续命的订阅。
///
/// 两个需求逼出来的，换回 `await for` 会同时丢掉这两条：
///
///  1. **看门狗必须按字节算，不能按事件算**。服务端空闲时会推 `: ping` 注释行，
///     它被 [SseParser] 丢掉了、不会变成一个事件 —— 拿 `Stream.timeout`（按事件计时）
///     当看门狗，会在长工具调用、长时间思考时误杀一条完全健康的连接，然后
///     用户看到「连接中」闪一下、历史重拉、滚动位置乱跳。
///     [onActivity] 每收到一块字节回调一次，这才是「连接还活着」的正确信号。
///
///  2. **回到前台要能立刻换一条新连接**。App 被系统冻结时 TCP 连接通常是死的，
///     但读操作还挂着（没人会抛错），只能等看门狗。用户回到界面是不可能盯着
///     一个不动的转圈等 50 秒的：`kick()` 直接收掉当前订阅，外层循环立刻重连。
///
///  3. **收线必须靠 [abort]，不能只靠 `cancel()`**。对 `async*` 生成器的
///     `Subscription.cancel()` 只有在生成器到达 `yield` 点时才被处理；它挂在
///     `await for` 等字节时永远到不了 `yield` 点，`cancel()` 的 Future
///     **永远不完成**（实测 3 秒仍挂着，连接也没断）。所以 `open` 拿到的
///     `abort` 必须透传给底层（`streamSse(abort: ...)`），由它结束内层流。
library;

import 'dart:async';

/// 把 `Stream<T>` 订阅包装成「可主动收掉 + 空闲超时」的单次运行。
///
/// 用法（外层都是「连上 → 循环读 → 断了重连」的结构）：
///
/// ```dart
/// final StreamPump<SseEvent> pump = StreamPump<SseEvent>(
///   open: (void Function() onActivity, Future<void> abort) =>
///       client.streamSse(path, onActivity: onActivity, abort: abort),
///   idleTimeout: const Duration(seconds: 50),
/// );
/// current = pump;                       // 让别处（返回前台）能 kick 到它
/// await pump.run(_handleEvent);          // 正常收尾或出错时返回
/// if (pump.kicked) continue;             // 被主动踢掉的不退避
/// ```
class StreamPump<T> {
  StreamPump({
    required Stream<T> Function(void Function() onActivity, Future<void> abort) open,
    this.idleTimeout = const Duration(seconds: 50),
  }) {
    _stream = open(touch, _aborted.future);
  }

  /// 多久一个字节都没收到就判定这条连接已经死了。
  final Duration idleTimeout;

  /// 交给底层的「收线」信号。完成它 = 源流结束，生成器才会走到 yield 点。
  final Completer<void> _aborted = Completer<void>();

  late final Stream<T> _stream;
  final Completer<void> _finished = Completer<void>();
  StreamSubscription<T>? _subscription;
  Timer? _idleTimer;
  bool _kicked = false;
  bool _done = false;

  /// 是否是被 [kick] 主动收掉的（这种情况重连不用退避：不是网络故障）。
  bool get kicked => _kicked;

  /// 续命一次。由 `streamSse` 的 `onActivity`（每块字节）和每个事件调用。
  void touch() {
    if (_done) return;
    _idleTimer?.cancel();
    _idleTimer = Timer(idleTimeout, _onIdle);
  }

  /// 读这条流，直到收尾 / 出错 / 被踢。错误会从返回值里抛出去。
  Future<void> run(void Function(T event) onEvent) async {
    if (_done) return;
    touch();
    _subscription = _stream.listen(
      (T event) {
        touch();
        onEvent(event);
      },
      onError: (Object error, StackTrace stack) => _finish(error: error, stack: stack),
      onDone: _finish,
      cancelOnError: true,
    );
    try {
      await _finished.future;
    } finally {
      _done = true;
      _idleTimer?.cancel();
      _idleTimer = null;
      final StreamSubscription<T>? subscription = _subscription;
      _subscription = null;
      await subscription?.cancel();
    }
  }

  /// 主动收掉这条流（回到前台换新连接）。
  void kick() {
    if (_done) return;
    _kicked = true;
    _finish();
  }

  void _onIdle() => _finish();

  void _finish({Object? error, StackTrace? stack}) {
    if (_done || _finished.isCompleted) return;
    if (!_aborted.isCompleted) _aborted.complete();
    if (error != null) {
      _finished.completeError(error, stack);
    } else {
      _finished.complete();
    }
  }
}
