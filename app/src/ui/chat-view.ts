/** 对话渲染：历史消息 + 当前轮的流式块 + ask 确认卡片。 */

import type { ChatBlock, PendingUiRequest, RunStatus, WireMessage } from "@core/types.ts";
import { h, clear } from "./dom.ts";
import { createProcessGroup, isProcessBlock, summarizeProcess } from "./process-group.ts";
import { splitThinking, stripCompleteThinking } from "./thinking-tags.ts";
import {
  answeredCount,
  isAnswered,
  normalizeBatchQuestions,
  serializeBatchAnswers,
  toAskOptions,
  toggleSelection,
} from "./ask-model.ts";
import type { BatchAnswer, BatchAnswerMeta } from "./ask-model.ts";

export interface ChatViewHandlers {
  onRespond: (pending: PendingUiRequest, response: Record<string, unknown>) => void;
}

export class ChatView {
  private readonly scroll: HTMLElement;
  private readonly historyEl: HTMLElement;
  private readonly liveEl: HTMLElement;
  private readonly pendingEl: HTMLElement;
  private readonly statusEl: HTMLElement;
  private readonly handlers: ChatViewHandlers;
  private liveNodes = new Map<string, HTMLElement>();
  private liveKey = "";
  private liveGroups: Array<ReturnType<typeof createProcessGroup>> = [];
  private liveShape = "";
  private localEcho: { text: string; el: HTMLElement } | null = null;
  private stick = true;

  constructor(root: HTMLElement, handlers: ChatViewHandlers) {
    this.handlers = handlers;
    this.historyEl = h("div", { class: "history" });
    this.liveEl = h("div", { class: "live" });
    this.pendingEl = h("div", { class: "pending" });
    this.statusEl = h("div", { class: "run-status" });
    this.scroll = h("div", { class: "chat-scroll" }, [this.historyEl, this.liveEl, this.statusEl, this.pendingEl]);
    this.scroll.addEventListener("scroll", () => {
      const distance = this.scroll.scrollHeight - this.scroll.scrollTop - this.scroll.clientHeight;
      this.stick = distance < 80;
    });
    clear(root);
    root.append(this.scroll);
  }

  clear(): void {
    clear(this.historyEl);
    clear(this.liveEl);
    clear(this.pendingEl);
    this.statusEl.textContent = "";
    this.liveNodes.clear();
    this.liveGroups = [];
    this.liveShape = "";
    this.liveKey = "";
    this.localEcho = null;
  }

  /**
   * 发送时立刻把自己的提问显示出来。
   *
   * 服务端要等下一轮历史落盘才会带上这条消息，若不本地回显，用户会以为没发出去
   * （之前的 bug：只有重新进会话才看得到自己的提问）。
   */
  appendLocalUser(text: string): void {
    const trimmed = text.trim();
    if (!trimmed) return;
    this.clearLocalEcho();
    const el = messageNode("user", trimmed, Date.now());
    el.classList.add("msg--local");
    this.historyEl.append(el);
    this.localEcho = { text: trimmed, el };
    this.scrollToBottom();
  }

  private clearLocalEcho(): void {
    if (!this.localEcho) return;
    this.localEcho.el.remove();
    this.localEcho = null;
  }

  /** 清空当前轮的流式区（轮次结束后调用，避免工具卡片一直挂着）。 */
  clearLive(): void {
    clear(this.liveEl);
    this.liveNodes.clear();
    this.liveGroups = [];
    this.liveShape = "";
    this.liveKey = "";
    this.statusEl.textContent = "";
    this.statusEl.dataset.status = "";
  }

  setEmpty(text: string): void {
    clear(this.historyEl);
    this.historyEl.append(h("p", { class: "empty", text }));
  }

  /** 当前是否已经渲染出内容（用于区分「首次加载失败」和「静默刷新失败」）。 */
  hasContent(): boolean {
    return this.historyEl.querySelector(".msg, .tool, .error-card") !== null;
  }

