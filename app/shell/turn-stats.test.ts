/**
 * 回合统计行的纯函数部分：口径与桌面端 `AgentRuntimeState` 对齐，
 * 数据来自 assistant 消息的 `meta`（见 turn-stats.ts 顶部说明）。
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
	formatSeconds,
	formatTokenCount,
	formatTokensPerSecond,
	formatTurnStats,
	lastAssistantMessage,
	sessionIdFromPath,
	statsFromMessage,
} from "./turn-stats.ts";

/** 真机上抓到的形状（`/api/sessions/:id/messages/page` 的 assistant 消息 meta）。 */
const REAL_META = {
	entryId: "bf01899d",
	_piDeckMsgSeq: 169,
	usage: { inputTokens: 243, outputTokens: 433, cacheReadTokens: 57088, cacheWriteTokens: 0, reasoningTokens: 86, cost: 0 },
	durationMs: 5282,
	startedAt: 1793776522993,
	endedAt: 1793776528275,
};

describe("statsFromMessage", () => {
	it("读出耗时/token/端到端速度（output ÷ 总耗时）", () => {
		const stats = statsFromMessage(REAL_META);
		assert.ok(stats);
		assert.equal(stats.durationMs, 5282);
		assert.equal(stats.outputTokens, 433);
		assert.equal(stats.inputTokens, 243);
		assert.equal(stats.cacheReadTokens, 57088);
		assert.equal(stats.cost, 0);
		// 433 ÷ 5.282s = 81.98 tok/s
		assert.ok(Math.abs((stats.endToEndTps ?? 0) - 81.98) < 0.05);
	});

	it("没有 durationMs 时退化到 endedAt - startedAt", () => {
		const stats = statsFromMessage({ usage: { outputTokens: 100 }, startedAt: 1000, endedAt: 3000 });
		assert.ok(stats);
		assert.equal(stats.durationMs, 2000);
		assert.equal(stats.endToEndTps, 50);
	});

	it("0 输出或 0 耗时一律不显示（别画「0.0s」）", () => {
		assert.equal(statsFromMessage({ usage: { outputTokens: 0 }, durationMs: 5000 }), null);
		assert.equal(statsFromMessage({ usage: { outputTokens: 100 }, durationMs: 0 }), null);
		assert.equal(statsFromMessage({ usage: {} }), null);
	});

	it("脏数据不炸", () => {
		assert.equal(statsFromMessage(null), null);
		assert.equal(statsFromMessage("x"), null);
		assert.equal(statsFromMessage({ usage: "nope", durationMs: "5000" }), null);
	});
});

describe("格式化", () => {
	it("时长：秒级一位小数，过分钟用 m12s", () => {
		assert.equal(formatSeconds(5282), "5.3s");
		assert.equal(formatSeconds(900), "0.9s");
		assert.equal(formatSeconds(72_400), "1m12s");
	});

	it("token：千位以下原样，1k–10k 一位小数，再往上取整", () => {
		assert.equal(formatTokenCount(433), "433");
		assert.equal(formatTokenCount(1234), "1.2k");
		assert.equal(formatTokenCount(18_400), "18k");
	});

	it("速度：十位以上取整", () => {
		assert.equal(formatTokensPerSecond(81.98), "82");
		assert.equal(formatTokensPerSecond(9.64), "9.6");
	});

	it("整行：时长 · token · 端到端速度", () => {
		const stats = statsFromMessage(REAL_META);
		assert.ok(stats);
		assert.equal(formatTurnStats(stats), "回复 5.3s · 433 tok · 端到端 82 tok/s");
	});

	it("没有速度时只剩两项（不画空段）", () => {
		assert.equal(formatTurnStats({ durationMs: 2000, outputTokens: 40 }), "回复 2.0s · 40 tok");
	});
});

describe("消息页解析", () => {
	it("取最后一条 assistant 的 meta（跳过尾部 tool 消息）", () => {
		const payload = {
			messages: [
				{ role: "user", meta: { x: 1 } },
				{ role: "assistant", meta: REAL_META },
				{ role: "tool", meta: { toolName: "read" } },
				{ role: "tool", meta: { toolName: "bash" } },
			],
		};
		assert.equal(lastAssistantMessage(payload), REAL_META);
	});

	it("没有 assistant 或结构不对 → null", () => {
		assert.equal(lastAssistantMessage({ messages: [{ role: "user" }] }), null);
		assert.equal(lastAssistantMessage({}), null);
		assert.equal(lastAssistantMessage(null), null);
	});
});

describe("会话路由", () => {
	it("从 /s/<id> 取会话 id", () => {
		assert.equal(sessionIdFromPath("/s/f421df54-44d4-48f0-82d3-7bd881cdbe88"), "f421df54-44d4-48f0-82d3-7bd881cdbe88");
		assert.equal(sessionIdFromPath("/s/abc/"), "abc");
		assert.equal(sessionIdFromPath("/"), "");
		assert.equal(sessionIdFromPath("/s/"), "");
	});
});
