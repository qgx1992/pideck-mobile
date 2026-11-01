import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'sse_probe.dart';

/// 流式探针的执行器。
///
/// 目的不是「做一个聊天页」，而是把聊天页迁移前必须回答的三个问题量化：
///   1. Dart 用 `HttpClient` 直连 pi，能不能稳定地逐帧读流（而不是攒完再给）；
///   2. 帧间隔长什么样（首帧延迟 / 中位间隔 / 最长停顿）—— 决定要不要做增量合并；
///   3. App 切到后台后流还在不在（Dart isolate 被挂起 vs 原生前台服务保活）。
class SseProbeController extends ChangeNotifier {
  SseProbeController({
    required this.baseUrl,
    required this.token,
    required this.sessionId,
  });

  final String baseUrl;
  final String token;
  final String sessionId;

  final ProbeRecorder recorder = ProbeRecorder();
  final List<String> log = <String>[];

  bool _running = false;
  bool get running => _running;

  /// 当前这次探测的说明（报告标题用）。
  String _label = '';
  String get label => _label;

  /// 会话流模式下，从「prompt 已发出」到首帧的延迟。
  int? promptToFirstFrameMs;

  HttpClient? _client;
  final Stopwatch _clock = Stopwatch();
  bool _aborted = false;

  /// 后台窗口（进入后台 → 回到前台）里流是否还活着。
  String backgroundVerdict = '未测试';

  void _write(String line) {
    final String stamp = _clock.elapsedMilliseconds == 0
        ? '+0ms'
        : '+${(_clock.elapsedMilliseconds / 1000).toStringAsFixed(2)}s';
    log.add('[$stamp] $line');
    if (log.length > 400) log.removeAt(0);
    notifyListeners();
  }

  void markLifecycle(String state) {
    final bool nowBackground = state == 'paused' || state == 'hidden' || state == 'inactive';
    recorder.markBackground(nowBackground);
    _write(nowBackground ? 'App 进入后台（${_countAtBackground()} 帧）' : 'App 回到前台');
    if (!nowBackground && recorder.eventsWhileBackground == 0 && _running) {
      backgroundVerdict = '后台期间 0 帧 —— 流被挂起或已断（需要原生保活）';
    } else if (!nowBackground && _running) {
      backgroundVerdict = '后台期间仍收到 ${recorder.eventsWhileBackground} 帧 —— Dart 侧能自己活着';
    }
  }

  int _countAtBackground() => recorder.eventCount;

  void _reset(String label) {
    _label = label;
    recorder.timesMs.clear();
    recorder.types.clear();
    recorder.chars = 0;
    recorder.eventsWhileBackground = 0;
    recorder.finishMs = null;
    promptToFirstFrameMs = null;
    backgroundVerdict = '未测试';
    log.clear();
    _aborted = false;
    _clock
      ..reset()
      ..start();
    _write('开始：$label');
  }

  /// 只读探测：连一条 GET 流，跑 [seconds] 秒，量帧间隔。
  Future<void> runEventsProbe({int seconds = 30, String path = '/api/events'}) async {
    if (_running) return;
    _reset('只读探测 $path（${seconds}s）');
    _running = true;
    notifyListeners();
    try {
      await _consume(path, stopAfterMs: seconds * 1000, stopOnFinish: false);
    } finally {
      _finish();
    }
  }

  /// 会话流探测：连 `/api/sessions/<id>/stream`，再发一条真实消息，量整轮。
  Future<void> runTurnProbe({required String message, int timeoutSeconds = 90}) async {
    if (_running) return;
    if (sessionId.isEmpty) {
      _reset('会话流探测');
      _write('没有会话 id：先打开一个会话，再从设置页进这个探针');
      notifyListeners();
      return;
    }
    _reset('会话流探测 ${sessionId.substring(0, sessionId.length < 8 ? sessionId.length : 8)}…');
    _running = true;
    notifyListeners();

    final Future<void> stream = _consume(
      '/api/sessions/${Uri.encodeComponent(sessionId)}/stream',
      stopAfterMs: timeoutSeconds * 1000,
      stopOnFinish: true,
    );

    // 先连流再投递：流会回放当前轮已产出的帧，先连不会漏
    await Future<void>.delayed(const Duration(milliseconds: 400));
    try {
      await _postPrompt(message);
    } catch (error) {
      _write('投递失败：$error');
    }

    try {
      await stream;
    } finally {
      _finish();
    }
  }

