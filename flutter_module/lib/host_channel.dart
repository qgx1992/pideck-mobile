import 'dart:convert';

import 'package:flutter/services.dart';

/// 与 `app/android/app/src/main/java/dev/pideck/mobile/FlutterHostActivity.java` 一一对应。
///
///   getArgs()          → {"route", "sessionId", "endpoint", "token", "nativeRuntime"}
///   getSettings()      → 宿主缓存的 SettingsData JSON（首帧用，不等回程）
///   settingsAction(s)  → 把用户在 Flutter 页上的动作回传给 WebView 执行
///   close()            → 关掉本页，回到 WebView
///
/// 反过来（native → Dart）走 `setMethodCallHandler`：
///   setSettings(json)  → 宿主推来的最新 SettingsData
///   showNotice(text)   → 需要在 Flutter 页上显示的提示（WebView 的 toast 这里看不见）
const MethodChannel hostChannel = MethodChannel('pideck/flutter_host');

class HostArgs {
  const HostArgs({
    this.route = '',
    this.sessionId = '',
    this.endpoint = '',
    this.token = '',
    this.nativeRuntime = false,
  });

  final String route;
  final String sessionId;

  /// 当前活动端点的 baseUrl / token。
  ///
  /// 只给「自己跑数据面」的页面用（聊天页）。不能用 `getSettings()` 里那份
  /// connection：设置缓存只在打开设置页时推，换过端点之后它可能是旧的，
  /// 而这里必须是「此刻真正连着的那台」。
  final String endpoint;
  final String token;

  /// 聊天页是否由 Dart 自己跑帧流 / 历史 / 待答请求。
  ///
  /// 老包不带这个 extra → false，页面照旧只画 WebView 推来的快照。
  final bool nativeRuntime;

  bool get canRunNativeRuntime =>
      nativeRuntime && endpoint.isNotEmpty && sessionId.isNotEmpty;
}

/// 取宿主传来的参数。宿主没给（比如直接 flutter run 调试）时给默认值，
/// 不要让 channel 报错把首帧卡住。
Future<HostArgs> loadHostArgs() async {
  try {
    final raw = await hostChannel.invokeMethod<String>('getArgs');
    if (raw == null || raw.isEmpty) return const HostArgs();
    final map = jsonDecode(raw) as Map<String, dynamic>;
    return HostArgs(
      route: (map['route'] as String?) ?? '',
      sessionId: (map['sessionId'] as String?) ?? '',
      endpoint: (map['endpoint'] as String?) ?? '',
      token: (map['token'] as String?) ?? '',
      nativeRuntime: map['nativeRuntime'] == true,
    );
  } catch (_) {
    return const HostArgs();
  }
}

/// 首帧数据。宿主在打开本页之前就已经 push 过一份，这里拿到的是同步缓存，
/// 不用等一次往返，避免白屏。
Future<String> loadInitialSettings() async {
  try {
    final raw = await hostChannel.invokeMethod<String>('getSettings');
    return raw == null || raw.isEmpty ? '{}' : raw;
  } catch (_) {
    return '{}';
  }
}

/// 聊天页的首帧快照（同 [loadInitialSettings]，只是另一份缓存）。
Future<String> loadInitialChat() async {
  try {
    final raw = await hostChannel.invokeMethod<String>('getChat');
    return raw == null || raw.isEmpty ? '{}' : raw;
  } catch (_) {
    return '{}';
  }
}

/// 侧栏的首帧快照。侧栏是聊天页的 drawer，跟着聊天页一起起来，
/// 所以宿主在打开聊天页之前就已经缓存过一份。
Future<String> loadInitialSidebar() async {
  try {
    final raw = await hostChannel.invokeMethod<String>('getSidebar');
    return raw == null || raw.isEmpty ? '{}' : raw;
  } catch (_) {
    return '{}';
  }
}

/// 订阅宿主的推送。
void bindHostChannel({
  void Function(String json)? onSettings,
  void Function(String text)? onNotice,
  void Function(String json)? onChat,
  void Function(String json)? onSidebar,
}) {
  hostChannel.setMethodCallHandler((call) async {
    final raw = call.arguments is String ? call.arguments as String : '';
    if (call.method == 'setSettings') {
      onSettings?.call(raw.isEmpty ? '{}' : raw);
    } else if (call.method == 'setChat') {
      onChat?.call(raw.isEmpty ? '{}' : raw);
    } else if (call.method == 'setSidebar') {
      onSidebar?.call(raw.isEmpty ? '{}' : raw);
    } else if (call.method == 'showNotice') {
      onNotice?.call(raw);
    }
    return null;
  });
}

/// 把动作回传给 WebView（那边是唯一的状态源）。
///
/// 参数必须是 **Map**：宿主的 `MethodCall.argument("json")` 只在 arguments 是 Map 时
/// 才能取值，直接传字符串会抛 ClassCastException（宿主侧 catch 不住，Dart 这边也看不到），
/// 表现就是「点了什么反应都没有」。
Future<void> sendSettingsAction(Map<String, Object?> action) async {
  try {
    await hostChannel.invokeMethod<void>('settingsAction', <String, Object?>{
      'json': jsonEncode(action),
    });
  } catch (_) {
    /* 宿主已经不在时忽略 */
  }
}

Future<void> closeHost() async {
  try {
    await hostChannel.invokeMethod<void>('close');
  } catch (_) {
    /* 忽略 */
  }
}

