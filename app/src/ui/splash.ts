/**
 * 启动动画的收尾逻辑。
 *
 * 动画本身是 index.html 里的内联 DOM/CSS（必须在 JS 包加载前就画出来，
 * 否则深色主题用户会先看到一闪白屏）。这里只负责「什么时候可以收」：
 *   - 最短显示时间：太快闪一下反而更像卡顿
 *   - 幂等：多个入口（配对页 ready / 主界面首帧 / boot 失败）都可能调用
 *   - 兜底：就算谁都没调用，也必须在超时后自己收掉，绝不能把界面挡住
 */

/** 最短显示时间：动画得有被看见的机会。 */
export const SPLASH_MIN_MS = 700;
/** 最迟多久必须消失（万一某个初始化分支挂住）。 */
export const SPLASH_MAX_MS = 6_000;
/** 淡出动画时长，需与 CSS 的 .splash.is-leaving 保持一致。 */
export const SPLASH_FADE_MS = 300;

/**
 * 距离可以淡出还差多少毫秒（纯函数，便于测试）。
 * 已经超过最短时间就返回 0。
 */
export function splashRemainingMs(shownAt: number, now: number, minMs = SPLASH_MIN_MS): number {
  if (!Number.isFinite(shownAt) || !Number.isFinite(now)) return 0;
  return Math.max(0, minMs - (now - shownAt));
}

let hidden = false;
let hideTimer: number | null = null;

/** 启动页上显示的那行小字（可用来提示「正在连接…」之类）。 */
export function setSplashHint(text: string): void {
  const node = document.getElementById("splash-hint");
  if (node) node.textContent = text;
}

/**
 * 收起启动动画。
 * 重复调用是安全的；会等最短显示时间后再淡出，淡出结束后从 DOM 移除。
 */
export function hideSplash(): void {
  if (hidden) return;
  hidden = true;

  if (hideTimer !== null) {
    window.clearTimeout(hideTimer);
    hideTimer = null;
  }

  const el = document.getElementById("splash");
  if (!el) return;

  const shownAt = Number(el.dataset.shownAt ?? 0);
  const wait = splashRemainingMs(shownAt, Date.now());

  const start = (): void => {
    el.classList.add("is-leaving");
    window.setTimeout(() => el.remove(), SPLASH_FADE_MS);
  };

  if (wait > 0) window.setTimeout(start, wait);
  else start();
}

/** 是否已经收起了（调试/测试用）。 */
export function isSplashHidden(): boolean {
  return hidden;
}

/** 测试用：重置内部状态。 */
export function resetSplashStateForTest(): void {
  hidden = false;
  hideTimer = null;
}

/**
 * 看门狗：从这里起算，最多 SPLASH_MAX_MS 后强制收起。
 * 在 main.ts 最开头调用一次。
 */
export function armSplashWatchdog(): void {
  const el = document.getElementById("splash");
  if (!el) return;
  if (!el.dataset.shownAt) el.dataset.shownAt = String(Date.now());
  hideTimer = window.setTimeout(() => hideSplash(), SPLASH_MAX_MS);
}
