/**
 * Wire types for PiDeck's web service.
 *
 * 这些形状来自对 PiDeck 0.9.7 构建产物的核对（out/main/index.js / out/renderer/assets），
 * 不是官方文档。PiDeck 的 /api/* 没有版本前缀，字段可能随版本漂移，因此：
 *   - 已核实的字段用必填/可选如实标注
 *   - 无法完全确认的载荷保留 `unknown` / 索引签名，交给上层解释
 *   - 升级 PiDeck 后请重跑 `npm run probe -- health` 与 tests/
 */

/** GET /api/health —— 唯一免鉴权端点，用于配对校验与探活。不含版本号。 */
export interface HealthInfo {
  ok: boolean;
  service: string;
  host: string;
  port: number;
}

export type BackendKind = "pi" | "dsh" | "imagegen";

export interface Project {
  id: string;
  name?: string;
  path?: string;
  /** "chat" 表示内置 Chat 项目，不可删除 */
  kind?: string;
  [key: string]: unknown;
}

/** /api/state 与 /api/projects/:id/sessions 里的会话投影。 */
export interface SessionSummary {
  id: string;
  projectId: string;
  title: string;
  status: string;
  projectPath?: string;
  model?: { provider?: string; modelId?: string; modelName?: string } | null;
  thinkingLevel?: string | null;
  backend?: BackendKind | string;
  updatedAt?: number;
  parentSessionId?: string | null;
  forked?: boolean;
  createdAt?: number;
  permissionPreset?: string | null;
  [key: string]: unknown;
}

/**
 * 运行时目标三元组。所有 /runtime/{action} 调用都必须带它，
 * 且必须来自最新的 /api/state.runtimes —— generation 变了会返回 409。
 */
export interface RuntimeTarget {
  sessionId: string;
  agentId: string;
  runtimeGeneration: number;
}

export interface RuntimeInfo extends RuntimeTarget {
  projectId?: string;
  cwd?: string;
  status?: string;
  sessionPath?: string;
  createdAt?: number;
  compactionCount?: number;
  noSession?: boolean;
  deckSessionId?: string;
  [key: string]: unknown;
}

export type UiRequestMethod = "select" | "confirm" | "input" | "editor" | "batch_ask";

export interface BatchQuestion {
  question: string;
  options?: string[];
  placeholder?: string;
  prefill?: string;
  [key: string]: unknown;
}

/** ask_question / 权限确认等待应答的卡片。 */
export interface PendingUiRequest extends RuntimeTarget {
  requestId: string;
  method: UiRequestMethod | string;
  title: string;
  options?: string[];
  placeholder?: string;
  prefill?: string;
  allowOther?: boolean;
  batchQuestions?: BatchQuestion[];
  batchReview?: boolean;
  [key: string]: unknown;
}

export interface PiDeckState {
  projects: Project[];
  sessions: SessionSummary[];
  runtimes: RuntimeInfo[];
  pendingUiRequests: PendingUiRequest[];
}

/** 单条消息。结构与 renderer 的会话读取器同源，此处按“可渲染载荷”宽泛描述。 */
export interface WireMessage {
  id?: string;
  entryId?: string;
  role?: string;
  parts?: unknown[];
  content?: unknown;
  timestamp?: number;
  [key: string]: unknown;
}

export interface MessageWindow {
  messages: WireMessage[];
  total: number;
  windowStart: number;
  truncated: boolean;
}

export interface MessagePage {
  messages: WireMessage[];
  total: number;
  nextBefore: number | null;
}

export interface ModelInfo {
  provider?: string;
  providerId?: string;
  id?: string;
  modelId?: string;
  name?: string;
  [key: string]: unknown;
}

export interface ImageAttachment {
  /** data URL 或纯 base64，取决于 PiDeck 版本；以桌面端 Web 端实际载荷为准 */
  data: string;
  mimeType?: string;
  [key: string]: unknown;
}

/* ------------------------------------------------------------------ */
/* 会话流帧（SSE，响应头 x-vercel-ai-ui-message-stream: v1）            */
/* 帧类型与字段取自实机 0.9.7 的 PiEventToUiMessageStream               */
/* ------------------------------------------------------------------ */

export interface FrameStart {
  type: "start";
  messageId: string;
}
export interface FrameStartStep {
  type: "start-step";
}
export interface FrameTextStart {
  type: "text-start";
  id: string;
}
export interface FrameTextDelta {
  type: "text-delta";
  id: string;
  delta: string;
}
export interface FrameTextEnd {
  type: "text-end";
  id: string;
}
export interface FrameReasoningStart {
  type: "reasoning-start";
  id: string;
}
export interface FrameReasoningDelta {
  type: "reasoning-delta";
  id: string;
  delta: string;
}
export interface FrameReasoningEnd {
  type: "reasoning-end";
  id: string;
}
export interface FrameToolInputStart {
  type: "tool-input-start";
  toolCallId: string;
  toolName: string;
}
export interface FrameToolInputAvailable {
  type: "tool-input-available";
  toolCallId: string;
  toolName: string;
  input: unknown;
}
export interface FrameToolOutputAvailable {
  type: "tool-output-available";
  toolCallId: string;
  output: unknown;
}
export interface FrameToolOutputError {
  type: "tool-output-error";
  toolCallId: string;
  errorText: string;
}
export interface FrameError {
  type: "error";
  errorText: string;
}
export interface FrameFinish {
  type: "finish";
}

export type KnownFrame =
  | FrameStart
  | FrameStartStep
  | FrameTextStart
  | FrameTextDelta
  | FrameTextEnd
  | FrameReasoningStart
  | FrameReasoningDelta
  | FrameReasoningEnd
  | FrameToolInputStart
  | FrameToolInputAvailable
  | FrameToolOutputAvailable
  | FrameToolOutputError
  | FrameError
  | FrameFinish;

/** 未知帧原样透传：新版本 PiDeck 可能新增类型，客户端不应崩溃。 */
export type UnknownFrame = { type: string; [key: string]: unknown };
export type Frame = KnownFrame | UnknownFrame;

/* ------------------------------------------------------------------ */
/* 归一化后的对话块（UI 层直接渲染）                                     */
/* ------------------------------------------------------------------ */

export interface TextBlock {
  kind: "text";
  id: string;
  text: string;
  done: boolean;
}

export interface ReasoningBlock {
  kind: "reasoning";
  id: string;
  text: string;
  done: boolean;
}

export interface ToolBlock {
  kind: "tool";
  toolCallId: string;
  toolName: string;
  input?: unknown;
  output?: unknown;
  errorText?: string;
  state: "running" | "ok" | "error";
}

export type ChatBlock = TextBlock | ReasoningBlock | ToolBlock;

export type RunStatus = "idle" | "running" | "finished" | "error" | "aborted";

/** GET /api/files 返回的目录项。 */
export interface FileNode {
  name: string;
  relativePath: string;
  type: "file" | "directory";
  hasChildren?: boolean;
}

/** /runtime/commands 返回的斜线命令。 */
export interface CommandInfo {
  name: string;
  description?: string;
  source?: string;
}
