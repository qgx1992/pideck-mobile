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

/// 订阅宿主的推送。
void bindHostChannel({
  required void Function(String json) onSettings,
  required void Function(String text) onNotice,
}) {
  hostChannel.setMethodCallHandler((call) async {
    final raw = call.arguments is String ? call.arguments as String : '';
    if (call.method == 'setSettings') {
      onSettings(raw.isEmpty ? '{}' : raw);
    } else if (call.method == 'showNotice') {
      onNotice(raw);
    }
    return null;
  });
}

/// 把动作回传给 WebView（那边是唯一的状态源）。
Future<void> sendSettingsAction(Map<String, Object?> action) async {
  try {
    await hostChannel.invokeMethod<void>('settingsAction', jsonEncode(action));
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
