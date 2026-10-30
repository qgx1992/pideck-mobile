/**
 * 任务清单条带（贴在输入框上方，对齐 Web 端的 WebTodoStrip）。
 *
 * 只做展示，不可点选：清单由 agent 维护，用户点它没意义。
 * 头部可点击折叠/展开，折叠状态记在内存里（每个会话各自记住）。
 */

import { h, clear } from "./dom.ts";
import { createChevron, setChevronExpanded } from "./chevron.ts";
import { todoProgressLabel, todoTitle } from "./todo-model.ts";
import type { TodoView } from "./todo-model.ts";
import { shouldShowTodoStrip } from "./todo-history-model.ts";

export interface TodoStrip {
  el: HTMLElement;
  /** 渲染最新数据；view 为 null 时整条隐藏 */
  update: (view: TodoView | null, options?: { collapsed?: boolean }) => void;
}

/** 清单图标（lucide list-todo 的简化两行版）。 */
function listIcon(): SVGSVGElement {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("width", "14");
  svg.setAttribute("height", "14");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "2");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("class", "todo-icon");
  for (const d of ["M9 6h11", "M9 12h11", "M9 18h11", "M4 6h1", "M4 12h1", "M4 18h1"]) {
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", d);
    svg.append(path);
  }
  return svg;
}

/** 单条状态图标：完成=对勾，进行中=脉冲点，待办=空心圈。 */
function statusGlyph(status: string): HTMLElement {
  if (status === "completed") return h("span", { class: "todo-check", text: "✓" });
  if (status === "in_progress") return h("span", { class: "todo-running" });
  return h("span", { class: "todo-dot" });
}

export function createTodoStrip(): TodoStrip {
  const headLabel = h("span", { class: "todo-label", text: "任务清单" });
  const progress = h("span", { class: "todo-progress", text: "" });
  const head = h("button", { class: "todo-head", type: "button" }, [
    listIcon(),
    headLabel,
    progress,
  ]);
  const list = h("ul", { class: "todo-list" });
  const el = h("div", { class: "todo-strip is-hidden" }, [head, list]);

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
    update: (view, options = {}) => {
      // 全部完成就不再挂在输入框上方（历史里能查到）
      if (view === null || !shouldShowTodoStrip(view)) {
        el.classList.add("is-hidden");
        el.classList.remove("has-active", "is-all-done");
        clear(list);
        // 进度文案也要清掉，否则切到没有清单的会话会留着上一个会话的 "6/6"
        if (progress.textContent !== "") progress.textContent = "";
        return;
      }
      el.classList.remove("is-hidden");
      el.classList.toggle("has-active", view.active > 0);
      el.classList.toggle("is-all-done", !view.unfinished);

      const label = todoProgressLabel(view);
      if (progress.textContent !== label) progress.textContent = label;
      head.title = todoTitle(view);

      if (options.collapsed !== undefined) applyCollapsed(options.collapsed);

      // 条目不多，整体重画最省心（清单量级通常 < 20 条）
      clear(list);
      for (const item of view.items) {
        list.append(
          h(
            "li",
            {
              class: `todo-item is-${item.status}`,
              title: item.text,
            },
            [statusGlyph(item.status), h("span", { class: "todo-text", text: item.text })],
          ),
        );
      }
    },
  };
}
