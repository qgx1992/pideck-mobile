/**
 * 服务端拒收提问时的翻译层。
 *
 * 以前的 bug：`appendLocalUser` 先乐观上屏，`channel.send` 的返回值没人看，
 * 于是 ACP 的四类拒收（turnBusy / imagesNotSupported / hostInstructionsUnsupported /
 * processNotRunning）都会留下「幽灵气泡」，而且一句提示都没有。
 */

import test from "node:test";
import assert from "node:assert/strict";

const { isPromptRejection, rejectionMessage, shouldRollbackRejection } = await import(
  "../src/ui/acp-notice.ts"
);

test("只认 accepted === false（老桌面端的空 result 不算拒收）", () => {
  assert.equal(isPromptRejection({ accepted: false }), true);
  assert.equal(isPromptRejection({ accepted: true }), false);
  assert.equal(isPromptRejection({}), false);
  assert.equal(isPromptRejection(undefined), false);
  assert.equal(isPromptRejection(null), false);
  assert.equal(isPromptRejection("nope"), false);
});

test("回合中发消息：文案里带上 CLI 名字", () => {
  assert.equal(
    rejectionMessage({ accepted: false, i18nKey: "acp.turnBusy", i18nParams: { tool: "Kimi" } }),
    "Kimi 还在回复中，等这一轮结束或先点停止再发",
  );
});

test("CLI 没装在电脑上：把命令一起给出来，用户才知道该装什么", () => {
  const text = rejectionMessage({
    accepted: false,
    i18nKey: "acp.toolNotInstalled",
    i18nParams: { tool: "Codex", command: "npx -y @agentclientprotocol/codex-acp" },
  });
  assert.match(text, /「Codex」启动失败/);
  assert.match(text, /npx -y @agentclientprotocol\/codex-acp/);
  assert.match(text, /电脑上/);
});

test("图片不支持 / 内部指令不支持 / 进程没起来 都有中文文案", () => {
  assert.match(
    rejectionMessage({ accepted: false, i18nKey: "acp.imagesNotSupported", i18nParams: { tool: "Kimi" } }),
    /不接受图片输入/,
  );
  assert.match(
    rejectionMessage({ accepted: false, i18nKey: "acp.hostInstructionsUnsupported" }),
    /不支持 PiDeck 的内部指令/,
  );
  assert.equal(
    rejectionMessage({ accepted: false, i18nKey: "acp.processNotRunning", i18nParams: { tool: "Gemini" } }),
    "Gemini 进程未在运行，请重试",
  );
});

test("回合超时：分钟数来自 i18nParams，缺失时不留花括号", () => {
  assert.match(
    rejectionMessage({ accepted: false, i18nKey: "acp.turnTimeout", i18nParams: { tool: "Kimi", minutes: "10" } }),
    /连续 10 分钟/,
  );
  const withoutMinutes = rejectionMessage({
    accepted: false,
    i18nKey: "acp.turnTimeout",
    i18nParams: { tool: "Kimi" },
  });
  assert.ok(!withoutMinutes.includes("{"), "模板占位符不能漏到界面上");
});

test("缺 tool 参数时用中性称呼，不漏 {tool}", () => {
  const text = rejectionMessage({ accepted: false, i18nKey: "acp.turnBusy" });
  assert.match(text, /该 CLI 还在回复中/);
  assert.ok(!text.includes("{"));
});

test("未登录 / 工具被删：指向电脑端，因为手机上没有那个设置页", () => {
  assert.match(
    rejectionMessage({ accepted: false, i18nKey: "acp.authRequired", i18nParams: { tool: "Kimi" } }),
    /电脑的终端/,
  );
  assert.match(rejectionMessage({ accepted: false, i18nKey: "acp.toolMissing" }), /电脑端 设置 → ACP 工具/);
});

test("未知 i18nKey 退到服务端原文；连原文都没有时给可行动的兜底", () => {
  assert.equal(
    rejectionMessage({ accepted: false, i18nKey: "acp.somethingNew", error: "boom" }),
    "发送被拒绝：boom",
  );
  assert.match(rejectionMessage({ accepted: false }), /桌面端没有说明原因/);
});

test("delivery=unknown 时不回滚气泡（服务端也不确定发没发出去）", () => {
  assert.equal(shouldRollbackRejection({ accepted: false, delivery: "rejected" }), true);
  assert.equal(shouldRollbackRejection({ accepted: false }), true);
  assert.equal(shouldRollbackRejection({ accepted: false, delivery: "unknown" }), false);
});
