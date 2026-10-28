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

export interface BatchQuestion {
  id: string;
  question: string;
  options: AskOption[];
  /** 是否多选 */
  multiple: boolean;
  placeholder: string;
  prefill: string;
}

function asStringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
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
      question,
      options: asStringArray(record.options).map(splitAskOption),
      multiple: record.multiple === true || record.multiSelect === true,
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

export function isAnswered(_question: BatchQuestion, answer: BatchAnswer | undefined): boolean {
  if (answer === undefined) return false;
  if (Array.isArray(answer)) return answer.length > 0;
  return answer.trim().length > 0;
}

export function answeredCount(questions: readonly BatchQuestion[], answers: Record<string, BatchAnswer>): number {
  return questions.filter((q) => isAnswered(q, answers[q.id])).length;
}

/**
 * 批量回答的提交格式。
 * 服务端按 `{ value: 序列化字符串 }` 接收，这里按题目顺序拼成「问题：回答」行。
 */
export function serializeBatchAnswers(
  questions: readonly BatchQuestion[],
  answers: Record<string, BatchAnswer>,
): string {
  return questions
    .map((question) => {
      const answer = answers[question.id];
      const text = Array.isArray(answer) ? answer.join("、") : (answer ?? "");
      return `${question.question}：${text}`;
    })
    .join("\n");
}

/** 普通 select 的多选切换。 */
export function toggleSelection(current: readonly string[], value: string): string[] {
  return current.includes(value) ? current.filter((item) => item !== value) : [...current, value];
}
