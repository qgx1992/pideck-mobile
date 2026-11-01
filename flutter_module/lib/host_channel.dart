import 'dart:convert';

import 'package:flutter/services.dart';

/// 与 `app/android/app/src/main/java/dev/pideck/mobile/FlutterHostActivity.java` 一一对应。
///
///   getArgs()          → {"route": "...", "sessionId": "..."}
///   getSettings()      → 宿主缓存的 SettingsData JSON（首帧用，不等回程）
///   settingsAction(s)  → 把用户在 Flutter 页上的动作回传给 WebView 执行
///   close()            → 关掉本页，回到 WebView
///
/// 反过来（native → Dart）走 `setMethodCallHandler`：
///   setSettings(json)  → 宿主推来的最新 SettingsData
///   showNotice(text)   → 需要在 Flutter 页上显示的提示（WebView 的 toast 这里看不见）
const MethodChannel hostChannel = MethodChannel('pideck/flutter_host');

class HostArgs {
  const HostArgs({this.route = '', this.sessionId = ''});

  final String route;
  final String sessionId;
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
