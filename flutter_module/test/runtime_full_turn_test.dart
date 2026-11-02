import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/runtime/api_client.dart';
import 'package:pideck_flutter/runtime/chat_runtime.dart';
import 'package:pideck_flutter/runtime/frames.dart';
import 'package:pideck_flutter/sidebar/drawer_state.dart';
import 'package:pideck_flutter/sidebar/sidebar_native.dart';
import 'package:pideck_flutter/workspace/workspace_runtime.dart';

/// 一轮完整对话的端到端自测：状态面 + 帧流 + 历史 + 工作区 + 侧栏快照。
///
/// 这条链路是 0.2.10 以来所有「搬进 Dart」的东西的汇合点，最容易被单个单元测试
/// 漏掉的正是**它们之间**的衔接：发送后 reducer 要归零、finish 后 400ms 要重拉历史
/// 并把 live 清掉、历史里的用户消息要能盖掉乐观回显、工作区与侧栏都从同一份 state 算。
void main() {
  late HttpServer server;
  late PiDeckClient client;
  late ChatRuntime runtime;
  late WorkspaceRuntime workspace;
  late List<String> requests;
  late List<Map<String, Object?>> promptBodies;
  late Map<String, Object?> stateJson;
  late List<Map<String, Object?>> historyItems;

  /// 帧流推什么：由用例在发送之后填，服务端把新出现的帧逐条写给同一条连接。
  List<Map<String, Object?>> frames = <Map<String, Object?>>[];

  /// 帧推完没有：为真时服务端补 `[DONE]` 收口（整轮结束）。
  bool doneStreaming = false;

  setUp(() async {
    requests = <String>[];
    promptBodies = <Map<String, Object?>>[];
    frames = <Map<String, Object?>>[];
    stateJson = <String, Object?>{
      'projects': <Object?>[
        <String, Object?>{'id': 'p1', 'name': 'PiDeck', 'path': '/repo'},
      ],
      'sessions': <Object?>[
        <String, Object?>{
          'id': 's1',
          'projectId': 'p1',
          'title': '第一个会话',
          'status': 'idle',
          'updatedAt': 2,
          'backend': 'pi',
        },
      ],
      'runtimes': <Object?>[
        <String, Object?>{'sessionId': 's1', 'agentId': 'a1', 'runtimeGeneration': 1},
      ],
      'pendingUiRequests': <Object?>[],
    };
    historyItems = <Map<String, Object?>>[
      <String, Object?>{
        'id': 'm1',
        'role': 'assistant',
        'time': '00:00',
        'content': <Object?>[
          <String, Object?>{'type': 'text', 'text': '上一轮的回答'},
        ],
      },
    ];

    server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((HttpRequest request) async {
      final String path = request.uri.path;
      requests.add(path);
      final String raw = await utf8.decoder.bind(request).join();
      if (path == '/api/sessions/s1/prompt') {
        promptBodies.add(raw.isEmpty ? <String, Object?>{} : Map<String, Object?>.from(jsonDecode(raw) as Map));
        request.response.headers.contentType = ContentType.json;
        request.response.write(jsonEncode(<String, Object?>{'ok': true}));
        await request.response.close();
        return;
      }
      if (path == '/api/events') {
        final HttpResponse response = request.response;
        // 关键：不关缓冲的话客户端一帧都收不到（流式连接永远不 close）。
        response.bufferOutput = false;
        response.headers.contentType = ContentType('text', 'event-stream', charset: 'utf-8');
        response.write('event: state\ndata: ${jsonEncode(stateJson)}\n\n');
        await response.flush();
        return;
      }
      if (path == '/api/sessions/s1/stream') {
        final HttpResponse response = request.response;
        response.bufferOutput = false;
        response.headers.contentType = ContentType('text', 'event-stream', charset: 'utf-8');
        // 一条连接活到整轮结束：帧是用例发送之后才推的，连上就写 [DONE] 会让后面一帧都收不到。
        int sent = 0;
        while (!doneStreaming) {
          while (sent < frames.length) {
            response.write('data: ${jsonEncode(frames[sent++])}\n\n');
            await response.flush();
          }
          await Future<void>.delayed(const Duration(milliseconds: 5));
        }
        while (sent < frames.length) {
          response.write('data: ${jsonEncode(frames[sent++])}\n\n');
          await response.flush();
        }
        response.write('data: [DONE]\n\n');
        await response.flush();
        await response.close();
        return;
      }
      final HttpResponse response = request.response;
      response.headers.contentType = ContentType.json;
      if (path == '/api/state') {
        response.write(jsonEncode(stateJson));
      } else if (path == '/api/sessions/s1/messages') {
        response.write(jsonEncode(<String, Object?>{
          'messages': historyItems,
          'total': historyItems.length,
        }));
      } else if (path == '/api/git/status') {
        response.write(jsonEncode(<String, Object?>{
          'repo': true,
          'branch': <String, Object?>{'current': 'master', 'branches': <Object?>['master']},
          'groups': <String, Object?>{
            'workingTree': <Object?>[
              <String, Object?>{'path': '/repo/src/a.dart', 'status': 2, 'letter': 'M'},
            ],
          },
        }));
      } else if (path == '/api/git/log') {
        response.write(jsonEncode(<String, Object?>{
          'commits': <Object?>[
            <String, Object?>{
              'hash': 'abcdef1234567',
              'message': 'feat: 一条提交\n\n正文',
              'authorDate': '2026-11-02T10:00:00+08:00',
            },
          ],
        }));
      } else if (path == '/api/files') {
        response.write(jsonEncode(<String, Object?>{
          'nodes': <Object?>[
            <String, Object?>{'name': 'src', 'relativePath': 'src', 'type': 'directory', 'hasChildren': true},
            <String, Object?>{'name': 'README.md', 'relativePath': 'README.md', 'type': 'file'},
          ],
        }));
      } else if (path == '/api/file-content') {
        response.write(jsonEncode(<String, Object?>{'content': '# 标题\n正文', 'size': 12}));
      } else {
        response.write(jsonEncode(<String, Object?>{'ok': true}));
      }
      await response.close();
    });

    client = PiDeckClient(baseUrl: 'http://127.0.0.1:${server.port}', token: 't');
    runtime = ChatRuntime(client: client, sessionId: 's1');
    workspace = WorkspaceRuntime(client: client, store: runtime.store, sessionId: 's1');
    runtime.start();
    await _waitFor(() => runtime.historyLoaded && runtime.store.ready);
  });

  tearDown(() async {
    workspace.dispose();
    runtime.dispose();
    await server.close(force: true);
  });

  test('一轮对话：发送 → 收帧 → 收口 → 重拉历史 → live 清空', () async {
    expect(await runtime.submit('你好'), isTrue);
    expect(promptBodies.single['message'], '你好');
    expect(promptBodies.single['requestId'], isNotEmpty);

    // 服务端开始推这一轮：帧只会在发送之后出现，顺序与真实链路一致。
    frames = <Map<String, Object?>>[
      <String, Object?>{'type': 'start', 'messageId': 'm2'},
      <String, Object?>{'type': 'text-start', 'id': 't1'},
      <String, Object?>{'type': 'text-delta', 'id': 't1', 'delta': '好的'},
      <String, Object?>{'type': 'text-end', 'id': 't1'},
      <String, Object?>{'type': 'finish'},
    ];
    doneStreaming = true;

    // 服务端把这一轮落盘：下一次拉历史就要看到用户消息 + 回答。
    historyItems = <Map<String, Object?>>[
      ...historyItems,
      <String, Object?>{
        'id': 'm2',
        'role': 'user',
        'time': '00:01',
        'content': <Object?>[
          <String, Object?>{'type': 'text', 'text': '你好'},
        ],
      },
      <String, Object?>{
        'id': 'm3',
        'role': 'assistant',
        'time': '00:01',
        'content': <Object?>[
          <String, Object?>{'type': 'text', 'text': '好的'},
        ],
      },
    ];

    // 帧到齐 → finished；之后 400ms 自动重拉历史（settle）。
    await _waitFor(() => runtime.reducer.status == RunStatus.finished, label: 'finished');
    // settle：finish 后 400ms 重拉历史，然后把 live 块清空（否则历史与流式双份渲染）。
    await _waitFor(() {
      final Map<String, Object?> live = runtime.snapshotJson()['live']! as Map<String, Object?>;
      return (live['blocks']! as List<Object?>).isEmpty;
    }, label: 'live 清空（状态 ${runtime.reducer.status}）');

    // 650ms 后的历史对账要把乐观回显摘掉（同文本的 user 消息已经在历史里了）。
    await _waitFor(() => (runtime.snapshotJson()['history']! as List<Object?>).length == 3, label: '历史对账');

    final Map<String, Object?> snapshot = runtime.snapshotJson();
    expect(snapshot['status'], 'finished');
    final List<Object?> history = snapshot['history']! as List<Object?>;
    expect(history.length, 3);
    expect((history[1] as Map<String, Object?>)['text'], '你好');
    // 乐观回显必须已经被历史覆盖掉，否则同一句话会出现两次。
    expect(
      history.where((Object? item) => (item as Map<String, Object?>)['text'] == '你好').length,
      1,
    );
    expect(snapshot['title'], '第一个会话');
  });

  test('工作区：文件 → git → 预览，都从 Dart 自己的接口取', () async {
    await workspace.openDrawer();
    expect(workspace.isOpen, isTrue);
    expect(workspace.tab, 'files');
    Map<String, Object?> snapshot = workspace.snapshotJson();
    // 「文件」页不查 git，分支是空的（切到「改动」才有）。
    expect(snapshot['branch'], '');
    expect((snapshot['entries']! as List<Object?>).length, 2);
    // 目录排前面，且相对路径取最后一段做名字。
    expect((snapshot['entries']! as List<Object?>).first, containsPair('name', 'src'));
    expect(snapshot['crumbs'], <Object?>['PiDeck']);

    await workspace.setTab('git');
    snapshot = workspace.snapshotJson();
    expect(snapshot['tab'], 'git');
    expect(snapshot['branch'], 'master');
    expect(snapshot['changedTotal'], 1);
    expect((snapshot['changes']! as List<Object?>).single, containsPair('path', 'src/a.dart'));
    expect((snapshot['commits']! as List<Object?>).single, containsPair('sha', 'abcdef1'));

    await workspace.preview('README.md');
    snapshot = workspace.snapshotJson();
    final Map<String, Object?> preview = snapshot['preview']! as Map<String, Object?>;
    expect(preview['path'], 'README.md');
    expect(preview['content'], '# 标题\n正文');
    expect(preview['truncated'], isFalse);

    workspace.closePreview();
    expect(workspace.snapshotJson()['preview'], isNull);
  });

  test('侧栏快照：Dart 用同一份 state 算出会话行', () async {
    final Map<String, Object?> snapshot = buildSidebarSnapshot(
      DrawerState().toInput(
        state: runtime.store.state,
        endpointId: 'native',
        endpointLabel: '',
        activeSessionId: 's1',
        activeProjectId: 'p1',
        sheet: null,
      ),
    );
    final List<Object?> sections = snapshot['sections']! as List<Object?>;
    expect(sections, isNotEmpty);
    final String encoded = jsonEncode(sections);
    expect(encoded, contains('第一个会话'));
    expect(snapshot['deviceCount'], 1);
  });
}

Future<void> _waitFor(
  bool Function() condition, {
  Duration timeout = const Duration(seconds: 8),
  String label = '',
}) async {
  final DateTime deadline = DateTime.now().add(timeout);
  while (!condition()) {
    if (DateTime.now().isAfter(deadline)) throw StateError('等待超时: $label');
    await Future<void>.delayed(const Duration(milliseconds: 10));
  }
}
