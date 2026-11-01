/**
 * 聊天页「视图迁移」的桥：把 ChatView 的入口翻译成一份可 JSON 化的快照，推给 Flutter。
 *
 * 为什么不在 Dart 里重写一遍 ChatReducer：
 *   状态机在 `src/api/frames.ts`（协议核心，桌面端与手机端共用），
 *   在 Flutter 再写一份就等于两个真相源 —— 一旦协议加帧类型，两边必然漂移。
 *   所以这里沿用设置页那套：WebView 侧算好「要画什么」，Flutter 只负责画，
 *   用户动作原样回传，由 `app/src/main.ts` 里同一套函数执行。
 *
 * 与设置页的差别只有一个：聊天数据每帧都在变，所以这里做了合并推送
 * （40ms 尾沿），而不是每次变化都过桥。
 */

import type { ChatBlock, PendingUiRequest, RunStatus, WireMessage } from "@core/types.ts";
import { messageText, normalizeRole } from "../ui/chat-view.ts";
import { splitThinking } from "../ui/thinking-tags.ts";
import { collectImageSources } from "../ui/image-attach-model.ts";
import { actionsForRole } from "../ui/message-actions-model.ts";
import type { PerfMetrics } from "../ui/perf-metrics.ts";
import type { ChatHeaderView, ChatPickerView } from "./flutter-chat-header.ts";

/** 一条已落盘的历史消息（Flutter 侧只认这个形状，不认 WireMessage）。 */
export interface ChatHistoryItem {
  kind: "message";
  /** 长按菜单要用的 id（`message.id`，没有就退回 `meta.entryId`） */
  id: string | null;
  role: "user" | "assistant" | "tool";
  text: string;
  thinking: string;
  /** HH:MM，空串表示这条没有时间戳 */
  time: string;
  /** data URL，最多 MAX_IMAGES 张 */
  images: string[];
  /** 仅 role=tool 时有值 */
  toolName: string;
  toolState: "" | "running" | "ok" | "error";
  toolDetail: string;
}

/** 推给 Flutter 的整份快照。字段一旦改名，Dart 侧的 `ChatSnapshot.fromJson` 要同步。 */
export interface ChatSnapshot {
  sessionId: string | null;
  title: string;
  /** idle / running / finished / error / aborted */
  status: RunStatus | string;
  /** 状态行的文案（`turnStatusText()` 的结果） */
  statusText: string;
  error: { title: string; detail?: string; hint?: string } | null;
  /** 空态文案；null 表示不显示空态 */
  empty: string | null;
  loading: boolean;
  history: ChatHistoryItem[];
  live: { messageId: string | null; blocks: ChatBlock[] } | null;
  pending: PendingUiRequest | null;
  perf: PerfMetrics | null;
  /**
   * 长按一条消息能做什么（已按当前后端收敛：ACP 会话只剩「复制」）。
   *
   * 菜单内容由 `ui/message-actions-model.ts` 的 `actionsForRole()` 决定，
   * Dart 侧只负责画成底部弹层，**不要**在那里维护一份动作清单 ——
   * 后端能力一变（比如 ACP 不能分叉）两边就会分叉。
   */
  messageMenus: { user: ChatMenuItem[]; assistant: ChatMenuItem[] };
  /**
   * 顶栏（标题 / meta / chips / 上下文 / 发送模式 / 待办与子代理摘要）。
   *
   * 由 `platform/flutter-chat-header.ts` 的 `buildChatHeaderView()` 组装，
   * 规则全在 `ui/header-model.ts` 等模块里 —— Dart 只画。
   */
  header: ChatHeaderView;
  /**
   * 当前要显示的选择面板（模型 / 思考 / 安全 / 提示词 / 上下文）；null = 不显示。
   *
   * 跟随快照一起推，不另开通道：面板内容本来就是 WebView 算好的，
   * 多一条通道只会多一处不同步。
   */
  picker: ChatPickerView | null;
}

/** 长按菜单里的一项。 */
export interface ChatMenuItem {
  /** copy / edit / resend / fork / delete */
  id: string;
  label: string;
  /** 危险操作（删除）：菜单里标红，由 Dart 侧先二次确认再回传 */
  danger?: boolean;
}

/** 工具条目的两行摘要（和 `historyToolEntry()` 的口径一致）。 */
const TOOL_DETAIL_LIMIT = 2_000;

function clockOf(timestamp: unknown): string {
  if (typeof timestamp !== "number" || !Number.isFinite(timestamp) || timestamp <= 0) return "";
  try {
    return new Date(timestamp).toLocaleTimeString().slice(0, 5);
  } catch {
    return "";
  }
}

/**
 * 历史消息 → 渲染条目（纯函数）。
 *
 * 与 `ChatView.setHistory()` 的差别：Web 版把连续的「思考 + 工具」收进同一个
 * `<details>` 分组，这里**不平铺分组**，而是每条消息各自成项、思考挂在消息自己身上。
 * 理由：分组是为了省 DOM 行数，Flutter 的 `ListView.builder` 本来就不建屏外 widget，
 * 而分组会让「长按某条消息」的归属变得难解释。
 */
