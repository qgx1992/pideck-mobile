/**
 * 「提示词」面板的视图模型：只列**已导入**的提示词模板。
 *
 * 已导入 = pi 真正加载到的 prompt 模板（`~/.pi/agent/prompts/*.md`、`<项目>/.pi/prompts/*.md`、
 * npm 包与内置模板）。它们在 `/runtime/commands` 里以 `source: "prompt"` 出现，名字就是
 * 输入框里敲的 `/名字`。
 *
 * 选中后只往输入框插 `/名字 `，正文交给 pi 在发送时展开 —— 与电脑端输入框的模板行为一致。
 * 不要试图自己拉正文：Web API 没有「读模板正文」的路由（`/api/prompts` 是另一套
 * XuePrompt 中文商店库，与这里不是一回事）。
 */

import type { CommandInfo } from "@core/types.ts";

export interface PromptEntry {
  /** 不含斜线的模板名 */
  name: string;
  description: string;
}

/** `/runtime/commands` 条目是不是提示词模板。 */
export function isPromptCommand(raw: CommandInfo): boolean {
  return typeof raw.source === "string" && raw.source.trim().toLowerCase() === "prompt";
}

/**
 * 从运行时命令表里挑出提示词模板：只留 `source === "prompt"`，按名字去重并按名字排序。
 *
 * 排序用 localeCompare（与电脑端模板面板一致），不然顺序随 pi 的加载顺序漂。
 */
export function importedPrompts(commands: readonly CommandInfo[]): PromptEntry[] {
  const seen = new Set<string>();
  const items: PromptEntry[] = [];
  for (const raw of commands ?? []) {
    if (!isPromptCommand(raw)) continue;
    const name = typeof raw.name === "string" ? raw.name.trim().replace(/^\/+/, "") : "";
    if (!name) continue;
    const key = name.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    items.push({
      name,
      description: typeof raw.description === "string" ? raw.description.trim() : "",
    });
  }
  return items.sort((a, b) => a.name.localeCompare(b.name));
}

/** 本地过滤：名字或描述命中即可（数量只有几十条，不必打接口）。 */
export function filterPromptEntries(items: readonly PromptEntry[], query: string): PromptEntry[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [...items];
  return items.filter(
    (item) =>
      item.name.toLowerCase().includes(needle) || item.description.toLowerCase().includes(needle),
  );
}

/** 点一条后插进输入框的文本（尾随空格，方便接着写参数）。 */
export function promptInsertText(entry: PromptEntry): string {
  return `/${entry.name} `;
}

/**
 * 该向哪个会话要命令表。
 *
 * 模板是用户级/项目级资源，与具体是哪个会话无关，所以当前会话没有运行实例时可以借
 * 任意一个有运行实例的会话（优先 idle）—— 否则「新建会话还没发消息」时面板会一直是空的。
 */
export function promptCommandSource(
  runtimes: readonly { sessionId: string; status?: string }[],
  activeSessionId: string | null,
): string | null {
  if (activeSessionId && runtimes.some((item) => item.sessionId === activeSessionId)) {
    return activeSessionId;
  }
  const idle = runtimes.find((item) => item.status === "idle");
  if (idle) return idle.sessionId;
  return runtimes[0]?.sessionId ?? null;
}

/** 一条都没有时的引导文案。 */
export const PROMPT_EMPTY_HINT =
  "这台 PiDeck 还没有已导入的提示词。在电脑端「设置 → 提示词库」里导入，或把 .md 放进 ~/.pi/agent/prompts/，之后这里就能看到。";

/** 借不到运行时（一台会话都没启动过）时的文案。 */
export const PROMPT_NO_RUNTIME_HINT = "还没有启动中的会话，读不到提示词列表：先发一条消息，再回来点这里。";
