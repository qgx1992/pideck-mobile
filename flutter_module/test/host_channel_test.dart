import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/host_channel.dart';

/// 回归：Dart → 宿主的动作必须包在 Map 里。
///
/// 宿主的 `MethodCall.argument("json")` 在 arguments 不是 Map 时会抛
/// ClassCastException（不是返回 null），异常从 setMethodCallHandler 里逃出去，
/// Dart 这边只看到一个被 catch 掉的 PlatformException —— 用户看到的就是
/// 「设置页上点什么都没反应」。0.1.21 就踩了这个。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() => messenger.setMockMethodCallHandler(hostChannel, null));

  test('sendSettingsAction 把动作包成 {json: "..."}', () async {
    MethodCall? captured;
    messenger.setMockMethodCallHandler(hostChannel, (call) async {
      captured = call;
      return null;
    });

    await sendSettingsAction(<String, Object?>{
      'type': 'toggleSkill',
      'sourceId': 'pi-global',
      'name': 'usage-probe',
      'enabled': true,
    });

    expect(captured, isNotNull);
    expect(captured!.method, 'settingsAction');
    expect(
      captured!.arguments,
      isA<Map<Object?, Object?>>(),
      reason: '宿主只认 Map，直接传字符串会抛 ClassCastException',
    );
    final raw = (captured!.arguments as Map<Object?, Object?>)['json'];
    expect(raw, isA<String>());
    expect(jsonDecode(raw! as String), <String, Object?>{
      'type': 'toggleSkill',
      'sourceId': 'pi-global',
      'name': 'usage-probe',
      'enabled': true,
    });
  });

  test('closeHost 走独立的 close 方法，不带参数', () async {
    MethodCall? captured;
    messenger.setMockMethodCallHandler(hostChannel, (call) async {
      captured = call;
      return null;
    });

    await closeHost();

    expect(captured!.method, 'close');
    expect(captured!.arguments, isNull);
  });

  test('宿主不在（channel 报错）时不抛异常', () async {
    messenger.setMockMethodCallHandler(hostChannel, (call) async {
      throw PlatformException(code: 'unavailable');
    });

    await expectLater(sendSettingsAction(<String, Object?>{'type': 'close'}), completes);
    await expectLater(closeHost(), completes);
  });
}
