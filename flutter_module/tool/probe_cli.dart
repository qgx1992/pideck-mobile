// 本机直接跑探针逻辑，不经过手机 —— 用来判断「帧不是 JSON」是服务端发的东西，
// 还是 Dart 侧解码/切帧的锅。
//
//   dart run tool/probe_cli.dart "http://127.0.0.1:8765/api/sessions/<id>/stream?token=xxx" [秒数]
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:pideck_flutter/probe/sse_probe.dart';

Future<void> main(List<String> argv) async {
  if (argv.isEmpty) {
    stderr.writeln('用法: dart run tool/probe_cli.dart <url> [秒数]');
    exit(2);
  }
  final url = Uri.parse(argv.first);
  final seconds = argv.length > 1 ? int.tryParse(argv[1]) ?? 10 : 10;

  final client = HttpClient();
  final watch = Stopwatch()..start();
  final request = await client.getUrl(url);
  request.headers.set('Accept', 'text/event-stream');
  final response = await request.close();
  stdout.writeln(
    'HTTP ${response.statusCode}  content-type=${response.headers.contentType}'
    '  encoding=${response.headers.value('content-encoding')}'
    '  chunked=${response.headers.chunkedTransferEncoding}',
  );

  final parser = SseEventParser();
  final recorder = ProbeRecorder();
  var chunks = 0;
  var bytes = 0;
  var shown = 0;

  final timer = Timer(Duration(seconds: seconds), () => client.close(force: true));

  try {
    await for (final chunk in response.transform(utf8.decoder)) {
      chunks++;
      bytes += chunk.length;
      for (final event in parser.feed(chunk)) {
        recorder.record(watch.elapsedMilliseconds, event);
        if (shown < 5) {
          shown++;
          final head = event.data.length > 220 ? '${event.data.substring(0, 220)}…' : event.data;
          stdout.writeln('--- 帧 $shown  event=${event.event} dataLen=${event.data.length}');
          stdout.writeln('    $head');
        }
      }
    }
  } catch (error) {
    stdout.writeln('[读取结束] $error');
  }
  timer.cancel();
  client.close(force: true);

  stdout.writeln('\n=== 统计 ===');
  stdout.writeln('chunks=$chunks  chars=$bytes  事件=${recorder.eventCount}');
  stdout.writeln('首帧=${fmtMs(recorder.firstMs)}  中位/平均/最长='
      '${fmtMs(recorder.medianGapMs)} / ${fmtMs(recorder.avgGapMs)} / ${fmtMs(recorder.maxGapMs)}');
  stdout.writeln('帧类型=${recorder.typeSummary}');
}
