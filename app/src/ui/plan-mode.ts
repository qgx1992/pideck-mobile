/**
 * 「计划模式」的发送载荷。
 *
 * PiDeck 的做法（`buildComposerPromptSubmission`）：用户可见的消息保持原文，
 * 另外在 `agentMessage` 里塞一个**隐藏标记** `__PI_DECK_PLAN_MODE__`，
 * 由内置扩展 `pi-deck-plan-mode` 在 pi 的 input 事件里识别，切成只读工具集。
 *
 * 这里必须与桌面端逐字一致，否则扩展识别不到 → 标记会原样进 prompt（更糟）。
 * 所以标记和指令文本都照抄上游源码。
 */

/** 与 `@shared/composerModeMarkers` 同一字面量 */
export const PI_DECK_PLAN_MODE_MARKER = "__PI_DECK_PLAN_MODE__";

/** 计划模式追加给 agent 的指令（上游 buildComposerPromptSubmission 原文） */
const PLAN_INSTRUCTION_LINES = [
  "请先只做只读分析，不要修改文件。最后必须输出以 `Plan:` 开头的编号计划，格式如下：",
  "Plan:",
  "1. 第一步",
  "2. 第二步",
];

/**
 * 空输入时也要给 agent 一句可执行的指令（上游同款兜底）。
 */
const EMPTY_INSTRUCTION = "请根据已附加的图片或上下文先制定实施计划。";

export interface PlanSubmission {
  /** 用户可见文本（时间线上显示的就是它，不含任何标记） */
  message: string;
  /** 是否带上了计划模式标记 */
  planMode: boolean;
  /** 仅发给 agent 的隐藏消息 */
  agentMessage?: string;
}

/**
 * 构造一次发送。规则与上游一致：
 *   - 斜线命令原样发送（`/plan off` 这类要在计划模式下也能用），不包标记
 *   - 其余消息：visible 保持用户原文，agentMessage = 标记 + 指令 + 计划格式
 */
export function buildPlanSubmission(text: string, planMode: boolean): PlanSubmission {
  const message = text.trim();
  if (!planMode) return { message: text, planMode: false };
  if (message.startsWith("/")) return { message: text, planMode: false };

  const instruction = message || EMPTY_INSTRUCTION;
  return {
    message: text,
    planMode: true,
    agentMessage: [PI_DECK_PLAN_MODE_MARKER, instruction, "", ...PLAN_INSTRUCTION_LINES].join("\n"),
  };
}

/**
 * 计划模式是否应该自动关掉。
 *
 * 桌面端是「一次性提交流」：发完回到普通模式，避免用户忘了还开着计划模式
 * 导致后续提问全被限制成只读。
 */
export function shouldAutoExitPlan(planMode: boolean): boolean {
  return planMode;
}
