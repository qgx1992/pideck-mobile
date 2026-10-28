/** 从正文里拆出 <thinking> 思考块（Pi 某些会话把思考写进 text 字段）。 */

import test from "node:test";
import assert from "node:assert/strict";
import { splitThinking, stripCompleteThinking, hasUnclosedThinking } from "../src/ui/thinking-tags.ts";

test("单个思考块被拆出来，正文只留回答", () => {
  const result = splitThinking("<thinking>先想一下</thinking>最终回答");
  assert.equal(result.thinking, "先想一下");
  assert.equal(result.text, "最终回答");
  assert.equal(result.found, true);
});

test("多个思考块合并，正文按原顺序保留", () => {
  const result = splitThinking("<thinking>第一段</thinking>中间<thinking>第二段</thinking>结尾");
  assert.equal(result.thinking, "第一段\n\n第二段");
  assert.equal(result.text, "中间结尾");
});

test("没有思考标签时原样返回", () => {
  const raw = "普通回答，没有标签";
  const result = splitThinking(raw);
  assert.equal(result.text, raw);
  assert.equal(result.thinking, "");
  assert.equal(result.found, false);
});

test("不误伤看起来像标签的正文（repo / token / keyfile）", () => {
  const raw = "<repo>foo</repo> 和 <token>abc</token> 以及 <keyfile>x</keyfile>";
  const result = splitThinking(raw);
  assert.equal(result.text, raw, "这些不是思考块，必须保留");
  assert.equal(result.found, false);
});

test("空思考块不产生空段落", () => {
  const result = splitThinking("<thinking>  </thinking>正文");
  assert.equal(result.text, "正文");
  assert.equal(result.thinking, "");
  assert.equal(result.found, false, "空白思考不算找到内容");
});

test("大小写不敏感", () => {
  const result = splitThinking("<THINKING>大写</THINKING>回答");
  assert.equal(result.thinking, "大写");
  assert.equal(result.text, "回答");
});

test("跨行思考块能正确匹配", () => {
  const raw = "<thinking>\n第一步\n第二步\n</thinking>\n\n结论";
  const result = splitThinking(raw);
  assert.ok(result.thinking.includes("第一步"));
  assert.ok(result.thinking.includes("第二步"));
  assert.equal(result.text, "结论");
});

test("stripCompleteThinking：已闭合的剥掉，未闭合的藏起来不显示裸标签", () => {
  assert.equal(stripCompleteThinking("<thinking>a</thinking>正文"), "正文");
  // 未闭合：把尾巴藏起来（等 </thinking> 到齐后下一帧再显示）
  assert.equal(stripCompleteThinking("<thinking>还没写完"), "");
});

test("hasUnclosedThinking 判定流式中间态", () => {
  assert.equal(hasUnclosedThinking("<thinking>半截"), true);
  assert.equal(hasUnclosedThinking("<thinking>完整</thinking>"), false);
  assert.equal(hasUnclosedThinking("没有标签"), false);
});

test("只有思考没有正文时，正文为空串", () => {
  const result = splitThinking("<thinking>只有想法</thinking>");
  assert.equal(result.text, "");
  assert.equal(result.thinking, "只有想法");
});

test("未闭合的思考：尾巴归到思考里，正文不留裸标签", () => {
  const result = splitThinking("<thinking>还没写完的想法");
  assert.equal(result.text, "", "正文不能露出 <thinking>");
  assert.equal(result.thinking, "还没写完的想法");
  assert.equal(result.found, true);
});

test("已闭合 + 未闭合混合", () => {
  const result = splitThinking("<thinking>第一段</thinking>正文<thinking>半截");
  assert.equal(result.text, "正文");
  assert.ok(result.thinking.includes("第一段"));
  assert.ok(result.thinking.includes("半截"));
});

test("stripCompleteThinking：未闭合时把尾巴藏起来，不显示裸标签", () => {
  assert.equal(stripCompleteThinking("<thinking>写了一半"), "");
  assert.equal(stripCompleteThinking("已出正文<thinking>半截"), "已出正文");
});
