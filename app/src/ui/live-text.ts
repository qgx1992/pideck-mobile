/**
 * 流式文本的增量写入。
 *
 * 直接 `el.textContent = text` 有两个副作用：
 *
 *   1. 整段文本节点被换掉，元素内部滚动位置被浏览器重置到顶部。
 *      `.reasoning-body` / `.tool-body` 有 `max-height` + `overflow: auto`，
 *      思考还在流式输出时每隔几秒写一次，用户读到一半就被拽回块的开头
 *      （用户报的「展开的思考块每隔几秒重置到该块的头部内容」就是这个）。
 *   2. 每次都重新排版整段文字，长思考块越写越卡。
 *
 * 所以：能纯追加就只 append 一个文本节点；必须整体替换时把 scrollTop 还回去。
 */

/** 判定「已经贴底」的容差（px）。 */
const STICK_TOLERANCE_PX = 4;

export interface SetStreamTextOptions {
  /** 允许滚动的元素才需要「贴底跟随」，默认 true */
  followBottom?: boolean;
}

/**
 * 把 `text` 写进 `el`，尽量只做增量，并保住滚动位置。
 *
 * - 新文本以旧文本开头 → 只 append 差量（DOM 节点不变，滚动位置自然不丢）
 * - 否则整体替换，但保留原来的 scrollTop
 * - 写之前就贴在底部 → 写之后继续贴底（正在看最新输出的人不用手动下拉）
 */
export function setStreamText(
  el: HTMLElement,
  text: string,
  options: SetStreamTextOptions = {},
): void {
  const previous = el.textContent ?? "";
  if (previous === text) return;

  const followBottom = options.followBottom ?? true;
  const scrollable = el.scrollHeight > el.clientHeight;
  const atBottom =
    !scrollable || el.scrollHeight - el.scrollTop - el.clientHeight <= STICK_TOLERANCE_PX;
  const keepTop = el.scrollTop;

  if (previous.length > 0 && text.startsWith(previous)) {
    el.append(el.ownerDocument.createTextNode(text.slice(previous.length)));
  } else {
    el.textContent = text;
    // 内容整体换了（思考被替换 / 回退 / 重新分组）：尽量停在原来的位置
    el.scrollTop = keepTop;
  }

  if (scrollable && atBottom && followBottom) el.scrollTop = el.scrollHeight;
}
