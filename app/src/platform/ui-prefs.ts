/**
 * 界面偏好（不是凭据，所以放 localStorage 就够）。
 *
 * 目前只有侧栏分段的选择：用户选了「聊天」，下次打开 App 还应该在「聊天」。
 */

import { parseSidebarTab } from "../ui/sidebar-tabs-model.ts";
import type { SidebarTab } from "../ui/sidebar-tabs-model.ts";

const SIDEBAR_TAB_KEY = ["pideck", "ui", "sidebarTab", "v1"].join("-");

export function loadSidebarTab(): SidebarTab | null {
  try {
    return parseSidebarTab(localStorage.getItem(SIDEBAR_TAB_KEY));
  } catch {
    return null;
  }
}

export function saveSidebarTab(tab: SidebarTab): void {
  try {
    localStorage.setItem(SIDEBAR_TAB_KEY, tab);
  } catch {
    /* 隐私模式写不进去，忽略 */
  }
}
