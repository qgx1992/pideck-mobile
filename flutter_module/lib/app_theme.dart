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

/// 主题种子色：Material 3 会从这一个颜色推出一整套 tonal palette。
const Color _seedColor = Color(0xFF4C6FE0);

/// 构建 Material 3 主题。
///
/// 设计思路（0.2.0 起的重构）：不再"对齐网页版的 CSS 变量"，而是一台安卓原生应用该有的样子 ——
/// 颜色、字号、圆角、控件都交给 M3 的 colorScheme + 组件主题，页面里不再手写像素值。
/// `PiColors` 保留为「语义色」的薄适配层（正文/次级/分隔线/危险色），映射自 colorScheme，
/// 这样还没逐个改写的页面也会立刻跟着 M3 调色板走，不会出现两套视觉。
ThemeData buildPiTheme(Brightness brightness) {
  final ColorScheme scheme = ColorScheme.fromSeed(seedColor: _seedColor, brightness: brightness);
  final bool dark = brightness == Brightness.dark;

  // 语义色 ← colorScheme。注意 surface / surfaceAlt 取的是 M3 的"容器层级"，
  // 不是 scheme.surface 本身：气泡、卡片要靠层级差分层，直接同色就成了一片。
  final PiColors colors = PiColors(
    background: scheme.surface,
    surface: scheme.surfaceContainerLow,
    surfaceAlt: scheme.surfaceContainerHigh,
    accent: scheme.primary,
    danger: scheme.error,
    text: scheme.onSurface,
    textSecondary: scheme.onSurfaceVariant,
    textMuted: scheme.onSurfaceVariant.withValues(alpha: 0.72),
    divider: scheme.outlineVariant,
  );

  final TextTheme base = Typography.material2021(platform: TargetPlatform.android).black;
  final TextTheme text = (brightness == Brightness.dark ? base.apply(bodyColor: scheme.onSurface, displayColor: scheme.onSurface) : base)
      .apply(bodyColor: scheme.onSurface, displayColor: scheme.onSurface)
      .copyWith(
        titleLarge: TextStyle(fontSize: 20, height: 1.3, fontWeight: FontWeight.w600, color: scheme.onSurface),
        titleMedium: TextStyle(fontSize: 15.5, height: 1.35, fontWeight: FontWeight.w600, color: scheme.onSurface),
        bodyLarge: TextStyle(fontSize: 15, height: 1.45, color: scheme.onSurface),
        bodyMedium: TextStyle(fontSize: 14, height: 1.45, color: scheme.onSurface),
        bodySmall: TextStyle(fontSize: 12.5, height: 1.4, color: scheme.onSurfaceVariant),
        labelLarge: TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600, color: scheme.onSurface),
      );

  const ShapeBorder sheetShape = RoundedRectangleBorder(
    borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
  );

  return ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    scaffoldBackgroundColor: scheme.surface,
    textTheme: text,
    extensions: <ThemeExtension<dynamic>>[colors],
    appBarTheme: AppBarTheme(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      foregroundColor: scheme.onSurface,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
      titleTextStyle: text.titleLarge,
    ),
    cardTheme: CardThemeData(
      color: scheme.surfaceContainerLow,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: scheme.outlineVariant.withValues(alpha: dark ? 0.5 : 0.7)),
      ),
    ),
    dividerTheme: DividerThemeData(color: scheme.outlineVariant, thickness: 1, space: 1),
    listTileTheme: ListTileThemeData(
      iconColor: scheme.onSurfaceVariant,
      textColor: scheme.onSurface,
      titleTextStyle: text.bodyLarge,
      subtitleTextStyle: text.bodySmall,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16),
      minVerticalPadding: 10,
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: scheme.surfaceContainerLow,
      surfaceTintColor: Colors.transparent,
      modalBackgroundColor: scheme.surfaceContainerLow,
      modalBarrierColor: Colors.black.withValues(alpha: dark ? 0.55 : 0.35),
      shape: sheetShape,
      showDragHandle: true,
      dragHandleColor: scheme.onSurfaceVariant.withValues(alpha: 0.4),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: scheme.surfaceContainerHigh,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: scheme.surfaceContainerHigh,
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      hintStyle: TextStyle(color: scheme.onSurfaceVariant.withValues(alpha: 0.8), fontSize: 15),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(20),
        borderSide: BorderSide.none,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(20),
        borderSide: BorderSide.none,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(20),
        borderSide: BorderSide(color: scheme.primary, width: 1.4),
      ),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: scheme.surfaceContainerHigh,
      selectedColor: scheme.secondaryContainer,
      side: BorderSide.none,
      labelStyle: text.bodySmall,
      secondaryLabelStyle: text.bodySmall,
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        textStyle: text.labelLarge,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        side: BorderSide(color: scheme.outlineVariant),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 11),
        textStyle: text.labelLarge,
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
        textStyle: text.labelLarge,
      ),
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: scheme.inverseSurface,
      contentTextStyle: TextStyle(color: scheme.onInverseSurface, fontSize: 13.5),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      insetPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
    ),
    popupMenuTheme: PopupMenuThemeData(
      color: scheme.surfaceContainerHigh,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
    ),
  );
}
