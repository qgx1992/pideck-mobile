import 'package:flutter/material.dart';

/// PiDeck 的调色板。
///
/// 两套（深色 / 浅色）由 `buildPiTheme(Brightness)` 挂到 `ThemeData.extensions` 上，
/// 页面里用 `PiColors.of(context)` 取 —— 这样主题一切换，整页跟着变，
/// 不用在 MaterialApp 和每个 widget 之间传颜色。
@immutable
class PiColors extends ThemeExtension<PiColors> {
  const PiColors({
    required this.background,
    required this.surface,
    required this.surfaceAlt,
    required this.accent,
    required this.danger,
    required this.text,
    required this.textSecondary,
    required this.textMuted,
    required this.divider,
  });

  /// 页面底色。
  final Color background;

  /// 卡片 / 分组底色。
  final Color surface;

  /// 输入框、次级块底色。
  final Color surfaceAlt;

  final Color accent;
  final Color danger;

  /// 正文（强调）。
  final Color text;

  /// 正文（次级）。
  final Color textSecondary;

  /// 说明文字。
  final Color textMuted;

  final Color divider;

  /// 与 Web 侧 `app/src/styles.css` 的深色底保持一致，切页时不会闪一下浅色。
  static const PiColors dark = PiColors(
    background: Color(0xFF16181D),
    surface: Color(0xFF1F2229),
    surfaceAlt: Color(0xFF272B34),
    accent: Color(0xFF7C9CFF),
    danger: Color(0xFFFF6B6B),
    text: Color(0xFFFFFFFF),
    textSecondary: Color(0xB3FFFFFF),
    textMuted: Color(0xFF9A9AA4),
    divider: Color(0x1AFFFFFF),
  );

  static const PiColors light = PiColors(
    background: Color(0xFFF5F6F8),
    surface: Color(0xFFFFFFFF),
    surfaceAlt: Color(0xFFEDEFF3),
    accent: Color(0xFF4C6FE0),
    danger: Color(0xFFD93F3F),
    text: Color(0xFF15171C),
    textSecondary: Color(0xB315171C),
    textMuted: Color(0xFF6B7280),
    divider: Color(0x14000000),
  );

  static PiColors of(BuildContext context) =>
      Theme.of(context).extension<PiColors>() ??
      (Theme.of(context).brightness == Brightness.dark ? dark : light);

  @override
  PiColors copyWith({
    Color? background,
    Color? surface,
    Color? surfaceAlt,
    Color? accent,
    Color? danger,
    Color? text,
    Color? textSecondary,
    Color? textMuted,
    Color? divider,
  }) {
    return PiColors(
      background: background ?? this.background,
      surface: surface ?? this.surface,
      surfaceAlt: surfaceAlt ?? this.surfaceAlt,
      accent: accent ?? this.accent,
      danger: danger ?? this.danger,
      text: text ?? this.text,
      textSecondary: textSecondary ?? this.textSecondary,
      textMuted: textMuted ?? this.textMuted,
      divider: divider ?? this.divider,
    );
  }

  @override
  PiColors lerp(ThemeExtension<PiColors>? other, double t) {
    if (other is! PiColors) return this;
    return PiColors(
      background: Color.lerp(background, other.background, t)!,
      surface: Color.lerp(surface, other.surface, t)!,
      surfaceAlt: Color.lerp(surfaceAlt, other.surfaceAlt, t)!,
      accent: Color.lerp(accent, other.accent, t)!,
      danger: Color.lerp(danger, other.danger, t)!,
      text: Color.lerp(text, other.text, t)!,
      textSecondary: Color.lerp(textSecondary, other.textSecondary, t)!,
      textMuted: Color.lerp(textMuted, other.textMuted, t)!,
      divider: Color.lerp(divider, other.divider, t)!,
    );
  }
}

/// Web 侧存的 `theme.mode` 是 `system` / `light` / `dark`，这里做一次映射。
ThemeMode themeModeFrom(String mode) {
  switch (mode) {
    case 'light':
      return ThemeMode.light;
    case 'system':
      return ThemeMode.system;
    default:
      return ThemeMode.dark;
  }
}

ThemeData buildPiTheme(Brightness brightness) {
  final PiColors colors = brightness == Brightness.dark ? PiColors.dark : PiColors.light;
  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    scaffoldBackgroundColor: colors.background,
    colorScheme: ColorScheme.fromSeed(
      seedColor: colors.accent,
      brightness: brightness,
      surface: colors.background,
    ),
    extensions: <ThemeExtension<dynamic>>[colors],
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: colors.surfaceAlt,
      contentTextStyle: TextStyle(color: colors.text, fontSize: 13),
    ),
  );
}
