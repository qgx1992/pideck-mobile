/**
 * 子代理活动条（贴在输入框上方，任务清单条之上）。
 *
 * 与任务清单同一套交互：头部可折叠、条目只读、没有数据就整条隐藏。
 * 有子代理在跑时头部带呼吸点，跟会话运行中的视觉语言保持一致。
 */

import { h, clear } from "./dom.ts";
import { createChevron, setChevronExpanded } from "./chevron.ts";
import { shouldShowSubagents, summarizeSubagents } from "./subagent-model.ts";
import type { SubagentView } from "./subagent-model.ts";

export interface SubagentStrip {
  el: HTMLElement;
  /** 传空数组/undefined 就隐藏整条 */
  update: (list: readonly SubagentView[], options?: { collapsed?: boolean }) => void;
}

function agentIcon(): SVGSVGElement {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("width", "14");
  svg.setAttribute("height", "14");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "2");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("class", "subagent-icon");
  // lucide bot 的简化：一个方脑袋 + 两个眼睛
  for (const d of ["M12 3v3", "M5 8h14v10H5z", "M9 13h.01", "M15 13h.01"]) {
    const path = document.createElementNS(ns, "path");
    path.setAttribute("d", d);
    svg.append(path);
  }
  return svg;
}

export function createSubagentStrip(): SubagentStrip {
  const headLabel = h("span", { class: "subagent-label", text: "子代理" });
  const summaryEl = h("span", { class: "subagent-summary", text: "" });
  const pulse = h("span", { class: "subagent-pulse", "aria-hidden": "true" });
  const head = h("button", { class: "subagent-head", type: "button" }, [agentIcon(), headLabel, summaryEl, pulse]);
  const list = h("ul", { class: "subagent-list" });
  const el = h("div", { class: "subagent-strip is-hidden" }, [head, list]);

  let collapsed = false;
  const chevron = createChevron(true, 12);
  head.append(chevron);

  const applyCollapsed = (next: boolean): void => {
    collapsed = next;
    el.classList.toggle("is-collapsed", collapsed);
    setChevronExpanded(chevron, !collapsed);
    list.style.display = collapsed ? "none" : "";
  };
  head.addEventListener("click", () => applyCollapsed(!collapsed));

  return {
    el,
    update: (items, options = {}) => {
      if (!shouldShowSubagents(items)) {
        el.classList.add("is-hidden");
        el.classList.remove("has-active", "has-failure");
        clear(list);
        if (summaryEl.textContent !== "") summaryEl.textContent = "";
        return;
      }

      const summary = summarizeSubagents(items);
      el.classList.remove("is-hidden");
      el.classList.toggle("has-active", summary.active > 0);
      el.classList.toggle("has-failure", summary.failed > 0);
      if (summaryEl.textContent !== summary.title) summaryEl.textContent = summary.title;
      head.title = summary.title;

      if (options.collapsed !== undefined) applyCollapsed(options.collapsed);

      clear(list);
      for (const item of items) {
        const row = h("li", { class: `subagent-item is-${item.status}` }, [
          h("span", { class: "subagent-type", text: item.type }),
          h("span", { class: "subagent-desc", text: item.description || item.statusLabel }),
          h("span", { class: "subagent-status", text: item.statusLabel }),
        ]);
        if (item.detail && item.detail !== item.statusLabel) {
          row.append(h("div", { class: "subagent-detail", text: item.detail }));
        }
        list.append(row);
      }
    },
  };
}
