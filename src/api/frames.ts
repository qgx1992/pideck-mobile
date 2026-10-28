/**
 * 把会话流的原始帧收敛成可直接渲染的块序列。
 *
 * 帧协议来自 PiDeck 0.9.7 的 PiEventToUiMessageStream：
 *   start → (start-step) → text-start / text-delta* / text-end
 *                        → reasoning-start / reasoning-delta* / reasoning-end
 *                        → tool-input-start / tool-input-available / tool-output-available|error
 *   error → finish
 * 未知帧不报错：原样交给 onUnknown，保证新版本 PiDeck 不会把客户端打挂。
 */

import type { ChatBlock, Frame, RunStatus, ToolBlock } from "../types.ts";

export interface FrameApplyResult {
  /** 是否有可见变化（UI 可据此跳过重渲染） */
  changed: boolean;
  /** 本帧是否代表整轮结束 */
  finished: boolean;
  unknownFrame?: Frame;
}

export class ChatReducer {
  blocks: ChatBlock[] = [];
  status: RunStatus = "idle";
  messageId: string | null = null;
  error: string | null = null;

  reset(): void {
    this.blocks = [];
    this.status = "idle";
    this.messageId = null;
    this.error = null;
  }

  apply(frame: Frame): FrameApplyResult {
    switch (frame.type) {
      case "start": {
        // 新一轮开始：上一轮已经收口则清空重来
        if (this.status !== "running") {
          this.blocks = [];
          this.error = null;
        }
        this.messageId = String(frame.messageId ?? "");
        this.status = "running";
        return { changed: true, finished: false };
      }
      case "start-step": {
        return { changed: false, finished: false };
      }
      case "text-start": {
        this.ensureText(String(frame.id));
        this.status = "running";
        return { changed: false, finished: false };
      }
      case "text-delta": {
        const block = this.ensureText(String(frame.id));
        block.text += String(frame.delta ?? "");
        this.status = "running";
        return { changed: true, finished: false };
      }
      case "text-end": {
        const block = this.findText(String(frame.id));
        if (block) block.done = true;
        return { changed: false, finished: false };
      }
      case "reasoning-start": {
        this.ensureReasoning(String(frame.id));
        return { changed: false, finished: false };
      }
      case "reasoning-delta": {
        const block = this.ensureReasoning(String(frame.id));
        block.text += String(frame.delta ?? "");
        return { changed: true, finished: false };
      }
      case "reasoning-end": {
        const block = this.findReasoning(String(frame.id));
        if (block) block.done = true;
        return { changed: false, finished: false };
      }
      case "tool-input-start": {
        this.ensureTool(String(frame.toolCallId), String(frame.toolName ?? "tool"));
        return { changed: true, finished: false };
      }
      case "tool-input-available": {
        const block = this.ensureTool(String(frame.toolCallId), String(frame.toolName ?? "tool"));
        block.input = frame.input;
        return { changed: true, finished: false };
      }
      case "tool-output-available": {
        const block = this.findTool(String(frame.toolCallId));
        if (block) {
          block.state = "ok";
          block.output = frame.output;
        }
        return { changed: true, finished: false };
      }
      case "tool-output-error": {
        const block = this.findTool(String(frame.toolCallId));
        if (block) {
          block.state = "error";
          block.errorText = String(frame.errorText ?? "Tool failed");
        }
        return { changed: true, finished: false };
      }
      case "error": {
        this.error = String(frame.errorText ?? "Agent run failed");
        this.status = "error";
        this.closeOpenBlocks();
        return { changed: true, finished: false };
      }
      case "finish": {
        this.closeOpenBlocks();
        if (this.status !== "error") this.status = "finished";
        return { changed: true, finished: true };
      }
      default: {
        return { changed: false, finished: false, unknownFrame: frame };
      }
    }
  }

  /** 用户主动 stop 后由上层调用，把未收口的块标成结束。 */
  markAborted(): void {
    this.closeOpenBlocks();
    if (this.status === "running" || this.status === "idle") this.status = "aborted";
  }

  private closeOpenBlocks(): void {
    for (const block of this.blocks) {
      if (block.kind === "text" || block.kind === "reasoning") block.done = true;
    }
  }

  private findText(id: string) {
    return this.blocks.find((b): b is Extract<ChatBlock, { kind: "text" }> => b.kind === "text" && b.id === id);
  }

  private ensureText(id: string) {
    const existing = this.findText(id);
    if (existing) return existing;
    const block = { kind: "text" as const, id, text: "", done: false };
    this.blocks.push(block);
    return block;
  }

  private findReasoning(id: string) {
    return this.blocks.find(
      (b): b is Extract<ChatBlock, { kind: "reasoning" }> => b.kind === "reasoning" && b.id === id,
    );
  }

  private ensureReasoning(id: string) {
    const existing = this.findReasoning(id);
    if (existing) return existing;
    const block = { kind: "reasoning" as const, id, text: "", done: false };
    this.blocks.push(block);
    return block;
  }

  private findTool(toolCallId: string): ToolBlock | undefined {
    return this.blocks.find((b): b is ToolBlock => b.kind === "tool" && b.toolCallId === toolCallId);
  }

  private ensureTool(toolCallId: string, toolName: string): ToolBlock {
    const existing = this.findTool(toolCallId);
    if (existing) return existing;
    const block: ToolBlock = { kind: "tool", toolCallId, toolName, state: "running" };
    this.blocks.push(block);
    return block;
  }
}

/** CLI / 通知预览用的纯文本摘要。 */
export function blocksToPlainText(blocks: readonly ChatBlock[], options: { includeReasoning?: boolean } = {}): string {
  const parts: string[] = [];
  for (const block of blocks) {
    if (block.kind === "text") parts.push(block.text);
    else if (block.kind === "reasoning") {
      if (options.includeReasoning) parts.push(`[思考] ${block.text}`);
    } else {
      const status = block.state === "ok" ? "完成" : block.state === "error" ? "失败" : "运行中";
      parts.push(`[工具 ${block.toolName} · ${status}]`);
    }
  }
  return parts.join("\n");
}
