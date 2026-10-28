/**
 * 单个会话的常驻通道：连流 → 收帧 → 归一化 → 断线重连。
 *
 * 服务端行为（已核实）：
 *   - 连接 /api/sessions/:id/stream 会**回放当前轮**已产出的帧，然后继续推增量；
 *   - 一轮结束推 `finish` + `data: [DONE]`，但连接不关闭（keepAlive）；
 *   - 不支持 Last-Event-ID，所以重连后要么靠回放、要么重新拉历史消息对账。
 * 因此这里不做「断线期间自己拼文本」，只在重连成功后由 UI 拉一次历史，
 * 避免同一段正文被回放 + 增量拼两遍。
 */

import { Backoff, sleep } from "./api/backoff.ts";
import { ChatReducer } from "./api/frames.ts";
import { PiDeckClient } from "./api/client.ts";
import { isAuthError } from "./api/errors.ts";
import type { ImageAttachment, RuntimeTarget } from "./types.ts";

export type ChannelStatus =
  | "idle"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "closed"
  | "auth-required";

export interface SessionChannelOptions {
  client: PiDeckClient;
  sessionId: string;
  /** 块/状态变化（含每段 delta） */
  onUpdate?: (channel: SessionChannel) => void;
  onStatus?: (status: ChannelStatus) => void;
  /** 断线、鉴权失败等需要提示的错误 */
  onError?: (error: unknown) => void;
  /** 重连成功（期间可能漏帧，建议此时重拉一次历史） */
  onRecovered?: () => void;
  autoReconnect?: boolean;
}

export class SessionChannel {
  readonly sessionId: string;
  readonly reducer = new ChatReducer();
  private readonly client: PiDeckClient;
  private readonly options: SessionChannelOptions;
  private controller: AbortController | null = null;
  private status: ChannelStatus = "idle";
  private running = false;
  private everConnected = false;
  private pendingSend: Promise<unknown> | null = null;

  constructor(options: SessionChannelOptions) {
    this.options = options;
    this.client = options.client;
    this.sessionId = options.sessionId;
  }

  getStatus(): ChannelStatus {
    return this.status;
  }

  private setStatus(next: ChannelStatus): void {
    if (this.status === next) return;
    this.status = next;
    this.options.onStatus?.(next);
  }

  private touch(): void {
    this.options.onUpdate?.(this);
  }

  /* ---------------------------- 连接 ---------------------------- */

  start(): void {
    if (this.running) return;
    this.running = true;
    void this.loop();
  }

  private async loop(): Promise<void> {
    const controller = new AbortController();
    this.controller = controller;
    const backoff = new Backoff({ baseMs: 800, maxMs: 15_000 });
    const autoReconnect = this.options.autoReconnect !== false;

    while (this.running && !controller.signal.aborted) {
      try {
        this.setStatus(backoff.attempts === 0 ? "connecting" : "reconnecting");
        await this.client.streamSession(this.sessionId, {
          signal: controller.signal,
          onEvent: () => undefined,
          onOpen: () => {
            const recovered = this.everConnected;
            this.everConnected = true;
            backoff.reset();
            this.setStatus("connected");
            if (recovered) this.options.onRecovered?.();
          },
          onFrame: (frame) => {
            const before = this.reducer.status;
            const result = this.reducer.apply(frame);
            // 状态翻转（idle→running→finished）即使内容没变也要通知 UI，
            // 否则「发送/停止」这类依赖状态的按钮不会跟着切。
            if (result.changed || this.reducer.status !== before) this.touch();
            if (!result.unknownFrame) return;
            // 未知帧只上报，不影响连接
            this.options.onError?.(new Error(`未知帧类型：${result.unknownFrame.type}`));
          },
        });
        if (!autoReconnect) break;
      } catch (error) {
        if (controller.signal.aborted || !this.running) break;
        if (isAuthError(error)) {
          this.setStatus("auth-required");
          this.options.onError?.(error);
          break;
        }
        this.options.onError?.(error);
        if (!autoReconnect) {
          this.setStatus("closed");
          break;
        }
      }
      if (!this.running || controller.signal.aborted) break;
      await sleep(backoff.nextDelay(), controller.signal);
    }
    if (this.status !== "auth-required") this.setStatus("closed");
  }

  /* ---------------------------- 发送 ---------------------------- */

  /**
   * 发一条消息。返回投递结果；正文走 reducer 的流式更新。
   * requestId 用于服务端的投递去重（同 id 重复 POST 不会重复发送）。
   */
  async send(
    text: string,
    options: { images?: ImageAttachment[]; requestId?: string; extra?: Record<string, unknown> } = {},
  ): Promise<unknown> {
    if (!this.running) this.start();
    await this.waitConnected(4_000);

    // 新一轮：上一轮已收口则清空；正在跑就保留（服务端会发 start 帧）
    if (this.reducer.status !== "running") this.reducer.reset();

    const requestId = options.requestId ?? createRequestId();
    const promise = this.client.sendPrompt(this.sessionId, {
      requestId,
      message: text,
      ...(options.images ? { images: options.images } : {}),
      ...(options.extra ? { extra: options.extra } : {}),
    });
    this.pendingSend = promise;
    try {
      return await promise;
    } finally {
      if (this.pendingSend === promise) this.pendingSend = null;
    }
  }

  /** 等首帧连接就绪；超时不算失败（投递后靠回放追平）。 */
  private async waitConnected(timeoutMs: number): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      if (this.status === "connected") return;
      if (this.status === "auth-required" || this.status === "closed") return;
      await sleep(50);
    }
  }

  /** 停止本轮生成（需要当前 runtime target）。 */
  async stopRun(target: RuntimeTarget): Promise<void> {
    await this.client.abort(this.sessionId, target);
    this.reducer.markAborted();
    this.touch();
  }

  close(): void {
    this.running = false;
    this.controller?.abort();
    this.controller = null;
    this.setStatus("closed");
  }
}

function createRequestId(): string {
  const cryptoObj = globalThis.crypto as Crypto | undefined;
  if (cryptoObj?.randomUUID) return cryptoObj.randomUUID();
  return `req_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
}