  /** 历史读取失败：给明确原因 + 重试入口，而不是只闪一个 toast。 */
  setError(options: { title: string; detail?: string; hint?: string; onRetry?: () => void }): void {
    clear(this.historyEl);
    const card = h("div", { class: "error-card" });
    card.append(h("div", { class: "error-title", text: options.title }));
    if (options.detail) card.append(h("div", { class: "error-detail", text: options.detail }));
    if (options.hint) card.append(h("p", { class: "hint", text: options.hint }));
    if (options.onRetry) {
      card.append(h("button", { class: "btn btn-ghost", text: "重试", onclick: options.onRetry }));
    }
    this.historyEl.append(card);
  }

  setHistory(messages: readonly WireMessage[]): void {
    clear(this.historyEl);
    let rendered = 0;

    // 连续的「思考 + 工具」收进同一个折叠块，避免几百个工具各占一行
    let pending: Array<{ el: HTMLElement; kind: "tool" | "reasoning"; state?: string }> = [];
    let group: ReturnType<typeof createProcessGroup> | null = null;

    const flushProcess = (): void => {
      if (pending.length === 0) return;
      const created = createProcessGroup();
      for (const item of pending) created.body.append(item.el);
      created.refresh(summarizeProcess(pending));
      this.historyEl.append(created.el);
      pending = [];
      group = null;
      rendered += 1;
    };

    for (const message of messages) {
      const role = normalizeRole(message.role);

      if (role === "tool") {
        const entry = historyToolEntry(message);
        if (entry) {
          pending.push(entry);
          if (!group) group = createProcessGroup();
        }
        continue;
      }
      if (role === "system") continue;

      // 思考可能来自独立字段，也可能以 <thinking> 标签嵌在正文里
      const rawText = messageText(message);
      const split = splitThinking(rawText);
      const fieldThinking = typeof (message as { thinking?: unknown }).thinking === "string"
        ? ((message as { thinking: string }).thinking).trim()
        : "";
      const thinking = [fieldThinking, split.thinking].filter(Boolean).join(LF2 + LF2).trim();
      const text = split.text;

      if (thinking) pending.push({ el: reasoningEntry(thinking), kind: "reasoning" });
      if (!text) continue;

      flushProcess();
      this.historyEl.append(messageNode(role, text, message.timestamp, typeof message.id === "string" ? message.id : undefined));
      rendered += 1;
    }

    flushProcess();

    // 本地回显的对账：服务端历史里已经有同样的提问就撤掉占位，否则继续显示
    if (this.localEcho) {
      const echo = this.localEcho;
      this.localEcho = null;
      const lastUser = [...messages].reverse().find((m) => normalizeRole(m.role) === "user");
      const lastText = lastUser ? messageText(lastUser).trim() : "";
      if (!lastText.includes(echo.text)) this.historyEl.append(echo.el);
    }

    if (rendered === 0) {
      this.historyEl.append(
        h("p", { class: "empty", text: "这个会话还没有可显示的消息（历史可能尚未落盘）" }),
      );
    }
    this.scrollToBottom();
  }

  /**
   * 流式块：按 id 复用节点（保留 details 展开状态），但顺序每次重排，
   * 并把连续的思考/工具塞进同一个「执行过程」折叠块。
   */
  /**
   * 流式渲染。
   *
   * 关键约束：**分组结构必须整体重建，不能每次新建 group 而把旧的留在 DOM 里**。
   * 之前的 bug 就是每帧 createProcessGroup() 却从不移除上一帧的，导致屏幕上堆出
   * 几十个「执行过程」，只有轮次结束 clearLive() 才消失。
   *
   * 优化：只有「分组结构」变化（新块出现 / text 与 process 交替）时才重建，
   * 纯 delta 更新只改节点内容，避免每秒几十次重建。
   */
  setLive(messageId: string | null, blocks: readonly ChatBlock[], status: RunStatus): void {
    const key = messageId ?? "anonymous";
    if (key !== this.liveKey) {
      clear(this.liveEl);
      this.liveNodes.clear();
      this.liveGroups = [];
      this.liveShape = "";
      this.liveKey = key;
    }

    // 分组只由「块类型序列」决定（text 会打断 process 分组）
    const shape = blocks.map((b) => (b.kind === "text" ? "T" : "P")).join("");

    if (shape !== this.liveShape) {
      this.rebuildLive(blocks);
    } else {
      // 结构没变：只更新内容
      for (const block of blocks) {
        const node = this.liveNodes.get(this.blockKey(block));
        if (node) updateBlockNode(node, block);
      }
      this.refreshLiveGroups(blocks);
    }

    this.liveShape = shape;

    this.statusEl.textContent =
      status === "running" ? "运行中…" : status === "error" ? "本轮失败" : status === "aborted" ? "已停止" : "";
    this.statusEl.dataset.status = status;
    if (this.stick) this.scrollToBottom();
  }