/// 直接退出 App（宿主 `finishAffinity()`）。
///
/// 为什么需要它：返回键原先只发一个 `exitApp` 动作给 WebView，由它调
/// `CapacitorApp.exitApp()`。WebView 被 Flutter 页盖着时是 paused
/// （被系统挂起时连 JS 都不跑），那个动作就没人执行 —— 用户按返回没任何反应。
/// 自己跑数据面的页面（= 主界面就是这一页）可以直接让宿主动手。
Future<void> exitAppHost() async {
  try {
    await hostChannel.invokeMethod<void>('exitApp');
  } catch (_) {
    /* 忽略 */
  }
}

/// 拉起后台看护（原生前台服务）。
///
/// 什么时候必须调：Dart 自己把提问发出去之后。用户经常发完就上滑回桌面，
/// 而 Android 12+ 不允许在后台启动前台服务 —— 等 WebView 的 SSE 把 running
/// 推下来再启动，进程可能已经被冻结，那一轮跑完就不会有通知。
Future<void> startRunWatch({
  required String baseUrl,
  required String token,
  Map<String, String> titles = const <String, String>{},
}) async {
  if (baseUrl.isEmpty || token.isEmpty) return;
  try {
    await hostChannel.invokeMethod<void>('startRunWatch', <String, Object?>{
      'baseUrl': baseUrl,
      'token': token,
      'titles': titles,
    });
  } catch (_) {
    /* 起不来不该把一次成功的发送变成失败 */
  }
}

/// 停止后台看护（幂等）。
Future<void> stopRunWatch() async {
  try {
    await hostChannel.invokeMethod<void>('stopRunWatch');
  } catch (_) {
    /* 忽略 */
  }
}

/// 聊天页的动作回传（同 [sendSettingsAction]，参数同样必须是 Map）。
///
/// 动作里的 `type` 由 `app/src/main.ts` 的 `dispatchFlutterChatAction()` 分发，
/// 只传 id 与必要参数，**不要**把整条消息/整个选项对象传回去 —— 那边的状态源
/// 可能已经变了，按 id 重新查一次才不会拿到陈旧数据。
Future<void> sendChatAction(Map<String, Object?> action) async {
  try {
    await hostChannel.invokeMethod<void>('chatAction', <String, Object?>{
      'json': jsonEncode(action),
    });
  } catch (_) {
    /* 宿主已经不在时忽略 */
  }
}

/// 侧栏的动作回传。动作由 `app/src/main.ts` 的 `dispatchFlutterSidebarAction()` 分发，
/// 只传 id / key / query，**不要**把整行或整组数据传回去。
Future<void> sendSidebarAction(Map<String, Object?> action) async {
  try {
    await hostChannel.invokeMethod<void>('sidebarAction', <String, Object?>{
      'json': jsonEncode(action),
    });
  } catch (_) {
    /* 宿主已经不在时忽略 */
  }
}

/// 宿主选回来的一张图。
class PickedImage {
  const PickedImage({
    required this.data,
    this.mimeType = 'image/jpeg',
    this.width = 0,
    this.height = 0,
    this.bytes = 0,
    this.name = '',
  });

  /// **裸** base64（不带 `data:` 前缀），已经由原生按 1600px / 0.82 压过。
  final String data;
  final String mimeType;
  final int width;
  final int height;
  final int bytes;
  final String name;

  /// 给 `Image.memory` 用。
  Uint8List get bytesOf => base64Decode(data);

  /// 给 WebView 侧用（那边存的是 data URL）。
  String get dataUrl => 'data:$mimeType;base64,$data';

  static PickedImage? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final String data = '${raw['data'] ?? ''}';
    if (data.isEmpty) return null;
    return PickedImage(
      data: data,
      mimeType: '${raw['mimeType'] ?? 'image/jpeg'}',
      width: raw['width'] is int ? raw['width'] as int : 0,
      height: raw['height'] is int ? raw['height'] as int : 0,
      bytes: raw['bytes'] is int ? raw['bytes'] as int : 0,
      name: '${raw['name'] ?? ''}',
    );
  }
}

/// 弹系统相册选一张图。用户取消（或宿主不支持）时返回 null。
///
/// 解码与压缩都在原生侧做（`FlutterFiles.readImage`）：Dart 里整张进内存再缩放，
/// 手机上一张 4000×3000 的照片很容易 OOM，而 `BitmapFactory.inSampleSize` 是解码时就降采样。
Future<PickedImage?> pickImage() async {
  try {
    final Object? raw = await hostChannel.invokeMethod<Object?>('pickImage');
    return PickedImage.fromJson(raw);
  } catch (_) {
    return null;
  }
}

/// 把一段文本存到手机（「下载/PiDeck」）。返回落地位置，失败返回 null。
///
/// 走原生 `SavedFiles`，与 WebView 的 `FileSaver` 插件同一份实现 —— 两条入口各写一份
/// MediaStore 代码迟早会出现「网页版能存、Flutter 版报错」。
Future<String?> saveTextFile({
  required String name,
  required String text,
  String mime = 'text/plain',
}) async {
  try {
    final Object? raw = await hostChannel.invokeMethod<Object?>('saveFile', <String, Object?>{
      'name': name,
      'mime': mime,
      'data': base64Encode(utf8.encode(text)),
    });
    if (raw is Map) {
      final Object? location = raw['location'];
      return location == null ? null : '$location';
    }
    return null;
  } catch (_) {
    return null;
  }
}

/// 关掉本页并让 WebView 打开一个弹层（现在只有扫码配对用）。
///
/// 扫码走的是 Capacitor 的 `BarcodeScanner.scan()`：它靠 bridge 接 ActivityResult，
/// 在 Flutter 宿主 Activity 里拿不到结果，所以不在这里重写一遍 ML Kit + CameraX。
Future<void> openWebSheet(String name) async {
  try {
    await hostChannel.invokeMethod<void>('openWebSheet', <String, Object?>{'name': name});
  } catch (_) {
    /* 忽略 */
  }
}
