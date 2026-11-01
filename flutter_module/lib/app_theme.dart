import 'package:flutter/material.dart';

/// 与 Web 侧 `app/src/styles.css` 的深色底保持一致，切页时不会闪一下浅色。
const Color piBackground = Color(0xFF16181D);
const Color piSurface = Color(0xFF1F2229);
const Color piSurfaceAlt = Color(0xFF272B34);
const Color piAccent = Color(0xFF7C9CFF);
const Color piDanger = Color(0xFFFF6B6B);
const Color piTextMuted = Color(0xFF9A9AA4);

ThemeData buildPiTheme() {
  return ThemeData(
    useMaterial3: true,
    brightness: Brightness.dark,
    scaffoldBackgroundColor: piBackground,
    colorScheme: ColorScheme.fromSeed(
      seedColor: piAccent,
      brightness: Brightness.dark,
      surface: piBackground,
    ),
    snackBarTheme: const SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: piSurfaceAlt,
      contentTextStyle: TextStyle(color: Colors.white, fontSize: 13),
    ),
  );
}
