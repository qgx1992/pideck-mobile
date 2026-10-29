/**
 * PiDeck Web 服务客户端（零依赖，可用于 Node / React Native / Capacitor）。
 *
 * 设计约束（均已对照 0.9.7 实机核实）：
 *   1. 鉴权：非 GET 用 `Authorization: Bearer`，GET 额外把 `?token=` 附上（SSE 只能用 query）。
 *   2. `/api/*` 没有版本前缀，也没有 schema 文档 —— 未核实的端点一律走 `requestJson` 透传。
 *   3. 运行时操作必须带 `{sessionId, agentId, runtimeGeneration}`，过期会 409。
 *   4. 网络失败与 401 要能被上层区分（决定「重连」还是「重新配对」）。
 */

import { ChatReducer } from "./frames.ts";
import {
  PiDeckAuthError,
  PiDeckError,
  PiDeckHttpError,
  PiDeckOfflineError,
  PiDeckRuntimeError,
} from "./errors.ts";
import { SSE_DONE_MARKER, SseParser, Utf8StreamDecoder } from "./sse.ts";
import type { SseEvent } from "./sse.ts";
import type {
  Frame,
  HealthInfo,
  ImageAttachment,
  MessagePage,
  MessageWindow,
  ModelInfo,
  PiDeckState,
  PendingUiRequest,
  Project,
  RuntimeTarget,
  SessionSummary,
  CommandInfo,
  FileNode,
  WireMessage,
} from "../types.ts";

export type FetchLike = typeof fetch;

export interface PiDeckClientOptions {
  /** 例如 http://192.168.1.20:8765 或 https://my-pc.tailnet.ts.net */
  baseUrl: string;
  /** 桌面端「Web 服务」里的访问令牌 */
  token: string;
  fetchImpl?: FetchLike;
  /** 普通 JSON 请求超时，默认 20s；SSE 不受此限制 */
  timeoutMs?: number;
}

export interface StateResponse {
  state: PiDeckState;
  etag: string | null;
  notModified: boolean;
}

export interface PromptInput {
  requestId: string;
  message: string;
  images?: ImageAttachment[];
  /** 透传字段（如 agentMessage），未核实的键原样带给服务端 */
  extra?: Record<string, unknown>;
}

export interface SseOptions {
  signal?: AbortSignal;
  onEvent: (event: SseEvent) => void;
  /** 收到响应头（连接已建立）时回调 */
  onOpen?: () => void;
  /**
   * 收到任意字节时回调（含 SSE 注释心跳 `: ping`）。
   * 用于检测「连接假死」：后台被系统冻结后 socket 不报错也不来数据。
   */
  onActivity?: () => void;
  onClose?: () => void;
}

export type RuntimeAction =
  | "activate"
  | "stop"
  | "abort"
  | "restart"
  | "compact"
  | "state"
  | "commands"
  | "models"
  | "model"
  | "thinking"
  | "permission"
  | "clone"
  | "fork"
  | "get-fork-messages"
  | "edit-message"
  | "delete-message"
  | "prepare-resend"
  | "export-html"
  | "rewind-list"
  | "rewind-diff"
  | "rewind-restore";

