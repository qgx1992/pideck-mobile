/**
 * AI 提问卡片（ask）的解析与序列化。
 *
 * 对齐桌面端（PiDeck 0.9.7）的行为：
 *   - 选项可能带描述，用分隔符切开（桌面端 splitAskOption）
 *   - select 支持多选（allowOther / 多选由 batchQuestions 的选项决定）
 *   - batch_ask 是「一次问多题」，需要逐题作答、可前后翻页、最后统一提交
 *   - 取消会回 { cancelled: true }，服务端按第一项处理
 *
 * 这些逻辑抽成纯函数，方便直接测。
 */

export interface AskOption {
  /** 显示用的主文本 */
  label: string;
  /** 可选的补充说明 */
  description: string;
  /** 原样回传给服务端的值 */
  value: string;
}

/**
 * 切开「选项 + 描述」。
 * 桌面端支持几种写法：`值 — 描述`、`值 - 描述`、`值：描述`、`值\n描述`。
 * 优先用第一个分隔符切，保持值为原文（服务端按值匹配）。
 */
export function splitAskOption(raw: string): AskOption {
  const text = String(raw ?? "");
  const patterns = [/\s+—\s+/, /\s+--\s+/, /\s+-\s+/, /：/, /:\s/];
  for (const pattern of patterns) {
    const index = text.search(pattern);
    if (index > 0) {
      return {
        label: text.slice(0, index).trim(),
        description: text.slice(index).replace(pattern, "").trim(),
        value: text,
      };
    }
  }
  const newline = text.indexOf("\n");
  if (newline > 0) {
    return {
      label: text.slice(0, newline).trim(),
      description: text.slice(newline + 1).trim(),
      value: text,
    };
  }
  return { label: text.trim(), description: "", value: text };
}

/**
 * 把任意选项形态归一化成 AskOption。
 *
 * 两种真实来源（都已对着 PiDeck 源码核实）：
 *   - 字符串：`"允许 — 只对本次生效"`（extension_ui_request 直传 select 时）
 *   - 对象：`{ label, value, description }`（ask_question 工具批量提问时的标准结构）
 * 之前只认字符串，导致工具的选项整批被过滤掉，卡片上只剩输入框。
 */
export function toAskOption(raw: unknown): AskOption | null {
  if (typeof raw === "string") return splitAskOption(raw);
  if (raw && typeof raw === "object") {
    const record = raw as { label?: unknown; value?: unknown; description?: unknown };
    const label = typeof record.label === "string" ? record.label.trim() : "";
    const value = typeof record.value === "string" ? record.value : label;
    if (!label && !value) return null;
    return {
      label: label || value,
      description: typeof record.description === "string" ? record.description : "",
      value,
    };
  }
  return null;
}

/** 批量把选项数组归一化，坏值直接丢掉。 */
export function toAskOptions(raw: unknown): AskOption[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .map(toAskOption)
    .filter((option): option is AskOption => option !== null);
}

export interface BatchQuestion {
  id: string;
  question: string;
  options: AskOption[];
  /** 是否多选 */
  multiple: boolean;
  /** 原始类型（select / multi_select / confirm / input / editor） */
  kind: string;
  placeholder: string;
  prefill: string;
}

/** 把 batchQuestions 归一化成可渲染的结构。 */
export function normalizeBatchQuestions(raw: unknown): BatchQuestion[] {
  if (!Array.isArray(raw)) return [];
  const result: BatchQuestion[] = [];
  raw.forEach((item, index) => {
    if (!item || typeof item !== "object") return;
    const record = item as Record<string, unknown>;
    const question =
      typeof record.question === "string"
        ? record.question
        : typeof record.title === "string"
          ? record.title
          : `第 ${index + 1} 题`;
    result.push({
      id: typeof record.id === "string" && record.id ? record.id : `q${index}`,
      kind: typeof record.type === "string" ? record.type : "select",
      question,
      options: toAskOptions(record.options),
      // PiDeck 的 ask_question 用具名 type 区分：multi_select 才是多选。
      // 之前只读 multiple/multiSelect 字段，导致多选题目被当成单选（点一个就跳下一题）。
      multiple:
        record.type === "multi_select" ||
        record.multiple === true ||
        record.multiSelect === true,
      placeholder: typeof record.placeholder === "string" ? record.placeholder : "",
      prefill: typeof record.prefill === "string" ? record.prefill : "",
    });
  });
  return result;
}

export type BatchAnswer = string | string[];

export interface BatchState {
  answers: Record<string, BatchAnswer>;
  index: number;
}

/**
 * 是否「已作答」。
 *
 * 注意区分两种「空」：
 *   - undefined  → 用户没碰过这题，算未答（审阅页会标红）
 *   - ""         → 用户主动留空 / 点了跳过，算已答（允许提交空答案）
 * PiDeck 侧只判 value !== null，空字符串是合法答案，所以这里不能用 trim 判空。
 */
export function isAnswered(_question: BatchQuestion, answer: BatchAnswer | undefined): boolean {
  if (answer === undefined) return false;
  if (Array.isArray(answer)) return answer.length > 0;
  return true;
}

export function answeredCount(questions: readonly BatchQuestion[], answers: Record<string, BatchAnswer>): number {
  return questions.filter((q) => isAnswered(q, answers[q.id])).length;
}

/**
 * 批量回答的提交格式。
 * 服务端按 `{ value: 序列化字符串 }` 接收，这里按题目顺序拼成「问题：回答」行。
 */
/** 每题的补充信息：用户是点了选项还是自己输入，用于回传 label / wasCustom。 */
export interface BatchAnswerMeta {
  label?: string;
  wasCustom?: boolean;
}

/** 与桌面端 batchAnswerLabel 一致：boolean → true/false，数组用顿号连接。 */
export function batchAnswerLabel(value: BatchAnswer | undefined): string {
  if (typeof value === "boolean") return value ? "true" : "false";
  if (Array.isArray(value)) return value.join("、");
  return value ?? "";
}

/**
 * 序列化批量回答。
 *
 * **必须是 JSON**：`ask_question` 扩展走 `ctx.ui.input()`，拿到字符串后直接
 * `JSON.parse(raw)`，解析失败就会返回 `{ answers: [], cancelled: true }`——
 * 表现就是「提交了却显示提问被取消」。格式与桌面端保持一致：
 *
 *   {"answers":[{"id","type","value","label","wasCustom"}]}
 */
export function serializeBatchAnswers(
  questions: readonly BatchQuestion[],
  answers: Record<string, BatchAnswer>,
  meta: Record<string, BatchAnswerMeta> = {},
): string {
  const result = questions.map((question) => {
    const value = answers[question.id] ?? null;
    const itemMeta = meta[question.id];
    return {
      id: question.id,
      type: question.kind,
      value,
      label: itemMeta?.label ?? batchAnswerLabel(value ?? undefined),
      wasCustom: Boolean(itemMeta?.wasCustom),
    };
  });
  return JSON.stringify({ answers: result });
}

/** 普通 select 的多选切换。 */
export function toggleSelection(current: readonly string[], value: string): string[] {
  return current.includes(value) ? current.filter((item) => item !== value) : [...current, value];
}
