/**
 * 启动动画的收尾逻辑。
 *
 * 动画本身是 index.html 里的内联 DOM/CSS（必须在 JS 包加载前就画出来，
 * 否则深色主题用户会先看到一闪白屏）。这里只负责「什么时候可以收」：
 *   - 最短显示时间：太快闪一下反而更像卡顿
 *   - 幂等：多个入口（配对页 ready / 主界面首帧 / boot 失败）都可能调用
 *   - 兜底：就算谁都没调用，也必须在超时后自己收掉，绝不能把界面挡住
 */

/**
 * 最短显示时间。
 *
 * 连接往往 100ms 内就成功了，如果这时立刻收掉，启动动画等于没出现过。
 * 1200ms 是「仪式感」和「别让人干等」之间的折中：
 * 图标入场 460ms、字标收拢 720ms、进度条 520ms 起 —— 刚好都看得完整。
 */
export const SPLASH_MIN_MS = 1_200;
/** 进度条补满的那一下，让「好了」有个明确的信号。 */
export const SPLASH_READY_MS = 240;
/** 最迟多久必须消失（万一某个初始化分支挂住）。 */
export const SPLASH_MAX_MS = 6_000;
/** 淡出动画时长，需与 CSS 的 .splash.is-leaving 保持一致。 */
export const SPLASH_FADE_MS = 300;
/** 超过这个时间后，点一下启动页可以立刻跳过（不绑架急性子）。 */
export const SPLASH_SKIP_AFTER_MS = 400;

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
export function hideSplash(options: { immediate?: boolean } = {}): void {
  if (hidden) return;
  hidden = true;

  if (hideTimer !== null) {
    window.clearTimeout(hideTimer);
    hideTimer = null;
  }

  const el = document.getElementById("splash");
  if (!el) return;

  const shownAt = Number(el.dataset.shownAt ?? 0);
  const wait = options.immediate ? 0 : splashRemainingMs(shownAt, Date.now());

  const start = (): void => {
    // ① 进度条先补满（明确的「好了」），同时让主界面在遮罩后面淡入
    el.classList.add("is-ready");
    document.getElementById("app")?.classList.add("is-appearing");

    // ② 再整体淡出，露出已经摆好的界面
    window.setTimeout(() => {
      el.classList.add("is-leaving");
      window.setTimeout(() => el.remove(), SPLASH_FADE_MS);
    }, SPLASH_READY_MS);
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

  // 等了一会儿还嫌慢？点一下立刻跳过（太早的误触不算，免得遮罩刚出现就没了）
  el.addEventListener("pointerdown", () => {
    const shownAt = Number(el.dataset.shownAt ?? 0);
    if (Date.now() - shownAt < SPLASH_SKIP_AFTER_MS) return;
    hideSplash({ immediate: true });
  });
}
