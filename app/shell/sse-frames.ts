/**
 * SSE 帧解析（EventSource 语义用得到的那部分）。
 *
 * 单独成文件是为了能脱离浏览器跑单测：native-sse / event-source 都会碰
 * `window` 与 Capacitor 插件，解析逻辑不该被它们拖着一起失去可测性。
 */

export interface ParsedEvent {
	type: string;
	data: string;
	id: string;
}

export class SseFrameParser {
	private buffer = "";
	private dataLines: string[] = [];
	private eventType = "";
	private lastId = "";

	push(chunk: string): ParsedEvent[] {
		this.buffer += chunk;
		const events: ParsedEvent[] = [];
		for (;;) {
			const match = /\r\n\r\n|\n\n|\r\r/.exec(this.buffer);
			if (!match) break;
			const raw = this.buffer.slice(0, match.index);
			this.buffer = this.buffer.slice(match.index + match[0].length);
			const parsed = this.parseBlock(raw);
			if (parsed) events.push(parsed);
		}
		return events;
	}

	private parseBlock(raw: string): ParsedEvent | null {
		this.dataLines = [];
		this.eventType = "";
		for (const line of raw.split(/\r\n|\n|\r/)) {
			if (!line || line.startsWith(":")) continue;
			const colon = line.indexOf(":");
			const field = colon === -1 ? line : line.slice(0, colon);
			let value = colon === -1 ? "" : line.slice(colon + 1);
			if (value.startsWith(" ")) value = value.slice(1);
			if (field === "data") this.dataLines.push(value);
			else if (field === "event") this.eventType = value;
			else if (field === "id") this.lastId = value;
		}
		if (this.dataLines.length === 0) return null;
		return { type: this.eventType || "message", data: this.dataLines.join("\n"), id: this.lastId };
	}
}
