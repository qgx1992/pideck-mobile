/**
 * 相机扫码时的取景遮罩。
 *
 * 原生 startScan() 把相机画面铺在 WebView **下面**，所以整个页面必须藏起来、
 * 只留这个遮罩可见（body 加 class `barcode-scanner-active`，CSS 里处理）。
 * 遮罩本身只有取景框 + 提示 + 手电筒 + 取消，背景全透明。
 */

import { h } from "./dom.ts";

export type OverlayTone = "info" | "error";

export interface ScanOverlayHandlers {
  /** 用户点「取消」/ 返回键 */
  onCancel?: () => void;
  /** 用户点手电筒（参数是「切换后是否开着」） */
  onTorch?: (next: boolean) => void;
}

export interface ScanOverlay {
  el: HTMLElement;
  /** 底部提示文案（识别失败、权限被拒时用） */
  setHint: (text: string, tone?: OverlayTone) => void;
  /** 外部改手电筒状态（例如原生侧报错回滚） */
  setTorch: (on: boolean) => void;
  setTorchAvailable: (available: boolean) => void;
  destroy: () => void;
}

const BODY_CLASS = "barcode-scanner-active";

export function createScanOverlay(handlers: ScanOverlayHandlers = {}): ScanOverlay {
  let torchOn = false;

  const hint = h("div", { class: "scan-hint", text: "把二维码放进框里" });
  const torchBtn = h("button", {
    class: "scan-btn scan-btn--ghost",
    text: "手电筒",
    "aria-label": "手电筒",
    onclick: () => {
      torchOn = !torchOn;
      torchBtn.classList.toggle("is-on", torchOn);
      handlers.onTorch?.(torchOn);
    },
  });
  torchBtn.hidden = true;

  const cancelBtn = h("button", {
    class: "scan-btn",
    text: "取消",
    onclick: () => handlers.onCancel?.(),
  });

  const frame = h("div", { class: "scan-frame" }, [
    h("span", { class: "scan-corner scan-corner--tl" }),
    h("span", { class: "scan-corner scan-corner--tr" }),
    h("span", { class: "scan-corner scan-corner--bl" }),
    h("span", { class: "scan-corner scan-corner--br" }),
    h("span", { class: "scan-sweep" }),
  ]);

  const el = h("div", { class: "scan-overlay", "aria-label": "扫码" }, [
    h("div", { class: "scan-top" }, [
      h("span", { class: "scan-title", text: "扫描配对二维码" }),
      cancelBtn,
    ]),
    h("div", { class: "scan-stage" }, [frame, hint]),
    h("div", { class: "scan-bottom" }, [torchBtn]),
  ]);

  document.body.classList.add(BODY_CLASS);
  document.body.append(el);

  return {
    el,
    setHint: (text, tone = "info") => {
      hint.textContent = text;
      hint.className = `scan-hint scan-hint--${tone}`;
    },
    setTorch: (on) => {
      torchOn = on;
      torchBtn.classList.toggle("is-on", on);
    },
    setTorchAvailable: (available) => {
      torchBtn.hidden = !available;
      if (!available) {
        torchOn = false;
        torchBtn.classList.remove("is-on");
      }
    },
    destroy: () => {
      document.body.classList.remove(BODY_CLASS);
      el.remove();
    },
  };
}
