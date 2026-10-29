/**
 * 折叠指示箭头（chevron）。
 *
 * 之前用的是文字字符 ▸ / ▾：字号只有 10px，不同 WebView 的字形、基线差别很大，
 * 而且「执行过程」那处的 CSS content 被写坏成了字面量 "B8"。
 * 这里统一改成内联 SVG（lucide 的 chevron-right，stroke-width 2），
 * 尺寸对齐 Web 端：分组标题 14px，行内小按钮 12px。
 *
 * 展开态不换图标，只旋转 90°——动画更顺，也省一次换节点。
 */

const SVG_NS = "http://www.w3.org/2000/svg";

/**
 * 造一个 chevron 图标。
 * @param expanded 展开时旋转 90°（指向下方）
 * @param size 边长（px），Web 端分组是 14、行内是 12
 */
export function createChevron(expanded: boolean, size = 14): SVGSVGElement {
  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("width", String(size));
  svg.setAttribute("height", String(size));
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "2");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("class", `chev-icon${expanded ? " is-expanded" : ""}`);

  const path = document.createElementNS(SVG_NS, "path");
  // lucide chevron-right：M9 18l6-6-6-6
  path.setAttribute("d", "M9 18l6-6-6-6");
  svg.append(path);
  return svg;
}

/** 就地更新箭头方向（不换节点，避免丢动画/焦点）。 */
export function setChevronExpanded(target: Element | null, expanded: boolean): void {
  if (!target) return;
  target.classList.toggle("is-expanded", expanded);
}
