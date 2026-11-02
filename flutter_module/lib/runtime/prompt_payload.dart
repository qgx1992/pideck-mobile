/// 发送载荷：计划模式注入、运行中排队/插话、服务端拒收文案。
///
/// 全部逐字对齐 Web 侧三份实现，**不要在这里改文案或规则**：
/// - `app/src/ui/plan-mode.ts`      → [buildPlanSubmission]
/// - `app/src/ui/send-mode.ts`      → [streamingBehaviorFor]
/// - `app/src/ui/acp-notice.ts`     → [PromptRejection] / [rejectionMessage] / [shouldRollbackRejection]
///
/// 这些字面量（尤其 `__PI_DECK_PLAN_MODE__` 标记和计划格式）必须与桌面端逐字一致，
/// 否则内置扩展识别不到，标记会原样进 prompt —— 那比没有计划模式更糟。
library;

/// 与 `@shared/composerModeMarkers` 同一字面量。
const String planModeMarker = '__PI_DECK_PLAN_MODE__';

/// 计划模式追加给 agent 的指令（上游 `buildComposerPromptSubmission` 原文）。
const List<String> _planInstructionLines = <String>[
  '请先只做只读分析，不要修改文件。最后必须输出以 `Plan:` 开头的编号计划，格式如下：',
  'Plan:',
  '1. 第一步',
  '2. 第二步',
];

/// 空输入时也要给 agent 一句可执行的指令（上游同款兜底）。
const String _emptyInstruction = '请根据已附加的图片或上下文先制定实施计划。';

/// 运行中发消息的两种行为。
const String sendModeQueue = 'queue';
const String sendModeSteer = 'steer';

/// 一次发送的载荷。
class PlanSubmission {
  const PlanSubmission({
    required this.message,
    required this.planMode,
    this.agentMessage,
  });

  /// 用户可见文本（时间线上显示的就是它，不含任何标记）。
  final String message;

  /// 是否带上了计划模式标记。
  final bool planMode;

  /// 仅发给 agent 的隐藏消息。
  final String? agentMessage;
}

/// 构造一次发送。规则与上游一致：
/// - 斜线命令原样发送（`/plan off` 这类要在计划模式下也能用），不包标记
/// - 其余消息：visible 保持用户原文，agentMessage = 标记 + 指令 + 计划格式
PlanSubmission buildPlanSubmission(String text, bool planMode) {
  final String message = text.trim();
  if (!planMode) return PlanSubmission(message: text, planMode: false);
  if (message.startsWith('/')) return PlanSubmission(message: text, planMode: false);

  final String instruction = message.isEmpty ? _emptyInstruction : message;
  return PlanSubmission(
    message: text,
    planMode: true,
    agentMessage: <String>[planModeMarker, instruction, '', ..._planInstructionLines].join('\n'),
  );
}

/// 决定这次 prompt 要不要带 `streamingBehavior`。
///
/// 空闲时不带：让服务端按普通发送处理，也免得旧版本 PiDeck 收到未知字段。
String? streamingBehaviorFor(bool running, String mode) {
  if (!running) return null;
  return mode == sendModeSteer ? 'steer' : 'followUp';
}

/// 服务端「拒收本条提问」的结构化说明（`accepted === false`）。
class PromptRejection {
  const PromptRejection({
    this.error = '',
    this.delivery = '',
    this.i18nKey = '',
    this.i18nParams = const <String, Object?>{},
  });

  final String error;

  /// `rejected` = 服务端确定没发出去；`unknown` = 可能已经发出去（别回滚气泡）。
  final String delivery;
  final String i18nKey;
  final Map<String, Object?> i18nParams;

  /// 拒收后要不要把乐观上屏的气泡撤掉。
  ///
  /// `delivery == "unknown"` 表示服务端自己也不确定发没发出去（比如回合中途超时），
  /// 这时撤掉气泡会跟真实历史对不上，宁可按「已发出」处理。
  bool get shouldRollback => delivery != 'unknown';
}

/// 缺参数时的兜底称呼：宁可说「该 CLI」，也不要漏出 `{tool}` 花括号或裸 id。
const String _toolFallback = '该 CLI';

String _strOf(Object? value) {
  if (value is String) return value.trim();
  if (value is num && value.isFinite) return value.toString();
  return '';
}

String _toolOf(Map<String, Object?> params) {
  final String tool = _strOf(params['tool']);
  return tool.isEmpty ? _toolFallback : tool;
}

String _rejectionMessageFor(String key, Map<String, Object?> params) {
  final String tool = _toolOf(params);
  switch (key) {
    case 'acp.processNotRunning':
      return '$tool 进程未在运行，请重试';
    case 'acp.toolNotInstalled':
      final String command = _strOf(params['command']);
      return '「$tool」启动失败：电脑上这个 CLI 没装或不在 PATH 里'
          '${command.isEmpty ? '' : '（$command）'}，装好后再在电脑端检测一次';
    case 'acp.toolMissing':
      return '这个会话绑定的 ACP 工具已被删除或停用，请在电脑端 设置 → ACP 工具 里重新登记后重试';
    case 'acp.startupTimeout':
      return '「$tool」启动超时（首次下载或冷启动会比较慢），再发一次试试';
    case 'acp.turnTimeout':
      final String minutes = _strOf(params['minutes']);
      return '「$tool」回合超时'
          '${minutes.isEmpty ? '' : '（连续 $minutes 分钟没有任何输出）'}，可以在电脑端把超时保护调大';
    case 'acp.hostInstructionsUnsupported':
      return 'ACP 后端不支持 PiDeck 的内部指令（计划模式、安全级别这类）';
    case 'acp.turnBusy':
      return '$tool 还在回复中，等这一轮结束或先点停止再发';
    case 'acp.abortForceStopped':
      return '「$tool」长时间没响应停止请求，已强制结束本轮，重新发送即可继续';
    case 'acp.stallForceStopped':
      return '「$tool」长时间没有任何输出，已自动结束本轮，检查该 CLI 的模型/网络后再试';
    case 'acp.emptyTurn':
      return '「$tool」这一轮结束了但没有任何输出，重发一次试试';
    case 'acp.imagesNotSupported':
      return '$tool 不接受图片输入，移除图片后再发';
    case 'acp.authRequired':
      return '「$tool」未登录或凭据已失效：先在电脑的终端里运行这个 CLI 完成登录，再回来重试';
  }
  return '';
}

/// 拒绝文案。命中 i18nKey 就用本地化模板；没命中退到服务端原文 `error`；
/// 两者都没有给一句能 actionable 的兜底。
String rejectionMessage(PromptRejection rejection) {
  final String key = rejection.i18nKey.trim();
  if (key.isNotEmpty) {
    final String text = _rejectionMessageFor(key, rejection.i18nParams);
    if (text.isNotEmpty) return text;
  }
  final String raw = rejection.error.trim();
  if (raw.isNotEmpty) return '发送被拒绝：$raw';
  return '发送被拒绝：桌面端没有说明原因，可以重发一次看看';
}
