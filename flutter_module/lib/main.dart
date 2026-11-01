import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// 与 `app/android/app/src/main/java/dev/pideck/mobile/FlutterHostActivity.java` 一一对应。
///
///   getArgs() → {"route": "...", "sessionId": "..."}
///   close()   → 关掉本页，回到 WebView
const MethodChannel hostChannel = MethodChannel('pideck/flutter_host');

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final args = await _loadArgs();
  runApp(PiDeckFlutterApp(route: args.$1, sessionId: args.$2));
}

/// 取宿主传来的参数。宿主没给（比如直接 flutter run 调试）时给默认值，
/// 不要让 channel 报错把首帧卡住。
Future<(String, String)> _loadArgs() async {
  try {
    final raw = await hostChannel.invokeMethod<String>('getArgs');
    if (raw == null || raw.isEmpty) return ('', '');
    final map = jsonDecode(raw) as Map<String, dynamic>;
    return (
      (map['route'] as String?) ?? '',
      (map['sessionId'] as String?) ?? '',
    );
  } catch (_) {
    return ('', '');
  }
}

class PiDeckFlutterApp extends StatelessWidget {
  const PiDeckFlutterApp({super.key, required this.route, required this.sessionId});

  final String route;
  final String sessionId;

  /// 与 Web 侧 `app/src/styles.css` 的深色底保持一致，切页时不会闪一下浅色。
  static const Color background = Color(0xFF16181D);
  static const Color surface = Color(0xFF1F2229);
  static const Color accent = Color(0xFF7C9CFF);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'PiDeck',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: background,
        colorScheme: ColorScheme.fromSeed(
          seedColor: accent,
          brightness: Brightness.dark,
          surface: background,
        ),
      ),
      home: Scaffold(
        appBar: AppBar(
          backgroundColor: background,
          surfaceTintColor: Colors.transparent,
          title: const Text('Flutter 页面', style: TextStyle(fontSize: 16)),
        ),
        body: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
          children: [
            _Card(
              title: '通道已连通',
              lines: [
                'route: ${route.isEmpty ? "(未传)" : route}',
                'sessionId: ${sessionId.isEmpty ? "(未传)" : sessionId}',
              ],
            ),
            const SizedBox(height: 12),
            const _Card(
              title: '下一步',
              lines: [
                '这里就是逐页迁移的落脚点：把某个 Web 页面改写成 Dart，',
                '然后从 WebView 用 FlutterHost.open({route}) 跳进来。',
                '迁移顺序与插件等价方案见 docs/flutter-migration.md。',
              ],
            ),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: () => hostChannel.invokeMethod<void>('close'),
              child: const Text('返回 WebView'),
            ),
            const SizedBox(height: 8),
            Text(
              'Flutter ${_engineVersion()}',
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 11, color: Colors.white38),
            ),
          ],
        ),
      ),
    );
  }

  String _engineVersion() => '3.47.x';
}

class _Card extends StatelessWidget {
  const _Card({required this.title, required this.lines});

  final String title;
  final List<String> lines;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: PiDeckFlutterApp.surface,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 8),
          for (final line in lines)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text(
                line,
                style: const TextStyle(fontSize: 12.5, height: 1.5, color: Colors.white70),
              ),
            ),
        ],
      ),
    );
  }
}
