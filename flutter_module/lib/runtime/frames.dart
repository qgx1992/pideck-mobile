/// 会话流 → 可渲染块序列 —— `src/api/frames.ts` 的 Dart 移植。
///
/// 状态机语义必须与 TS 版本逐条对齐（`tests/frames.test.ts` 是基准）：
///   start → (start-step) → text-start / text-delta* / text-end
///                        → reasoning-start / reasoning-delta* / reasoning-end
///                        → tool-input-start / tool-input-available / tool-output-available|error
///   error → finish
/// 未知帧不报错：原样交给上层（`unknownFrame`），保证新版本服务端不会把客户端打挂。
library;

import 'dart:convert';

/// 一轮运行的状态。
enum RunStatus { idle, running, finished, error, aborted }

/// 一个可渲染块：正文 / 思考 / 工具调用。
sealed class ChatBlock {
  const ChatBlock();

  /// 流式更新时的 widget key：工具按 callId，其余按 id。
  String get key;

  Map<String, Object?> toJson();
}

class TextBlock extends ChatBlock {
  TextBlock({required this.id, this.text = '', this.done = false});

  final String id;
  String text;
  bool done;

  @override
  String get key => 'text:$id';

  @override
  Map<String, Object?> toJson() => <String, Object?>{
        'kind': 'text',
        'id': id,
        'text': text,
        'done': done,
      };
}

class ReasoningBlock extends ChatBlock {
  ReasoningBlock({required this.id, this.text = '', this.done = false});

  final String id;
  String text;
  bool done;

  @override
  String get key => 'reasoning:$id';

  @override
  Map<String, Object?> toJson() => <String, Object?>{
        'kind': 'reasoning',
        'id': id,
        'text': text,
        'done': done,
      };
}

class ToolBlock extends ChatBlock {
  ToolBlock({required this.toolCallId, this.toolName = 'tool', this.state = 'running'});

  final String toolCallId;
  final String toolName;

  /// `running` / `ok` / `error`
  String state;
  Object? input;
  Object? output;
  String? errorText;

  @override
  String get key => 'tool:$toolCallId';

  @override
  Map<String, Object?> toJson() => <String, Object?>{
        'kind': 'tool',
        'toolCallId': toolCallId,
        'toolName': toolName,
        'state': state,
        if (input != null) 'input': input,
        if (output != null) 'output': output,
        if (errorText != null) 'errorText': errorText,
      };
}

/// 一次 `apply()` 的结果。
class FrameApplyResult {
  const FrameApplyResult({this.changed = false, this.finished = false, this.unknownFrame});

  /// 是否有可见变化（UI 可据此跳过重渲染）。
  final bool changed;

  /// 本帧是否代表整轮结束。
  final bool finished;

  /// 未识别的帧，原样带出。
  final Map<String, Object?>? unknownFrame;
}

/// 唯一的聊天状态机（Dart 侧）。
///
/// **不要**在别处再实现一份：网页版用的就是同一份语义，两边漂移的代价是
/// 「同一个会话在网页版正常、原生页少一段」。
class ChatReducer {
  List<ChatBlock> blocks = <ChatBlock>[];
  RunStatus status = RunStatus.idle;
  String? messageId;
  String? error;

  void reset() {
    blocks = <ChatBlock>[];
    status = RunStatus.idle;
    messageId = null;
    error = null;
  }

  /// 只清流式块，保留 `status` / `messageId`。
  ///
  /// `settle`（finish 后重拉历史）时用：历史里已经有这一轮了，流式块再画一遍
  /// 就是双份。与 Web 侧 `chatView.clearLive()` 同口径 —— 状态仍然是 `finished`，
  /// 直到下一次发送时 `reset()` 才归零。
  void clearLive() {
    blocks = <ChatBlock>[];
  }