  private blockKey(block: ChatBlock): string {
    return block.kind === "tool" ? `tool:${block.toolCallId}` : `${block.kind}:${block.id}`;
  }

  /** 按当前块序列重建整个流式区（旧 group 必须先移除，否则会残留）。 */
  private rebuildLive(blocks: readonly ChatBlock[]): void {
    // 先记住哪些 group 被用户手动展开了，重建后恢复
    const openIndexes = new Set<number>();
    this.liveGroups.forEach((group, index) => {
      if (group.el.hasAttribute("open")) openIndexes.add(index);
    });

    // 拆除旧结构：detail 里的 item 节点仍被 liveNodes 引用，可以复用
    for (const group of this.liveGroups) group.el.remove();
    this.liveGroups = [];

    const ordered: HTMLElement[] = [];
    let group: ReturnType<typeof createProcessGroup> | null = null;

    for (const block of blocks) {
      const id = this.blockKey(block);
      let node = this.liveNodes.get(id);
      if (!node) {
        node = blockNode(block);
        this.liveNodes.set(id, node);
      }
      updateBlockNode(node, block);

      if (isProcessBlock(block)) {
        if (!group) {
          group = createProcessGroup({ open: openIndexes.has(this.liveGroups.length) });
          this.liveGroups.push(group);
          ordered.push(group.el);
        }
        if (node.parentElement !== group.body) group.body.append(node);
      } else {
        group = null;
        ordered.push(node);
      }
    }

    // 清掉容器里可能残留的游离节点，再按顺序贴回
    for (const child of [...this.liveEl.children]) {
      if (!ordered.includes(child as HTMLElement)) child.remove();
    }
    for (const node of ordered) this.liveEl.append(node);

    this.refreshLiveGroups(blocks);
  }

  /** 刷新每个 group 的标题（N 个工具 · M 段思考）。 */
  private refreshLiveGroups(blocks: readonly ChatBlock[]): void {
    let groupIndex = 0;
    let items: Array<{ kind: "tool" | "reasoning"; state?: string }> = [];
    let inGroup = false;

    const flush = (): void => {
      if (!inGroup) return;
      this.liveGroups[groupIndex]?.refresh(summarizeProcess(items));
      groupIndex += 1;
      items = [];
      inGroup = false;
    };

    for (const block of blocks) {
      if (isProcessBlock(block)) {
        inGroup = true;
        items.push({
          kind: block.kind as "tool" | "reasoning",
          ...(block.kind === "tool" ? { state: block.state } : {}),
        });
      } else {
        flush();
      }
    }
    flush();
  }

  setPending(list: readonly PendingUiRequest[]): void {
    clear(this.pendingEl);
    for (const pending of list) this.pendingEl.append(this.pendingCard(pending));
    if (this.stick) this.scrollToBottom();
  }

