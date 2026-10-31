/** 极小 DOM helper：避免为一个原型引入框架。 */

type Child = Node | string | null | undefined | false;

export interface Props {
  class?: string;
  text?: string;
  title?: string;
  role?: string;
  "aria-label"?: string;
  "aria-hidden"?: string;
  type?: string;
  placeholder?: string;
  value?: string;
  disabled?: boolean;
  onclick?: (event: MouseEvent) => void;
  oninput?: (event: Event) => void;
  onkeydown?: (event: KeyboardEvent) => void;
  dataset?: Record<string, string>;
  style?: string;
}

export function h<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: Props = {},
  children: Child[] = [],
): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  if (props.class) el.className = props.class;
  if (props.text !== undefined) el.textContent = props.text;
  if (props.title) el.title = props.title;
  if (props.style) el.setAttribute("style", props.style);
  if (props.dataset) for (const [key, value] of Object.entries(props.dataset)) el.dataset[key] = value;
  // aria-* / role 之类原样落到 attribute 上
  for (const [key, value] of Object.entries(props)) {
    if (!key.startsWith("aria-") && key !== "role") continue;
    if (typeof value === "string") el.setAttribute(key, value);
  }

  if (tag === "input" || tag === "textarea") {
    const field = el as unknown as HTMLInputElement;
    if (props.type) field.type = props.type;
    if (props.placeholder) field.placeholder = props.placeholder;
    if (props.value !== undefined) field.value = props.value;
    if (props.disabled) field.disabled = true;
  } else if (props.disabled) {
    (el as HTMLButtonElement).disabled = true;
  }

  if (props.onclick) el.addEventListener("click", props.onclick as EventListener);
  if (props.oninput) el.addEventListener("input", props.oninput);
  if (props.onkeydown) el.addEventListener("keydown", props.onkeydown as EventListener);

  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    el.append(typeof child === "string" ? document.createTextNode(child) : child);
  }
  return el;
}

export function clear(el: Element): void {
  while (el.firstChild) el.removeChild(el.firstChild);
}

export function fmtTime(ms: number | undefined): string {
  if (!ms) return "";
  const date = new Date(ms);
  const now = Date.now();
  const sameDay = new Date(now).toDateString() === date.toDateString();
  const hh = String(date.getHours()).padStart(2, "0");
  const mm = String(date.getMinutes()).padStart(2, "0");
  if (sameDay) return `${hh}:${mm}`;
  return `${date.getMonth() + 1}/${date.getDate()}`;
}

/**
 * 轻提示。
 *
 * 挂在 #app 内（position: relative），避免 fixed 在部分 WebView 里定位偏移。
 *
 * 位置：**抬到输入框上方**。以前固定在底部 28px，正好盖住正在打字的输入框，
 * 一边提示一边挡着用户看自己刚打的字。这里按 composer 的实际高度算偏移，
 * 输入框随内容长高 / 附件行出现时也能跟着走。
 */
export function toast(message: string): void {
  // 同一时刻只留一条，避免连点时叠成一坨
  for (const previous of Array.from(document.querySelectorAll(".toast"))) previous.remove();

  const el = h("div", { class: "toast", text: message });

  const composer = document.querySelector<HTMLElement>(".composer");
  if (composer && el.ownerDocument.defaultView) {
    const host = document.getElementById("app") ?? document.body;
    const hostRect = host.getBoundingClientRect();
    const composerRect = composer.getBoundingClientRect();
    // 输入框占掉的高度 = 容器底 - 输入框顶；再留 12px 空隙
    const occupied = hostRect.bottom - composerRect.top;
    if (hostRect.height > 0 && occupied > 0 && occupied <= hostRect.height) {
      el.style.setProperty("--toast-offset", `${Math.round(occupied) + 12}px`);
    }
  }

  (document.getElementById("app") ?? document.body).append(el);
  setTimeout(() => el.classList.add("toast--out"), 2_400);
  setTimeout(() => el.remove(), 3_000);
}
