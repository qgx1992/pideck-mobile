/**
 * agentCodeTokenCache —— shiki 代码高亮结果的进程内缓存（独立模块便于单测）。
 *
 * 为什么有上限：一条高亮结果可达数十 KB，长会话中代码块不断累积，
 * 无限 Map 会让渲染进程 JS 堆只涨不缩。高亮是廉价可重算的，
 * 清缓存只损失一次重算，不损失正确性（内存换时间的天平在这里应偏向内存）。
 */

export interface AgentCodeToken {
	content: string;
	offset: number;
	light?: string;
	dark?: string;
}

export type AgentCodeTokenLines = AgentCodeToken[][];

/** 缓存条目上限：超过后整体清空重建（简单 FIFO 变体，避免引入 LRU 复杂度）。 */
export const MAX_TOKEN_CACHE_ENTRIES = 200;

const tokenCache = new Map<string, AgentCodeTokenLines>();

/** 写入缓存；达到上限时清空再写（防长会话 JS 堆无限增长）。 */
export function cacheTokens(key: string, lines: AgentCodeTokenLines): void {
	if (tokenCache.size >= MAX_TOKEN_CACHE_ENTRIES) {
		tokenCache.clear();
	}
	tokenCache.set(key, lines);
}

/** 读取缓存；未命中返回 undefined（调用方走完整高亮流程）。 */
export function getCachedTokens(key: string): AgentCodeTokenLines | undefined {
	return tokenCache.get(key);
}

/** 当前缓存条目数（诊断用，不参与业务判断）。 */
export function tokenCacheSize(): number {
	return tokenCache.size;
}