  /**
   * 滚到指定消息并高亮。返回是否找到。
   *
   * 找不到通常意味着该消息在未加载的更早历史里（接口只返回窗口内的消息）。
   */
  scrollToMessage(messageId: string): boolean {
    // 不用 CSS.escape（老 WebView / jsdom 里可能没有），直接遍历匹配，
    // 顺带避免 id 里含特殊字符时的选择器转义问题
    let target: HTMLElement | null = null;
    for (const node of this.historyEl.querySelectorAll<HTMLElement>(".msg[data-mid]")) {
      if (node.dataset.mid === messageId) {
        target = node;
        break;
      }
    }
    if (!target) return false;

    // jsdom / 老 WebView 可能没有 scrollIntoView，退化为直接改 scrollTop
    if (typeof target.scrollIntoView === "function") {
      target.scrollIntoView({ block: "center", behavior: "smooth" });
    } else {
      const offset = target.offsetTop - this.scroll.clientHeight / 2;
      this.scroll.scrollTop = Math.max(0, offset);
    }
    target.classList.remove("is-flash");
    // 强制重排，保证连续点同一条也能重新播放动画
    void target.offsetWidth;
    target.classList.add("is-flash");
    setTimeout(() => target.classList.remove("is-flash"), 1_800);
    return true;
  }

  /**
   * 视口变化（软键盘弹起 / 收起）后调用。
   * 原本贴在底部就继续保持贴底，用户主动往上翻过就不要抢他的位置。
   */
  keepBottomOnResize(): void {
    if (!this.stick) return;
    // 直接改 scrollTop，跳过 rAF：键盘弹出时是连续 resize，用 rAF 会滞后一帧
    this.scroll.scrollTop = Math.max(0, this.scroll.scrollHeight - this.scroll.clientHeight);
  }

  scrollToBottom(): void {
    requestAnimationFrame(() => {
      this.scroll.scrollTop = Math.max(0, this.scroll.scrollHeight - this.scroll.clientHeight);
      this.stick = true;
    });
  }

