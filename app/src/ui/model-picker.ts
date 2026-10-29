/**
 * 模型 / 思考档位的视图模型：把 /api/models 的原始结构收敛成可选项。
 *
 * 已核实的字段（PiDeck 0.9.7 /api/models）：
 *   { provider, id, name, reasoning, thinkingLevels: ["low","high",...],
 *     thinkingLevelMap: { low:"low", medium:null, ... }, contextWindow, maxTokens }
 * thinkingLevelMap 里为 null 的档位表示该模型不支持，必须过滤掉，否则切了会报错。
 */

import type { ModelInfo } from "@core/types.ts";

export interface ModelOption {
  key: string;
  provider: string;
  modelId: string;
  name: string;
  /** 该模型支持的思考档位（已按 thinkingLevelMap 过滤） */
  thinkingLevels: string[];
  contextWindow?: number;
}

export interface ModelPickerSection {
  provider: string;
  models: ModelOption[];
}

const LEVEL_ORDER = ["off", "minimal", "low", "medium", "high", "xhigh", "max"];

export function modelKey(provider: string, modelId: string): string {
  return `${provider}\u0000${modelId}`;
}

function resolveThinkingLevels(raw: ModelInfo): string[] {
  const map = (raw as { thinkingLevelMap?: Record<string, unknown> }).thinkingLevelMap;
  const declared = Array.isArray((raw as { thinkingLevels?: unknown }).thinkingLevels)
    ? ((raw as { thinkingLevels: string[] }).thinkingLevels.filter((level) => typeof level === "string"))
    : [];

  let levels = declared;

  if (map && typeof map === "object") {
    const allowed = Object.entries(map)
      .filter(([, value]) => value !== null && value !== undefined && value !== false)
      .map(([key]) => key);
    if (allowed.length > 0) levels = allowed;
    else if ((raw as { reasoning?: boolean }).reasoning === false) levels = [];
  }

  // 去重 + 按固定顺序排列，未知档位排在后面
  const unique = [...new Set(levels)];
  return unique.sort((a, b) => {
    const ai = LEVEL_ORDER.indexOf(a);
    const bi = LEVEL_ORDER.indexOf(b);
    if (ai === -1 && bi === -1) return a.localeCompare(b);
    if (ai === -1) return 1;
    if (bi === -1) return -1;
    return ai - bi;
  });
}

export function toModelOption(raw: ModelInfo): ModelOption | null {
  const provider = (raw.provider ?? (raw as { providerId?: string }).providerId ?? "").trim();
  const modelId = (raw.id ?? (raw as { modelId?: string }).modelId ?? "").trim();
  if (!provider || !modelId) return null;

  const name = (raw.name ?? modelId).trim() || modelId;
  const contextWindow = typeof (raw as { contextWindow?: unknown }).contextWindow === "number"
    ? ((raw as { contextWindow: number }).contextWindow)
    : undefined;

  return {
    key: modelKey(provider, modelId),
    provider,
    modelId,
    name,
    thinkingLevels: resolveThinkingLevels(raw),
    ...(contextWindow ? { contextWindow } : {}),
  };
}

export function buildModelSections(models: readonly ModelInfo[]): ModelPickerSection[] {
  const byProvider = new Map<string, ModelOption[]>();
  for (const raw of models) {
    const option = toModelOption(raw);
    if (!option) continue;
    const list = byProvider.get(option.provider) ?? [];
    list.push(option);
    byProvider.set(option.provider, list);
  }

  return [...byProvider.entries()]
    .map(([provider, list]) => ({
      provider,
      models: list.sort((a, b) => a.name.localeCompare(b.name)),
    }))
    .sort((a, b) => a.provider.localeCompare(b.provider));
}

export function findModel(
  sections: readonly ModelPickerSection[],
  provider: string | undefined,
  modelId: string | undefined,
): ModelOption | undefined {
  if (!provider || !modelId) return undefined;
  for (const section of sections) {
    if (section.provider !== provider) continue;
    const found = section.models.find((model) => model.modelId === modelId);
    if (found) return found;
  }
  return undefined;
}

/** 思考档位的中文说明，用于选择面板。 */
export const THINKING_LABELS: Record<string, { label: string; hint: string }> = {
  off: { label: "关闭", hint: "不思考，最快最省" },
  minimal: { label: "最少", hint: "简单问答" },
  low: { label: "低", hint: "日常小改动" },
  medium: { label: "中", hint: "平衡速度与深度" },
  high: { label: "高", hint: "复杂任务" },
  xhigh: { label: "很高", hint: "需要模型支持" },
  max: { label: "最高", hint: "最贵最慢" },
};

export function thinkingLabel(level: string): string {
  return THINKING_LABELS[level]?.label ?? level;
}

export function thinkingHint(level: string): string {
  return THINKING_LABELS[level]?.hint ?? "";
}

/** 模型副标题：provider · 上下文窗口 */
export function modelSubtitle(model: ModelOption): string {
  const parts = [model.provider];
  if (model.contextWindow) {
    const k = Math.round(model.contextWindow / 1000);
    parts.push(k >= 1000 ? `${Math.round(k / 1000)}M 上下文` : `${k}K 上下文`);
  }
  return parts.join(" · ");
}

/**
 * 按关键字过滤模型分组（面板里的搜索框用）。
 *
 * 匹配范围：模型名、modelId、provider。空格分隔的多个词必须**同时**命中，
 * 这样「deep flash」能筛出 DeepSeek V4.1 Flash，而单个词也不会漏。
 * 空查询原样返回（保持原顺序，不做无谓拷贝）。
 */
export function filterModelSections(
  sections: readonly ModelPickerSection[],
  query: string,
): ModelPickerSection[] {
  const terms = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
  if (terms.length === 0) return sections as ModelPickerSection[];

  const result: ModelPickerSection[] = [];
  for (const section of sections) {
    const models = section.models.filter((model) => {
      const haystack = `${model.name} ${model.modelId} ${model.provider}`.toLowerCase();
      return terms.every((term) => haystack.includes(term));
    });
    // 整组都不匹配就不要留空的分组标题
    if (models.length > 0) result.push({ provider: section.provider, models });
  }
  return result;
}

/** 过滤后有多少个模型（搜索结果计数用）。 */
export function countModels(sections: readonly ModelPickerSection[]): number {
  return sections.reduce((sum, section) => sum + section.models.length, 0);
}
