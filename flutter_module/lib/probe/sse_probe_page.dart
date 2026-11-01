import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app_theme.dart';
import '../host_channel.dart';
import 'sse_probe.dart';
import 'sse_probe_controller.dart';

/// 流式探针页（调试用，不出现在正式流程里）。
///
/// 入口：设置页「关于」最下面的「流式探针（调试）」。
/// 结论看报告里的三行：首帧延迟、帧间隔中位/最长、后台判定。
class SseProbePage extends StatefulWidget {
  const SseProbePage({
    super.key,
    required this.baseUrl,
    required this.token,
    required this.sessionId,
  });

  final String baseUrl;
  final String token;
  final String sessionId;

  @override
  State<SseProbePage> createState() => _SseProbePageState();
}

class _SseProbePageState extends State<SseProbePage> with WidgetsBindingObserver {
  late final SseProbeController _controller = SseProbeController(
    baseUrl: widget.baseUrl,
    token: widget.token,
    sessionId: widget.sessionId,
  );

  final TextEditingController _message = TextEditingController(
    text: '用一句话解释 TCP 三次握手，不要超过 80 字',
  );
  bool _turnMode = true;

  /// 只读模式的时长。测后台保活得留够窗口（30s 装不下一次 60s 的后台停留）。
  int _durationSeconds = 30;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _controller.addListener(_onChanged);
  }

  void _onChanged() {
    if (mounted) setState(() {});
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _controller.markLifecycle(state.name);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller.removeListener(_onChanged);
    _controller.dispose();
    _message.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    final SseProbeController probe = _controller;
    final ProbeRecorder r = probe.recorder;
    final bool ready = widget.baseUrl.isNotEmpty;

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
        title: const Text('流式探针', style: TextStyle(fontSize: 16)),
        actions: [
          TextButton(
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: probe.report()));
              if (!context.mounted) return;
              ScaffoldMessenger.of(context)
                ..hideCurrentSnackBar()
                ..showSnackBar(const SnackBar(content: Text('报告已复制')));
            },
            child: const Text('复制报告', style: TextStyle(fontSize: 13)),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(14, 4, 14, 28),
        children: [
          _card(c, '目标', <String>[
            '服务：${widget.baseUrl.isEmpty ? "(没有配对信息)" : widget.baseUrl}',
            '会话：${widget.sessionId.isEmpty ? "(无，会话流用例不可用)" : widget.sessionId}',
            'token：${widget.token.isEmpty ? "(空)" : "已带上（长度 ${widget.token.length}）"}',
          ]),
          const SizedBox(height: 10),
          if (!ready)
            _card(c, '没法测', const <String>['Dart 侧没拿到 serviceUrl，先回设置页刷新一次再进。'])
          else
            _card(c, '用例', <Widget>[
              Row(
                children: [
                  Expanded(
                    child: _ModeChip(
                      label: '会话流 + 发一轮',
                      selected: _turnMode,
                      onTap: probe.running ? null : () => setState(() => _turnMode = true),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: _ModeChip(
                      label: '只读 /api/events',
                      selected: !_turnMode,
                      onTap: probe.running ? null : () => setState(() => _turnMode = false),
                    ),
                  ),
                ],
              ),
              if (!_turnMode) ...<Widget>[
                const SizedBox(height: 8),
                Row(
                  children: [
                    for (final int seconds in const <int>[30, 90])
                      Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: _ModeChip(
                          label: '${seconds}s',
                          selected: _durationSeconds == seconds,
                          onTap: probe.running ? null : () => setState(() => _durationSeconds = seconds),
                        ),
                      ),
                    Expanded(
                      child: Text(
                        '测后台保活选 90s',
                        textAlign: TextAlign.right,
                        style: TextStyle(fontSize: 11, color: c.textMuted),
                      ),
                    ),
                  ],
                ),
              ],
              const SizedBox(height: 10),
              if (_turnMode)
                TextField(
                  controller: _message,
                  enabled: !probe.running,
                  minLines: 1,
                  maxLines: 3,
                  style: TextStyle(fontSize: 13, color: c.text),
                  decoration: InputDecoration(
                    hintText: '测试消息（会真的发进这个会话）',
                    hintStyle: TextStyle(fontSize: 12, color: c.textMuted),
                    filled: true,
                    fillColor: c.surfaceAlt,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(8),
                      borderSide: BorderSide.none,
                    ),
                    isDense: true,
                    contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
                  ),
                ),
              const SizedBox(height: 10),
              Row(
                children: [
                  FilledButton(
                    onPressed: probe.running
                        ? null
                        : () {
                            if (_turnMode) {
                              probe.runTurnProbe(message: _message.text.trim());
                            } else {
                              probe.runEventsProbe(seconds: _durationSeconds);
                            }
                          },
                    child: Text(probe.running ? '运行中…' : '开始'),
                  ),
                  const SizedBox(width: 10),
                  TextButton(
                    onPressed: probe.running ? probe.stop : null,
                    child: const Text('停止'),
                  ),
                ],
              ),
            ]),
          const SizedBox(height: 10),
          _card(c, '摘要', <String>[
            '事件数：${r.eventCount}（回放 ${probe.replayFrames} / 实时 ${probe.liveFrames}）    字符数：${r.chars}',
            '建连耗时：${fmtMs(probe.connectMs)}',
            '首帧延迟：${fmtMs(r.firstMs)}    prompt→首帧：${fmtMs(probe.promptToFirstFrameMs)}',
            '帧间隔 中位/平均/最长：${fmtMs(r.medianGapMs)} / ${fmtMs(r.avgGapMs)} / ${fmtMs(r.maxGapMs)}',
            '实时帧间隔 中位/最长：${fmtMs(medianOf(gapsOf(probe.liveTimesMs)))} / ${fmtMs(maxOf(gapsOf(probe.liveTimesMs)))}',
            '整轮耗时：${fmtMs(r.finishMs)}',
            '帧类型：${r.typeSummary}',
            '后台停留：${fmtMs(probe.backgroundTotalMs)}（${probe.backgroundWindows} 次）',
            '后台判定：${probe.backgroundVerdict}',
          ]),
          const SizedBox(height: 10),
          _card(c, '日志（${probe.log.length}）', <Widget>[
            for (final String line in probe.log.reversed.take(60))
              Padding(
                padding: const EdgeInsets.only(bottom: 3),
                child: Text(
                  line,
                  style: TextStyle(fontSize: 11.5, height: 1.45, color: c.textSecondary),
                ),
              ),
          ]),
        ],
      ),
    );
  }

  Widget _card(PiColors c, String title, List<Object> lines) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
      decoration: BoxDecoration(color: c.surface, borderRadius: BorderRadius.circular(10)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w600, color: c.text),
          ),
          const SizedBox(height: 8),
          for (final Object line in lines)
            if (line is Widget)
              line
            else
              Padding(
                padding: const EdgeInsets.only(bottom: 4),
                child: Text(
                  '$line',
                  style: TextStyle(fontSize: 12, height: 1.5, color: c.textSecondary),
                ),
              ),
        ],
      ),
    );
  }
}

class _ModeChip extends StatelessWidget {
  const _ModeChip({required this.label, required this.selected, this.onTap});

  final String label;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        decoration: BoxDecoration(
          color: selected ? c.accent.withValues(alpha: 0.18) : c.surfaceAlt,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: selected ? c.accent : c.divider),
        ),
        child: Text(
          label,
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 12,
            fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
            color: selected ? c.accent : c.textSecondary,
          ),
        ),
      ),
    );
  }
}
