/** 右侧工作区：Git 状态/提交解析、路径压缩、预览限制。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  FILE_PREVIEW_MAX_BYTES,
  fileExtension,
  formatCommitDate,
  parseGitLog,
  parseGitStatus,
  shouldPreviewFile,
  toRelativePath,
} from "../src/ui/workspace-model.ts";

const PROJECT = "D:/A-my project/demo";

test("非仓库返回 isRepo=false", () => {
  const view = parseGitStatus({ repo: false }, PROJECT);
  assert.equal(view.isRepo, false);
  assert.deepEqual(view.groups, []);
});

test("解析分支、分组与文件，绝对路径压成相对路径", () => {
  const view = parseGitStatus(
    {
      repo: true,
      branch: { current: "main", branches: ["main", "dev"] },
      groups: {
        merge: [],
        index: [{ path: "D:\\A-my project\\demo\\src\\a.ts", status: 1, letter: "A" }],
        workingTree: [{ path: "D:\\A-my project\\demo\\b.ts", status: 2, letter: "M" }],
        untracked: [{ path: "D:/A-my project/demo/c.txt", status: 7, letter: "U" }],
      },
    },
    PROJECT,
  );

  assert.equal(view.isRepo, true);
  assert.equal(view.branch, "main");
  assert.deepEqual(view.branches, ["main", "dev"]);
  assert.equal(view.total, 3);
  assert.deepEqual(
    view.groups.map((g) => g.key),
    ["index", "workingTree", "untracked"],
  );
  assert.equal(view.groups[0]?.files[0]?.relativePath, "src/a.ts");
  assert.equal(view.groups[2]?.files[0]?.relativePath, "c.txt");
});

test("分组顺序固定，空分组不出现", () => {
  const view = parseGitStatus(
    {
      repo: true,
      branch: { current: "main" },
      groups: { merge: [], index: [], workingTree: [], untracked: [{ path: "x", letter: "U" }] },
    },
    PROJECT,
  );
  assert.deepEqual(
    view.groups.map((g) => g.title),
    ["未跟踪"],
  );
});

test("跨盘符路径只保留末两段，避免整行被撑爆", () => {
  assert.equal(toRelativePath("E:\\other\\deep\\file.ts", PROJECT), "deep/file.ts");
});

test("项目路径为空时原样返回（正斜杠）", () => {
  assert.equal(toRelativePath("D:\\a\\b.ts", ""), "D:/a/b.ts");
});

test("解析提交历史，只取首行 message", () => {
  const commits = parseGitLog({
    commits: [
      {
        hash: "abcdef1234567890",
        shortHash: "abcdef1",
        message: "修复登录问题\n\n详细说明",
        authorName: "张三",
        authorDate: "2026-10-27T15:20:00+08:00",
      },
      { hash: "second", message: "无 shortHash 时用 hash 截断" },
      { message: "没有 hash 应丢弃" },
    ],
  });

  assert.equal(commits.length, 2);
  assert.equal(commits[0]?.message, "修复登录问题");
  assert.equal(commits[0]?.shortHash, "abcdef1");
  assert.equal(commits[1]?.shortHash, "second");
});

test("提交时间格式化；非法值原样截断", () => {
  assert.match(formatCommitDate("2026-10-27T15:20:00"), /^10-27 \d{2}:\d{2}$/);
  assert.equal(formatCommitDate("not-a-date"), "not-a-date");
  assert.equal(formatCommitDate(""), "");
});

test("预览大小限制", () => {
  assert.equal(shouldPreviewFile(1024), true);
  assert.equal(shouldPreviewFile(FILE_PREVIEW_MAX_BYTES), true);
  assert.equal(shouldPreviewFile(FILE_PREVIEW_MAX_BYTES + 1), false);
  assert.equal(shouldPreviewFile(Number.NaN), false);
});

test("取扩展名", () => {
  assert.equal(fileExtension("a/b/c.TS"), "ts");
  assert.equal(fileExtension("Makefile"), "");
});

test("目录栈逐层递进时不会重复累积（回归：/api/files 只认 dir，传 path 会被忽略）", () => {
  // 模拟点击目录：只取 name 的最后一段
  const pushSegment = (stack: string[], name: string): string[] => [
    ...stack,
    name.split(/[\/]/).filter(Boolean).pop() ?? name,
  ];

  let stack: string[] = [];
  stack = pushSegment(stack, "pideck-mobile");
  assert.deepEqual(stack, ["pideck-mobile"], "第一层正常");

  // 即使服务端意外返回带完整路径的 name，也不能让面包屑变长两截
  stack = pushSegment(stack, "pideck-mobile/app");
  assert.deepEqual(stack, ["pideck-mobile", "app"], "只取最后一段");

  stack = pushSegment(stack, "app");
  assert.equal(stack.join("/"), "pideck-mobile/app/app");
  assert.equal(new Set(stack).size, 2);
});
