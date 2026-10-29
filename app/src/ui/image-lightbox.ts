/**
 * 图片放大查看（轻量 lightbox）。
 *
 * 对话里的缩略图最多 160px，看不清截图里的代码/报错，必须能点开看原图。
 * 只做一件事：全屏黑底 + 原图 + 点任意处/Esc 关闭。
 */

import { h } from "./dom.ts";

let active: HTMLElement | null = null;

export function closeImageLightbox(): void {
  if (!active) return;
  active.remove();
  active = null;
}

export function openImageLightbox(src: string, alt = ""): void {
  closeImageLightbox();

  const img = h("img", { class: "lightbox-img", "aria-label": alt || "图片" });
  img.src = src;
  img.alt = alt;

  const overlay = h("div", { class: "lightbox", "aria-label": "图片预览" }, [
    img,
    h("button", { class: "lightbox-close", text: "关闭", "aria-label": "关闭" }),
  ]);
  // 点遮罩或按钮都关；点图片本身不关（用户可能想长按保存）
  overlay.addEventListener("click", (event) => {
    if (event.target === img) return;
    closeImageLightbox();
  });
  const onKey = (event: KeyboardEvent): void => {
    if (event.key === "Escape") closeImageLightbox();
  };
  document.addEventListener("keydown", onKey);
  overlay.addEventListener("remove", () => document.removeEventListener("keydown", onKey));

  document.body.append(overlay);
  active = overlay;
}
