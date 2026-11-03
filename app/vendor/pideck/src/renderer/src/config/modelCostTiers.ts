/**
 * 模型分档计费（tiered pricing）的数据规整纯函数。
 * pi 的 models.json cost.tiers 语义（pi-ai calculateCost）：
 * 单次请求输入 token（input + cacheRead + cacheWrite）超过 inputTokensAbove
 * 阈值后，整次请求按该档费率计费；多档取满足条件中阈值最高的一档。
 */

/** 编辑态的一行梯度：输入框受控为字符串，允许为空（未填即忽略） */
export interface CostTierDraft {
	inputTokensAbove: string;
	input: string;
	output: string;
	cacheRead: string;
	cacheWrite: string;
}

/** 写入 models.json 的规范梯度（与 pi ModelCostTierSchema 对齐） */
export interface ModelCostTier {
	inputTokensAbove: number;
	input: number;
	output: number;
	cacheRead: number;
	cacheWrite: number;
}

/** 空行工厂：添加梯度按钮使用 */
export function emptyTierDraft(): CostTierDraft {
	return { inputTokensAbove: "", input: "", output: "", cacheRead: "", cacheWrite: "" };
}

/** 已有梯度 → 编辑态草稿（数字转字符串，undefined 字段置空） */
export function toTierDrafts(tiers: ModelCostTier[] | undefined): CostTierDraft[] {
	return (tiers ?? []).map((tier) => ({
		inputTokensAbove: String(tier.inputTokensAbove ?? ""),
		input: String(tier.input ?? ""),
		output: String(tier.output ?? ""),
		cacheRead: String(tier.cacheRead ?? ""),
		cacheWrite: String(tier.cacheWrite ?? ""),
	}));
}

/**
 * 编辑态草稿 → 规范梯度列表。
 * - 阈值或任一费率为非有限数字 → 整行忽略（半成品不落盘）
 * - 按 inputTokensAbove 升序排列（UI 展示与阅读习惯；pi 取档不依赖顺序）
 */
export function normalizeTiers(drafts: CostTierDraft[]): ModelCostTier[] {
	const tiers: ModelCostTier[] = [];
	for (const draft of drafts) {
		const inputTokensAbove = Number(draft.inputTokensAbove);
		if (!Number.isFinite(inputTokensAbove) || inputTokensAbove < 0) continue;
		const input = Number(draft.input);
		const output = Number(draft.output);
		const cacheRead = Number(draft.cacheRead);
		const cacheWrite = Number(draft.cacheWrite);
		// 费率为空字符串 Number("") === 0，视为合法 0 费率？——不，未填的字段应忽略整行，
		// 防止用户只填阈值就保存产生 0 费率档。用原始字符串判空。
		if (draft.input === "" || draft.output === "" || draft.cacheRead === "" || draft.cacheWrite === "") continue;
		if (![input, output, cacheRead, cacheWrite].every(Number.isFinite)) continue;
		if ([input, output, cacheRead, cacheWrite].some((value) => value < 0)) continue;
		tiers.push({ inputTokensAbove, input, output, cacheRead, cacheWrite });
	}
	tiers.sort((a, b) => a.inputTokensAbove - b.inputTokensAbove);
	return tiers;
}