export function historyItemsOf(messages: readonly WireMessage[]): ChatHistoryItem[] {
  const items: ChatHistoryItem[] = [];

  for (const message of messages) {
    const role = normalizeRole(message.role);
    if (role === "system") continue;

    const id =
      typeof message.id === "string" && message.id
        ? message.id
        : typeof (message as { entryId?: unknown }).entryId === "string"
          ? String((message as { entryId: string }).entryId)
          : null;

    if (role === "tool") {
      const meta = ((message as { meta?: Record<string, unknown> }).meta ?? {}) as Record<string, unknown>;
      const toolName = typeof meta.toolName === "string" && meta.toolName ? meta.toolName : "tool";
      const status = typeof meta.status === "string" ? meta.status : "";
      const isError = meta.isError === true || status === "error";
      const detail = [
        typeof meta.detailText === "string" ? meta.detailText : "",
        messageText(message),
      ]
        .filter(Boolean)
        .join("\n\n")
        .slice(0, TOOL_DETAIL_LIMIT);

      items.push({
        kind: "message",
        id,
        role: "tool",
        text: "",
        thinking: "",
        time: clockOf(message.timestamp),
        images: [],
        toolName,
        toolState: isError ? "error" : status === "running" ? "running" : "ok",
        toolDetail: detail,
      });
      continue;
    }

    // 思考可能来自独立字段，也可能以 <thinking> 标签嵌在正文里
    const rawText = messageText(message);
    const split = splitThinking(rawText);
    const fieldThinking =
      typeof (message as { thinking?: unknown }).thinking === "string"
        ? String((message as { thinking: string }).thinking).trim()
        : "";
    const thinking = [fieldThinking, split.thinking].filter(Boolean).join("\n\n").trim();
    const images = collectImageSources(message);

    if (!split.text && !thinking && images.length === 0) continue;

    items.push({
      kind: "message",
      id,
      role: role === "user" ? "user" : "assistant",
      text: split.text,
      thinking,
      time: clockOf(message.timestamp),
      images,
      toolName: "",
      toolState: "",
      toolDetail: "",
    });
  }

  return items;
}

export interface ChatSnapshotInput {
  sessionId: string | null;
  title?: string;
  messages: readonly WireMessage[];
  status?: RunStatus | string;
  statusText?: string;
  error?: { title: string; detail?: string; hint?: string } | null;
  empty?: string | null;
  loading?: boolean;
  live?: { messageId: string | null; blocks: readonly ChatBlock[] } | null;
  pending?: readonly PendingUiRequest[] | null;
  perf?: PerfMetrics | null;
  /** 当前会话的后端（`activeBackend()`）；决定长按菜单能出现哪些动作 */
  backend?: unknown;
  /** 顶栏快照（`buildChatHeaderView()` 的结果）；缺省给一份安全的空顶栏 */
  header?: ChatHeaderView;
  /** 当前选择面板；缺省 null */
  picker?: ChatPickerView | null;
}

/** 组装快照（纯函数，方便单测）。 */
export function buildChatSnapshot(input: ChatSnapshotInput): ChatSnapshot {
  const pending = input.pending && input.pending.length > 0 ? input.pending[0] : null;
  return {
    sessionId: input.sessionId,
    title: input.title ?? "",
    status: input.status ?? "idle",
    statusText: input.statusText ?? "",
    error: input.error ?? null,
    empty: input.empty ?? null,
    loading: input.loading === true,
    history: historyItemsOf(input.messages),
    live: input.live
      ? { messageId: input.live.messageId, blocks: [...input.live.blocks] }
      : null,
    pending: pending ? { ...pending } : null,
    perf: input.perf ?? null,
    messageMenus: {
      user: menuItemsFor("user", input.backend),
      assistant: menuItemsFor("assistant", input.backend),
    },
    header: input.header ?? emptyHeader(),
    picker: input.picker ?? null,
  };
}

/** 没传 header 时的兜底：没有任何 chip，标题留空。 */
function emptyHeader(): ChatHeaderView {
  return {
    title: "",
    meta: "",
    running: false,
    chips: [],
    context: null,
    sendMode: "queue",
    showSendMode: false,
    canSteer: false,
    promptCount: 0,
    todo: null,
    subagents: null,
  };
}

/** `actionsForRole()` 的结果拷成纯数据（去掉 readonly，方便过桥）。 */
function menuItemsFor(role: "user" | "assistant", backend: unknown): ChatMenuItem[] {
  return actionsForRole(role, backend).map((item) =>
    item.danger ? { id: item.id, label: item.label, danger: true } : { id: item.id, label: item.label },
  );
}

/**
 * 合并推送（40ms 尾沿）。
 *
 * 为什么是 40ms 而不是 16ms：过桥是 `invokeMethod(String)`，一次几十 KB 的 JSON 要序列化、
 * 跨线程投递、再在 Dart 侧 parse。25fps 对「读字」这个场景完全够，而 16ms 会让
 * 主线程在长回答时明显发热（原生 SsePlugin 自己已经在 16ms 合并过一轮）。
 */
const PUSH_INTERVAL_MS = 40;

export interface ChatPushTransport {
  push: (json: string) => void | Promise<void>;
}

export class FlutterChatMirror {
  private readonly transport: ChatPushTransport;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private latest: ChatSnapshot | null = null;
  private sent: string | null = null;

  constructor(transport: ChatPushTransport) {
    this.transport = transport;
  }

  /** 记下最新快照，必要时安排一次推送。 */
  update(snapshot: ChatSnapshot): void {
    this.latest = snapshot;
    if (this.timer !== null) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.flush();
    }, PUSH_INTERVAL_MS);
  }

  /** 立刻推（打开页面时用：不能让用户等 40ms 看空白）。 */
  flush(): void {
    const snapshot = this.latest;
    if (!snapshot) return;
    const json = JSON.stringify(snapshot);
    // 内容没变就别过桥：状态轮询 1s 一次，每次都推纯属浪费
    if (json === this.sent) return;
    this.sent = json;
    void this.transport.push(json);
  }

  dispose(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
    this.latest = null;
    this.sent = null;
  }
}