export function normalizeBaseUrl(input: string): string {
  let value = input.trim();
  if (!value) throw new PiDeckError({ kind: "protocol", message: "baseUrl is empty" });
  if (!/^https?:\/\//i.test(value)) value = `http://${value}`;
  value = value.replace(/\/+$/, "");
  // 允许直接贴带 /?token= 的完整链接
  const url = new URL(value);
  url.search = "";
  url.hash = "";
  url.pathname = url.pathname.replace(/\/+$/, "");
  return url.toString().replace(/\/+$/, "");
}

export class PiDeckClient {
  readonly baseUrl: string;
  readonly token: string;
  private readonly fetchImpl: FetchLike;
  private readonly timeoutMs: number;

  constructor(options: PiDeckClientOptions) {
    this.baseUrl = normalizeBaseUrl(options.baseUrl);
    this.token = options.token.trim();
    const impl = options.fetchImpl ?? globalThis.fetch;
    if (typeof impl !== "function") {
      throw new PiDeckError({ kind: "protocol", message: "global fetch is not available; inject fetchImpl" });
    }
    this.fetchImpl = impl;
    this.timeoutMs = options.timeoutMs ?? 20_000;
  }

  /** 换令牌后复用同一实例（避免重建时丢掉 fetchImpl 之外的配置） */
  withToken(token: string): PiDeckClient {
    return new PiDeckClient({
      baseUrl: this.baseUrl,
      token,
      fetchImpl: this.fetchImpl,
      timeoutMs: this.timeoutMs,
    });
  }

  /* ------------------------------------------------------------------ */
  /* 基础请求                                                            */
  /* ------------------------------------------------------------------ */

  private buildUrl(path: string, query?: Record<string, string | number | boolean | undefined>): string {
    const url = new URL(`${this.baseUrl}${path.startsWith("/") ? path : `/${path}`}`);
    for (const [key, value] of Object.entries(query ?? {})) {
      if (value === undefined) continue;
      url.searchParams.set(key, String(value));
    }
    return url.toString();
  }

  /** GET 请求带上 query token：SSE / EventSource / 跨域场景只有这条路可用。 */
  private withQueryToken(rawUrl: string): string {
    if (!this.token) return rawUrl;
    const url = new URL(rawUrl);
    if (!url.searchParams.has("token")) url.searchParams.set("token", this.token);
    return url.toString();
  }

  /**
   * 请求头。
   *
   * `authorization` 只在需要时加：PiDeck 的 CORS 预检只放行 `content-type`，
   * 一旦 GET 带上 Authorization 就从「简单请求」升级成需要预检的请求，
   * 预检失败 → 浏览器/WebView 直接抛 `Failed to fetch`。
   * 所以 GET 一律只用 `?token=` 鉴权（服务端支持），非 GET 才用 Bearer。
   */
  private headers(extra?: Record<string, string>, options: { withAuth?: boolean } = {}): Record<string, string> {
    const headers: Record<string, string> = { accept: "application/json", ...extra };
    if (options.withAuth !== false && this.token) headers.authorization = `Bearer ${this.token}`;
    return headers;
  }

  private async fetchWithTimeout(url: string, init: RequestInit, timeoutMs: number): Promise<Response> {
    const controller = new AbortController();
    const outer = init.signal;
    let abortedByOuter = false;
    const onOuterAbort = () => {
      abortedByOuter = true;
      controller.abort();
    };
    if (outer) {
      if (outer.aborted) controller.abort();
      else outer.addEventListener("abort", onOuterAbort, { once: true });
    }
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await this.fetchImpl(url, { ...init, signal: controller.signal });
    } catch (error) {
      if (abortedByOuter) throw error;
      const reason = error instanceof Error ? error.message : String(error);
      throw new PiDeckOfflineError({
        message: `无法连接 PiDeck（${new URL(url).host}）：${reason}`,
        cause: error,
      });
    } finally {
      clearTimeout(timer);
      if (outer) outer.removeEventListener("abort", onOuterAbort);
    }
  }

  private async toError(response: Response, fallback: string): Promise<PiDeckError> {
    let code: string | undefined;
    let message = fallback;
    let details: unknown;
    try {
      const body = (await response.json()) as { code?: unknown; error?: unknown; params?: unknown };
      if (typeof body.code === "string") code = body.code;
      if (typeof body.error === "string" && body.error) message = body.error;
      if (body.params !== undefined) details = body.params;
    } catch {
      /* 非 JSON 错误体：保留兜底文案 */
    }

    if (response.status === 401) {
      return new PiDeckAuthError({ message: message || "访问令牌无效或已过期", status: 401, code, details });
    }
    if (response.status === 409) {
      return new PiDeckRuntimeError({ message: message || "会话运行时已变更，请刷新后重试", status: 409, code, details });
    }
    return new PiDeckHttpError({ message, status: response.status, code, details });
  }

  async requestJson<T>(
    path: string,
    options: {
      method?: "GET" | "POST";
      query?: Record<string, string | number | boolean | undefined>;
      body?: unknown;
      headers?: Record<string, string>;
      signal?: AbortSignal;
      timeoutMs?: number;
    } = {},
  ): Promise<T> {
    const method = options.method ?? "GET";
    let url = this.buildUrl(path, options.query);
    if (method === "GET") url = this.withQueryToken(url);

    const init: RequestInit = {
      method,
      headers: this.headers(
        {
          ...(options.body !== undefined ? { "content-type": "application/json" } : {}),
          ...(options.headers ?? {}),
        },
        // GET 走 query token，不加 Authorization，保持简单请求不触发预检
        { withAuth: method !== "GET" },
      ),
    };
    if (options.body !== undefined) init.body = JSON.stringify(options.body);

    const response = await this.fetchWithTimeout(url, init, options.timeoutMs ?? this.timeoutMs);
    if (!response.ok) throw await this.toError(response, `PiDeck 请求失败：${method} ${path}`);
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }

  /** 打开一条 SSE 流，逐事件回调；返回的 Promise 在流结束时 resolve。 */
  async streamSse(path: string, options: SseOptions & { query?: Record<string, string | number | undefined> }): Promise<void> {
    const url = this.withQueryToken(this.buildUrl(path, options.query));
    const init: RequestInit = {
      method: "GET",
      headers: this.headers({ accept: "text/event-stream" }, { withAuth: false }),
      // SSE 不设超时：由调用方用 signal 控制
      signal: options.signal,
    };

    const response = await this.fetchImpl(url, init).catch((error: unknown) => {
      const reason = error instanceof Error ? error.message : String(error);
      throw new PiDeckOfflineError({ message: `无法连接 SSE（${new URL(url).host}）：${reason}`, cause: error });
    });

    if (!response.ok) throw await this.toError(response, `SSE 连接失败：${path}`);
    if (!response.body) {
      throw new PiDeckError({
        kind: "protocol",
        message: "当前运行时不支持流式响应体（RN 需用 expo/fetch 或 react-native-sse 替代）",
      });
    }

    options.onOpen?.();

    const reader = response.body.getReader();
    const decoder = new Utf8StreamDecoder();
    const parser = new SseParser();
    const onAbort = () => {
      void reader.cancel().catch(() => undefined);
    };
    options.signal?.addEventListener("abort", onAbort, { once: true });

    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        if (!value) continue;
        options.onActivity?.();
        for (const event of parser.push(decoder.decode(value))) options.onEvent(event);
      }
      for (const event of parser.flush()) options.onEvent(event);
    } catch (error) {
      if (options.signal?.aborted) return;
      const reason = error instanceof Error ? error.message : String(error);
      throw new PiDeckOfflineError({ message: `SSE 中断：${reason}`, cause: error });
    } finally {
      options.signal?.removeEventListener("abort", onAbort);
      options.onClose?.();
    }
  }

  /* ------------------------------------------------------------------ */
  /* 探活与状态                                                          */
  /* ------------------------------------------------------------------ */

  /** 免鉴权。用于配对时确认对面确实是 PiDeck。 */
  async health(): Promise<HealthInfo> {
    return this.requestJson<HealthInfo>("/api/health");
  }

  /**
   * 读取全量状态。
   *
   * 传 etag 时会带 `If-None-Match`，命中返回 `notModified: true`（省掉几十 KB 传输）。
   *
   * 注意：`If-None-Match` 不是 CORS 安全请求头，浏览器里跨域会触发预检而被拦。
   * 之所以现在能用，是因为非 SSE 请求都走 CapacitorHttp 原生栈（无 CORS）；
   * 浏览器开发模式走 Vite 的 /api 同源代理，也不触发预检。
   * 如果哪天有人把这里接回 WebView 直连，必须把 etag 去掉。
   */
  async getState(etag?: string | null): Promise<StateResponse> {
    const url = this.withQueryToken(this.buildUrl("/api/state"));
    const headers = etag ? { "if-none-match": etag } : undefined;
    const response = await this.fetchWithTimeout(
      url,
      { method: "GET", headers: this.headers(headers, { withAuth: false }) },
      this.timeoutMs,
    );
    if (response.status === 304) {
      return { state: emptyState(), etag: etag ?? null, notModified: true };
    }
    if (!response.ok) throw await this.toError(response, "读取状态失败");
    const state = normalizeState((await response.json()) as unknown);
    return { state, etag: response.headers.get("etag"), notModified: false };
  }

  async listModels(force = false): Promise<ModelInfo[]> {
    const body = await this.requestJson<{ models?: ModelInfo[] }>("/api/models", {
      query: force ? { force: 1 } : {},
    });
    return Array.isArray(body.models) ? body.models : [];
  }

  async listProjects(): Promise<Project[]> {
    const state = await this.getState();
    return state.state.projects;
  }

  async createProject(path: string): Promise<Project> {
    const body = await this.requestJson<{ project: Project }>("/api/projects", {
      method: "POST",
      body: { path },
    });
    return body.project;
  }

  async deleteProject(projectId: string): Promise<boolean> {
    const body = await this.requestJson<{ deleted: boolean }>(
      `/api/projects/${encodeURIComponent(projectId)}/delete`,
      { method: "POST", body: {} },
    );
    return Boolean(body.deleted);
  }

  /* ------------------------------------------------------------------ */
  /* 会话                                                               */
  /* ------------------------------------------------------------------ */

  async listSessions(projectId: string): Promise<SessionSummary[]> {
    const body = await this.requestJson<{ sessions?: SessionSummary[] }>(
      `/api/projects/${encodeURIComponent(projectId)}/sessions`,
    );
    return Array.isArray(body.sessions) ? body.sessions : [];
  }

  async listCatalogSessions(projectId: string): Promise<SessionSummary[]> {
    const body = await this.requestJson<{ sessions?: SessionSummary[] }>(
      `/api/projects/${encodeURIComponent(projectId)}/sessions/catalog`,
    );
    return Array.isArray(body.sessions) ? body.sessions : [];
  }

  async listRuntimes(): Promise<PiDeckState["runtimes"]> {
    const body = await this.requestJson<{ runtimes?: PiDeckState["runtimes"] }>("/api/sessions/runtimes");
    return Array.isArray(body.runtimes) ? body.runtimes : [];
  }

  async createSession(input: {
    projectId: string;
    title?: string;
    model?: { provider: string; modelId: string; modelName?: string };
    thinkingLevel?: string;
  }): Promise<SessionSummary> {
    const body = await this.requestJson<{ session: SessionSummary }>("/api/sessions", {
      method: "POST",
      body: input,
    });
    return body.session;
  }

  async getMessages(sessionId: string): Promise<MessageWindow> {
    return this.requestJson<MessageWindow>(`/api/sessions/${encodeURIComponent(sessionId)}/messages`);
  }

  async getMessagePage(
    sessionId: string,
    options: { before?: number; pageSize?: number } = {},
  ): Promise<MessagePage> {
    return this.requestJson<MessagePage>(`/api/sessions/${encodeURIComponent(sessionId)}/messages/page`, {
      query: { before: options.before, pageSize: options.pageSize },
    });
  }

  async getTodo(sessionId: string): Promise<unknown> {
    const body = await this.requestJson<{ todo: unknown }>(`/api/sessions/${encodeURIComponent(sessionId)}/todo`);
    return body.todo ?? null;
  }

  /** 会话内全文检索；索引未就绪时服务端返回 {result:null}。 */
  async searchSession(sessionId: string, query: string): Promise<unknown> {
    const body = await this.requestJson<{ result: unknown }>(
      `/api/sessions/${encodeURIComponent(sessionId)}/search`,
      { query: { q: query } },
    );
    return body.result ?? null;
  }

  /** update | delete | copy | export-html */
  async sessionRecordAction(
    sessionId: string,
    action: "update" | "delete" | "copy" | "export-html",
    patch: Record<string, unknown> = {},
  ): Promise<unknown> {
    return this.requestJson<unknown>(`/api/sessions/${encodeURIComponent(sessionId)}/${action}`, {
      method: "POST",
      body: patch,
    });
  }

  /* ------------------------------------------------------------------ */
  /* 发消息 + 流式                                                       */
  /* ------------------------------------------------------------------ */

  /** 只负责「投递」。内容必须从 /stream 或 /api/chat 的流里读。 */
  async sendPrompt(sessionId: string, input: PromptInput): Promise<unknown> {
    const body: Record<string, unknown> = {
      requestId: input.requestId,
      message: input.message,
      ...(input.images && input.images.length > 0 ? { images: input.images } : {}),
      ...(input.extra ?? {}),
    };
    const response = await this.requestJson<{ result: unknown }>(
      `/api/sessions/${encodeURIComponent(sessionId)}/prompt`,
      { method: "POST", body },
    );
    return response.result;
  }

  /**
   * 持久会话流：连接即回放当前轮已产出的帧，之后持续推送。
   * 断线重连由上层负责（服务端不支持 Last-Event-ID）。
   */
  async streamSession(
    sessionId: string,
    options: SseOptions & { onFrame?: (frame: Frame) => void },
  ): Promise<void> {
    await this.streamSse(`/api/sessions/${encodeURIComponent(sessionId)}/stream`, {
      ...options,
      onEvent: (event) => {
        options.onEvent(event);
        if (!options.onFrame) return;
        if (event.data === SSE_DONE_MARKER) {
          options.onFrame({ type: "finish" });
          return;
        }
        const frame = safeParseFrame(event.data);
        if (frame) options.onFrame(frame);
      },
    });
  }

  /** 全局状态流（event: state）。 */
  async streamState(options: SseOptions & { onState?: (state: PiDeckState) => void }): Promise<void> {
    await this.streamSse("/api/events", {
      ...options,
      onEvent: (event) => {
        options.onEvent(event);
        if (event.event !== "state" || !options.onState) return;
        try {
          options.onState(normalizeState(JSON.parse(event.data)));
        } catch {
          /* 单个坏帧不应断开整条流 */
        }
      },
    });
  }

  /* ------------------------------------------------------------------ */
  /* 运行时控制                                                          */
  /* ------------------------------------------------------------------ */

  async runtime<T = unknown>(
    sessionId: string,
    action: RuntimeAction,
    target?: RuntimeTarget | null,
    payload: Record<string, unknown> = {},
  ): Promise<T> {
    const body: Record<string, unknown> = { ...payload };
    if (action !== "activate") {
      if (!target) {
        throw new PiDeckError({ kind: "protocol", message: `${action} 需要 runtime target（先拉 /api/state）` });
      }
      body.target = target;
    }
    const response = await this.requestJson<{ result: T }>(
      `/api/sessions/${encodeURIComponent(sessionId)}/runtime/${action}`,
      { method: "POST", body },
    );
    return response.result;
  }

  activate(sessionId: string) {
    return this.runtime(sessionId, "activate");
  }
  stopRuntime(sessionId: string, target: RuntimeTarget) {
    return this.runtime(sessionId, "stop", target);
  }
  abort(sessionId: string, target: RuntimeTarget) {
    return this.runtime(sessionId, "abort", target);
  }
  restart(sessionId: string, target: RuntimeTarget) {
    return this.runtime(sessionId, "restart", target);
  }
  compact(sessionId: string, target: RuntimeTarget, prompt?: string) {
    return this.runtime(sessionId, "compact", target, prompt ? { prompt } : {});
  }
  getRuntimeState(sessionId: string, target: RuntimeTarget) {
    return this.runtime(sessionId, "state", target);
  }
  listRuntimeModels(sessionId: string, target: RuntimeTarget) {
    return this.runtime(sessionId, "models", target);
  }
  setModel(
    sessionId: string,
    target: RuntimeTarget,
    model: { provider: string; modelId: string; modelName?: string },
  ) {
    return this.runtime(sessionId, "model", target, model);
  }
  setThinking(sessionId: string, target: RuntimeTarget, level: string) {
    return this.runtime(sessionId, "thinking", target, { level });
  }
  setPermission(sessionId: string, target: RuntimeTarget, preset: string) {
    return this.runtime(sessionId, "permission", target, { preset });
  }
  fork(
    sessionId: string,
    target: RuntimeTarget,
    entryId: string,
    options: { mutationFork?: boolean; branchMode?: boolean } = {},
  ) {
    return this.runtime(sessionId, "fork", target, { entryId, ...options });
  }
  cloneRuntime(sessionId: string, target: RuntimeTarget) {
    return this.runtime(sessionId, "clone", target);
  }
  editMessage(sessionId: string, target: RuntimeTarget, messageId: string, newText: string) {
    return this.runtime(sessionId, "edit-message", target, { messageId, newText });
  }
  deleteMessage(sessionId: string, target: RuntimeTarget, messageId: string) {
    return this.runtime(sessionId, "delete-message", target, { messageId });
  }
  prepareResend(sessionId: string, target: RuntimeTarget, messageId: string) {
    return this.runtime(sessionId, "prepare-resend", target, { messageId });
  }

  /* ------------------------------------------------------------------ */
  /* 交互卡片（ask_question / 权限确认）                                  */
  /* ------------------------------------------------------------------ */

  async respondUi(pending: PendingUiRequest, response: Record<string, unknown>): Promise<void> {
    await this.requestJson<{ ok: boolean }>("/api/ui-response", {
      method: "POST",
      body: {
        sessionId: pending.sessionId,
        agentId: pending.agentId,
        runtimeGeneration: pending.runtimeGeneration,
        requestId: pending.requestId,
        response,
      },
    });
  }

  /* ------------------------------------------------------------------ */
  /* 工作区只读（shape 未完全核实，透传）                                 */
  /* ------------------------------------------------------------------ */

  /**
   * 列出项目内某个目录（dir 省略 = 项目根）。
   *
   * 注意参数名是 **dir** 不是 path —— 服务端只认 dir，
   * 传 path 会被静默忽略并始终返回项目根，表现为「点进目录后看到的还是根目录」。
   */
  async listFiles(projectId: string, dir = ""): Promise<FileNode[]> {
    const body = await this.requestJson<{ nodes?: FileNode[] }>("/api/files", {
      query: { projectId, ...(dir ? { dir } : {}) },
    });
    return Array.isArray(body.nodes) ? body.nodes : [];
  }

  /** 会话可用的斜线命令（需要运行时 target）。 */
  async listRuntimeCommands(sessionId: string, target: RuntimeTarget): Promise<CommandInfo[]> {
    const result = await this.runtime<{ ok?: boolean; value?: { value?: CommandInfo[] } | CommandInfo[] }>(
      sessionId,
      "commands",
      target,
    );
    if (!result) return [];
    // 服务端包了一层：result = { ok, value: { target, value: [...] } }
    if (Array.isArray(result)) return result;
    const inner = result.value;
    if (Array.isArray(inner)) return inner;
    if (inner && Array.isArray((inner as { value?: CommandInfo[] }).value)) {
      return (inner as { value: CommandInfo[] }).value;
    }
    return [];
  }
  /** 文件内容：{ content, size } */
  async fileContent(projectId: string, path: string): Promise<{ content: string; size: number }> {
    const body = await this.requestJson<{ content?: unknown; size?: unknown }>("/api/file-content", {
      query: { projectId, path },
    });
    return {
      content: typeof body.content === "string" ? body.content : "",
      size: typeof body.size === "number" ? body.size : 0,
    };
  }
  /** Git 状态：{ repo, branch, groups } —— 形状见 ui/workspace-model.ts */
  async gitStatus(projectId: string): Promise<unknown> {
    return this.requestJson<unknown>("/api/git/status", { query: { projectId } });
  }
  async gitLog(projectId: string, limit = 20): Promise<unknown> {
    return this.requestJson<unknown>("/api/git/log", { query: { projectId, limit } });
  }
  async gitDiff(projectId: string, path: string): Promise<unknown> {
    return this.requestJson<unknown>("/api/git/diff", { query: { projectId, path } });
  }
  listPrompts() {
    return this.requestJson<unknown>("/api/prompts");
  }
  listSkills() {
    return this.requestJson<unknown>("/api/skills");
  }
  listExtensions() {
    return this.requestJson<unknown>("/api/extensions");
  }
}