  /**
   * AI 提问卡片。
   *
   * 对齐桌面端：选项可带描述、select 可多选、batch_ask 逐题作答 + 审阅后统一提交。
   * 之前只做了最简版（单选/确认/输入），batch_ask 直接提示「不支持」。
   */
  private pendingCard(pending: PendingUiRequest): HTMLElement {
    const card = h("section", { class: "ask-card" });

    const respond = (response: Record<string, unknown>): void => {
      this.handlers.onRespond(pending, response);
    };

    const rawMethod = String(pending.method ?? "");
    // 选项可能是字符串，也可能是 {label, value, description} 对象
    const optionList = toAskOptions(pending.options);
    // PiDeck 主进程在 select 没有选项时会把它降级成 input；
    // 客户端也要做同样处理，否则会掉进「暂不支持」分支，用户看到一张空卡片。
    const method = rawMethod === "select" && optionList.length === 0 ? "input" : rawMethod;
    const isBatch = method === "batch_ask" && Array.isArray(pending.batchQuestions);

    if (isBatch) {
      card.append(this.batchCard(pending, respond));
      return card;
    }

    card.append(h("div", { class: "ask-kicker", text: "AI 提问" }));
    card.append(h("div", { class: "ask-title", text: pending.title || "需要你的确认" }));

    if (method === "select" && optionList.length > 0) {
      const parsed = optionList;
      const multiple = (pending as { multiple?: boolean }).multiple === true;
      const allowOther = pending.allowOther === true;
      const selected: string[] = [];

      const list = h("div", { class: "ask-options ask-options--column" });
      const buttons: HTMLButtonElement[] = [];

      const hintEl = h("p", { class: "hint", text: "可多选" });
      const submitMulti = (): void => {
        if (selected.length === 0) {
          hintEl.textContent = "先选至少一项";
          hintEl.classList.add("hint--warn");
          return;
        }
        respond({ value: selected.join("、") });
      };

      parsed.forEach((option) => {
        const button = h("button", { class: "ask-option" }, [
          h("span", { class: "ask-option-label", text: option.label }),
          ...(option.description ? [h("span", { class: "ask-option-desc", text: option.description })] : []),
        ]);
        button.addEventListener("click", () => {
          if (!multiple) {
            respond({ value: option.value });
            return;
          }
          const next = toggleSelection(selected, option.value);
          selected.length = 0;
          selected.push(...next);
          button.classList.toggle("is-selected", selected.includes(option.value));
        });
        buttons.push(button);
        list.append(button);
      });

      card.append(list);
      // allowOther：桌面端在选项下方额外给一个「或输入自定义内容…」
      const otherInput = allowOther
        ? h("textarea", {
            class: "ask-input",
            placeholder: pending.placeholder ?? "或输入自定义内容…",
            value: pending.prefill ?? "",
          })
        : null;
      if (otherInput) card.append(otherInput);

      if (multiple) {
        card.append(
          hintEl,
          h("button", {
            class: "btn",
            text: "提交",
            onclick: () => {
              if (otherInput?.value.trim()) {
                respond({ value: otherInput.value.trim() });
                return;
              }
              submitMulti();
            },
          }),
        );
      } else if (otherInput) {
        card.append(
          h("button", {
            class: "btn",
            text: "提交自定义内容",
            onclick: () => {
              const value = otherInput.value.trim();
              if (!value) {
                hintEl.textContent = "先输入内容";
                hintEl.classList.add("hint--warn");
                return;
              }
              respond({ value });
            },
          }),
        );
      }
    } else if (method === "confirm") {
      const row = h("div", { class: "ask-options" });
      row.append(
        h("button", { class: "btn", text: "允许", onclick: () => respond({ confirmed: true }) }),
        h("button", { class: "btn btn-ghost", text: "拒绝", onclick: () => respond({ confirmed: false }) }),
      );
      card.append(row);
    } else if (method === "input" || method === "editor") {
      const input = h("textarea", {
        class: "ask-input",
        placeholder: pending.placeholder ?? (method === "editor" ? "编辑内容…" : "输入回答…"),
        value: pending.prefill ?? "",
      });
      const submit = h("button", { class: "btn", text: "提交" });

      // 内容为空时允许「留空提交」（PiDeck 接受空字符串，不该逼用户打字）
      const syncLabel = (): void => {
        submit.textContent = input.value.trim() ? "提交" : "留空提交";
        submit.title = input.value.trim() ? "" : "不填内容直接回答";
      };
      input.addEventListener("input", syncLabel);
      syncLabel();

      submit.addEventListener("click", () => {
        respond({ value: input.value.trim() });
      });
      card.append(input, submit);
    } else {
      card.append(h("p", { class: "hint", text: `暂不支持 ${method} 类型，请回桌面端处理` }));
    }

    card.append(
      h("button", {
        class: "btn btn-text",
        text: "取消",
        title: "取消后模型会按第一项处理",
        onclick: () => respond({ cancelled: true }),
      }),
    );
    return card;
  }

