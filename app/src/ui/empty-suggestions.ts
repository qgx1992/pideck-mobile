/**
 * 首屏空态的快捷提示词。
 *
 * 图上的三条是给「项目」场景的（介绍项目 / 跑测试 / 审 diff）。
 * 内置 Chat 项目里没有 git、也没有可跑的测试，照搬会点了没意义，
 * 所以按项目类型给不同的三条。
 */

export type SuggestionContext = "project" | "chat";

export const PROJECT_SUGGESTIONS: readonly string[] = [
  "介绍一下这个项目的整体情况",
  "运行测试并修复失败的部分",
  "审查我尚未提交的改动",
];

export const CHAT_SUGGESTIONS: readonly string[] = [
  "介绍一下你自己，以及你能帮我做什么",
  "帮我把刚才的想法整理成一份清单",
  "用简单的例子解释一下这个概念",
];

export function suggestionsFor(context: SuggestionContext): readonly string[] {
  return context === "chat" ? CHAT_SUGGESTIONS : PROJECT_SUGGESTIONS;
}

/** 从项目对象推断该用哪一组（kind === "chat" 的内置项目走 Chat 组）。 */
export function suggestionContext(project: { kind?: string } | undefined | null): SuggestionContext {
  return project?.kind === "chat" ? "chat" : "project";
}

/** 空态标题。 */
export function emptyStateTitle(): string {
  return "开始使用你的 PiDeck";
}

/** 空态副标题。 */
export function emptyStateSubtitle(context: SuggestionContext): string {
  return context === "chat"
    ? "发送一条消息即可开始新的一轮。"
    : "发送一条消息即可开始新的一轮，或者先用下面的提示词试试。";
}
