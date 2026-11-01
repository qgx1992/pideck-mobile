import 'dart:convert';

import 'package:flutter/material.dart';

import 'app_theme.dart';
import 'chat/chat_page.dart';
import 'host_channel.dart';
import 'probe/sse_probe_page.dart';
import 'settings/settings_models.dart';
import 'settings/settings_page.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final args = await loadHostArgs();
  // 首帧就要是对的主题，否则浅色用户会先看到一下深色再跳过去。
  final initialJson = await loadInitialSettings();
  final SettingsData? seed = _parse(initialJson);
  // 聊天页的首帧快照是另一份缓存；只在真的要进聊天页时多要一次。
  final String initialChatJson = args.route == 'chat' ? await loadInitialChat() : '';
  runApp(
    PiDeckFlutterApp(
      route: args.route,
      sessionId: args.sessionId,
      initialSettingsJson: initialJson,
      initialChatJson: initialChatJson,
      initialThemeMode: themeModeFrom(seed?.theme.mode ?? 'dark'),
      connection: seed?.connection,
    ),
  );
}

SettingsData? _parse(String json) {
  if (json.trim().isEmpty) return null;
  try {
    return SettingsData.fromJson(mapOf(jsonDecode(json)));
  } catch (_) {
    return null;
  }
}

class PiDeckFlutterApp extends StatefulWidget {
  const PiDeckFlutterApp({
    super.key,
    required this.route,
    required this.sessionId,
    this.initialSettingsJson = '',
    this.initialChatJson = '',
    this.initialThemeMode = ThemeMode.dark,
    this.connection,
  });

  final String route;
  final String sessionId;

  /// 宿主在打开本页之前 push 的那份数据，用来让首帧直接是完整内容。
  final String initialSettingsJson;

  /// 聊天页的首帧快照（`ChatSnapshot` 的 JSON）。
  final String initialChatJson;
  final ThemeMode initialThemeMode;

  /// 探针页要自己发请求，需要 serviceUrl + token。
  final ConnectionInfo? connection;

  @override
  State<PiDeckFlutterApp> createState() => _PiDeckFlutterAppState();
}

class _PiDeckFlutterAppState extends State<PiDeckFlutterApp> {
  /// 主题跟着 Web 侧走：页面收到新数据就改这里，整个 MaterialApp 重建。
  late final ValueNotifier<ThemeMode> _themeMode = ValueNotifier<ThemeMode>(
    widget.initialThemeMode,
  );

  @override
  void dispose() {
    _themeMode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<ThemeMode>(
      valueListenable: _themeMode,
      builder: (context, mode, _) {
        return MaterialApp(
          title: 'PiDeck',
          debugShowCheckedModeBanner: false,
          theme: buildPiTheme(Brightness.light),
          darkTheme: buildPiTheme(Brightness.dark),
          themeMode: mode,
          // 路由是宿主的 EXTRA_ROUTE，加新页面时在这里挂分支。
          home: switch (widget.route) {
            'settings' => SettingsScreen(
              initialJson: widget.initialSettingsJson,
              onThemeModeChanged: (next) => _themeMode.value = next,
            ),
            'chat' => ChatScreen(initialJson: widget.initialChatJson),
            'sse-probe' => SseProbePage(
              baseUrl: widget.connection?.serviceUrl ?? '',
              token: widget.connection?.token ?? '',
              sessionId: widget.sessionId,
            ),
            _ => _HostPlaceholderPage(
              route: widget.route,
              sessionId: widget.sessionId,
            ),
          },
        );
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
    final PiColors c = PiColors.of(context);
    return Scaffold(
      backgroundColor: c.background,
      appBar: AppBar(
        backgroundColor: c.background,
        surfaceTintColor: Colors.transparent,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, size: 20),
          tooltip: '返回',
          onPressed: closeHost,
        ),
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
              '已迁移：settings（设置页）、sidebar（侧栏）。',
              '加新页面：在 main.dart 的 switch 里挂一个分支，',
              '然后从 WebView 用 FlutterHost.open({route}) 跳进来。',
              '迁移顺序与接口约定见 docs/flutter-migration.md。',
            ],
          ),
          const SizedBox(height: 24),
          FilledButton(
            onPressed: closeHost,
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
    final PiColors c = PiColors.of(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: c.surface,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: c.text),
          ),
          const SizedBox(height: 8),
          for (final line in lines)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text(
                line,
                style: TextStyle(fontSize: 12.5, height: 1.5, color: c.textSecondary),
              ),
            ),
        ],
      ),
    );
  }
}