  FrameApplyResult apply(Map<String, Object?> frame) {
    switch (frame['type']) {
      case 'start':
        {
          // 新一轮开始：上一轮已经收口则清空重来
          if (status != RunStatus.running) {
            blocks = <ChatBlock>[];
            error = null;
          }
          messageId = _str(frame['messageId']);
          status = RunStatus.running;
          return const FrameApplyResult(changed: true);
        }
      case 'start-step':
        return const FrameApplyResult();
      case 'text-start':
        {
          _ensureText(_str(frame['id']));
          status = RunStatus.running;
          return const FrameApplyResult();
        }
      case 'text-delta':
        {
          final TextBlock block = _ensureText(_str(frame['id']));
          block.text += _str(frame['delta']);
          status = RunStatus.running;
          return const FrameApplyResult(changed: true);
        }
      case 'text-end':
        {
          _findText(_str(frame['id']))?.done = true;
          return const FrameApplyResult();
        }
      case 'reasoning-start':
        {
          _ensureReasoning(_str(frame['id']));
          return const FrameApplyResult();
        }
      case 'reasoning-delta':
        {
          final ReasoningBlock block = _ensureReasoning(_str(frame['id']));
          block.text += _str(frame['delta']);
          return const FrameApplyResult(changed: true);
        }
      case 'reasoning-end':
        {
          _findReasoning(_str(frame['id']))?.done = true;
          return const FrameApplyResult();
        }
      case 'tool-input-start':
        {
          _ensureTool(_str(frame['toolCallId']), _strOr(frame['toolName'], 'tool'));
          return const FrameApplyResult(changed: true);
        }
      case 'tool-input-available':
        {
          final ToolBlock block =
              _ensureTool(_str(frame['toolCallId']), _strOr(frame['toolName'], 'tool'));
          block.input = frame['input'];
          return const FrameApplyResult(changed: true);
        }
      case 'tool-output-available':
        {
          final ToolBlock? block = _findTool(_str(frame['toolCallId']));
          if (block != null) {
            block.state = 'ok';
            block.output = frame['output'];
          }
          return const FrameApplyResult(changed: true);
        }
      case 'tool-output-error':
        {
          final ToolBlock? block = _findTool(_str(frame['toolCallId']));
          if (block != null) {
            block.state = 'error';
            block.errorText = _strOr(frame['errorText'], 'Tool failed');
          }
          return const FrameApplyResult(changed: true);
        }
      case 'error':
        {
          error = _strOr(frame['errorText'], 'Agent run failed');
          status = RunStatus.error;
          _closeOpenBlocks();
          return const FrameApplyResult(changed: true);
        }
      case 'finish':
        {
          _closeOpenBlocks();
          if (status != RunStatus.error) status = RunStatus.finished;
          return const FrameApplyResult(changed: true, finished: true);
        }
      default:
        return FrameApplyResult(unknownFrame: frame);
    }
  }

  /// 用户主动 stop 后由上层调用，把未收口的块标成结束。
  void markAborted() {
    _closeOpenBlocks();
    if (status == RunStatus.running || status == RunStatus.idle) status = RunStatus.aborted;
  }

  void _closeOpenBlocks() {
    for (final ChatBlock block in blocks) {
      switch (block) {
        case TextBlock():
          block.done = true;
        case ReasoningBlock():
          block.done = true;
        case ToolBlock():
          break;
      }
    }
  }

  TextBlock? _findText(String id) {
    for (final ChatBlock block in blocks) {
      if (block is TextBlock && block.id == id) return block;
    }
    return null;
  }

  TextBlock _ensureText(String id) {
    final TextBlock? existing = _findText(id);
    if (existing != null) return existing;
    final TextBlock block = TextBlock(id: id);
    blocks.add(block);
    return block;
  }

  ReasoningBlock? _findReasoning(String id) {
    for (final ChatBlock block in blocks) {
      if (block is ReasoningBlock && block.id == id) return block;
    }
    return null;
  }

  ReasoningBlock _ensureReasoning(String id) {
    final ReasoningBlock? existing = _findReasoning(id);
    if (existing != null) return existing;
    final ReasoningBlock block = ReasoningBlock(id: id);
    blocks.add(block);
    return block;
  }

  ToolBlock? _findTool(String toolCallId) {
    for (final ChatBlock block in blocks) {
      if (block is ToolBlock && block.toolCallId == toolCallId) return block;
    }
    return null;
  }

  ToolBlock _ensureTool(String toolCallId, String toolName) {
    final ToolBlock? existing = _findTool(toolCallId);
    if (existing != null) return existing;
    final ToolBlock block = ToolBlock(toolCallId: toolCallId, toolName: toolName);
    blocks.add(block);
    return block;
  }
}

/// CLI / 通知预览用的纯文本摘要。
String blocksToPlainText(List<ChatBlock> blocks, {bool includeReasoning = false}) {
  final List<String> parts = <String>[];
  for (final ChatBlock block in blocks) {
    switch (block) {
      case TextBlock():
        parts.add(block.text);
      case ReasoningBlock():
        if (includeReasoning) parts.add('[思考] ${block.text}');
      case ToolBlock():
        final String state = block.state == 'ok'
            ? '完成'
            : block.state == 'error'
                ? '失败'
                : '运行中';
        parts.add('[工具 ${block.toolName} · $state]');
    }
  }
  return parts.join('\n');
}

/// 把一帧原文解析成帧对象：非法 JSON / 无 `type` 一律返回 null。
Map<String, Object?>? safeParseFrame(String data) {
  final String trimmed = data.trim();
  if (trimmed.isEmpty) return null;
  final Object? parsed;
  try {
    parsed = jsonDecode(trimmed);
  } catch (_) {
    return null;
  }
  if (parsed is! Map) return null;
  final Object? type = parsed['type'];
  if (type is! String || type.isEmpty) return null;
  return Map<String, Object?>.from(parsed);
}

/// JS 语义的字符串化：缺省 / null → 空串。
String _str(Object? value) => value == null ? '' : value.toString();

/// JS 语义的 `?? fallback`：只有 null / 缺省才替换（空串保留）。
String _strOr(Object? value, String fallback) => value == null ? fallback : value.toString();
