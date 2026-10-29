/**
 * 把连续的「过程」条目（思考、工具调用）收进一个折叠块。
 *
 * 桌面端就是这么做的：整轮收成一行「执行过程：3 个工具 · 2 段思考」，
 * 而不是每个工具各占一行。工具多的会话（几百次调用）差异非常明显。
 */

import type { ChatBlock } from "@core/types.ts";
import { h } from "./dom.ts";
import { createChevron, setChevronExpanded } from "./chevron.ts";

export interface ProcessSummary {
  tools: number;
  thinkings: number;
  /** 有失败的工具时，折叠标题上直接标出来，避免「藏起来的错误」 */
  failures: number;
}

export function summarize(items: readonly { kind: "tool" | "reasoning" }[]): ProcessSummary {
  let tools = 0;
  let thinkings = 0;
  let failures = 0;
  for (const item of items) {
    if (item.kind === "tool") {
      tools += 1;
      const state = (item as unknown as { state?: string }).state;
      if (state === "error") failures += 1;
    } else {
      thinkings += 1;
    }
  }
  return { tools, thinkings, failures };
}

export function processTitle(summary: ProcessSummary): string {
  const parts: string[] = [];
  if (summary.tools > 0) parts.push(`${summary.tools} 个工具`);
  if (summary.thinkings > 0) parts.push(`${summary.thinkings} 段思考`);
  const head = parts.length > 0 ? parts.join(" · ") : "执行过程";
  return summary.failures > 0 ? `${head}（${summary.failures} 个失败）` : head;
}

export interface ProcessGroup {
  /** <details> 节点，直接放进容器 */
  el: HTMLElement;
  /** 条目挂载点 */
  body: HTMLElement;
  /** 根据当前条目刷新折叠标题 */
  refresh: (summary: ProcessSummary) => void;
}

export function createProcessGroup(options: { open?: boolean } = {}): ProcessGroup {
  // 默认一律收起：流式期间展开会让几十个工具卡片铺满屏幕（桌面端也是收起的）
  const summaryEl = h("span", { class: "process-title", text: "执行过程" });
  const details = h("details", { class: "process" });
  if (options.open) details.setAttribute("open", "");
  // 箭头自己画（之前是 CSS content，被写坏成字面量 "B8"）
  const chevron = createChevron(Boolean(options.open), 14);
  details.append(h("summary", {}, [chevron, summaryEl]));
  details.addEventListener("toggle", () => {
    setChevronExpanded(chevron, details.hasAttribute("open"));
  });

  const body = h("div", { class: "process-body" });
  details.append(body);

  return {
    el: details,
    body,
    refresh: (summary) => {
      const text = processTitle(summary);
      if (summaryEl.textContent !== text) summaryEl.textContent = text;
      details.classList.toggle("has-failure", summary.failures > 0);
    },
  };
}

/** 从块序列里判断是否属于「过程」类。 */
export function isProcessBlock(block: ChatBlock): boolean {
  return block.kind === "tool" || block.kind === "reasoning";
}

export { summarize as summarizeProcess };
