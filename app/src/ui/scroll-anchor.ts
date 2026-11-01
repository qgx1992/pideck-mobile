/**
 * 跳转定位的纯计算（「跳转到历史消息」用）。
 *
 * 为什么不能像以前那样 `scrollIntoView({ block: "center" })` 居中：
 * 一条几千字的长回答居中后，视口落在正文中间 —— 用户看不到这条消息从哪儿开始，
 * 也就无法判断到底跳对没跳对。改成对齐顶部，短消息再用 clamp 兜底（不会滚过头）。
 */

/** 上边距：让消息顶部不要紧贴容器边缘（`.chat-scroll` 自己还有 14px padding）。 */
export const ANCHOR_GAP = 8;

/**
 * 目标消息 → 应该设置的 scrollTop。
 *
 * 参数都是「视口坐标系原值」，不用自己换算，方便在 jsdom 里测。
 * 结果永远落在 `[0, scrollHeight - clientHeight]`，靠近文档末尾时自然退化成贴底。
 */
export function topAlignedScrollTop(
  targetViewportTop: number,
  scrollerViewportTop: number,
  scrollTop: number,
  scrollHeight: number,
  clientHeight: number,
  gap: number = ANCHOR_GAP,
): number {
  const wanted = targetViewportTop - scrollerViewportTop + scrollTop - gap;
  const max = Math.max(0, scrollHeight - clientHeight);
  if (!Number.isFinite(wanted)) return 0;
  return Math.min(Math.max(0, wanted), max);
}

/** 一屏的高度（客户端高度可能是 0：jsdom 或还没布局，别拿去做除法）。 */
function screenful(clientHeight: number): number {
  return Number.isFinite(clientHeight) && clientHeight > 0 ? clientHeight : 0;
}

/**
 * 跳太远就别平滑滚：WebView 里几千像素的平滑动画要滚好几秒，
 * 1.8 秒的高亮早就过期了，用户体验是「点了没反应，然后突然飞过去」。
 */
export function jumpBehavior(distance: number, clientHeight: number, maxScreens = 3): "auto" | "smooth" {
  const height = screenful(clientHeight);
  if (height === 0) return "auto";
  if (!Number.isFinite(distance)) return "auto";
  return Math.abs(distance) > height * maxScreens ? "auto" : "smooth";
}
