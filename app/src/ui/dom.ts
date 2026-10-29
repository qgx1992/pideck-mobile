/** 极小 DOM helper：避免为一个原型引入框架。 */

type Child = Node | string | null | undefined | false;

export interface Props {
  class?: string;
  text?: string;
  title?: string;
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

export function toast(message: string): void {
  const el = h("div", { class: "toast", text: message });
  // 挂在 #app 内（position: relative），避免 fixed 在部分 WebView 里定位偏移
  (document.getElementById("app") ?? document.body).append(el);
  setTimeout(() => el.classList.add("toast--out"), 2_400);
  setTimeout(() => el.remove(), 3_000);
}
