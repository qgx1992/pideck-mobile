/// 指数退避 + 抖动（`src/api/backoff.ts` 的 Dart 版）。
///
/// 为什么不用「固定间隔重连」：手机几乎总在切网 —— 地铁里断 30 秒、回到桌面又连上。
/// 固定 1 秒重连会让 5 条流（会话帧、状态、每台设备的状态）同时锤服务端；
/// 固定 15 秒又会让「切回 Wi-Fi 后要等十几秒才恢复」。指数 + ±10% 抖动是这里唯一
/// 的节流策略，两条流共用同一份语义，不要各自发明。
library;

import 'dart:math';

class Backoff {
  Backoff({
    this.baseMs = 1000,
    this.maxMs = 15000,
    this.factor = 2,
    this.jitter = 0.2,
    Random? random,
  }) : _random = random ?? Random();

  final int baseMs;
  final int maxMs;
  final num factor;
  final double jitter;
  final Random _random;

  int _attempt = 0;

  /// 已经重试了几次（0 = 还没重试过）。
  int get attempts => _attempt;

  /// 第 n 次重试前该等多久（毫秒）。调用即把计数 +1。
  int nextDelay() {
    final double raw = min(maxMs.toDouble(), baseMs * pow(factor, _attempt).toDouble());
    _attempt += 1;
    final double spread = raw * jitter;
    final double jittered = raw - spread + _random.nextDouble() * spread * 2;
    return max(0, jittered.round());
  }

  void reset() => _attempt = 0;
}