  /** 一次问多题：逐题作答 → 审阅 → 统一提交。 */
  private batchCard(
    pending: PendingUiRequest,
    respond: (response: Record<string, unknown>) => void,
  ): HTMLElement {
    const questions = normalizeBatchQuestions(pending.batchQuestions);
    const answers: Record<string, BatchAnswer> = {};
    // 记录每题是「点了选项」还是「自己输入」，回传给 pi 的 wasCustom / label 需要它
    const meta: Record<string, BatchAnswerMeta> = {};
    let index = 0;
    let reviewing = false;

    const card = h("section", { class: "ask-card" });

    const rerender = (): void => {
      clear(card);
      const done = answeredCount(questions, answers);

      card.append(h("div", { class: "ask-kicker", text: "AI 提问" }));
      card.append(h("div", { class: "ask-title", text: `提问（${questions.length} 题）` }));
      card.append(h("div", { class: "ask-progress", text: `已答 ${done}/${questions.length}` }));

      if (reviewing) {
        // 审阅页：把所有题与答案列出来，确认后提交
        card.append(h("div", { class: "ask-review-title", text: "确认全部回答" }));
        card.append(h("p", { class: "hint", text: "检查无误后再提交，避免误操作" }));
        const list = h("div", { class: "ask-review" });
        for (const question of questions) {
          const answer = answers[question.id];
          // 三种状态要分开：没碰过（undefined）/ 主动留空（""）/ 有内容
          const answered = answer !== undefined;
          const text = Array.isArray(answer)
            ? answer.join("、")
            : typeof answer === "boolean"
              ? answer
                ? "是"
                : "否"
              : (answer ?? "");
          list.append(
            h("div", { class: "ask-review-item" }, [
              h("div", { class: "ask-review-q", text: question.question }),
              h("div", {
                class: `ask-review-a${answered ? (text ? "" : " is-blank") : " is-empty"}`,
                text: answered ? (text || "（留空）") : "未回答",
              }),
            ]),
          );
        }
        card.append(list);

        const incomplete = done < questions.length;
        card.append(
          h("div", { class: "ask-options" }, [
            h("button", { class: "btn btn-ghost", text: "上一题", onclick: () => {
              reviewing = false;
              index = questions.length - 1;
              rerender();
            } }),
            h("button", {
              class: "btn",
              text: incomplete ? "还有题目未回答" : "提交",
              ...(incomplete ? { disabled: true } : {}),
              onclick: () => {
                if (incomplete) return;
                respond({ value: serializeBatchAnswers(questions, answers, meta) });
              },
            }),
          ]),
        );
      } else {
        const question = questions[index];
        if (!question) {
          card.append(h("p", { class: "hint", text: "题目为空" }));
          return;
        }

        card.append(h("div", { class: "ask-question", text: question.question }));

        // confirm 题：是 / 否
        if (question.kind === "confirm") {
          card.append(
            h("div", { class: "ask-options" }, [
              h("button", {
                class: "btn",
                text: "是",
                onclick: () => {
                  answers[question.id] = true as unknown as BatchAnswer;
                  meta[question.id] = { label: "是", wasCustom: false };
                  advance();
                },
              }),
              h("button", {
                class: "btn btn-ghost",
                text: "否",
                onclick: () => {
                  answers[question.id] = false as unknown as BatchAnswer;
                  meta[question.id] = { label: "否", wasCustom: false };
                  advance();
                },
              }),
            ]),
          );
        }

        // 该题没有选项时只显示输入框（不要给一张空卡片）
        if (question.kind !== "confirm" && question.options.length > 0) {
          const list = h("div", { class: "ask-options ask-options--column" });
          for (const option of question.options) {
            const current = answers[question.id];
            const isSelected = Array.isArray(current)
              ? current.includes(option.value)
              : current === option.value;

            const button = h("button", { class: `ask-option${isSelected ? " is-selected" : ""}` }, [
              h("span", { class: "ask-option-label", text: option.label }),
              ...(option.description ? [h("span", { class: "ask-option-desc", text: option.description })] : []),
            ]);
            button.addEventListener("click", () => {
              if (question.multiple) {
                const currentList = Array.isArray(answers[question.id]) ? (answers[question.id] as string[]) : [];
                const next = toggleSelection(currentList, option.value);
                answers[question.id] = next;
                meta[question.id] = { label: next.join("、"), wasCustom: false };
              } else {
                answers[question.id] = option.value;
                meta[question.id] = { label: option.label, wasCustom: false };
                advance();
                return;
              }
              rerender();
            });
            list.append(button);
          }
          card.append(list);
          if (question.multiple) card.append(h("p", { class: "hint", text: "可多选" }));
        }

        if (question.kind !== "confirm") {
          const input = h("textarea", {
            class: "ask-input",
            placeholder: question.placeholder || (question.options.length > 0 ? "或输入自定义内容…（可留空）" : "输入回答…（可留空）"),
            value: typeof answers[question.id] === "string" ? (answers[question.id] as string) : question.prefill,
          });
          input.addEventListener("input", () => {
            // 输入即记入答案（含空串），这样「不填直接下一题」不会被当成未作答
            answers[question.id] = input.value;
            meta[question.id] = { label: input.value, wasCustom: true };
          });
          card.append(input);
        }

        const isLast = index === questions.length - 1;
        const isAnsweredNow = answers[question.id] !== undefined;

        card.append(
          h("div", { class: "ask-options" }, [
            h("button", {
              class: "btn btn-ghost",
              text: "上一题",
              ...(index === 0 ? { disabled: true } : {}),
              onclick: () => {
                index = Math.max(0, index - 1);
                rerender();
              },
            }),
            h("button", {
              class: "btn btn-ghost",
              text: "跳过",
              title: "留空并继续（答案为空）",
              onclick: () => {
                answers[question.id] = "";
                meta[question.id] = { label: "", wasCustom: true };
                advance();
              },
            }),
            h("button", {
              class: "btn",
              // 没填任何东西时提示会留空提交，而不是禁用按钮
              text: isLast ? "去审阅" : "下一题",
              title: isAnsweredNow ? "" : "这题会留空",
              onclick: () => advance(),
            }),
          ]),
        );
      }

      card.append(
        h("button", {
          class: "btn btn-text",
          text: "取消",
          title: "取消后模型会按第一项处理",
          onclick: () => respond({ cancelled: true }),
        }),
      );
    };

    const advance = (): void => {
      const question = questions[index];
      if (question) {
        const input = card.querySelector<HTMLTextAreaElement>(".ask-input");
        if (input) {
          const text = input.value;
          if (text) {
            // 输入框有内容 → 以自定义内容为准
            answers[question.id] = text;
            meta[question.id] = { label: text, wasCustom: true };
          } else if (answers[question.id] === undefined) {
            // 既没点选项也没打字 → 视为留空作答，避免卡在未答状态
            answers[question.id] = "";
          }
          // 注意：输入框为空但已经点过选项时，必须保留选项值，
          // 否则会把刚选的答案覆盖成空串（审阅页显示「未回答」）。
        } else if (answers[question.id] === undefined && question.kind !== "confirm") {
          answers[question.id] = "";
        }
      }
      if (index < questions.length - 1) {
        index += 1;
        rerender();
        return;
      }
      reviewing = true;
      rerender();
    };

    // 保证第一题未答时也能进入审阅（提示未完成）
    void isAnswered;

    rerender();
    return card;
  }
}

