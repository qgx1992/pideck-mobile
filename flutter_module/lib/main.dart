import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app_theme.dart';
import 'theme_mode.dart';
import 'chat/chat_page.dart';
import 'host_channel.dart';
import 'probe/sse_probe_page.dart';
import 'settings/settings_models.dart';
import 'settings/settings_page.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // 边到边：系统栏透明，页面内容铺到底下，由 AnnotatedRegion 决定图标明暗
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);

  final args = await loadHostArgs();
  // 首帧就要是对的主题，否则浅色用户会先看到一下深色再跳过去。
  final initialJson = await loadInitialSettings();
  final SettingsData? seed = _parse(initialJson);
  // 聊天页的首帧快照是另一份缓存；只在真的要进聊天页时多要一次。
  final String initialChatJson = args.route == 'chat' ? await loadInitialChat() : '';
  // 首帧就用对主题：Web 侧存的是 light / dark / system，但只有快照才知道最新值，
  // 这里的 seed 只是"上一次设置页推过来的"—— 聊天页首帧之后会立刻按快照纠正。
  piThemeMode.value = themeModeFrom(seed?.theme.mode ?? 'system');
  runApp(
    PiDeckFlutterApp(
      route: args.route,
      sessionId: args.sessionId,
      endpoint: args.endpoint,
      token: args.token,
      nativeRuntime: args.nativeRuntime,
      initialSettingsJson: initialJson,
      initialChatJson: initialChatJson,
      // 默认跟随系统：这是 0.2.0 起的产品决定（Web 侧旧默认是 dark）
      initialThemeMode: themeModeFrom(seed?.theme.mode ?? 'system'),
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
    this.endpoint = '',
    this.token = '',
    this.nativeRuntime = false,
    this.initialSettingsJson = '',
    this.initialChatJson = '',
    this.initialThemeMode = ThemeMode.system,
    this.connection,
  });

  final String route;
  final String sessionId;

  /// 当前活动端点：聊天页的 Dart 运行时自己连服务端（帧流 / 历史）要用。
  final String endpoint;
  final String token;

  /// 聊天页是否由 Dart 跑数据面（Dart 侧接管开关）。
  final bool nativeRuntime;

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
  /// 主题跟着 Web 侧走：全局 notifier（页面收到新快照就写它），整个 MaterialApp 重建。
  ValueNotifier<ThemeMode> get _themeMode => piThemeMode;

  @override
  void initState() {
    super.initState();
    // 宿主显式传进来的模式（设置页路由）优先级最高
    if (piThemeMode.value != widget.initialThemeMode) {
      piThemeMode.value = widget.initialThemeMode;
    }
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
          // 每一页（含弹层路由）都自动带上系统栏样式，页面自己不用管
          builder: (BuildContext context, Widget? child) {
            final bool dark = Theme.of(context).brightness == Brightness.dark;
            return AnnotatedRegion<SystemUiOverlayStyle>(
              value: piSystemUiOverlayStyle(dark),
              child: child ?? const SizedBox.shrink(),
            );
          },
          // 路由是宿主的 EXTRA_ROUTE，加新页面时在这里挂分支。
          home: switch (widget.route) {
            'settings' => SettingsScreen(
              initialJson: widget.initialSettingsJson,
              onThemeModeChanged: (next) => _themeMode.value = next,
            ),
            'chat' => ChatScreen(
              initialJson: widget.initialChatJson,
              sessionId: widget.sessionId,
              baseUrl: widget.endpoint,
              token: widget.token,
              nativeRuntime: widget.nativeRuntime,
            ),
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
