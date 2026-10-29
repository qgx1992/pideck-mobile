/**
 * 全局状态仓：优先吃 /api/events 的 SSE 快照，不可流式时退化为 ETag 轮询。
 *
 * 设计要点：
 *   - 服务端每 400ms 去抖推一次快照，另有 5s 兜底 tick；客户端不需要高频轮询。
 *   - SSE 断线没有 Last-Event-ID，重连后必须重新拉 /api/state 对账。
 *   - 401 是终态：停止重连，交给 UI 引导重新配对。
 */

import { Backoff, sleep } from "../api/backoff.ts";
import { PiDeckClient } from "../api/client.ts";
import { emptyState, normalizeState } from "../api/client.ts";
import { describeError, isAuthError } from "../api/errors.ts";
import type { PiDeckState, PendingUiRequest, Project, SessionSummary } from "../types.ts";
import { findTarget } from "../runtime/target.ts";

export type StoreStatus = "idle" | "connecting" | "live" | "polling" | "reconnecting" | "auth-required" | "closed";

export interface StateStoreOptions {
  client: PiDeckClient;
  /** 不支持 SSE 时的轮询间隔，默认 5s */
  pollMs?: number;
  /**
   * SSE 正常时的兜底轮询间隔，默认 30s（仅在前台可见时执行）。
   *
   * 为什么要兜底：PiDeck 的 /api/events 是「变化驱动」的，而它的 revision 判据
   * 只包含运行时状态与待答卡片——**会话列表变化（新建/删除/改名）不在其中**，
   * 服务端在 revision 未变时直接 return，连 5s tick 的全量比对都不会执行。
   * 结果就是列表类变更永远不会主动推送，只能靠客户端定时拉一次。
   *
   * 为什么省不掉流量：PiDeck 的 /api/state 虽然算了 ETag，但注入的 sendJson
   * 只转发两个参数，`{ etag }` 被丢掉，响应里从来没有 ETag 头，也就永远拿不到 304。
   * 所以每次都是全量（当前约 58 KB）；客户端做的是「前台 + 30s」限速，
   * 后台不可见时完全不轮询。
   */
  fallbackPollMs?: number;
  onStatus?: (status: StoreStatus) => void;
  onError?: (error: unknown) => void;
  /** 首次快照到达 */
  onReady?: (state: PiDeckState) => void;
}

type Listener = (state: PiDeckState) => void;

export class StateStore {
  readonly client: PiDeckClient;
  private current: PiDeckState = emptyState();
  private etag: string | null = null;
  private listeners = new Set<Listener>();
  private statusListeners = new Set<(status: StoreStatus) => void>();
  private controller: AbortController | null = null;
  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private pollIntervalMs = 0;
  private visibilityHandler: (() => void) | null = null;
  private refreshing: Promise<void> | null = null;
  private running = false;
  private status: StoreStatus = "idle";
  private readonly pollMs: number;
  private readonly fallbackPollMs: number;
  private readonly options: StateStoreOptions;

  constructor(options: StateStoreOptions) {
    this.options = options;
    this.client = options.client;
    this.pollMs = options.pollMs ?? 5_000;
    this.fallbackPollMs = options.fallbackPollMs ?? 30_000;
  }

  get state(): PiDeckState {
    return this.current;
  }

  getStatus(): StoreStatus {
    return this.status;
  }

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  onStatus(listener: (status: StoreStatus) => void): () => void {
    this.statusListeners.add(listener);
    return () => this.statusListeners.delete(listener);
  }

  /* ---------------------------- 选择器 ---------------------------- */

  projects(): Project[] {
    return this.current.projects;
  }