/* ----------------------------- 消息节点 ----------------------------- */

function normalizeRole(role: unknown): string {
  if (typeof role !== "string") return "assistant";
  const value = role.toLowerCase();
  if (value === "user" || value === "assistant" || value === "system" || value === "tool") return value;
  return "assistant";
}

/** 历史消息结构随版本变化，这里做宽松提取：parts[].text / parts[].content / content 字符串。 */
/**
 * 提取消息正文。
 *
 * PiDeck 的历史消息实际形状是扁平的：
 *   { id, agentId, role, text, timestamp, meta: { entryId, toolName?, ... } }
 * assistant 可能额外带 `thinking`；tool 的细节在 `meta.detailText` / `meta.args`。
 * 早期版本渲染器用过 parts/content 结构，所以这里保留兜底。
 */
export function messageText(message: WireMessage): string {
  const top = (message as { text?: unknown }).text;
  if (typeof top === "string" && top.trim()) return top.trim();

  const buckets: string[] = [];
  const collect = (value: unknown): void => {
    if (typeof value === "string") {
      buckets.push(value);
      return;
    }
    if (Array.isArray(value)) {
      for (const item of value) collect(item);
      return;
    }
    if (value && typeof value === "object") {
      const record = value as Record<string, unknown>;
      if (typeof record.text === "string") buckets.push(record.text);
      else if (typeof record.content === "string") buckets.push(record.content);
      else if (record.content !== undefined) collect(record.content);
    }
  };
  if (message.parts !== undefined) collect(message.parts);
  if (buckets.length === 0) collect(message.content);
  return buckets.join("\n").trim();
}

