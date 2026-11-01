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

  group('pickImage', () {
    test('解析宿主回传的图（裸 base64 + 尺寸）', () async {
      messenger.setMockMethodCallHandler(hostChannel, (call) async {
        expect(call.method, 'pickImage');
        expect(call.arguments, isNull);
        return <String, Object?>{
          'data': base64Encode(<int>[1, 2, 3, 4]),
          'mimeType': 'image/jpeg',
          'width': 1200,
          'height': 900,
          'bytes': 4,
          'name': 'IMG_0001.jpg',
        };
      });

      final PickedImage? image = await pickImage();
      expect(image, isNotNull);
      expect(image!.bytesOf, <int>[1, 2, 3, 4]);
      expect(image.dataUrl, 'data:image/jpeg;base64,${base64Encode(<int>[1, 2, 3, 4])}');
      expect(image.width, 1200);
      expect(image.height, 900);
      expect(image.name, 'IMG_0001.jpg');
    });

    test('用户取消（宿主回 null）时返回 null', () async {
      messenger.setMockMethodCallHandler(hostChannel, (call) async => null);
      expect(await pickImage(), isNull);
    });

    test('宿主报错时不抛异常', () async {
      messenger.setMockMethodCallHandler(hostChannel, (call) async {
        throw PlatformException(code: 'image-too-large');
      });
      expect(await pickImage(), isNull);
    });
  });

  group('PickedImage.fromJson', () {
    test('缺 data 时返回 null', () {
      expect(PickedImage.fromJson(<String, Object?>{'mimeType': 'image/jpeg'}), isNull);
      expect(PickedImage.fromJson(null), isNull);
      expect(PickedImage.fromJson('not-a-map'), isNull);
    });

    test('缺尺寸字段时按 0 处理', () {
      final PickedImage? image = PickedImage.fromJson(<String, Object?>{'data': 'AAAA'});
      expect(image, isNotNull);
      expect(image!.mimeType, 'image/jpeg');
      expect(image.width, 0);
      expect(image.height, 0);
      expect(image.name, '');
    });
  });

  group('saveTextFile', () {
    test('把文本编成 base64 并带上文件名与 mime', () async {
      MethodCall? captured;
      messenger.setMockMethodCallHandler(hostChannel, (call) async {
        captured = call;
        return <String, Object?>{'location': '下载/PiDeck/snippet.txt'};
      });

      final String? location = await saveTextFile(name: 'snippet.txt', text: '你好');

      expect(location, '下载/PiDeck/snippet.txt');
      expect(captured!.method, 'saveFile');
      final Map<Object?, Object?> args = captured!.arguments as Map<Object?, Object?>;
      expect(args['name'], 'snippet.txt');
      expect(args['mime'], 'text/plain');
      expect(utf8.decode(base64Decode('${args['data']}')), '你好');
    });

    test('宿主报错时返回 null', () async {
      messenger.setMockMethodCallHandler(hostChannel, (call) async {
        throw PlatformException(code: 'write-failed');
      });
      expect(await saveTextFile(name: 'a.txt', text: 'x'), isNull);
    });
  });

  group('openWebSheet', () {
    test('只传面板名', () async {
      MethodCall? captured;
      messenger.setMockMethodCallHandler(hostChannel, (call) async {
        captured = call;
        return null;
      });

      await openWebSheet('connection');

      expect(captured!.method, 'openWebSheet');
      expect((captured!.arguments as Map<Object?, Object?>)['name'], 'connection');
    });
  });
}
