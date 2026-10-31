/**
 * 正文文件路径的识别与归一化。
 *
 * 两个方向都要顾：该识别的（`app/src/main.ts:12`）要识别，
 * 不该识别的（URL、裸文件名、`..` 相对路径）必须放过去当普通文字。
 */

import test from "node:test";
import assert from "node:assert/strict";

const { splitFileRef, findFileRefs, toProjectRelative, fileNameOf, dirStackOf } = await import(
  "../src/ui/file-ref-model.ts"
);

test("带行号的项目相对路径拆成 path + line", () => {
  assert.deepEqual(splitFileRef("src/api/client.ts:246"), { path: "src/api/client.ts", line: 246 });
  assert.deepEqual(splitFileRef("app/src/main.ts"), { path: "app/src/main.ts" });
  // `:12:5` 只取行
  assert.deepEqual(splitFileRef("app/a.ts:12:5"), { path: "app/a.ts", line: 12 });
});

test("Windows 绝对路径也认（反斜杠保留原样，归一化在 toProjectRelative 做）", () => {
  assert.deepEqual(splitFileRef("D:\\A-my project\\PiDeck-Mobile\\app\\b.ts"), {
    path: "D:\\A-my project\\PiDeck-Mobile\\app\\b.ts",
  });
});

test("裸文件名不当成引用（正文里太常见）", () => {
  assert.equal(splitFileRef("README.md"), null);
  assert.equal(splitFileRef("package.json"), null);
});

test("URL 不当成文件路径", () => {
  assert.equal(splitFileRef("https://github.com/a/b.md"), null);
  assert.equal(splitFileRef("//evil.com/a.ts"), null);
  assert.equal(splitFileRef("src//a.ts"), null);
});

test("`..` 段不认", () => {
  assert.equal(splitFileRef("../a/b.ts"), null);
  assert.equal(splitFileRef("src/../a.ts"), null);
});

test("findFileRefs 给出原文下标，便于拼回文本", () => {
  const text = "改 app/src/main.ts:12 和 docs/a.md 两个文件";
  const hits = findFileRefs(text);
  assert.equal(hits.length, 2);
  const [first, second] = hits;
  assert.equal(first?.path, "app/src/main.ts");
  assert.equal(first?.line, 12);
  assert.equal(text.slice(first!.index, first!.index + first!.length), "app/src/main.ts:12");
  assert.equal(second?.path, "docs/a.md");
  assert.equal(text.slice(second!.index, second!.index + second!.length), "docs/a.md");
});

test("findFileRefs 跳过 URL 内部的相似片段", () => {
  const hits = findFileRefs("见 https://x.com/a/b.md 与 src/a.ts 两处");
  assert.equal(hits.length, 1);
  assert.equal(hits[0]?.path, "src/a.ts");
});

test("findFileRefs 对列表/括号里的路径也生效", () => {
  const hits = findFileRefs("- [x] (src/a.ts)\n- `src/b.ts:3`");
  assert.deepEqual(
    hits.map((hit) => hit.path),
    ["src/a.ts", "src/b.ts"],
  );
});

test("绝对路径按项目根压成相对路径", () => {
  assert.equal(toProjectRelative("/home/u/proj/src/a.ts", "/home/u/proj"), "src/a.ts");
  assert.equal(
    toProjectRelative("D:\\A-my project\\PiDeck-Mobile\\app\\src\\main.ts", "D:\\A-my project\\PiDeck-Mobile"),
    "app/src/main.ts",
  );
});

test("盘符路径大小写不敏感，POSIX 路径保持大小写敏感", () => {
  assert.equal(toProjectRelative("d:/proj/app/a.ts", "D:/proj"), "app/a.ts");
  assert.equal(toProjectRelative("/home/U/proj/a.ts", "/home/u/proj"), "home/U/proj/a.ts");
});

test("压不掉时原样返回（反斜杠统一成正斜杠）", () => {
  assert.equal(toProjectRelative("src\\a.ts", ""), "src/a.ts");
  assert.equal(toProjectRelative("/etc/hosts", "/home/u/proj"), "etc/hosts");
});

test("fileNameOf / dirStackOf 取文件名与目录栈", () => {
  assert.equal(fileNameOf("app/src/main.ts"), "main.ts");
  assert.equal(fileNameOf("app\\src\\main.ts"), "main.ts");
  assert.deepEqual(dirStackOf("app/src/main.ts"), ["app", "src"]);
  assert.deepEqual(dirStackOf("main.ts"), []);
});
