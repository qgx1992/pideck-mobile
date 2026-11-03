/// 流泵自测：看门狗必须按**字节**续命，回前台必须能立刻换连接。
///
/// 两条都是「后台回来内容不刷新」的直接成因：
///  - 服务端 15s 一个 `: ping`，它是注释行、不会变成事件 —— 按事件计时的
///    `Stream.timeout` 会在长工具调用时误杀健康连接（看起来像随机断线重连）；
///  - App 被冻结后 TCP 连接是死的但读操作还挂着，没人会抛错，只能等看门狗，
///    而用户回到界面时是等不了 50 秒的。
library;

import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/runtime/stream_pump.dart';

void main() {
  test('空闲超时收掉这条流（不是错误，也没被 kick）', () async {
    final StreamController<int> source = StreamController<int>();
    final StreamPump<int> pump = StreamPump<int>(
      open: (void Function() onActivity, Future<void> abort) => source.stream,
      idleTimeout: const Duration(milliseconds: 40),
    );
    final DateTime startedAt = DateTime.now();
    final List<int> seen = <int>[];
    await pump.run(seen.add);

    expect(seen, isEmpty);
    expect(pump.kicked, isFalse);
    expect(
      DateTime.now().difference(startedAt).inMilliseconds,
      greaterThanOrEqualTo(30),
      reason: '没有字节也没有事件时必须等到 idleTimeout 才收，不能提前',
    );
    await source.close();
  });

  test('onActivity（服务端 ping）续命，不会被看门狗误杀', () async {
    final StreamController<int> source = StreamController<int>();
    late void Function() touch;
    final StreamPump<int> pump = StreamPump<int>(
      open: (void Function() onActivity, Future<void> abort) {
        touch = onActivity;
        return source.stream;
      },
      idleTimeout: const Duration(milliseconds: 50),
    );
    bool completed = false;
    final Future<void> done = pump.run((int _) {}).then((_) => completed = true);

    // 200ms 里每 15ms 来一块字节（= 服务端 ping），远超 50ms 的空闲阈值。
    for (int i = 0; i < 13; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 15));
      touch();
    }
    expect(completed, isFalse, reason: '有字节就应该一直活着');

    pump.kick();
    await done;
    expect(completed, isTrue);
    expect(pump.kicked, isTrue, reason: '被踢的要能被外层识别成「不必退避」');
    await source.close();
  });

  test('事件按序转发；流报错从 run() 抛出来', () async {
    final StreamController<int> source = StreamController<int>();
    final StreamPump<int> pump = StreamPump<int>(
      open: (void Function() onActivity, Future<void> abort) => source.stream,
      idleTimeout: const Duration(seconds: 5),
    );
    final List<int> seen = <int>[];
    final Future<void> done = pump.run(seen.add);

    source.add(1);
    source.add(2);
    await Future<void>.delayed(const Duration(milliseconds: 10));
    expect(seen, <int>[1, 2]);

    source.addError(StateError('boom'));
    await expectLater(done, throwsA(isA<StateError>()));
    await source.close();
  });

  test('kick 之后不再转发事件（回前台换连接时不能漏进旧流的数据）', () async {
    final StreamController<int> source = StreamController<int>();
    final StreamPump<int> pump = StreamPump<int>(
      open: (void Function() onActivity, Future<void> abort) => source.stream,
      idleTimeout: const Duration(seconds: 5),
    );
    final List<int> seen = <int>[];
    final Future<void> done = pump.run(seen.add);

    source.add(1);
    await Future<void>.delayed(const Duration(milliseconds: 10));
    pump.kick();
    await done;
    source.add(2);
    await Future<void>.delayed(const Duration(milliseconds: 10));

    expect(seen, <int>[1]);
    await source.close();
  });

  test('重复 kick / run 结束后再 kick 都是空操作', () async {
    final StreamController<int> source = StreamController<int>();
    final StreamPump<int> pump = StreamPump<int>(
      open: (void Function() onActivity, Future<void> abort) => source.stream,
      idleTimeout: const Duration(seconds: 5),
    );
    final Future<void> done = pump.run((int _) {});
    pump.kick();
    pump.kick();
    await done;
    pump.kick(); // 已结束：不能抛
    await source.close();
  });
}