/* -------------------------------------------------------------------- */
/* 纯函数：状态归一化 / 帧解析                                           */
/* -------------------------------------------------------------------- */

export function emptyState(): PiDeckState {
  return { projects: [], sessions: [], runtimes: [], pendingUiRequests: [] };
}

/** 服务端字段可能缺失或换名，这里做防御性兜底，UI 层就不用到处判空。 */
export function normalizeState(raw: unknown): PiDeckState {
  if (!raw || typeof raw !== "object") return emptyState();
  const record = raw as Record<string, unknown>;
  return {
    projects: asArray<Project>(record.projects),
    sessions: asArray<SessionSummary>(record.sessions),
    runtimes: asArray<PiDeckState["runtimes"][number]>(record.runtimes),
    pendingUiRequests: asArray<PendingUiRequest>(record.pendingUiRequests),
  };
}

function asArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? (value.filter((item) => item && typeof item === "object") as T[]) : [];
}

export function safeParseFrame(data: string): Frame | null {
  const trimmed = data.trim();
  if (!trimmed) return null;
  try {
    const parsed = JSON.parse(trimmed) as unknown;
    if (!parsed || typeof parsed !== "object") return null;
    const type = (parsed as { type?: unknown }).type;
    if (typeof type !== "string" || !type) return null;
    return parsed as Frame;
  } catch {
    return null;
  }
}

