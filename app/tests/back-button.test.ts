/** Android 返回键：一次只退一步。 */

import test from "node:test";
import assert from "node:assert/strict";
import {
  LEAVE_CONFIRM_MS,
  resolveBackIntent,
  resolveLeaveAction,
} from "../src/platform/back-button.ts";

const none = { sheetOpen: false, workspaceOpen: false, sidebarOpen: false };

test("最上层是面板时先关面板", () => {
  assert.equal(resolveBackIntent({ ...none, sheetOpen: true }), "close-sheet");
});

test("面板开着时，即使抽屉也开着也只关面板", () => {
  assert.equal(
    resolveBackIntent({ sheetOpen: true, workspaceOpen: true, sidebarOpen: true }),
    "close-sheet",
  );
});

test("没有面板时先关右侧工作区", () => {
  assert.equal(resolveBackIntent({ ...none, workspaceOpen: true, sidebarOpen: true }), "close-workspace");
});

test("只开着侧边栏就收侧边栏（用户报的场景）", () => {
  assert.equal(resolveBackIntent({ ...none, sidebarOpen: true }), "close-sidebar");
});

test("什么都没开才算离开", () => {
  assert.equal(resolveBackIntent(none), "leave");
});

test("第一次返回只提示，不退出也不缩后台", () => {
  assert.equal(resolveLeaveAction(0, 1_000), "confirm");
});

test("窗口内再按一次才退出", () => {
  assert.equal(resolveLeaveAction(1_000, 1_500), "exit");
});

test("超过确认窗口后又回到只提示", () => {
  assert.equal(resolveLeaveAction(1_000, 1_000 + LEAVE_CONFIRM_MS + 1), "confirm");
});

test("刚好卡在窗口边界算退出", () => {
  assert.equal(resolveLeaveAction(1_000, 1_000 + LEAVE_CONFIRM_MS), "exit");
});

test("设置页盖在最上面，返回键先关它", () => {
  assert.equal(
    resolveBackIntent({ settingsOpen: true, sheetOpen: true, workspaceOpen: true, sidebarOpen: true }),
    "close-settings",
  );
});

test("没开设置页时行为不变", () => {
  assert.equal(resolveBackIntent({ ...none, settingsOpen: false, sheetOpen: true }), "close-sheet");
});
