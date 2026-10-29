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
import type { StreamingBehavior } from "./api/client.ts";
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
  /**
   * 多久没收到任何字节（含心跳）就判定连接假死并强制重连，默认 50s。
   *
   * 服务端每 15s 发一次 `: ping`，所以 50s 无字节基本等于流已死。
   * 典型场景：App 切后台被系统冻结，socket 既不来数据也不报错，
   * 不主动断开会一直挂在半开连接上，永远等不到 finish。
   */
  stallMs?: number;
  autoReconnect?: boolean;
}

export class SessionChannel {
  readonly sessionId: string;
  readonly reducer = new ChatReducer();
  private readonly client: PiDeckClient;
  private readonly options: SessionChannelOptions;
  /** 当前这一轮连接的控制器（重连时换新的，不影响整个 loop） */
  private iteration: AbortController | null = null;
  private readonly stallMs: number;
  private status: ChannelStatus = "idle";
  private running = false;
  private everConnected = false;
  private pendingSend: Promise<unknown> | null = null;

  constructor(options: SessionChannelOptions) {
    this.options = options;
    this.client = options.client;
    this.sessionId = options.sessionId;
    this.stallMs = options.stallMs ?? 50_000;
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
    const backoff = new Backoff({ baseMs: 800, maxMs: 15_000 });
    const autoReconnect = this.options.autoReconnect !== false;

    while (this.running) {
      const iteration = new AbortController();
      this.iteration = iteration;
      let lastActivity = Date.now();
      let stalled = false;

      // 看门狗：服务端心跳 15s 一次，超过 stallMs 没有任何字节就判定假死
      const watchdog = setInterval(() => {
        if (Date.now() - lastActivity <= this.stallMs) return;
        stalled = true;
        iteration.abort();
      }, Math.min(5_000, Math.max(50, Math.round(this.stallMs / 5))));

      try {
        this.setStatus(backoff.attempts === 0 ? "connecting" : "reconnecting");
        await this.client.streamSession(this.sessionId, {
          signal: iteration.signal,
          onEvent: () => undefined,
          onActivity: () => {
            lastActivity = Date.now();
          },
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
        if (!this.running) break;
        if (isAuthError(error)) {
          this.setStatus("auth-required");
          this.options.onError?.(error);
          break;
        }
        // 假死 / 主动重连触发的 abort：不算错误，安静重连
        if (!iteration.signal.aborted) this.options.onError?.(error);
      } finally {
        clearInterval(watchdog);
        if (this.iteration === iteration) this.iteration = null;
      }

      if (!this.running) break;
      if (stalled) {
        // 静默断流：重置退避，立刻重连（后台恢复后要尽快追上）
        backoff.reset();
        this.setStatus("reconnecting");
      } else if (!autoReconnect) {
        break;
      }
      await sleep(stalled ? 0 : backoff.nextDelay());
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
    options: {
      images?: ImageAttachment[];
      requestId?: string;
      /** 运行中：steer 插话 / followUp 排队（空闲时无意义） */
      streamingBehavior?: StreamingBehavior;
      extra?: Record<string, unknown>;
    } = {},
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
      ...(options.streamingBehavior ? { streamingBehavior: options.streamingBehavior } : {}),
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

  /**
   * 强制重连（保持 running）。
   * 用在 App 从后台回到前台的场景：此时流多半已被系统冻死，但没有报错。
   */
  forceReconnect(): void {
    if (!this.running) {
      this.start();
      return;
    }
    this.iteration?.abort();
  }

  close(): void {
    this.running = false;
    this.iteration?.abort();
    this.iteration = null;
    this.setStatus("closed");
  }
}

function createRequestId(): string {
  const cryptoObj = globalThis.crypto as Crypto | undefined;
  if (cryptoObj?.randomUUID) return cryptoObj.randomUUID();
  return `req_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 10)}`;
}