  Future<void> _postPrompt(String message) async {
    final HttpClient client = _client ??= HttpClient()
      ..connectionTimeout = const Duration(seconds: 15);
    final Uri url = Uri.parse(
      '${baseUrl.replaceAll(RegExp(r"/+$"), "")}/api/sessions/${Uri.encodeComponent(sessionId)}/prompt',
    );
    final HttpClientRequest request = await client.postUrl(url);
    request.headers.set(HttpHeaders.authorizationHeader, 'Bearer $token');
    request.headers.contentType = ContentType.json;
    request.write(jsonEncode(<String, Object?>{
      'requestId': 'probe-${DateTime.now().microsecondsSinceEpoch}',
      'message': message,
    }));
    final int promptMs = _clock.elapsedMilliseconds;
    final HttpClientResponse response = await request.close();
    final String body = await response.transform(utf8.decoder).join();
    _write('prompt → HTTP ${response.statusCode}（${body.length} 字符）');
    promptToFirstFrameMs = recorder.firstMs == null ? null : recorder.firstMs! - promptMs;
  }

  Future<void> _consume(String path, {required int stopAfterMs, required bool stopOnFinish}) async {
    final HttpClient client = _client ??= HttpClient()
      ..connectionTimeout = const Duration(seconds: 15);
    final Uri base = Uri.parse(baseUrl.replaceAll(RegExp(r"/+$"), ""));
    final Uri url = base.replace(path: '${base.path}$path', queryParameters: <String, String>{
      ...base.queryParameters,
      if (token.isNotEmpty) 'token': token,
    });

    _write('GET ${url.path}');
    final HttpClientResponse response;
    try {
      final HttpClientRequest request = await client.getUrl(url);
      request.headers.set(HttpHeaders.acceptHeader, 'text/event-stream');
      request.headers.set(HttpHeaders.cacheControlHeader, 'no-cache');
      response = await request.close();
    } catch (error) {
      _write('连接失败：$error');
      return;
    }
    _write('HTTP ${response.statusCode} ${response.headers.contentType ?? ""}');
    if (response.statusCode != HttpStatus.ok) {
      final String body = await response.transform(utf8.decoder).join();
      _write('响应体：${body.length > 200 ? "${body.substring(0, 200)}…" : body}');
      return;
    }

    final SseEventParser parser = SseEventParser();
    final Timer deadline = Timer(Duration(milliseconds: stopAfterMs), stop);
    try {
      await for (final String chunk in response.transform(utf8.decoder)) {
        if (_aborted) break;
        for (final SseEvent event in parser.feed(chunk)) {
          recorder.record(_clock.elapsedMilliseconds, event);
        }
        if (recorder.eventCount > 0 && recorder.eventCount % 20 == 0) notifyListeners();
        if (stopOnFinish && recorder.finishMs != null) {
          _write('收到 finish，结束读取');
          break;
        }
      }
    } catch (error) {
      if (!_aborted) _write('读取中断：$error');
    } finally {
      deadline.cancel();
    }
    if (!_aborted) _write('流结束（HTTP ${response.statusCode}）');
  }

  void stop() {
    if (!_running) return;
    _aborted = true;
    _client?.close(force: true);
    _client = null;
    _write('手动停止');
    _finish();
  }

  void _finish() {
    _clock.stop();
    _running = false;
    if (backgroundVerdict == '未测试' && recorder.eventsWhileBackground == 0) {
      backgroundVerdict = '测试期间没切过后台';
    }
    notifyListeners();
  }

  /// 供「复制报告」用的纯文本。
  String report() {
    final StringBuffer out = StringBuffer()
      ..writeln('PiDeck 流式探针报告')
      ..writeln('时间：${DateTime.now().toIso8601String()}')
      ..writeln('服务：$baseUrl')
      ..writeln('会话：${sessionId.isEmpty ? "(无)" : sessionId}')
      ..writeln('用例：$_label')
      ..writeln('事件数：${recorder.eventCount}')
      ..writeln('字符数：${recorder.chars}')
      ..writeln('首帧延迟：${fmtMs(recorder.firstMs)}')
      ..writeln('prompt→首帧：${fmtMs(promptToFirstFrameMs)}')
      ..writeln('帧间隔 中位/平均/最长：${fmtMs(recorder.medianGapMs)} / ${fmtMs(recorder.avgGapMs)} / ${fmtMs(recorder.maxGapMs)}')
      ..writeln('整轮耗时：${fmtMs(recorder.finishMs)}')
      ..writeln('帧类型：${recorder.typeSummary}')
      ..writeln('后台判定：$backgroundVerdict');
    return out.toString();
  }

  @override
  void dispose() {
    _client?.close(force: true);
    _client = null;
    super.dispose();
  }
}