  sessionsOf(projectId: string): SessionSummary[] {
    return this.current.sessions
      .filter((session) => session.projectId === projectId)
      .sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0));
  }

  session(sessionId: string): SessionSummary | undefined {
    return this.current.sessions.find((session) => session.id === sessionId);
  }

  targetOf(sessionId: string) {
    return findTarget(this.current, sessionId);
  }

  pendingFor(sessionId?: string): PendingUiRequest[] {
    if (!sessionId) return this.current.pendingUiRequests;
    return this.current.pendingUiRequests.filter((item) => item.sessionId === sessionId);
  }

  /* ---------------------------- 拉取 ---------------------------- */

  /** 同一时刻只发一个 /api/state 请求（后台恢复时可能被多处同时触发）。 */
  async refresh(): Promise<void> {
    if (this.refreshing) return this.refreshing;
    this.refreshing = this.doRefresh().finally(() => {
      this.refreshing = null;
    });
    return this.refreshing;
  }

  private async doRefresh(): Promise<void> {
    const response = await this.client.getState(this.etag);
    if (response.notModified) return;
    this.etag = response.etag;
    this.replace(response.state);
  }

  private replace(next: PiDeckState): void {
    this.current = next;
    for (const listener of this.listeners) {
      try {
        listener(next);
      } catch (error) {
        this.options.onError?.(error);
      }
    }
  }

  private setStatus(next: StoreStatus): void {
    if (this.status === next) return;
    this.status = next;
    this.options.onStatus?.(next);
    for (const listener of this.statusListeners) listener(next);
  }

  /* ---------------------------- 生命周期 ---------------------------- */

  start(): void {
    if (this.running) return;
    this.running = true;
    this.watchVisibility();
    void this.loop();
  }

  /** 回到前台立即对账一次（SSE 在后台通常已被系统挂起）。 */
  private watchVisibility(): void {
    if (typeof document === "undefined" || this.visibilityHandler) return;
    this.visibilityHandler = () => {
      if (document.visibilityState !== "visible" || !this.running) return;
      void this.refresh().catch((error: unknown) => this.options.onError?.(error));
    };
    document.addEventListener("visibilitychange", this.visibilityHandler);
  }

  private detachVisibility(): void {
    if (!this.visibilityHandler || typeof document === "undefined") return;
    document.removeEventListener("visibilitychange", this.visibilityHandler);
    this.visibilityHandler = null;
  }

  private async loop(): Promise<void> {
    const controller = new AbortController();
    this.controller = controller;
    const backoff = new Backoff({ baseMs: 1_000, maxMs: 15_000 });
    let consecutiveFailures = 0;
    let firstReady = false;

    while (this.running && !controller.signal.aborted) {
      try {
        this.setStatus(backoff.attempts === 0 ? "connecting" : "reconnecting");
        // 每次（重）连之前先对账，避免断线期间漏掉的变更
        await this.refresh().catch((error: unknown) => {
          if (isAuthError(error)) throw error;
          this.options.onError?.(error);
        });

        await this.client.streamState({
          signal: controller.signal,
          onEvent: () => undefined,
          onState: (state) => {
            this.etag = null;
            this.replace(state);
            if (!firstReady) {
              firstReady = true;
              this.options.onReady?.(state);
            }
            this.setStatus("live");
            consecutiveFailures = 0;
            backoff.reset();
            this.startPolling(controller.signal, this.fallbackPollMs);
          },
          onOpen: () => {
            this.setStatus("live");
            // SSE 只推「运行时/pendingUi」相关变化，会话列表得靠轮询兜底
            this.startPolling(controller.signal, this.fallbackPollMs);
          },
        });
        // 服务端正常收尾：退避后重连
        consecutiveFailures = 0;
      } catch (error) {
        if (controller.signal.aborted || !this.running) break;
        if (isAuthError(error)) {
          this.setStatus("auth-required");
          this.options.onError?.(error);
          break;
        }
        consecutiveFailures += 1;
        this.options.onError?.(error);
        // 环境根本不支持流式（RN 默认 fetch）：退化轮询
        if (isStreamingUnsupported(error) || consecutiveFailures >= 3) {
          this.startPolling(controller.signal);
          this.setStatus("polling");
          return;
        }
      }
      if (!this.running || controller.signal.aborted) break;
      await sleep(backoff.nextDelay(), controller.signal);
    }
    this.setStatus(this.status === "auth-required" ? "auth-required" : "closed");
  }

  /**
   * 启动轮询。
   * intervalMs 不同会重建定时器：SSE 不通时用 5s 快速轮询，SSE 正常时退到 25s 兜底。
   */
  private startPolling(signal: AbortSignal, intervalMs: number = this.pollMs): void {
    if (this.pollTimer && this.pollIntervalMs === intervalMs) return;
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
    this.pollIntervalMs = intervalMs;
    this.pollTimer = setInterval(() => {
      if (signal.aborted || !this.running) return;
      // 后台标签页 / 息屏时不做无谓的全量拉取，回到前台会立即补一次
      if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
      void this.refresh().catch((error: unknown) => this.options.onError?.(error));
    }, intervalMs);
  }

  stop(): void {
    this.running = false;
    this.controller?.abort();
    this.controller = null;
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
    this.pollIntervalMs = 0;
    this.detachVisibility();
    this.setStatus("closed");
  }
}

function isStreamingUnsupported(error: unknown): boolean {
  // 运行时不支持流式响应体（RN 默认 fetch）会抛 protocol 类错误，此时改走轮询
  return !!error && typeof error === "object" && (error as { kind?: unknown }).kind === "protocol";
}

export { describeError, normalizeState };