/**
 * 把一条会话流喂给 ChatReducer 的便捷封装（不含重连）。
 * UI 层若需要更细粒度控制，直接用 streamSession + ChatReducer。
 */
export async function collectRun(
  client: PiDeckClient,
  sessionId: string,
  options: { signal?: AbortSignal; onUpdate?: (reducer: ChatReducer) => void; stopOnFinish?: boolean } = {},
): Promise<ChatReducer> {
  const reducer = new ChatReducer();
  const controller = new AbortController();
  const signal = options.signal
    ? combineSignals([options.signal, controller.signal])
    : controller.signal;

  await client.streamSession(sessionId, {
    signal,
    onEvent: () => undefined,
    onFrame: (frame) => {
      const result = reducer.apply(frame);
      if (result.changed) options.onUpdate?.(reducer);
      if (result.finished && options.stopOnFinish !== false) controller.abort();
    },
  }).catch((error: unknown) => {
    if (!controller.signal.aborted) throw error;
  });

  return reducer;
}

export function combineSignals(signals: AbortSignal[]): AbortSignal {
  const controller = new AbortController();
  for (const signal of signals) {
    if (signal.aborted) {
      controller.abort();
      break;
    }
    signal.addEventListener("abort", () => controller.abort(), { once: true });
  }
  return controller.signal;
}

export type { WireMessage };
