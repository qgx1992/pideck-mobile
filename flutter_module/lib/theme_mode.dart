import 'package:flutter/material.dart';

import 'app_theme.dart';

/// 全局主题模式（浅色 / 深色 / 跟随系统）。
///
/// 为什么是全局的：Flutter 主界面是"一个 Activity 里的一棵 MaterialApp 树"，
/// 主题模式来自 Web 侧的快照（`ChatSnapshot.themeMode`），但只有 `MaterialApp`
/// 才能改深浅 —— 页面自己拿不到它。所以页面只负责把值写进这里，
/// `main.dart` 的 `ValueListenableBuilder` 重建整棵树。
///
/// 之前是 `_PiDeckFlutterAppState` 里的私有 notifier，结果只有设置页能改：
/// 用户在设置里选了"跟随系统"，回到聊天页还是开页时那份旧主题。
final ValueNotifier<ThemeMode> piThemeMode = ValueNotifier<ThemeMode>(ThemeMode.system);

/// 把快照里的字符串（`light` / `dark` / `system`）落到全局主题上。
void applyThemeMode(String mode) {
  final ThemeMode next = themeModeFrom(mode);
  if (piThemeMode.value != next) piThemeMode.value = next;
}
