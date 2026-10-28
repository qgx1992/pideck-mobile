/**
 * Pi 的会话历史里，思考内容有时会以 `<thinking>...</thinking>` 的形式
 * 直接嵌在消息正文（text）里，而不是单独的 thinking 字段。
 *
 * 这会导致：正文气泡里出现一大段思考文字，且不受「执行过程」折叠控制。
 * 这里把它抽出来，正文只留真正的回答，思考并入折叠区。
 *
 * 注意只处理 `<thinking>`：正文里还可能出现 <repo>、<token>、<keyfile>
 * 这类看起来像标签、其实是内容的东西，不能一起剥掉。
 */

const THINKING_PATTERN = /<thinking>([\s\S]*?)<\/thinking>/gi;
const OPEN_TAG = "<thinking>";
const CLOSE_TAG = "</thinking>";

export interface SplitResult {
  /** 去掉思考块后的正文 */
  text: string;
  /** 抽出来的思考内容（多段用空行连接） */
  thinking: string;
  /** 是否找到完整的思考块 */
  found: boolean;
}

export function splitThinking(raw: string): SplitResult {
  if (!raw || !raw.toLowerCase().includes(OPEN_TAG)) {
    return { text: raw ?? "", thinking: "", found: false };
  }

  const parts: string[] = [];
  let text = raw.replace(THINKING_PATTERN, (_match, inner: string) => {
    const trimmed = inner.trim();
    if (trimmed) parts.push(trimmed);
    return "";
  });

  // 未闭合的思考（截断的历史 / 流式中途）：整段尾部归到思考里，
  // 否则正文会留下一个裸露的 <thinking> 标签
  const unclosed = text.toLowerCase().indexOf(OPEN_TAG);
  if (unclosed >= 0) {
    const tail = text.slice(unclosed + OPEN_TAG.length).trim();
    if (tail) parts.push(tail);
    text = text.slice(0, unclosed);
  }

  return {
    // 去掉块后可能留下多余空行，压缩一下但不破坏正常段落
    text: text.replace(/\n{3,}/g, "\n\n").trim(),
    thinking: parts.join("\n\n").trim(),
    found: parts.length > 0,
  };
}

/**
 * 只剥掉**已闭合**的思考块，不动未闭合的。
 * 流式输出时 `<thinking>` 可能刚写了一半，整段剥掉会吞掉后面的正文。
 */
export function stripCompleteThinking(raw: string): string {
  if (!raw || !raw.toLowerCase().includes(OPEN_TAG)) return raw ?? "";
  let text = raw.replace(THINKING_PATTERN, "");

  // 流式时 <thinking> 可能只写了一半：把尾巴先藏起来，别把裸标签显示给用户，
  // 等 </thinking> 到齐后下一帧会正常显示后面的正文。
  const unclosed = text.toLowerCase().indexOf(OPEN_TAG);
  if (unclosed >= 0) text = text.slice(0, unclosed);

  return text.replace(/\n{3,}/g, "\n\n").trim();
}

/** 是否处于「思考块还没闭合」的状态（流式中用于暂缓渲染）。 */
export function hasUnclosedThinking(raw: string): boolean {
  if (!raw) return false;
  const opens = raw.toLowerCase().split(OPEN_TAG).length - 1;
  const closes = raw.toLowerCase().split(CLOSE_TAG).length - 1;
  return opens > closes;
}
