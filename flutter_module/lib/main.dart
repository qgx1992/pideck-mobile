import 'package:flutter/material.dart';

import 'app_theme.dart';
import 'host_channel.dart';
import 'settings/settings_page.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final args = await loadHostArgs();
  runApp(PiDeckFlutterApp(route: args.route, sessionId: args.sessionId));
}

class PiDeckFlutterApp extends StatelessWidget {
  const PiDeckFlutterApp({super.key, required this.route, required this.sessionId});

  final String route;
  final String sessionId;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'PiDeck',
      debugShowCheckedModeBanner: false,
      theme: buildPiTheme(),
      // 路由是宿主的 EXTRA_ROUTE，加新页面时在这里挂分支。
      home: switch (route) {
        'settings' => const SettingsScreen(),
        _ => _HostPlaceholderPage(route: route, sessionId: sessionId),
      },
    );
  }
}

/// 还没迁移的 route 落到这里：显示通道已连通，避免一片空白。
class _HostPlaceholderPage extends StatelessWidget {
  const _HostPlaceholderPage({required this.route, required this.sessionId});

  final String route;
  final String sessionId;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: piBackground,
      appBar: AppBar(
        backgroundColor: piBackground,
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
            title: '这个 route 还没有 Dart 页面',
            lines: [
              '已迁移：settings（设置页）。',
              '加新页面：在 main.dart 的 switch 里挂一个分支，',
              '然后从 WebView 用 FlutterHost.open({route}) 跳进来。',
              '迁移顺序与接口约定见 docs/flutter-migration.md。',
            ],
          ),
          const SizedBox(height: 24),
          FilledButton(
            onPressed: () => closeHost(),
            child: const Text('返回 WebView'),
          ),
        ],
      ),
    );
  }
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
        color: piSurface,
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