function messageNode(role: string, text: string, timestamp: unknown, id?: string): HTMLElement {
  const wrap = h("article", { class: `msg msg--${role}` });
  if (id) wrap.dataset.mid = id;
  wrap.append(h("div", { class: "msg-role", text: role === "user" ? "我" : "Pi" }));
  wrap.append(h("div", { class: "msg-body", text }));
  if (typeof timestamp === "number" && timestamp > 0) {
    wrap.append(h("div", { class: "msg-time", text: new Date(timestamp).toLocaleTimeString().slice(0, 5) }));
  }
  return wrap;
}

/** 历史里的思考条目（折叠在「执行过程」内部）。 */
export function reasoningEntry(text: string): HTMLElement {
  const details = h("details", { class: "reasoning" });
  details.append(h("summary", { text: "思考" }), h("div", { class: "reasoning-body", text }));
  return details;
}

const LF2 = String.fromCharCode(10);

/** 历史里的工具条目（折叠在「执行过程」内部）。 */
function historyToolEntry(
  message: WireMessage,
): { el: HTMLElement; kind: "tool"; state: string } | null {
  const meta = (message as { meta?: Record<string, unknown> }).meta ?? {};
  const toolName = typeof meta.toolName === "string" && meta.toolName ? meta.toolName : "tool";
  const status = typeof meta.status === "string" ? meta.status : "";
  const isError = meta.isError === true || status === "error";
  const phase = isError ? "失败" : status === "running" ? "运行中" : "完成";

  const body = [typeof meta.detailText === "string" ? meta.detailText : "", messageText(message)]
    .filter(Boolean)
    .join(LF2 + LF2)
    .slice(0, 2000);

  const details = h("details", { class: "tool" });
  details.append(
    h("summary", { text: `工具 · ${toolName} · ${phase}` }),
    h("div", { class: "tool-body", text: body }),
  );
  return { el: details, kind: "tool", state: isError ? "error" : "ok" };
}

function blockNode(block: ChatBlock): HTMLElement {
  if (block.kind === "text") return h("article", { class: "msg msg--assistant" }, [h("div", { class: "msg-role", text: "Pi" })]);
  if (block.kind === "reasoning") {
    const details = h("details", { class: "reasoning" });
    details.append(h("summary", { text: "思考" }), h("div", { class: "reasoning-body" }));
    return details;
  }
  const details = h("details", { class: "tool" });
  details.append(h("summary", { text: `工具 · ${block.toolName}` }), h("div", { class: "tool-body" }));
  return details;
}

function updateBlockNode(node: HTMLElement, block: ChatBlock): void {
  if (block.kind === "text") {
    let body = node.querySelector<HTMLElement>(".msg-body");
    if (!body) {
      body = h("div", { class: "msg-body" });
      node.append(body);
    }
    // 流式时同样会推送带 <thinking> 的正文，已闭合的直接剥掉
    const visible = stripCompleteThinking(block.text);
    if (body.textContent !== visible) body.textContent = visible;
    node.classList.toggle("is-done", block.done);
    return;
  }
  if (block.kind === "reasoning") {
    const body = node.querySelector<HTMLElement>(".reasoning-body");
    if (body && body.textContent !== block.text) body.textContent = block.text;
    return;
  }
  const body = node.querySelector<HTMLElement>(".tool-body");
  if (!body) return;
  const summary = node.querySelector("summary");
  if (summary) summary.textContent = `工具 · ${block.toolName} · ${block.state === "ok" ? "完成" : block.state === "error" ? "失败" : "运行中"}`;
  const detailParts: string[] = [];
  if (block.input !== undefined) detailParts.push(`入参：${preview(block.input)}`);
  if (block.output !== undefined) detailParts.push(`输出：${preview(block.output)}`);
  if (block.errorText) detailParts.push(`错误：${block.errorText}`);
  const text = detailParts.join("\n\n");
  if (body.textContent !== text) body.textContent = text;
}

function preview(value: unknown): string {
  try {
    const text = typeof value === "string" ? value : JSON.stringify(value, null, 2);
    return text.length > 2_000 ? `${text.slice(0, 2_000)}…` : text;
  } catch {
    return "[无法序列化]";
  }
}
