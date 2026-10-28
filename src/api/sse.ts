/**
 * 增量 SSE 解析 + 流式 UTF-8 解码。
 *
 * PiDeck 的 SSE 很朴素：
 *   - 状态流：`event: state` + 一行 data（整份 JSON 快照），15s 一次 `: ping`
 *   - 会话流：只有 `data: {...}` 帧，结束时 `data: [DONE]`，同样 15s 心跳
 * 但网络分片会把一行切断，所以必须按字节流做增量解析，不能按 chunk 假设边界。
 */

export interface SseEvent {
  /** 缺省为 "message" */
  event: string;
  data: string;
  id?: string;
  retry?: number;
}

/** AI SDK 风格的流结束标记 */
export const SSE_DONE_MARKER = "[DONE]";

/**
 * UTF-8 流式解码器。
 * React Native 的 Hermes 早期版本没有 TextDecoder，因此保留纯 JS 回退实现。
 */
export class Utf8StreamDecoder {
  private readonly decoder: TextDecoder | null;
  private pending: number[] = [];

  constructor() {
    this.decoder = typeof TextDecoder !== "undefined" ? new TextDecoder("utf-8") : null;
  }

  decode(bytes: Uint8Array): string {
    if (this.decoder) return this.decoder.decode(bytes, { stream: true });

    const all = this.pending.concat(Array.from(bytes));
    const out: string[] = [];
    let i = 0;
    while (i < all.length) {
      const b0 = all[i] as number;
      if (b0 < 0x80) {
        out.push(String.fromCharCode(b0));
        i += 1;
        continue;
      }
      let need = 0;
      let codePoint = 0;
      if (b0 >= 0xf0) {
        need = 3;
        codePoint = b0 & 0x07;
      } else if (b0 >= 0xe0) {
        need = 2;
        codePoint = b0 & 0x0f;
      } else if (b0 >= 0xc0) {
        need = 1;
        codePoint = b0 & 0x1f;
      } else {
        // 孤立续字节：丢弃，避免整个流卡死
        i += 1;
        continue;
      }
      if (i + need >= all.length) break; // 序列被切断，留到下一次
      let ok = true;
      for (let k = 1; k <= need; k += 1) {
        const b = all[i + k] as number;
        if ((b & 0xc0) !== 0x80) {
          ok = false;
          break;
        }
        codePoint = (codePoint << 6) | (b & 0x3f);
      }
      if (!ok) {
        i += 1;
        continue;
      }
      out.push(String.fromCodePoint(codePoint));
      i += need + 1;
    }
    this.pending = all.slice(i);
    return out.join("");
  }

  flush(): string {
    if (this.decoder) return this.decoder.decode();
    const rest = this.pending;
    this.pending = [];
    return rest.length > 0 ? new Utf8StreamDecoder().decode(Uint8Array.from(rest)) : "";
  }
}

/** 逐行解析 SSE；一次 push 可能产出 0..n 个完整事件。 */
export class SseParser {
  private buffer = "";
  private dataLines: string[] = [];
  private eventName = "";
  private lastId: string | undefined;
  private retry: number | undefined;
  private started = false;

  push(chunk: string): SseEvent[] {
    if (!this.started) {
      this.started = true;
      if (chunk.charCodeAt(0) === 0xfeff) chunk = chunk.slice(1); // BOM
    }
    this.buffer += chunk;

    const events: SseEvent[] = [];
    for (;;) {
      const match = /\r\n|\r|\n/.exec(this.buffer);
      if (!match) break;
      // 末尾孤立的 "\r" 可能是被切开的 CRLF，等下一个分片再判定
      if (match[0] === "\r" && match.index === this.buffer.length - 1) break;

      const line = this.buffer.slice(0, match.index);
      this.buffer = this.buffer.slice(match.index + match[0].length);

      const event = this.consumeLine(line);
      if (event) events.push(event);
    }
    return events;
  }

  /** 连接正常结束但没有尾随空行时，把残留数据吐出来。 */
  flush(): SseEvent[] {
    const events: SseEvent[] = [];
    if (this.buffer.length > 0) {
      const event = this.consumeLine(this.buffer);
      this.buffer = "";
      if (event) events.push(event);
    }
    const tail = this.dispatch();
    if (tail) events.push(tail);
    return events;
  }

  private consumeLine(line: string): SseEvent | null {
    if (line === "") return this.dispatch();
    if (line.startsWith(":")) return null; // 注释 / 心跳

    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);

    switch (field) {
      case "data":
        this.dataLines.push(value);
        break;
      case "event":
        this.eventName = value;
        break;
      case "id":
        if (!value.includes("\0")) this.lastId = value;
        break;
      case "retry": {
        const parsed = Number.parseInt(value, 10);
        if (Number.isFinite(parsed)) this.retry = parsed;
        break;
      }
      default:
        break;
    }
    return null;
  }

  private dispatch(): SseEvent | null {
    if (this.dataLines.length === 0) {
      this.eventName = "";
      this.retry = undefined;
      return null;
    }
    const data = this.dataLines.join("\n");
    const event: SseEvent = { event: this.eventName || "message", data };
    if (this.lastId !== undefined) event.id = this.lastId;
    if (this.retry !== undefined) event.retry = this.retry;

    this.dataLines = [];
    this.eventName = "";
    this.retry = undefined;
    return event;
  }
}

/** 便利函数：一次性文本转事件（测试与回放用）。 */
export function parseSseText(text: string): SseEvent[] {
  const parser = new SseParser();
  return [...parser.push(text), ...parser.flush()];
}
