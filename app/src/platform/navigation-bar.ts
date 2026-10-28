/**
 * 导航栏（底部手势条 / 虚拟按键栏）配色。
 *
 * 为什么单独一个模块：@capacitor/status-bar 只有状态栏 API，导航栏完全没覆盖，
 * 所以底部小横条一直是系统默认色（浅色模式白条 / 深色模式黑条），不跟随 App 主题。
 * 这里调用本项目自带的原生插件 NavigationBar（android/.../NavigationBarPlugin.java）。
 *
 * 非原生平台（浏览器开发）直接跳过。
 */

import { Capacitor, registerPlugin } from "@capacitor/core";

export interface NavigationBarPlugin {
  setColor(options: { color: string; lightIcons?: boolean }): Promise<{ color: string; lightIcons: boolean }>;
}

const NavigationBar = registerPlugin<NavigationBarPlugin>("NavigationBar");

/**
 * @param color     导航栏背景色（十六进制）
 * @param lightIcons true = 深色背景上要浅色图标；false = 浅色背景上要深色图标
 */
export async function setNavigationBarColor(color: string, lightIcons: boolean): Promise<void> {
  if (!Capacitor.isNativePlatform() || Capacitor.getPlatform() !== "android") return;
  try {
    await NavigationBar.setColor({ color, lightIcons });
  } catch (error) {
    // 插件缺失（例如旧 APK）不应影响界面
    console.warn("[navigation-bar] 设置失败", error);
  }
}
