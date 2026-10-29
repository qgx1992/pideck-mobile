/**
 * 会话显示用的模型 / 思考档位。
 *
 * 为什么需要这一层：
 * `/api/state` 里的 `session.model` / `session.thinkingLevel` **经常是 null**
 * （实测 223 个会话只有 19 个带 model）——PiDeck 只在会话被显式设过偏好时才写进 catalog。
 * 真正的当前模型只有会话运行时知道，得从 `/runtime/state` 读。
 *
 * 所以显示优先级是：
 *   1. catalog 里显式记录的（最准，且不需要额外请求）
 *   2. 从运行时学到并缓存的
 *   3. 都没有 → 如实说明「默认模型（发消息后确定）」，不要假装成「选择模型」
 */

import type { SessionSummary } from "@core/types.ts";

export interface LearnedPrefs {
  provider?: string;
  modelId?: string;
  modelName?: string;
  thinkingLevel?: string;
}

export interface ResolvedPrefs {
  provider: string;
  modelId: string;
  modelName: string;
  thinkingLevel: string;
  /** 模型信息是编造的占位文案（既无 catalog 也无运行时数据） */
  placeholder: boolean;
}

/**
 * 合并 catalog 与运行时学到的偏好。
 * catalog 有值时优先用它（那是会话自己记录的，不会被别的会话污染）。
 */
export function resolveSessionPrefs(
  session: SessionSummary | undefined,
  learned?: LearnedPrefs,
): ResolvedPrefs {
  const catalogProvider = session?.model?.provider ?? "";
  const catalogModelId = session?.model?.modelId ?? "";
  const catalogModelName = session?.model?.modelName ?? "";

  const provider = catalogProvider || learned?.provider || "";
  const modelId = catalogModelId || learned?.modelId || "";
  const modelName =
    catalogModelName ||
    catalogModelId ||
    learned?.modelName ||
    learned?.modelId ||
    "";

  const thinkingLevel = session?.thinkingLevel || learned?.thinkingLevel || "";

  return {
    provider,
    modelId,
    modelName,
    thinkingLevel,
    placeholder: modelName === "",
  };
}

/** 模型按钮文案：没有信息时明确说是默认模型，而不是误导成「未选择」。 */
export function modelChipLabel(prefs: ResolvedPrefs, maxLen = 18): string {
  if (prefs.placeholder) return "默认模型";
  return prefs.modelName.length > maxLen ? `${prefs.modelName.slice(0, maxLen - 1)}…` : prefs.modelName;
}

/** 模型按钮的 title 提示。 */
export function modelChipTitle(prefs: ResolvedPrefs): string {
  if (prefs.placeholder) return "当前使用 PiDeck 的默认模型，发第一条消息后显示具体模型";
  const id = prefs.modelId && prefs.modelId !== prefs.modelName ? `（${prefs.modelId}）` : "";
  return `模型：${prefs.modelName}${id}`;
}
