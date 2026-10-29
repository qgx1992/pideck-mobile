/**
 * PiDeck 手机客户端原型。
 *
 * 分层：
 *   - @core/*：协议与状态（pideck-mobile/src），与 UI 无关
 *   - platform：fetch 分流（CapacitorHttp）与配对持久化
 *   - ui：DOM 渲染
 * 本文件只做「接线」：配对 → 状态仓 → 会话通道 → 视图。
 */

import {
  PiDeckClient,
  StateStore,
  SessionChannel,
  parsePairingInput,
  describeError,
  isAuthError,
  type PiDeckState,
  type PendingUiRequest,
} from "@core/index.ts";
import {
  savePairing,
  loadPairing,
  clearPairing,
  listPairings,
  forgetPairing,
  renamePairing,
  pairingLabel,
} from "./platform/storage.ts";
import type { SavedPairing } from "./platform/storage.ts";
import { createAppFetch } from "./platform/fetch.ts";
import { initTheme, setThemeMode, getThemeMode, themeLabel, THEME_MODES, onThemeChange } from "./platform/theme.ts";
import { initSafeArea } from "./platform/safe-area.ts";
import { sleep } from "./platform/sleep.ts";
import { ChatView } from "./ui/chat-view.ts";
import { buildSidebarGroups, projectLabel, resolveExpandedProjects } from "./ui/sidebar-model.ts";
import { renderProjectGroup } from "./ui/sidebar-view.ts";
import { buildHeaderView } from "./ui/header-model.ts";
import { buildMentionView, isDirectory, joinPath, mentionText } from "./ui/mention-model.ts";
import { collectJumpTargets, filterJumpTargets, roleLabel } from "./ui/history-jump-model.ts";
import {
  formatCommitDate,
  parseGitLog,
  parseGitStatus,
  shouldPreviewFile,
  FILE_PREVIEW_MAX_BYTES,
} from "./ui/workspace-model.ts";
import type { GitCommit, GitStatusView } from "./ui/workspace-model.ts";
import type { FileNode, RuntimeTarget, WireMessage } from "@core/types.ts";
import { BUILTIN_SLASH, filterSlashItems, groupSlashItems, mergeSlashItems, slashText } from "./ui/slash-model.ts";
import {
  buildModelSections,
  findModel,
  modelSubtitle,
  thinkingHint,
  thinkingLabel,
} from "./ui/model-picker.ts";
import type { ModelPickerSection } from "./ui/model-picker.ts";
import { h, clear, toast } from "./ui/dom.ts";
import { attachPressable } from "./ui/press.ts";
import { attachDrawerGestures } from "./ui/drawer-gestures.ts";
import { attachBackButton } from "./platform/back-button.ts";
import {
  collectRunWatchTitles,
  consumeLaunchSession,
  consumeNotifiedKeys,
  isRunWatchActive,
  setRunWatchVisible,
  shouldStartRunWatch,
  splitNotifiedKeys,
  startRunWatch,
  stopRunWatch,
} from "./platform/run-watch.ts";
import {
  modelChipLabel,
  modelChipTitle,
  resolveSessionPrefs,
} from "./ui/session-prefs.ts";
import { countModels, filterModelSections } from "./ui/model-picker.ts";
import { createTodoStrip } from "./ui/todo-strip.ts";
import { parseSessionTodo, shouldAutoExpand } from "./ui/todo-model.ts";
import type { TodoView } from "./ui/todo-model.ts";
import {
  contextChipLabel,
  contextChipTitle,
  contextPollIntervalMs,
  contextRows,
  formatPercent,
  readContextUsage,
  ringPercent,
} from "./ui/context-usage.ts";
import type { ContextUsage } from "./ui/context-usage.ts";
import type { LearnedPrefs } from "./ui/session-prefs.ts";
import {
  askNotificationBody,
  askNotificationTitle,
  completionBody,
  completionTitle,
  diffCompletedRuns,
  diffNewPendingRequests,
} from "./ui/run-completion.ts";
import type { PendingSnapshot } from "./ui/run-completion.ts";
import type { RuntimeSnapshot } from "./ui/run-completion.ts";
import {
  ensureTaskChannel,
  ensureNotificationPermission,
  notifyTaskFinished,
  onNotificationTap,
  isTaskNotifyEnabled,
  setTaskNotifyEnabled,
} from "./platform/notifications.ts";
import type { DrawerOpenState } from "./ui/drawer-gestures.ts";
import type { ChannelStatus } from "@core/session.ts";

const root = document.getElementById("app");
if (!root) throw new Error("#app not found");

initSafeArea();
initTheme();
// 系统主题变化（跟随系统模式）时刷新一次界面相关状态
onThemeChange(() => {
  if (refs) renderComposerState();
});
const appFetch = createAppFetch();
const isDev = import.meta.env.DEV;

interface Runtime {
  client: PiDeckClient;
  store: StateStore;
  pairing: { baseUrl: string; token: string; label?: string };
}

let runtime: Runtime | null = null;
let channel: SessionChannel | null = null;
let activeSessionId: string | null = null;
let activeProjectId: string | null = null;
let chatView: ChatView | null = null;
let lastRunStatus: string = "idle";
/** 当前会话已加载的历史消息（供「发送历史」使用） */
let sessionHistory: WireMessage[] = [];
/** 每个会话各自的输入框草稿：切走时保存，切回来恢复，避免内容串会话 */
const sessionDrafts = new Map<string, string>();
/** 上一次的运行时快照，用来检测「跑完了」 */
let previousRuntimes: RuntimeSnapshot[] = [];
/** 上一次的待答卡片快照，用来检测「AI 新提问」 */
let previousPending: PendingSnapshot[] = [];
/** 后台服务已经替我们通知过的会话（回到前台后别再弹一次） */
const notifiedByService = new Set<string>();
/** 同理，但针对「AI 提问」 */
const notifiedAsksByService = new Set<string>();
/**
 * 从 /runtime/state 学到的模型/思考偏好。
 * catalog 里 session.model 常为 null（实测 223 个里只有 19 个有），
 * 真实模型只有运行时知道，学到后缓存起来供 chip 显示。
 */
const learnedPrefs = new Map<string, LearnedPrefs>();
let disposers: Array<() => void> = [];

/* ------------------------------------------------------------------ */
/* 侧栏视图状态（项目分组 / 折叠 / 搜索）                                */
/* ------------------------------------------------------------------ */

const COLLAPSED_KEY = ["pideck", "app", "collapsed"].join("-");
let collapsedProjects = new Set<string>(loadCollapsedProjects());
let sidebarQuery = "";
/**
 * 「其余会话」的显式展开/收起意图：projectId → 是否展开。
 * 没记录的项目走默认（前 6 个，当前会话被截断时自动展开）。
 */
const expandedSessions = new Map<string, boolean>();

function loadCollapsedProjects(): string[] {
  try {
    const raw = localStorage.getItem(COLLAPSED_KEY);
    const parsed = raw ? (JSON.parse(raw) as unknown) : [];
    return Array.isArray(parsed) ? parsed.filter((id): id is string => typeof id === "string") : [];
  } catch {
    return [];
  }
}

function persistCollapsedProjects(): void {
  try {
    localStorage.setItem(COLLAPSED_KEY, JSON.stringify([...collapsedProjects]));
  } catch {
    /* 隐私模式下写不进去，忽略即可 */
  }
}

/** 窄屏时侧栏是抽屉。 */
function isNarrowScreen(): boolean {
  return window.matchMedia("(max-width: 760px)").matches;
}

/* ------------------------------------------------------------------ */
/* 公共：清理与屏幕切换                                                */
/* ------------------------------------------------------------------ */

function teardown(): void {
  for (const dispose of disposers) {
    try {
      dispose();
    } catch {
      /* ignore */
    }
  }
  disposers = [];
  channel?.close();
  channel = null;
}

function mount(node: HTMLElement): void {
  clear(root!);
  root!.append(node);
}

/* ------------------------------------------------------------------ */
/* 配对页                                                              */
/* ------------------------------------------------------------------ */

function renderPairing(options: { link?: string; error?: string } = {}): void {
  teardown();
  const history = listPairings();
  const linkInput = h("textarea", {
    placeholder: "粘贴桌面端「扫码访问」的链接（示例：http://192.168.1.20:8765/?token=…）",
    value: options.link ?? "",
  });
  const baseInput = h("input", {
    placeholder: isDev ? "留空 = 走 Vite 代理到 127.0.0.1:8765" : "http://192.168.1.20:8765",
  });
  const tokenInput = h("input", { placeholder: "访问令牌（从链接里带也可）" });
  const statusEl = h("div", { class: "status" });

  const setStatus = (text: string, tone: "ok" | "error" | "" = ""): void => {
    statusEl.textContent = text;
    statusEl.className = `status${tone ? ` status--${tone}` : ""}`;
  };

  const connectBtn = h("button", { class: "btn", text: "连接" });
  connectBtn.addEventListener("click", () => {
    void (async () => {
      const link = linkInput.value.trim();
      const manualBase = baseInput.value.trim();
      const manualToken = tokenInput.value.trim();

      let baseUrl = "";
      let token = "";
      if (link) {
        const payload = parsePairingInput(link);
        baseUrl = payload.baseUrl;
        token = payload.token;
      } else {
        baseUrl = manualBase;
        token = manualToken;
      }
      if (!token) {
        setStatus("缺少令牌：粘贴完整链接，或填下面的令牌", "error");
        return;
      }
      if (isDev && !baseUrl) baseUrl = window.location.origin;
      if (!baseUrl) {
        setStatus("缺少地址", "error");
        return;
      }

      connectBtn.disabled = true;
      setStatus("正在握手…");
      try {
        await connect({ baseUrl, token });
      } catch (error) {
        connectBtn.disabled = false;
        setStatus(describeError(error), "error");
      }
    })();
  });

  if (options.error) setStatus(options.error, "error");

  mount(
    h("div", { class: "pairing" }, [
      h("h1", { text: "连接到你的 PiDeck" }),
      h("p", {
        text: "先在电脑上打开：设置 → Web 服务 → 局域网 Web 服务（保持令牌鉴权开启），再扫描/复制那里的链接。",
      }),
      h("div", {}, [
        h("span", { class: "label", text: "配对链接" }),
        linkInput,
        h("div", { class: "row", style: "margin-top:8px" }, [connectBtn]),
      ]),
      history.length > 0
        ? h("div", {}, [
            h("span", { class: "label", text: "最近连接" }),
            h("div", { class: "pair-history" }, history.map((item) =>
              h(
                "button",
                {
                  class: "pair-history-item",
                  onclick: () => {
                    void switchToPairing(item);
                  },
                },
                [
                  h("span", { class: "conn-name", text: pairingLabel(item) }),
                  h("span", { class: "conn-url", text: item.baseUrl }),
                ],
              ),
            )),
          ])
        : null,
      h("div", { class: "divider", text: "或者手动填" }),
      h("div", {}, [
        h("span", { class: "label", text: "地址" }),
        baseInput,
        h("span", { class: "label", text: "令牌", style: "margin-top:8px" }),
        tokenInput,
      ]),
      statusEl,
      h("p", {
        text: isDev
          ? "开发模式：地址留空时走 Vite 代理（同源），避免 WebView/浏览器的 CORS 预检限制。"
          : "令牌会保存在本机，仅用于访问你自己的 PiDeck。",
      }),
    ]),
  );

  linkInput.focus();
}

/* ------------------------------------------------------------------ */
/* 连接                                                                */
/* ------------------------------------------------------------------ */

async function connect(pairing: { baseUrl: string; token: string; label?: string }): Promise<void> {
  const baseUrl = pairing.baseUrl || (isDev ? window.location.origin : "");
  if (!baseUrl) throw new Error("缺少服务器地址");

  const client = new PiDeckClient({ baseUrl, token: pairing.token, fetchImpl: appFetch });
  const health = await client.health();
  if (health.service !== "PiDeck") {
    throw new Error(`对面不是 PiDeck（返回 service=${health.service}），检查地址是否填错`);
  }

  const store = new StateStore({
    client,
    onStatus: (status) => updateConnectionDot(status),
    onError: (error) => {
      if (!isAuthError(error)) console.warn("[pideck]", describeError(error));
    },
  });

  await store.refresh();

  savePairing({
    baseUrl: pairing.baseUrl,
    token: pairing.token,
    savedAt: Date.now(),
    ...(pairing.label ? { label: pairing.label } : {}),
  });
  runtime = { client, store, pairing };
  store.start();

  renderMain();
}

/* ------------------------------------------------------------------ */
/* 主界面                                                              */
/* ------------------------------------------------------------------ */

interface MainRefs {
  headerTitle: HTMLElement;
  headerMeta: HTMLElement;
  headerSub: HTMLElement;
  headerDot: HTMLElement;
  main: HTMLElement;
  body: HTMLElement;
  sidebarScroll: HTMLElement;
  chatRoot: HTMLElement;
  composer: HTMLTextAreaElement;
  /** 主按钮：空闲时「发送」，运行中变「停止」 */
  primaryBtn: HTMLButtonElement;
  modelBtn: HTMLButtonElement;
  thinkingBtn: HTMLButtonElement;
  /** 上下文用量：有数据才显示 */
  contextBtn: HTMLButtonElement;
  contextRing: HTMLElement;
  contextText: HTMLElement;
  /** 任务清单条带（agent 维护的 todo） */
  todoStrip: ReturnType<typeof createTodoStrip>;
  /** 右侧「工作区」抽屉 */
  workspacePanel: HTMLElement;
  /** 用于切换 show-workspace 类 */
  hostBody: HTMLElement;
  scrim: HTMLElement;
}

let refs: MainRefs | null = null;

function renderMain(): void {
  if (!runtime) return;
  teardown();

  const { store } = runtime;

  /* ---------------------------- 侧栏 ---------------------------- */

  const dot = h("span", { class: "dot" });
  const brand = h("div", { class: "brand" }, [dot, h("span", { class: "brand-name", text: "PiDeck" })]);
  const newSessionBtn = h("button", { class: "icon-btn", title: "新建会话", text: "+" });

  const searchInput = h("input", {
    class: "sidebar-search",
    type: "search",
    placeholder: "搜索会话…",
    value: sidebarQuery,
  });

  const sidebarScroll = h("div", { class: "sidebar-scroll" });
  const themeBtn = h("button", { class: "side-foot-btn", text: "外观" });
  const pairBtn = h("button", { class: "side-foot-btn", text: "连接" });
  const hideBtn = h("button", { class: "side-foot-btn", text: "收起" });

  const sidebar = h("aside", { class: "sidebar" }, [
    h("div", { class: "sidebar-top" }, [brand, newSessionBtn]),
    searchInput,
    sidebarScroll,
    h("div", { class: "sidebar-foot" }, [themeBtn, pairBtn, hideBtn]),
  ]);

  /* ---------------------------- 主区 ---------------------------- */

  const headerTitle = h("div", { class: "title", text: "PiDeck" });
  const headerMeta = h("div", { class: "meta-line", text: "" });
  const headerSub = h("div", { class: "sub", text: "" });
  const menuBtn = h("button", { class: "icon-btn", title: "项目与会话", text: "☰" });
  const workspaceBtn = h("button", { class: "icon-btn", title: "工作区（Git / 文件）", text: "▤" });
  const header = h("header", { class: "header" }, [
    menuBtn,
    h("div", { class: "header-text" }, [
      h("div", { class: "title-row" }, [headerTitle, headerMeta]),
      headerSub,
    ]),
    workspaceBtn,
  ]);

  const chatRoot = h("div", { class: "chat-body" });
  const composer = h("textarea", { placeholder: "输入消息…", style: `height:${COMPOSER_MIN_H}px` });
  const primaryBtn = h("button", { class: "btn btn-primary-action", text: "发送", title: "发送（Enter）" });
  const mentionBtn = h("button", { class: "chip chip--icon", text: "@", title: "引用项目内文件" });
  const slashBtn = h("button", { class: "chip chip--icon", text: "/", title: "斜线命令" });
  const modelBtn = h("button", { class: "chip", text: "模型", title: "选择模型" });
  const thinkingBtn = h("button", { class: "chip", text: "思考", title: "选择思考档位" });
  const contextRing = h("span", { class: "ctx-ring" });
  const contextText = h("span", { class: "ctx-text", text: "上下文" });
  const contextBtn = h(
    "button",
    { class: "chip chip--ctx is-hidden", title: "上下文用量", "aria-label": "上下文用量" },
    [contextRing, contextText],
  );
  // @ / 放在输入框内部左下角，历史按钮固定在右下角
  // （输入框底部预留一条整宽的空白带，左右按钮都落在带子里，不会压到文字）
  const inputTools = h("div", { class: "composer-inline-tools" }, [mentionBtn, slashBtn]);
  const historyFab = h("button", {
    class: "composer-fab",
    title: "跳转到历史消息",
    text: "历史",
    "aria-label": "跳转到历史消息",
  });
  const composerInput = h("div", { class: "composer-input" }, [composer, inputTools, historyFab]);
  const chips = h("div", { class: "composer-chips" }, [modelBtn, thinkingBtn, contextBtn]);
  const todoStrip = createTodoStrip();
  // 记住用户手动折叠的选择（优先于「有进行中就自动展开」）
  todoStrip.el.querySelector(".todo-head")?.addEventListener("click", () => {
    if (!activeSessionId) return;
    const current = todoCollapsed.get(activeSessionId) ?? !shouldAutoExpand(todoView);
    todoCollapsed.set(activeSessionId, !current);
  });
  const composerEl = h("div", { class: "composer" }, [
    todoStrip.el,
    composerInput,
    h("div", { class: "composer-row" }, [chips, primaryBtn]),
  ]);
  const chat = h("section", { class: "chat" }, [header, chatRoot, composerEl]);

  // 右侧「工作区」抽屉（对齐 Web 端 workspaceDrawer：Git / 文件 两个 Tab）
  const wsPanel = h("aside", { class: "workspace-panel" });
  const wsScrim = h("div", { class: "workspace-scrim" });
  const scrim = h("div", { class: "scrim" });
  const body = h("div", { class: "body" }, [sidebar, chat, wsPanel, wsScrim, scrim]);
  const main = h("div", { class: "main" }, [body]);

  chatView = new ChatView(chatRoot, {
    onRespond: (pending, response) => {
      void respondToUi(pending, response);
    },
  });
  chatView.setEmpty("选择左侧会话，或直接输入消息新建一个。");

  refs = {
    headerTitle,
    headerMeta,
    headerSub,
    headerDot: dot,
    main,
    body,
    sidebarScroll,
    chatRoot,
    composer,
    primaryBtn,
    modelBtn,
    thinkingBtn,
    contextBtn,
    contextRing,
    contextText,
    todoStrip,
    workspacePanel: wsPanel,
    hostBody: body,
    scrim,
  };

  const closeDrawer = (): void => body.classList.remove("show-sidebar");
  const openDrawer = (): void => {
    closeWorkspacePanel(); // 打开左侧栏时收起右侧工作区
    body.classList.add("show-sidebar");
  };

  // Android 返回键 / 侧滑返回：先收最上层界面，别直接退到桌面
  void attachBackButton({
    readLayers: () => ({
      // 底部面板都存在 .sheet-backdrop 里，直接看 DOM 最准（面板可能由别处打开）
      sheetOpen: document.querySelector(".sheet-backdrop") !== null,
      workspaceOpen: refs?.hostBody?.classList.contains("show-workspace") ?? false,
      sidebarOpen: refs?.hostBody?.classList.contains("show-sidebar") ?? false,
    }),
    onIntent: (intent) => {
      if (intent === "close-sheet") closeSheet();
      else if (intent === "close-workspace") closeWorkspacePanel();
      else closeDrawer();
    },
    onLeave: () => toast("再按一次返回退出"),
  }).then((dispose) => disposers.push(dispose));

  // App 从后台回到前台：SSE 常被系统冻死（不报错也不来数据），
  // 这里主动补一次状态、重拉历史、并强制重连会话流。
  const onVisibleAgain = (): void => {
    if (typeof document === "undefined") return;
    // 可见性要同步给原生看护服务（不可见时才由它发通知）
    void setRunWatchVisible(document.visibilityState === "visible");
    if (document.visibilityState !== "visible") return;
    void resumeAfterBackground();
  };
  document.addEventListener("visibilitychange", onVisibleAgain);
  disposers.push(() => document.removeEventListener("visibilitychange", onVisibleAgain));

  // 窄屏手势：左右两个抽屉由同一个控制器管理，保证互斥——
  // 已有一侧打开时，反方向手势是「关闭它」，而不是去开另一侧。
  disposers.push(
    attachDrawerGestures({
      isEnabled: () =>
        isNarrowScreen() &&
        !workspaceOpen &&
        !document.querySelector(".sheet-backdrop"),
      currentOpen: (): DrawerOpenState => {
        if (body.classList.contains("show-sidebar")) return "left";
        if (body.classList.contains("show-workspace")) return "right";
        return "none";
      },
      left: {
        drawer: sidebar,
        scrim,
        applyOpen: () => {
          openDrawer();
        },
        applyClose: () => closeDrawer(),
      },
      right: {
        drawer: wsPanel,
        scrim: wsScrim,
        applyOpen: () => {
          void openWorkspacePanel();
        },
        applyClose: () => closeWorkspacePanel(),
      },
    }),
  );

  menuBtn.addEventListener("click", () => {
    // 窄屏：抽屉开合；宽屏：侧栏显示/收起（收起后靠这个按钮恢复）
    if (isNarrowScreen()) {
      if (body.classList.contains("show-sidebar")) body.classList.remove("show-sidebar");
      else openDrawer();
    } else {
      main.classList.toggle("sidebar-hidden");
    }
  });
  scrim.addEventListener("click", closeDrawer);
  hideBtn.addEventListener("click", () => main.classList.toggle("sidebar-hidden"));
  themeBtn.addEventListener("click", () => openThemeSheet());
  pairBtn.addEventListener("click", () => openConnectionSheet());
  newSessionBtn.addEventListener("click", () => void createSession());
  workspaceBtn.addEventListener("click", () => toggleWorkspacePanel());
  wsScrim.addEventListener("click", () => closeWorkspacePanel());
  historyFab.addEventListener("click", () => openSentHistorySheet());
  mentionBtn.addEventListener("click", () => void openMentionSheet());
  slashBtn.addEventListener("click", () => void openSlashSheet());
  modelBtn.addEventListener("click", () => void openModelSheet());
  thinkingBtn.addEventListener("click", () => void openThinkingSheet());
  contextBtn.addEventListener("click", () => openContextSheet());
  // 一个按钮两种行为：运行中=停止，否则=发送
  primaryBtn.addEventListener("click", () => {
    if (isRunActive()) void stop();
    else void send();
  });

  searchInput.addEventListener("input", () => {
    sidebarQuery = searchInput.value;
    if (runtime) renderSidebar(runtime.store.state);
  });
  searchInput.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      sidebarQuery = "";
      searchInput.value = "";
      if (runtime) renderSidebar(runtime.store.state);
    }
  });

  composer.addEventListener("input", () => {
    autosize(composer);
    renderPrimaryButton();
    if (activeSessionId) {
      const text = composer.value;
      if (text.trim()) sessionDrafts.set(activeSessionId, text);
      else sessionDrafts.delete(activeSessionId);
    }
  });

  // 软键盘弹起 / 收起时，会话末尾要跟着贴底（否则输入框上方会留一截被遮住的内容）
  const onViewportChange = (): void => {
    chatView?.keepBottomOnResize();
  };
  window.visualViewport?.addEventListener("resize", onViewportChange);
  window.visualViewport?.addEventListener("scroll", onViewportChange);
  window.addEventListener("resize", onViewportChange);

  disposers.push(() => {
    window.visualViewport?.removeEventListener("resize", onViewportChange);
    window.visualViewport?.removeEventListener("scroll", onViewportChange);
    window.removeEventListener("resize", onViewportChange);
  });
  composer.addEventListener("focus", () => {
    chatView?.scrollToBottom();
  });

  composer.addEventListener("keydown", (event) => {
    if (event.key !== "Enter") return;
    const coarse = window.matchMedia("(pointer: coarse)").matches;
    if (event.shiftKey || coarse) return;
    event.preventDefault();
    void send();
  });

  // 通知：建渠道、要权限、监听点击跳转
  previousRuntimes = [];
  previousPending = [];
  void ensureTaskChannel();
  if (isTaskNotifyEnabled()) {
    void ensureNotificationPermission().then((granted) => {
      // 权限被拒时后台通知会完全静默，必须当场说清楚
      if (!granted) toast("通知权限未授予，任务完成提醒不会弹出");
    });
  }
  void onNotificationTap((sessionId) => {
    void openSession(sessionId);
  }).then((detach) => disposers.push(detach));

  const unsubscribe = store.subscribe((state) => {
    if (!runtime) return;
    detectCompletedRuns(state);
    renderSidebar(state);
    renderHeader(state);
    if (activeSessionId) restorePending(activeSessionId);
    else chatView?.setPending([]);
  });
  disposers.push(unsubscribe);

  const statusUnsub = store.onStatus((status) => {
    updateConnectionDot(status);
    if (status === "auth-required") toast("访问令牌已失效，请重新配对");
  });
  disposers.push(statusUnsub);

  const projects = store.projects();
  activeProjectId = activeProjectId ?? projects.find((p) => p.kind === "chat")?.id ?? projects[0]?.id ?? null;

  mount(main);
  // 状态与 DOM 对齐：重建后如果工作区仍是「打开」状态，把类补回去，
  // 否则变量说已开、DOM 却关着，用户点 ▤ 会被当成「关闭」而看起来没反应
  if (workspaceOpen) body.classList.add("show-workspace");
  renderSidebar(store.state);
  renderHeader(store.state);
  renderComposerState();
  updateConnectionDot(store.getStatus());

  // 宽屏默认展开侧栏；窄屏先停在列表页
  if (isNarrowScreen() && !activeSessionId) openDrawer();
}

/**
 * 输入框最小高度。
 * 底部留了 42px 给 @ / 和历史按钮，再加上 10px 上内边距和一行文字（约 19px），
 * 小于 72px 就会把第一行文字裁掉——所以不能再用 44px。
 */
const COMPOSER_MIN_H = 74;
const COMPOSER_MAX_H = 180;

function autosize(el: HTMLTextAreaElement): void {
  el.style.height = "auto";
  el.style.height = `${Math.min(COMPOSER_MAX_H, Math.max(COMPOSER_MIN_H, el.scrollHeight))}px`;
}

function renderHeader(state: PiDeckState): void {
  if (!refs || !runtime) return;
  const learned = activeSessionId ? learnedPrefs.get(activeSessionId) : undefined;
  const view = buildHeaderView(state, activeSessionId, activeProjectId, learned);

  if (refs.headerTitle.textContent !== view.title) refs.headerTitle.textContent = view.title;

  // 运行中：标题旁加一个呼吸圆点，光有「运行中」三个字在长回答里不够醒目
  const metaKey = `${view.isRunning ? "1" : "0"}|${view.meta}`;
  if (refs.headerMeta.dataset.key !== metaKey) {
    refs.headerMeta.dataset.key = metaKey;
    refs.headerMeta.textContent = "";
    if (view.isRunning) {
      refs.headerMeta.append(h("span", { class: "header-run-dot", "aria-hidden": "true" }));
    }
    if (view.meta) refs.headerMeta.append(document.createTextNode(view.meta));
  }
  refs.headerMeta.classList.toggle("is-running", view.isRunning);
  refs.headerMeta.style.display = view.meta ? "" : "none";
  if (refs.headerSub.textContent !== view.subtitle) refs.headerSub.textContent = view.subtitle;
  refs.headerSub.style.display = view.subtitle ? "" : "none";

  if (refs.headerDot) {
    refs.headerDot.classList.toggle("is-running", view.isRunning);
    refs.headerDot.title = view.statusLabel ? `会话状态：${view.statusLabel}` : "";
  }

  if (document.title !== view.docTitle) document.title = view.docTitle;
}

function toggleProject(projectId: string): void {
  if (collapsedProjects.has(projectId)) collapsedProjects.delete(projectId);
  else collapsedProjects.add(projectId);
  persistCollapsedProjects();
  if (runtime) renderSidebar(runtime.store.state);
}

/** 侧栏 = 项目分组 + 组内会话。搜索时跨项目过滤，并强制展开命中的分组。 */
/** 展开 / 收起某项目的剩余会话（不影响项目本身的折叠状态）。 */
function toggleSessionsExpanded(projectId: string): void {
  if (!runtime) return;
  // 按「当前实际是否展开」取反：不能只看 Map 里有没有值，
  // 因为当前会话被截断时会临时展开（Map 里并没有记录）。
  const currentlyExpanded = isSessionsExpanded(projectId, runtime.store.state);
  expandedSessions.set(projectId, !currentlyExpanded);
  renderSidebar(runtime.store.state);
}

/** 以「本次渲染实际使用的展开集合」为准，判断该项目是否处于展开态。 */
function isSessionsExpanded(projectId: string, state: PiDeckState): boolean {
  const expanded = resolveExpandedProjects(state, expandedSessions, activeSessionId);
  return expanded.has(projectId);
}

function renderSidebar(state: PiDeckState): void {
  if (!refs || !runtime) return;
  const groups = buildSidebarGroups(state, sidebarQuery, collapsedProjects);

  clear(refs.sidebarScroll);
  if (groups.length === 0) {
    refs.sidebarScroll.append(
      h("p", {
        class: "side-empty",
        text: sidebarQuery.trim() ? "没有匹配的会话" : "还没有项目，请先在桌面端添加目录",
      }),
    );
    return;
  }

  const runningSessionIds = new Set(
    state.runtimes.filter((r) => r.status === "running").map((r) => r.sessionId),
  );

  // 用户手动展开的项目 + 「当前会话被截断时」的临时展开（不写回手动集合）
  const expandedForRender = resolveExpandedProjects(state, expandedSessions, activeSessionId);

  const ctx = {
    activeSessionId,
    activeProjectId,
    expandedProjects: expandedForRender,
    runningSessionIds,
    handlers: {
      onToggleProject: toggleProject,
      onOpenSession: (id: string) => void openSession(id),
      onCreateSession: (id: string) => void createSession(id),
      onSessionMenu: (id: string) => openSessionMenu(id),
      onProjectMenu: (id: string) => openProjectMenu(id),
      onToggleSessions: toggleSessionsExpanded,
    },
  };
  refs.sidebarScroll.append(...groups.map((group) => renderProjectGroup(group, ctx)));
}

function renderComposerState(): void {
  if (!refs || !runtime) return;
  const session = activeSessionId ? runtime.store.session(activeSessionId) : undefined;

  const prefs = resolveSessionPrefs(session, activeSessionId ? learnedPrefs.get(activeSessionId) : undefined);
  refs.modelBtn.textContent = modelChipLabel(prefs, 18);
  refs.modelBtn.title = modelChipTitle(prefs);
  refs.modelBtn.classList.toggle("chip--muted", prefs.placeholder);

  const level = prefs.thinkingLevel;
  refs.thinkingBtn.textContent = level ? `思考 ${thinkingLabel(level)}` : "思考";
  refs.thinkingBtn.title = level ? `思考档位：${level}（${thinkingHint(level)}）` : "选择思考档位";
  refs.thinkingBtn.classList.toggle("chip--muted", !level);

  renderPrimaryButton();
}

/** 当前是否有正在进行的轮次（决定主按钮是「发送」还是「停止」）。 */
function isRunActive(): boolean {
  return channel?.reducer.status === "running";
}

/**
 * 主按钮状态机：
 *   运行中 → 停止（红色描边、可点）
 *   有文字 → 发送（主色）
 *   无文字 → 发送（禁用）
 */
function renderPrimaryButton(): void {
  if (!refs) return;
  const btn = refs.primaryBtn;
  const running = isRunActive();
  const hasText = refs.composer.value.trim().length > 0;

  if (running) {
    btn.textContent = "停止";
    btn.title = "停止当前回答";
    btn.classList.add("is-stop");
    btn.disabled = false;
    return;
  }

  btn.textContent = "发送";
  btn.title = "发送（Enter）";
  btn.classList.remove("is-stop");
  btn.disabled = !hasText;
}

function updateConnectionDot(status: string): void {
  if (!refs) return;
  const dot = refs.headerDot;
  dot.className = "dot";
  if (status === "live" || status === "connected") dot.classList.add("dot--live");
  else if (status === "polling" || status === "reconnecting" || status === "connecting") dot.classList.add("dot--warn");
  else if (status === "auth-required") dot.classList.add("dot--error");
  dot.title = `连接状态：${status}`;
}

/* ------------------------------------------------------------------ */
/* 会话操作                                                            */
/* ------------------------------------------------------------------ */

async function openSession(sessionId: string): Promise<void> {
  if (!runtime || !chatView) return;
  const { client, store } = runtime;

  // 先把当前会话没发出去的内容存起来，再切走（否则会带到下一个会话）
  if (activeSessionId && refs) {
    const pendingText = refs.composer.value;
    if (pendingText.trim()) sessionDrafts.set(activeSessionId, pendingText);
    else sessionDrafts.delete(activeSessionId);
  }

  channel?.close();
  channel = null;
  stopContextPolling();
  stopTodoPolling();
  activeSessionId = sessionId;
  sessionHistory = [];
  activeProjectId = store.session(sessionId)?.projectId ?? activeProjectId;
  chatView.clear();
  chatView.setEmpty("加载中…");

  // 恢复这个会话自己的草稿（没有就清空，不能留着上一个会话的内容）
  if (refs) {
    refs.composer.value = sessionDrafts.get(sessionId) ?? "";
    autosize(refs.composer);
    renderPrimaryButton();
  }

  // 立刻恢复待答卡片：不要等历史加载完，否则切回来会闪一下空白
  restorePending(sessionId);
  if (refs) {
    // 窄屏选完会话就收起侧栏，回到对话
    refs.body.classList.remove("show-sidebar");
    // 先按已知信息把标题/状态画上，不要等历史加载完
    renderHeader(store.state);
    renderSidebar(store.state);
  }

  const session = store.session(sessionId);
  try {
    const page = await client.getMessages(sessionId);
    if (activeSessionId !== sessionId) return;
    sessionHistory = page.messages;
    chatView.setHistory(page.messages);
  } catch (error) {
    if (activeSessionId !== sessionId) return;
    chatView.setError(describeHistoryFailure(error, session?.backend));
  }

  channel = new SessionChannel({
    client,
    sessionId,
    onUpdate: (c) => {
      chatView?.setLive(c.reducer.messageId, c.reducer.blocks, c.reducer.status);
      renderComposerState();
      renderPrimaryButton();
      // 本轮状态翻转（running → finished/error）时头部也要跟着变
      if (c.reducer.status !== lastRunStatus) {
        const previous = lastRunStatus;
        lastRunStatus = c.reducer.status;
        if (runtime) renderHeader(runtime.store.state);
        // 一轮结束：把流式区收掉，并重拉历史，让消息进入正式历史
        if (previous === "running" && isSettled(c.reducer.status)) {
          void settleRun(sessionId);
          void refreshTodo(sessionId);
        }
      }
    },
    onStatus: (status: ChannelStatus) => {
      updateConnectionDot(status);
      renderComposerState();
      renderPrimaryButton();
    },
    onError: (error) => {
      if (!isAuthError(error)) console.warn("[pideck stream]", describeError(error));
    },
    onRecovered: () => {
      void reloadHistory(sessionId);
    },
  });
  channel.start();
  startContextPolling(sessionId);
  startTodoPolling(sessionId);

  // 切会话后头部（标题/状态/模型）必须立刻重画，否则会停留在上一个会话
  renderHeader(store.state);
  renderSidebar(store.state);
  renderComposerState();

  // 恢复该会话的 AI 提问卡。
  // 之前只在 store 推送时才渲染 pending，切走再切回来就丢了（用户看不到待答卡片，
  // 但服务端其实还在等）。这里先按本地快照画一次，再主动拉一次最新状态校正。
  restorePending(sessionId);
  void store.refresh().then(() => {
    if (sessionId === activeSessionId) restorePending(sessionId);
  }).catch(() => undefined);
}

/**
 * 检测「刚跑完」的会话并发通知。
 *
 * 通知条件（避免打扰）：
 *   - 不是当前正在看的那个会话，或 App 已经在后台
 *   - 用户在「外观」里没关掉任务完成提醒
 *
 * 注意边界：Android 冻结后台 WebView 后收不到 SSE，也就发不出通知；
 * 真正的离线推送需要服务端通道，PiDeck 目前没有。
 */
function detectCompletedRuns(state: PiDeckState): void {
  const next: RuntimeSnapshot[] = state.runtimes.map((item) => ({
    sessionId: item.sessionId,
    status: String(item.status ?? ""),
  }));

  const completed = diffCompletedRuns(previousRuntimes, next);
  previousRuntimes = next;

  // 有会话在跑就拉起后台看护（必须在 App 还可见时启动，见 run-watch.ts 的说明）
  syncRunWatch(next);

  // 活跃会话刚拿到运行时（比如刚发出第一条消息）→ 补学一次模型偏好
  if (activeSessionId && !learnedPrefs.has(activeSessionId)) {
    void learnRuntimePrefs(activeSessionId);
  }

  const hidden = typeof document !== "undefined" && document.visibilityState === "hidden";

  // AI 提问：agent 会一直阻塞等回答，所以这个通知比「跑完了」更该及时
  const nextPending: PendingSnapshot[] = state.pendingUiRequests.map((item) => ({
    sessionId: item.sessionId,
    requestId: item.requestId,
    title: item.title,
  }));
  const newAsks = diffNewPendingRequests(previousPending, nextPending);
  previousPending = nextPending;

  for (const ask of newAsks) {
    // 后台服务已经发过这条了
    if (notifiedAsksByService.delete(ask.sessionId)) continue;
    // 正在看这个会话时不打扰（卡片就在眼前）
    if (!hidden && ask.sessionId === activeSessionId) continue;
    // App 在后台且看护服务在跑 → 交给原生发（我们随时可能被冻结）
    if (hidden && isRunWatchActive()) continue;
    const session = state.sessions.find((item) => item.id === ask.sessionId);
    void notifyTaskFinished({
      sessionId: ask.sessionId,
      title: askNotificationTitle(),
      body: askNotificationBody(ask, session?.title),
    });
  }

  if (completed.length === 0) return;

  for (const run of completed) {
    // 后台服务已经发过这条了，回到前台别再弹一次
    if (notifiedByService.has(run.sessionId)) {
      notifiedByService.delete(run.sessionId);
      continue;
    }
    const session = state.sessions.find((item) => item.id === run.sessionId);
    // 正在看的会话不提醒；App 在后台时都提醒
    if (!hidden && run.sessionId === activeSessionId) continue;
    // App 在后台且看护服务在跑 → 交给原生发（我们这边随时可能被冻结）
    if (hidden && isRunWatchActive()) continue;

    void notifyTaskFinished({
      sessionId: run.sessionId,
      title: completionTitle(run.endedAs),
      body: completionBody(session?.title),
    });
  }
}

/**
 * 后台回来后的对账。
 *
 * 后台期间可能发生的三件事，都必须补上：
 *   1. 这一轮跑完了 → 历史需要重拉（SSE 的 finish 可能压根没送到）
 *   2. 流被系统冻死 → 需要强制重连，否则一直挂在半开连接上
 *   3. 运行时状态变了 → 主按钮要从「停止」回到「发送」
 */
async function resumeAfterBackground(): Promise<void> {
  if (!runtime) return;
  const sessionId = activeSessionId;

  // 后台服务替我们发过的通知，记下来别重复弹
  const notified = splitNotifiedKeys(await consumeNotifiedKeys());
  for (const id of notified.runs) notifiedByService.add(id);
  for (const id of notified.asks) notifiedAsksByService.add(id);

  await runtime.store.refresh().catch(() => undefined);

  // 点通知进来的话，直接跳到那个会话
  const launchSession = await consumeLaunchSession();
  if (launchSession && launchSession !== activeSessionId && runtime.store.session(launchSession)) {
    await openSession(launchSession);
    return;
  }
  if (!sessionId || sessionId !== activeSessionId) return;

  // 历史永远重拉一次：既覆盖「跑完了」，也覆盖「期间有新消息」
  void reloadHistory(sessionId);

  // 服务端已经不认为它在跑了 → 收口本地状态，别让按钮停在「停止」
  // 注意：跑完后 runtime 通常还在（status=idle），所以要看 status 而不是「有没有 runtime」
  const runtimeInfo = runtime.store.state.runtimes.find((item) => item.sessionId === sessionId);
  const stillRunning = runtimeInfo?.status === "running";
  if (!stillRunning) {
    channel?.reducer.reset();
    chatView?.clearLive();
  }

  channel?.forceReconnect();
  void learnRuntimePrefs(sessionId, true);
  void refreshContextUsage(sessionId);
  void refreshTodo(sessionId);
  renderComposerState();
  renderPrimaryButton();
  renderHeader(runtime.store.state);
  restorePending(sessionId);
}

/**
 * 从 /runtime/state 读出会话真实使用的模型与思考档位。
 *
 * catalog 的 session.model 经常是 null，光看 /api/state 会以为「没设模型」。
 * 这里只在需要时（打开会话、切完模型、回前台）补一次，学到就缓存。
 */
const learningPrefs = new Set<string>();

async function learnRuntimePrefs(sessionId: string, force = false): Promise<void> {
  if (!runtime) return;
  if (!force && learnedPrefs.has(sessionId)) return;
  if (learningPrefs.has(sessionId)) return;
  const target = runtime.store.targetOf(sessionId);
  if (!target) return;
  learningPrefs.add(sessionId);

  try {
    const state = await runtime.client.getRuntimeState(sessionId, target);
    const value = extractRuntimeValue(state);
    if (!value || typeof value !== "object") return;

    const record = value as Record<string, unknown>;
    const pick = (key: string): string => (typeof record[key] === "string" ? (record[key] as string) : "");
    const next: LearnedPrefs = {
      provider: pick("provider"),
      modelId: pick("modelId"),
      modelName: pick("modelName"),
      thinkingLevel: pick("thinkingLevel"),
    };
    if (!next.provider && !next.modelId && !next.modelName) return;

    learnedPrefs.set(sessionId, next);
    if (sessionId === activeSessionId) {
      renderComposerState();
      renderHeader(runtime.store.state);
    }
  } catch {
    /* 运行时刚起/已停都会失败，静默即可，chips 走占位文案 */
  } finally {
    learningPrefs.delete(sessionId);
  }
}

/* ---------------------------- 任务清单（todo） ---------------------------- */

let todoView: TodoView | null = null;
let todoPollTimer: number | null = null;
let todoPollSession: string | null = null;
/** 用户手动折叠过的会话（优先于「有进行中就自动展开」） */
const todoCollapsed = new Map<string, boolean>();

function stopTodoPolling(): void {
  if (todoPollTimer !== null) {
    window.clearTimeout(todoPollTimer);
    todoPollTimer = null;
  }
  todoPollSession = null;
  todoView = null;
  renderTodoStrip();
}

/**
 * 拉一次任务清单。
 * 服务端没有清单时返回 null，条带整体隐藏（不占高度）。
 */
async function refreshTodo(sessionId: string): Promise<void> {
  if (!runtime || sessionId !== activeSessionId) return;
  try {
    const raw = await runtime.client.getTodo(sessionId);
    if (sessionId !== activeSessionId) return;
    todoView = parseSessionTodo(raw);
    renderTodoStrip();
  } catch {
    /* 运行时没起 / 会话太老没有清单：保持现状 */
  }
}

/**
 * 轮询任务清单。Web 端是固定 5s，且页面隐藏时跳过——这里照搬。
 */
function startTodoPolling(sessionId: string): void {
  stopTodoPolling();
  todoPollSession = sessionId;

  const schedule = (): void => {
    if (todoPollSession !== sessionId) return;
    todoPollTimer = window.setTimeout(() => void tick(), 5_000);
  };

  const tick = async (): Promise<void> => {
    if (todoPollSession !== sessionId) return;
    const hidden = typeof document !== "undefined" && document.visibilityState === "hidden";
    if (!hidden) await refreshTodo(sessionId);
    schedule();
  };

  void tick();
}

function renderTodoStrip(): void {
  if (!refs) return;
  const sessionId = activeSessionId;
  const remembered = sessionId ? todoCollapsed.get(sessionId) : undefined;
  // 用户手动收起过就听用户的；否则有「进行中」就自动展开
  const collapsed = remembered ?? !shouldAutoExpand(todoView);
  refs.todoStrip.update(todoView, { collapsed });
}

/* ---------------------------- 上下文用量 ---------------------------- */

let contextUsage: ContextUsage | null = null;
let contextPollTimer: number | null = null;
let contextPollSession: string | null = null;

function stopContextPolling(): void {
  if (contextPollTimer !== null) {
    window.clearTimeout(contextPollTimer);
    contextPollTimer = null;
  }
  contextPollSession = null;
  contextUsage = null;
  renderContextChip();
}

/**
 * 拉一次 /runtime/state 里的 token 占用。
 * 运行时没起就直接失败（chips 保持上一次的值，不清空闪一下）。
 */
async function refreshContextUsage(sessionId: string): Promise<void> {
  if (!runtime || sessionId !== activeSessionId) return;
  const target = runtime.store.targetOf(sessionId);
  if (!target) {
    contextUsage = null;
    renderContextChip();
    return;
  }
  try {
    const raw = await runtime.client.getRuntimeState(sessionId, target);
    if (sessionId !== activeSessionId) return;
    contextUsage = readContextUsage(raw);
    renderContextChip();
  } catch {
    /* 运行时刚起 / 已停：保留上一次的读数 */
  }
}

/**
 * 轮询上下文用量。节奏照搬 Web 端：跑动中 3s、事件流断了 1s、空闲 10s，
 * 并且 App 在后台时完全不发请求。
 */
function startContextPolling(sessionId: string): void {
  stopContextPolling();
  contextPollSession = sessionId;

  const schedule = (): void => {
    if (contextPollSession !== sessionId) return;
    const streaming = channel?.reducer.status === "running";
    const connected = channel?.getStatus() === "connected";
    contextPollTimer = window.setTimeout(
      () => void tick(),
      contextPollIntervalMs(streaming, connected),
    );
  };

  const tick = async (): Promise<void> => {
    if (contextPollSession !== sessionId) return;
    const hidden = typeof document !== "undefined" && document.visibilityState === "hidden";
    if (!hidden) await refreshContextUsage(sessionId);
    schedule();
  };

  void tick();
}

function renderContextChip(): void {
  if (!refs) return;
  const usage = contextUsage;
  if (!usage) {
    refs.contextBtn.classList.add("is-hidden");
    return;
  }
  refs.contextBtn.classList.remove("is-hidden");
  refs.contextBtn.classList.toggle("is-overflow", usage.contextOverflow);
  refs.contextBtn.classList.toggle("is-compacting", usage.isCompacting);
  refs.contextText.textContent = contextChipLabel(usage);
  refs.contextBtn.title = contextChipTitle(usage);
  refs.contextBtn.setAttribute("aria-label", contextChipTitle(usage));
  refs.contextRing.style.setProperty("--ctx-pct", String(ringPercent(usage)));
}

/** 上下文用量面板：大号百分比 + 明细 + 压缩按钮（对齐 Web 端 ContextUsageSheetBody）。 */
function openContextSheet(): void {
  const sessionId = activeSessionId;
  const usage = contextUsage;
  if (!sessionId) {
    toast("先选一个会话");
    return;
  }
  if (!usage) {
    toast("还没有上下文数据：先发一条消息");
    return;
  }

  openSheet("上下文用量", (sheet) => {
    const summary = h("div", { class: "ctx-summary" }, [
      h("span", {
        class: `ctx-percent${usage.contextOverflow ? " is-overflow" : ""}`,
        text: `${formatPercent(usage.contextPercent)}%`,
      }),
    ]);
    if (usage.contextOverflow) summary.append(h("span", { class: "ctx-badge", text: "已超出" }));
    if (usage.isCompacting) summary.append(h("span", { class: "ctx-badge", text: "压缩中" }));
    sheet.append(summary);

    const list = h("ul", { class: "ctx-rows" });
    for (const row of contextRows(usage)) {
      list.append(
        h("li", { class: "ctx-row" }, [
          h("span", { class: "ctx-row-label", text: row.label }),
          h("span", { class: "ctx-row-value", text: row.value }),
        ]),
      );
    }
    sheet.append(list);

    const compactBtn = h("button", { class: "btn ctx-compact", text: "压缩上下文" });
    compactBtn.addEventListener("click", () => void compactContext(sessionId));
    sheet.append(compactBtn);
  });
}

async function compactContext(sessionId: string): Promise<void> {
  if (!runtime) return;
  closeSheet();
  try {
    const target = await ensureRuntimeTarget(sessionId);
    toast("正在压缩上下文…");
    await runtime.client.compact(sessionId, target);
    toast("已触发压缩");
    window.setTimeout(() => void refreshContextUsage(sessionId), 1_500);
  } catch (error) {
    toast(`压缩失败：${describeError(error)}`);
  }
}

/** runtime/state 的返回被包了两层：{ ok, value: { target, value } } */
function extractRuntimeValue(raw: unknown): unknown {
  if (!raw || typeof raw !== "object") return undefined;
  const first = (raw as { value?: unknown }).value;
  if (first && typeof first === "object" && "value" in first) {
    return (first as { value?: unknown }).value;
  }
  return first;
}

/**
 * 乐观启动看护：发送消息时就当作「马上要跑」。
 *
 * 服务只在 running → 非 running 时通知，所以提前启动没有副作用；
 * 但如果等状态推下来再启动，用户已经回到桌面时会被 Android 拒绝（禁止后台启动前台服务）。
 */
function kickRunWatch(sessionId: string | null): void {
  if (!runtime || isRunWatchActive()) return;
  const session = sessionId ? runtime.store.session(sessionId) : undefined;
  const titles: Record<string, string> = {};
  if (sessionId && session?.title) titles[sessionId] = session.title;
  void startRunWatch({
    baseUrl: runtime.client.baseUrl,
    token: runtime.client.token,
    titles,
  });
}

/**
 * 按当前运行状态启停后台看护服务。
 *
 * 只在「确实有会话在跑」时启动：前台服务会显示一条常驻通知，
 * 没事的时候不该占着。停止则无条件（幂等）。
 */
function syncRunWatch(runtimes: readonly RuntimeSnapshot[]): void {
  if (!runtime) return;
  if (!shouldStartRunWatch(runtimes)) {
    void stopRunWatch();
    return;
  }
  if (isRunWatchActive()) return;

  void startRunWatch({
    baseUrl: runtime.client.baseUrl,
    token: runtime.client.token,
    titles: collectRunWatchTitles(runtimes, (sessionId) => runtime?.store.session(sessionId)),
  });
}

/** 把指定会话的待答卡片画到对话区（没有就清空）。 */
function restorePending(sessionId: string): void {
  if (!runtime || !chatView) return;
  const pending = runtime.store.pendingFor(sessionId);
  chatView.setPending(pending);
}

/** 轮次结束的三种终态。 */
function isSettled(status: string): boolean {
  return status === "finished" || status === "error" || status === "aborted";
}

/**
 * 一轮结束后的收尾。
 *
 * 之前不做这件事，导致：工具卡片一直挂在流式区、自己的提问要重进会话才出现、
 * 回答内容也停在临时节点上。这里等服务端把本轮落盘后重拉一次历史，
 * 再把流式区清空，界面与磁盘就一致了。
 */
async function settleRun(sessionId: string): Promise<void> {
  if (!runtime || !chatView) return;
  const delay = 400;
  await sleep(delay);
  if (sessionId !== activeSessionId) return;
  await reloadHistory(sessionId);
  if (sessionId !== activeSessionId) return;
  chatView.clearLive();
}

async function reloadHistory(sessionId: string): Promise<void> {
  if (!runtime || !chatView || sessionId !== activeSessionId) return;
  try {
    const page = await runtime.client.getMessages(sessionId);
    if (sessionId === activeSessionId) sessionHistory = page.messages;
    chatView.setHistory(page.messages);
  } catch (error) {
    // 已经有内容时不要拿错误卡把历史顶掉，只提示一下
    if (chatView.hasContent()) {
      toast(`刷新历史失败：${describeError(error)}`);
      return;
    }
    chatView.setError(describeHistoryFailure(error, runtime.store.session(sessionId)?.backend));
  }
}

async function createSession(projectId?: string): Promise<void> {
  if (!runtime) return;
  const targetProject = projectId ?? activeProjectId ?? runtime.store.projects()[0]?.id;
  if (!targetProject) {
    toast("没有可用项目：请先在桌面端添加项目目录");
    return;
  }
  try {
    const session = await runtime.client.createSession({ projectId: targetProject });
    activeProjectId = targetProject;
    collapsedProjects.delete(targetProject);
    persistCollapsedProjects();
    await runtime.store.refresh();
    await openSession(session.id);
  } catch (error) {
    toast(describeError(error));
  }
}

async function send(): Promise<void> {
  if (!runtime || !refs) return;
  const text = refs.composer.value.trim();
  if (!text) return;
  refs.composer.value = "";
  autosize(refs.composer);
  renderPrimaryButton();
  if (activeSessionId) sessionDrafts.delete(activeSessionId);

  try {
    if (!activeSessionId) {
      const projectId = activeProjectId ?? runtime.store.projects()[0]?.id;
      if (!projectId) {
        toast("没有可用项目：请先在桌面端添加项目目录");
        return;
      }
      const session = await runtime.client.createSession({ projectId });
      await runtime.store.refresh();
      await openSession(session.id);
    }
    const current = channel;
    if (!current) return;

    // 发送前就先拉起后台看护：用户常常发完立刻上滑回桌面，
    // 等 SSE 把 running 推下来再启动，可能已经来不及（App 一旦被冻结就启动不了前台服务）
    kickRunWatch(activeSessionId);

    // 先把自己的提问显示出来，不要等服务端落盘
    chatView?.clearLive();
    chatView?.appendLocalUser(text);
    await current.send(text);
    renderComposerState();
  } catch (error) {
    toast(describeError(error));
  }
}

async function stop(): Promise<void> {
  if (!runtime || !activeSessionId) return;
  const target = runtime.store.targetOf(activeSessionId);
  if (!target) {
    toast("当前没有运行中的实例");
    return;
  }
  try {
    await channel?.stopRun(target);
  } catch (error) {
    toast(describeError(error));
  }
}

async function respondToUi(pending: PendingUiRequest, response: Record<string, unknown>): Promise<void> {
  if (!runtime) return;
  try {
    await runtime.client.respondUi(pending, response);
    await runtime.store.refresh();
  } catch (error) {
    toast(describeError(error));
  }
}

/**
 * 把读取历史的失败翻译成用户能行动的说明。
 *
 * 已知分布（对着本机 227 个会话实测）：
 *   pi  会话 → 200
 *   dsh 会话 → 500，原因是 DSH runtime 未就绪；PiDeck 自己的 Web 端同样读不出来，
 *              所以这属于后端状态问题，不是本 App 能绕过的东西。
 */
function describeHistoryFailure(
  error: unknown,
  backend: string | undefined,
): { title: string; detail?: string; hint?: string; onRetry?: () => void } {
  const isDsh = backend === "dsh";
  const status = (error as { status?: number }).status;
  const code = (error as { code?: string }).code;
  const detail = [status ? `HTTP ${status}` : "", code ?? "", describeError(error)]
    .filter(Boolean)
    .join(" · ");

  if (isDsh) {
    return {
      title: "这个 DSH 会话的历史读不出来",
      detail,
      hint: "PiDeck 的 Web 接口在读 DSH 会话时要依赖 DSH runtime，当前 runtime 未就绪。到电脑上的「设置 → 配置管理 → DSH」看运行时状态并修复，然后回来重试；期间可以先用 pi 会话，或直接在桌面端查看这段历史。",
      onRetry: () => {
        if (activeSessionId) void reloadHistory(activeSessionId);
      },
    };
  }

  return {
    title: "历史加载失败",
    detail,
    hint: "先确认电脑端 PiDeck 还开着 Web 服务；如果只有这个会话失败，可能是会话文件很大或已损坏，可在桌面端打开确认。",
    onRetry: () => {
      if (activeSessionId) void reloadHistory(activeSessionId);
    },
  };
}

/**
 * 拿到会话的运行时 target；没有就先激活。
 *
 * 新建的草稿会话在发第一条消息前没有 runtime，此时直接调 /runtime/model 会 409。
 * 桌面端是在选模型时确保 agent 已启动，这里对齐：先 activate，再轮询状态等 runtime 出现。
 */
async function ensureRuntimeTarget(sessionId: string, timeoutMs = 15_000): Promise<RuntimeTarget> {
  if (!runtime) throw new Error("未连接");
  const { client, store } = runtime;

  const existing = store.targetOf(sessionId);
  if (existing) return existing;

  toast("正在启动会话运行时…");
  await client.activate(sessionId);

  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    await sleep(400);
    await store.refresh().catch(() => undefined);
    const target = store.targetOf(sessionId);
    if (target) return target;
  }
  throw new Error("会话运行时启动超时，请稍后重试或先发一条消息激活");
}

/* ------------------------------------------------------------------ */
/* 右侧工作区抽屉（Git / 文件）                                         */
/* ------------------------------------------------------------------ */

type WorkspaceTab = "git" | "files";

let workspaceTab: WorkspaceTab = "git";
let workspaceOpen = false;
/** 文件 Tab 的当前目录栈（相对项目根） */
let workspaceStack: string[] = [];
let workspacePreview: { path: string; content: string } | null = null;

function toggleWorkspacePanel(): void {
  if (workspaceOpen) {
    closeWorkspacePanel();
    return;
  }
  // 左右抽屉互斥：同时开着会互相遮挡，滑动手势也会打架
  refs?.hostBody?.classList.remove("show-sidebar");
  void openWorkspacePanel();
}

function closeWorkspacePanel(): void {
  workspaceOpen = false;
  refs?.hostBody?.classList.remove("show-workspace");
}

async function openWorkspacePanel(): Promise<void> {
  if (!runtime) return;
  const projectId = currentWorkspaceProjectId();
  if (!projectId) {
    toast("没有可用项目：请先在桌面端添加项目目录");
    return;
  }
  workspaceOpen = true;
  refs?.hostBody?.classList.add("show-workspace");
  await renderWorkspacePanel();
}

function currentWorkspaceProjectId(): string | null {
  if (!runtime) return null;
  const session = activeSessionId ? runtime.store.session(activeSessionId) : undefined;
  return session?.projectId ?? activeProjectId ?? runtime.store.projects()[0]?.id ?? null;
}

function currentWorkspaceProjectPath(): string {
  if (!runtime) return "";
  const project = runtime.store.projects().find((p) => p.id === currentWorkspaceProjectId());
  return typeof project?.path === "string" ? project.path : "";
}

async function renderWorkspacePanel(): Promise<void> {
  if (!runtime) return;
  const panel = refs?.workspacePanel;
  if (!panel) return;

  const projectId = currentWorkspaceProjectId();
  clear(panel);

  // 头部：标题 + 关闭
  const closeBtn = h("button", { class: "icon-btn", title: "关闭", text: "✕" });
  closeBtn.addEventListener("click", () => closeWorkspacePanel());
  panel.append(
    h("div", { class: "ws-head" }, [h("span", { class: "ws-title", text: "工作区" }), h("div", { class: "spacer" }), closeBtn]),
  );

  // Tab 切换（Git 默认）
  const gitTab = h("button", {
    class: `ws-tab${workspaceTab === "git" ? " is-active" : ""}`,
    text: "Git",
  });
  const filesTab = h("button", {
    class: `ws-tab${workspaceTab === "files" ? " is-active" : ""}`,
    text: "文件",
  });
  gitTab.addEventListener("click", () => {
    if (workspaceTab === "git") return;
    workspaceTab = "git";
    void renderWorkspacePanel();
  });
  filesTab.addEventListener("click", () => {
    if (workspaceTab === "files") return;
    workspaceTab = "files";
    void renderWorkspacePanel();
  });
  panel.append(h("div", { class: "ws-tabs" }, [gitTab, filesTab]));

  const content = h("div", { class: "ws-content" });
  panel.append(content);

  if (!projectId) {
    content.append(h("p", { class: "ws-empty", text: "没有可用项目" }));
    return;
  }

  if (workspaceTab === "git") await renderGitTab(content, projectId);
  else await renderFilesTab(content, projectId);
}

async function renderGitTab(content: HTMLElement, projectId: string): Promise<void> {
  if (!runtime) return;
  content.append(h("p", { class: "ws-empty", text: "读取 Git 状态…" }));

  let status: GitStatusView;
  let commits: GitCommit[] = [];
  try {
    const [statusRaw, logRaw] = await Promise.all([
      runtime.client.gitStatus(projectId),
      runtime.client.gitLog(projectId, 20).catch(() => null),
    ]);
    status = parseGitStatus(statusRaw, currentWorkspaceProjectPath());
    commits = logRaw ? parseGitLog(logRaw) : [];
  } catch (error) {
    clear(content);
    content.append(h("p", { class: "ws-empty", text: `读取失败：${describeError(error)}` }));
    return;
  }

  clear(content);

  if (!status.isRepo) {
    content.append(h("p", { class: "ws-empty", text: "这个项目不是 Git 仓库" }));
    return;
  }

  content.append(
    h("div", { class: "ws-branch" }, [
      h("span", { class: "ws-branch-icon", text: "⎇" }),
      h("span", { class: "ws-branch-name", text: status.branch || "（无分支）" }),
      h("span", { class: "ws-branch-count", text: `${status.total} 个变更` }),
    ]),
  );

  if (status.total === 0) {
    content.append(h("p", { class: "ws-empty", text: "工作区干净" }));
  } else {
    for (const group of status.groups) {
      content.append(h("div", { class: "ws-group", text: `${group.title}（${group.files.length}）` }));
      for (const file of group.files) {
        const row = h("button", { class: "ws-file", title: file.path }, [
          h("span", { class: `ws-letter ws-letter--${file.letter.toLowerCase()}`, text: file.letter }),
          h("span", { class: "ws-file-path", text: file.relativePath }),
        ]);
        row.addEventListener("click", () => void openFilePreview(projectId, file.relativePath));
        content.append(row);
      }
    }
  }

  if (commits.length > 0) {
    content.append(h("div", { class: "ws-group", text: `提交历史（${commits.length}）` }));
    for (const commit of commits) {
      content.append(
        h("div", { class: "ws-commit" }, [
          h("span", { class: "ws-commit-hash", text: commit.shortHash }),
          h("span", { class: "ws-commit-msg", text: commit.message || "（无提交信息）" }),
          h("span", { class: "ws-commit-meta", text: [commit.authorName, formatCommitDate(commit.authorDate)].filter(Boolean).join(" · ") }),
        ]),
      );
    }
  }
}

async function renderFilesTab(content: HTMLElement, projectId: string): Promise<void> {
  if (!runtime) return;
  const dirPath = workspaceStack.join("/");

  let nodes;
  try {
    nodes = await runtime.client.listFiles(projectId, dirPath);
  } catch (error) {
    content.append(h("p", { class: "ws-empty", text: `读取目录失败：${describeError(error)}` }));
    return;
  }

  const project = runtime.store.projects().find((p) => p.id === projectId);
  const rootName = projectLabel(project) ?? "项目";

  // 面包屑
  const crumbs = h("div", { class: "ws-crumbs" });
  const pushCrumb = (label: string, depth: number): void => {
    if (depth > 0) crumbs.append(h("span", { class: "ws-crumb-sep", text: "/" }));
    const btn = h("button", { class: "ws-crumb", text: label });
    btn.addEventListener("click", () => {
      workspaceStack = workspaceStack.slice(0, depth);
      workspacePreview = null;
      void renderWorkspacePanel();
    });
    crumbs.append(btn);
  };
  pushCrumb(rootName, 0);
  workspaceStack.forEach((segment, index) => pushCrumb(segment, index + 1));
  content.append(crumbs);

  const sorted = [...nodes].sort((a, b) => {
    const aDir = a.type === "directory" ? 0 : 1;
    const bDir = b.type === "directory" ? 0 : 1;
    if (aDir !== bDir) return aDir - bDir;
    return a.name.localeCompare(b.name, "zh-Hans-CN", { numeric: true });
  });

  if (sorted.length === 0) {
    content.append(h("p", { class: "ws-empty", text: "空目录" }));
  }

  for (const node of sorted) {
    const isDir = node.type === "directory";

    // 文件行末尾的 @ 引用按钮（目录不加，目录是用来进的）
    const mentionBtn = isDir
      ? null
      : h("span", {
          class: "ws-mention",
          text: "@",
          title: "引用到输入框",
          onclick: (event: MouseEvent) => {
            event.stopPropagation();
            closeWorkspacePanel();
            insertAtCursor(mentionText(node));
            toast("已引用到输入框");
          },
        });

    const row = h(
      "div",
      { class: "ws-file", title: node.relativePath, ...(isDir ? {} : { role: "button", tabindex: "0" }) },
      [
        h("span", { class: "ws-file-icon", text: isDir ? "📁" : "📄" }),
        h("span", { class: "ws-file-path", text: node.name }),
        ...(mentionBtn ? [mentionBtn] : []),
      ],
    );
    row.addEventListener("click", () => {
      if (isDir) {
        // 只取最后一段：万一服务端 name 里带了路径，也不会让面包屑越点越长
        const segment = node.name.split(/[\/]/).filter(Boolean).pop() ?? node.name;
        workspaceStack = [...workspaceStack, segment];
        workspacePreview = null;
        void renderWorkspacePanel();
      } else {
        void openFilePreview(projectId, node.relativePath);
      }
    });
    content.append(row);
  }

  if (workspacePreview) {
    content.append(h("div", { class: "ws-group", text: workspacePreview.path.split("/").pop() ?? "预览" }));
    content.append(h("pre", { class: "ws-preview", text: workspacePreview.content }));
  }
}

async function openFilePreview(projectId: string, path: string): Promise<void> {
  if (!runtime) return;
  workspaceTab = "files";
  const parts = path.replace(/\\/g, "/").split("/");
  const fileName = parts.pop() ?? path;
  workspaceStack = parts;
  workspacePreview = { path: fileName, content: "读取中…" };
  await renderWorkspacePanel();

  try {
    const file = await runtime.client.fileContent(projectId, path);
    if (!shouldPreviewFile(file.size)) {
      workspacePreview = {
        path: fileName,
        content: `文件过大（${Math.round(file.size / 1024)} KB），超过 ${Math.round(FILE_PREVIEW_MAX_BYTES / 1024)} KB 不预览。`,
      };
    } else {
      workspacePreview = { path: fileName, content: file.content || "（空文件）" };
    }
  } catch (error) {
    workspacePreview = { path: fileName, content: `读取失败：${describeError(error)}` };
  }
  await renderWorkspacePanel();
}

/* ------------------------------------------------------------------ */
/* 发送历史（悬浮按钮）                                                 */
/* ------------------------------------------------------------------ */

function openSentHistorySheet(): void {
  if (!runtime || !chatView) return;
  if (!activeSessionId) {
    toast("先选一个会话");
    return;
  }

  const all = collectJumpTargets(sessionHistory);
  if (all.length === 0) {
    openSheet("跳转到消息", (sheet) => {
      sheet.append(
        h("p", {
          class: "sheet-note",
          text: "这个会话里还没有可定位的消息（或历史尚未加载）。",
        }),
      );
    });
    return;
  }

  const jump = (id: string): void => {
    closeSheet();
    const found = chatView?.scrollToMessage(id) ?? false;
    if (!found) toast("这条消息不在已加载范围内，先向上滚动加载更早历史");
  };

  const render = (query = ""): void => {
    const items = filterJumpTargets(all, query);
    openSheet(`跳转到消息（${items.length}）`, (sheet) => {
      const input = h("input", { class: "sheet-input", placeholder: "搜索消息内容…", value: query });
      input.addEventListener("input", () => render(input.value));
      sheet.append(input);

      if (items.length === 0) {
        sheet.append(h("p", { class: "sheet-note", text: "没有匹配的消息" }));
        return;
      }

      for (const item of items) {
        const when = item.timestamp ? fmtShortTime(item.timestamp) : "";
        sheet.append(
          h(
            "button",
            {
              class: `sheet-item sheet-item--two jump-item jump-item--${item.role}`,
              onclick: () => jump(item.id),
            },
            [
              h("span", { class: "jump-head" }, [
                h("span", { class: `jump-role jump-role--${item.role}`, text: roleLabel(item.role) }),
                ...(when ? [h("span", { class: "jump-time", text: when })] : []),
              ]),
              h("span", { class: "jump-text", text: item.preview }),
            ],
          ),
        );
      }

      sheet.append(
        h("p", {
          class: "sheet-note",
          text: "点一条会滚动到那条消息并高亮。只覆盖已加载的历史，更早的消息需要先向上滚动加载。",
        }),
      );
    });
  };

  render("");
}

function fmtShortTime(ms: number): string {
  const date = new Date(ms);
  const mm = String(date.getMonth() + 1).padStart(2, "0");
  const dd = String(date.getDate()).padStart(2, "0");
  const hh = String(date.getHours()).padStart(2, "0");
  const mi = String(date.getMinutes()).padStart(2, "0");
  return `${mm}-${dd} ${hh}:${mi}`;
}

/* ------------------------------------------------------------------ */
/* @ 文件引用                                                          */
/* ------------------------------------------------------------------ */

async function openMentionSheet(): Promise<void> {
  if (!runtime) return;
  const sessionId = activeSessionId;
  const session = sessionId ? runtime.store.session(sessionId) : undefined;
  const projectId = session?.projectId ?? activeProjectId;
  const project = runtime.store.projects().find((item) => item.id === projectId);
  if (!projectId || !project) {
    toast("没有可用项目：请先在桌面端添加项目目录");
    return;
  }

  const projectName = projectLabel(project) ?? "项目";
  const stack: string[] = [];
  const cache = new Map<string, FileNode[]>();

  const closeAndInsert = (text: string): void => {
    closeSheet();
    insertAtCursor(text);
  };

  const render = async (query = ""): Promise<void> => {
    const dirPath = joinPath(stack);
    let nodes = cache.get(dirPath);
    if (!nodes) {
      try {
        nodes = await runtime!.client.listFiles(projectId, dirPath);
        cache.set(dirPath, nodes);
      } catch (error) {
        openSheet("引用文件", (sheet) => {
          sheet.append(
            h("p", { class: "sheet-note", text: `读取目录失败：${describeError(error)}` }),
            h("p", {
              class: "sheet-note",
              text:
                "如果是「Failed to fetch」：PiDeck 的文件接口当前不返回跨域头，WebView 会直接拦掉。" +
                "可以改用 Tailscale 的 HTTPS 地址重试，或先手动在输入框里打 @路径。",
            }),
          );
        });
        return;
      }
    }

    const view = buildMentionView(projectName, stack, nodes, query);
    openSheet("引用文件", (sheet) => {
      const crumbs = h("div", { class: "mention-crumbs" });
      view.crumbs.forEach((crumb, index) => {
        if (index > 0) crumbs.append(h("span", { class: "crumb-sep", text: "/" }));
        crumbs.append(
          h("button", {
            class: "crumb",
            text: crumb.label,
            onclick: () => {
              stack.length = crumb.depth;
              void render(queryInput.value);
            },
          }),
        );
      });
      sheet.append(crumbs);

      const queryInput = h("input", {
        class: "sheet-input",
        placeholder: "过滤当前目录…",
        value: query,
      });
      queryInput.addEventListener("input", () => {
        void render(queryInput.value);
      });
      sheet.append(queryInput);

      if (view.nodes.length === 0) {
        sheet.append(h("p", { class: "sheet-note", text: "这一层没有可引用的条目" }));
        return;
      }

      for (const node of view.nodes) {
        const isDir = isDirectory(node);
        const row = h(
          "button",
          {
            class: "sheet-item sheet-item--two",
            onclick: () => {
              if (isDir) {
                stack.push(node.name.split(/[\/]/).filter(Boolean).pop() ?? node.name);
                void render("");
              } else {
                closeAndInsert(mentionText(node));
              }
            },
          },
          [
            h("span", { class: "conn-name", text: `${isDir ? "📁" : "📄"} ${node.name}` }),
            h("span", { class: "conn-url", text: node.relativePath }),
          ],
        );
        sheet.append(row);
      }
    });
  };

  await render("");
}

/* ------------------------------------------------------------------ */
/* / 斜线命令                                                          */
/* ------------------------------------------------------------------ */

/**
 * 斜线命令面板。
 *
 * 之前是「等两个网络请求都回来才弹面板」，其中 /api/prompts 还要跨域且服务端
 * 不带 CORS 头，浏览器要等预检/超时，所以点一下卡好几秒。
 * 现在：先用内置命令立刻弹出，再异步补齐运行时命令与提示词。
 */
async function openSlashSheet(): Promise<void> {
  if (!runtime) return;
  const { client, store } = runtime;

  const target = activeSessionId ? store.targetOf(activeSessionId) : undefined;
  let all = mergeSlashItems({ builtin: BUILTIN_SLASH });
  let query = "";

  const render = (nextQuery = query): void => {
    query = nextQuery;
    const filtered = filterSlashItems(all, query);
    const sections = groupSlashItems(filtered);

    openSheet("斜线命令", (sheet) => {
      const queryInput = h("input", { class: "sheet-input", placeholder: "搜索命令…", value: query });
      queryInput.addEventListener("input", () => render(queryInput.value));
      sheet.append(queryInput);

      if (sections.length === 0) {
        sheet.append(
          h("p", {
            class: "sheet-note",
            text: target ? "没有匹配的命令" : "当前会话还没启动运行时，只显示内置命令；发一条消息后可看到全部命令",
          }),
        );
        return;
      }

      for (const section of sections) {
        sheet.append(h("div", { class: "sheet-group", text: section.title }));
        for (const item of section.items) {
          sheet.append(
            h(
              "button",
              {
                class: "sheet-item sheet-item--two",
                onclick: () => {
                  closeSheet();
                  insertAtCursor(slashText(item));
                },
              },
              [
                h("span", { class: "conn-name", text: `/${item.name}` }),
                h("span", { class: "conn-url", text: item.description || " " }),
              ],
            ),
          );
        }
      }
    });

    const input = document.querySelector<HTMLInputElement>(".sheet-backdrop .sheet-input");
    if (query) input?.focus();
  };

  // 立刻弹出（只有内置命令，零等待）
  render("");

  // 后台补齐：运行时命令 + 提示词库，哪个先回就先刷新
  const patch = async (): Promise<void> => {
    const [runtimeCommands, prompts] = await Promise.all([
      activeSessionId && target
        ? client.listRuntimeCommands(activeSessionId, target).catch(() => [])
        : Promise.resolve([]),
      client
        .listPrompts()
        .then((body) => {
          const list = (body as { prompts?: unknown[] })?.prompts;
          return Array.isArray(list) ? list : [];
        })
        .catch(() => []),
    ]);

    all = mergeSlashItems({ runtime: runtimeCommands, prompts, builtin: BUILTIN_SLASH });
    // 面板可能已被关掉
    if (!document.querySelector(".sheet-backdrop")) return;
    render(query);
  };

  void patch();
}

/** 把文本插到输入框光标处（没有光标就追加到末尾）。 */
function insertAtCursor(text: string): void {
  if (!refs) return;
  const el = refs.composer;
  const start = el.selectionStart ?? el.value.length;
  const end = el.selectionEnd ?? el.value.length;
  el.value = `${el.value.slice(0, start)}${text}${el.value.slice(end)}`;
  const caret = start + text.length;
  el.focus();
  el.setSelectionRange(caret, caret);
  autosize(el);
}

/* ------------------------------------------------------------------ */
/* 外观：主题                                                          */
/* ------------------------------------------------------------------ */

function openThemeSheet(): void {
  const current = getThemeMode();
  openSheet("外观", (sheet) => {
    sheet.append(h("div", { class: "sheet-group", text: "主题" }));
    for (const mode of THEME_MODES) {
      const isActive = mode === current;
      sheet.append(
        h(
          "button",
          {
            class: `sheet-item sheet-item--two${isActive ? " is-active" : ""}`,
            onclick: () => {
              setThemeMode(mode);
              closeSheet();
              toast(`主题：${themeLabel(mode)}`);
            },
          },
          [
            h("span", { class: "conn-name", text: themeLabel(mode) }),
            h("span", {
              class: "conn-url",
              text:
                mode === "system"
                  ? "跟随手机系统的深浅色自动切换"
                  : mode === "dark"
                    ? "始终使用深色界面"
                    : "始终使用浅色界面",
            }),
          ],
        ),
      );
    }

    // 任务完成通知开关（和主题放同一个面板，避免再开一个入口）
    sheet.append(h("div", { class: "sheet-group", text: "通知" }));
    const enabled = isTaskNotifyEnabled();
    sheet.append(
      h(
        "button",
        {
          class: `sheet-item sheet-item--two${enabled ? " is-active" : ""}`,
          onclick: () => {
            const next = !isTaskNotifyEnabled();
            setTaskNotifyEnabled(next);
            closeSheet();
            if (next) {
              void ensureTaskChannel();
              void ensureNotificationPermission().then((granted) => {
                toast(granted ? "已开启任务完成提醒" : "系统未授予通知权限，请到系统设置里开启");
              });
            } else {
              toast("已关闭任务完成提醒");
            }
          },
        },
        [
          h("span", { class: "conn-name", text: `任务完成提醒${enabled ? "（已开启）" : "（已关闭）"}` }),
          h("span", {
            class: "conn-url",
            text: "会话跑完一轮时弹通知；App 切后台后由看护服务继续盯着",
          }),
        ],
      ),
    );
  });
}

/* ------------------------------------------------------------------ */
/* 连接管理：历史连接列表                                              */
/* ------------------------------------------------------------------ */

function openConnectionSheet(): void {
  const current = runtime?.pairing;
  const history = listPairings();

  openSheet("连接", (sheet) => {
    if (history.length === 0) {
      sheet.append(h("p", { class: "sheet-note", text: "还没有保存过的连接。" }));
    }

    for (const item of history) {
      const isCurrent = current ? item.baseUrl === current.baseUrl : false;
      const row = h(
        "button",
        { class: `sheet-item sheet-item--two sheet-conn${isCurrent ? " is-active" : ""}` },
        [
          h("span", { class: "conn-name", text: pairingLabel(item) }),
          h("span", { class: "conn-url", text: item.baseUrl }),
          ...(isCurrent ? [h("span", { class: "conn-badge", text: "当前" })] : []),
        ],
      );

      attachPressable(row, {
        onTap: () => {
          closeSheet();
          void switchToPairing(item);
        },
        onLongPress: () => openPairingMenu(item, isCurrent),
      });

      sheet.append(row);
    }

    sheet.append(
      h("button", {
        class: "sheet-item sheet-item--primary",
        text: "＋ 添加新连接",
        onclick: () => {
          closeSheet();
          runtime?.store.stop();
          runtime = null;
          renderPairing();
        },
      }),
    );

    if (current) {
      sheet.append(
        h("button", {
          class: "sheet-item",
          text: "断开当前连接",
          onclick: () => {
            closeSheet();
            clearPairing();
            runtime?.store.stop();
            runtime = null;
            renderPairing();
          },
        }),
      );
    }
  });
}

function openPairingMenu(item: SavedPairing, isCurrent: boolean): void {
  openSheet(pairingLabel(item), (sheet) => {
    sheet.append(
      h("button", {
        class: "sheet-item",
        text: "重命名",
        onclick: () => {
          closeSheet();
          openSheet("重命名连接", (inner) => {
            const input = h("input", {
              class: "sheet-input",
              value: item.label ?? "",
              placeholder: "例如：家里台式机",
            });
            inner.append(
              input,
              h("button", {
                class: "sheet-item sheet-item--primary",
                text: "保存",
                onclick: () => {
                  renamePairing(item.baseUrl, input.value);
                  closeSheet();
                  openConnectionSheet();
                },
              }),
            );
            setTimeout(() => input.focus(), 60);
          });
        },
      }),
      h("button", {
        class: "sheet-item",
        text: "更新令牌 / 重新配对",
        onclick: () => {
          closeSheet();
          runtime?.store.stop();
          runtime = null;
          renderPairing({ link: `${item.baseUrl}/?token=${encodeURIComponent(item.token)}` });
        },
      }),
      isCurrent
        ? h("p", { class: "sheet-note", text: "当前正在使用这个连接。" })
        : h("button", {
            class: "sheet-item sheet-item--danger",
            text: "删除这条记录",
            onclick: () => {
              forgetPairing(item.baseUrl);
              closeSheet();
              openConnectionSheet();
            },
          }),
    );
  });
}

async function switchToPairing(item: SavedPairing): Promise<void> {
  try {
    await connect({ baseUrl: item.baseUrl, token: item.token, label: item.label });
    closeSheet();
  } catch (error) {
    toast(`连接失败：${describeError(error)}`);
    renderPairing({
      link: `${item.baseUrl}/?token=${encodeURIComponent(item.token)}`,
      error: describeError(error),
    });
  }
}

/* ------------------------------------------------------------------ */
/* 模型 / 思考档位                                                      */
/* ------------------------------------------------------------------ */

let modelCache: { sections: ModelPickerSection[]; fetchedAt: number } | null = null;
const MODEL_CACHE_MS = 5 * 60_000;

async function loadModelSections(force = false): Promise<ModelPickerSection[]> {
  if (!runtime) return [];
  if (!force && modelCache && Date.now() - modelCache.fetchedAt < MODEL_CACHE_MS) {
    return modelCache.sections;
  }
  const models = await runtime.client.listModels(force);
  const sections = buildModelSections(models);
  modelCache = { sections, fetchedAt: Date.now() };
  return sections;
}

function currentModelOption(sections: ModelPickerSection[]) {
  const session = activeSessionId ? runtime?.store.session(activeSessionId) : undefined;
  return findModel(sections, session?.model?.provider, session?.model?.modelId);
}

async function openModelSheet(): Promise<void> {
  if (!runtime) return;
  const sessionId = activeSessionId;
  if (!sessionId) {
    toast("先选一个会话，再切模型");
    return;
  }

  openSheet("选择模型", (sheet) => {
    sheet.append(h("p", { class: "sheet-note", text: "加载模型列表…" }));
  });

  let sections: ModelPickerSection[];
  try {
    sections = await loadModelSections();
  } catch (error) {
    openSheet("选择模型", (sheet) => {
      sheet.append(h("p", { class: "sheet-note", text: `模型列表加载失败：${describeError(error)}` }));
    });
    return;
  }

  const active = currentModelOption(sections);
  openSheet("选择模型", (sheet) => {
    if (sections.length === 0) {
      sheet.append(h("p", { class: "sheet-note", text: "没有可用模型：先在桌面端完成 Provider 配置。" }));
      return;
    }

    // 搜索框：模型多的时候（几十个）靠滚动翻太慢，直接按名字/ID/供应商过滤
    const search = h("input", {
      class: "sheet-search",
      type: "search",
      placeholder: `搜索 ${countModels(sections)} 个模型（名称 / ID / 供应商）`,
      "aria-label": "搜索模型",
    });

    const list = h("div", { class: "sheet-list" });
    const empty = h("p", { class: "sheet-note", text: "没有匹配的模型" });
    empty.classList.add("is-hidden");

    const renderList = (query: string): void => {
      list.textContent = "";
      const filtered = filterModelSections(sections, query);
      empty.classList.toggle("is-hidden", countModels(filtered) > 0);

      for (const section of filtered) {
        list.append(h("div", { class: "sheet-group", text: section.provider }));
        for (const model of section.models) {
          const isActive = active?.key === model.key;
          list.append(
            h(
              "button",
              {
                class: `sheet-item sheet-item--two${isActive ? " is-active" : ""}`,
                onclick: () => void applyModel(model.provider, model.modelId, model.name),
              },
              [
                h("span", { class: "conn-name", text: model.name }),
                h("span", { class: "conn-url", text: modelSubtitle(model) }),
              ],
            ),
          );
        }
      }
    };

    search.addEventListener("input", () => renderList(search.value));
    // Enter 直接选第一个匹配项，键盘操作不用点
    search.addEventListener("keydown", (event) => {
      if (event.key !== "Enter") return;
      const first = filterModelSections(sections, search.value)[0]?.models[0];
      if (first) void applyModel(first.provider, first.modelId, first.name);
    });

    sheet.append(search, list, empty);
    renderList("");
    // 刻意不自动聚焦：手机上一进面板就弹键盘会挡住大半个列表，
    // 而且多数人是来「翻一翻选一个」的。想搜就点一下输入框。
  });
}

async function applyModel(provider: string, modelId: string, modelName: string): Promise<void> {
  if (!runtime || !activeSessionId) return;
  const sessionId = activeSessionId;
  closeSheet();

  try {
    const target = await ensureRuntimeTarget(sessionId);
    await runtime.client.setModel(sessionId, target, { provider, modelId, modelName });
    learnedPrefs.set(sessionId, { ...(learnedPrefs.get(sessionId) ?? {}), provider, modelId, modelName });
    await runtime.store.refresh();
    renderComposerState();
    renderHeader(runtime.store.state);
    toast(`已切换到 ${modelName}`);
  } catch (error) {
    toast(`切换失败：${describeError(error)}`);
  }
}

async function openThinkingSheet(): Promise<void> {
  if (!runtime) return;
  const sessionId = activeSessionId;
  if (!sessionId) {
    toast("先选一个会话，再切思考档位");
    return;
  }
  const session = runtime.store.session(sessionId);
  const currentLevel = session?.thinkingLevel ?? "";

  let levels: string[] = [];
  try {
    const sections = await loadModelSections();
    const model = findModel(sections, session?.model?.provider, session?.model?.modelId);
    levels = model?.thinkingLevels ?? [];
  } catch {
    levels = [];
  }

  if (levels.length === 0) {
    levels = ["off", "minimal", "low", "medium", "high", "xhigh", "max"];
  }

  openSheet("思考档位", (sheet) => {
    for (const level of levels) {
      const isActive = level === currentLevel;
      sheet.append(
        h(
          "button",
          {
            class: `sheet-item sheet-item--two${isActive ? " is-active" : ""}`,
            onclick: () => void applyThinking(level),
          },
          [
            h("span", { class: "conn-name", text: thinkingLabel(level) }),
            h("span", { class: "conn-url", text: thinkingHint(level) }),
          ],
        ),
      );
    }
  });
}

async function applyThinking(level: string): Promise<void> {
  if (!runtime || !activeSessionId) return;
  const sessionId = activeSessionId;
  closeSheet();

  try {
    const target = await ensureRuntimeTarget(sessionId);
    await runtime.client.setThinking(sessionId, target, level);
    learnedPrefs.set(sessionId, { ...(learnedPrefs.get(sessionId) ?? {}), thinkingLevel: level });
    await runtime.store.refresh();
    renderComposerState();
    renderHeader(runtime.store.state);
    toast(`思考档位：${thinkingLabel(level)}`);
  } catch (error) {
    toast(`切换失败：${describeError(error)}`);
  }
}

/* ------------------------------------------------------------------ */
/* 长按菜单：会话 / 项目                                                */
/* ------------------------------------------------------------------ */

function closeSheet(): void {
  document.querySelectorAll(".sheet-backdrop").forEach((node) => node.remove());
}

function openSheet(title: string, build: (sheet: HTMLElement) => void): void {
  closeSheet();
  // 工作区抽屉的遮罩铺满全屏，会吃掉后续的触摸事件；弹菜单前先收起来
  closeWorkspacePanel();
  // 收起软键盘：键盘占位时视口被压缩，fixed 面板容易被推到可视区之外
  if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
  const sheet = h("div", { class: "sheet" }, [h("h2", { text: title })]);
  build(sheet);
  sheet.append(h("button", { class: "sheet-item sheet-item--cancel", text: "取消", onclick: closeSheet }));

  const backdrop = h(
    "div",
    {
      class: "sheet-backdrop",
      onclick: (event: MouseEvent) => {
        if (event.target === backdrop) closeSheet();
      },
    },
    [sheet],
  );
  (document.getElementById("app") ?? document.body).append(backdrop);
}

function openSessionMenu(sessionId: string): void {
  if (!runtime) return;
  const session = runtime.store.session(sessionId);
  if (!session) {
    // 会话记录可能刚被别处删掉；给出反馈而不是静默无动作
    toast("这个会话已不在列表里，刷新中…");
    void runtime.store.refresh();
    return;
  }

  openSheet(session.title || "会话", (sheet) => {
    sheet.append(
      h("button", {
        class: "sheet-item",
        text: "重命名",
        onclick: () => openRenameSheet(sessionId, session.title ?? ""),
      }),
      h("button", {
        class: "sheet-item",
        text: "复制会话",
        onclick: () => void runSessionAction(sessionId, "copy", "已复制会话"),
      }),
      h("button", {
        class: "sheet-item",
        text: "导出 HTML",
        onclick: () => exportSessionHtml(sessionId),
      }),
      h("button", {
        class: "sheet-item sheet-item--danger",
        text: "删除会话",
        onclick: () => confirmDeleteSession(sessionId),
      }),
    );
  });
}

function openRenameSheet(sessionId: string, current: string): void {
  openSheet("重命名会话", (sheet) => {
    const input = h("input", { class: "sheet-input", value: current, placeholder: "输入新标题" });
    sheet.append(
      input,
      h("button", {
        class: "sheet-item sheet-item--primary",
        text: "保存",
        onclick: () => {
          const title = input.value.trim();
          if (!title) {
            toast("标题不能为空");
            return;
          }
          void runSessionAction(sessionId, "update", "已重命名", { title });
        },
      }),
    );
    setTimeout(() => input.focus(), 60);
  });
}

function confirmDeleteSession(sessionId: string): void {
  openSheet("删除会话？", (sheet) => {
    sheet.append(
      h("p", { class: "sheet-note", text: "会话记录与会话文件都会被删除，不可恢复。" }),
      h("button", {
        class: "sheet-item sheet-item--danger",
        text: "确认删除",
        onclick: () => void runSessionAction(sessionId, "delete", "已删除会话", {}, sessionId),
      }),
    );
  });
}

async function runSessionAction(
  sessionId: string,
  action: "update" | "copy" | "delete",
  successText: string,
  patch: Record<string, unknown> = {},
  deletedId?: string,
): Promise<void> {
  if (!runtime) return;
  closeSheet();
  try {
    await runtime.client.sessionRecordAction(sessionId, action, patch);
    if (deletedId && deletedId === activeSessionId) {
      channel?.close();
      channel = null;
      activeSessionId = null;
      chatView?.clear();
      chatView?.setEmpty("选择左侧会话，或直接输入消息新建一个。");
    }
    await runtime.store.refresh();
    renderSidebar(runtime.store.state);
    renderHeader(runtime.store.state);
    renderComposerState();
    toast(successText);
  } catch (error) {
    toast(describeError(error));
  }
}

function exportSessionHtml(sessionId: string): void {
  if (!runtime) return;
  closeSheet();
  const { client } = runtime;
  // 服务端提供 GET 下载端点，token 走 query（浏览器直接打开也能通过鉴权）
  const url = `${client.baseUrl}/api/sessions/${encodeURIComponent(sessionId)}/export-html?token=${encodeURIComponent(client.token)}`;
  const opened = window.open(url, "_system");
  if (!opened) window.open(url, "_blank");
  toast("正在导出 HTML…");
}

function openProjectMenu(projectId: string): void {
  if (!runtime) return;
  const project = runtime.store.projects().find((item) => item.id === projectId);
  if (!project) return;
  const collapsed = collapsedProjects.has(projectId);

  openSheet(projectLabel(project) ?? "项目", (sheet) => {
    sheet.append(
      h("button", {
        class: "sheet-item",
        text: "在此项目新建会话",
        onclick: () => {
          closeSheet();
          void createSession(projectId);
        },
      }),
      h("button", {
        class: "sheet-item",
        text: collapsed ? "展开该项目" : "收起该项目",
        onclick: () => {
          closeSheet();
          toggleProject(projectId);
        },
      }),
    );

    if (project.kind !== "chat") {
      sheet.append(
        h("button", {
          class: "sheet-item sheet-item--danger",
          text: "从列表移除项目",
          onclick: () => confirmRemoveProject(projectId),
        }),
      );
    } else {
      sheet.append(h("p", { class: "sheet-note", text: "内置 Chat 项目不能移除。" }));
    }
  });
}

function confirmRemoveProject(projectId: string): void {
  openSheet("移除项目？", (sheet) => {
    sheet.append(
      h("p", { class: "sheet-note", text: "只从 PiDeck 的列表里移除，不会删除磁盘上的项目文件。" }),
      h("button", {
        class: "sheet-item sheet-item--danger",
        text: "确认移除",
        onclick: () => {
          closeSheet();
          void (async () => {
            if (!runtime) return;
            try {
              await runtime.client.deleteProject(projectId);
              if (activeProjectId === projectId) activeProjectId = null;
              await runtime.store.refresh();
              renderSidebar(runtime.store.state);
              renderHeader(runtime.store.state);
              toast("已移除项目");
            } catch (error) {
              toast(describeError(error));
            }
          })();
        },
      }),
    );
  });
}

/* ------------------------------------------------------------------ */
/* 启动                                                                */
/* ------------------------------------------------------------------ */

function bootstrap(): void {
  const saved = loadPairing();
  if (!saved) {
    renderPairing();
    return;
  }
  void connect(saved).catch((error: unknown) => {
    renderPairing({ link: saved.baseUrl ? `${saved.baseUrl}/?token=${saved.token}` : "", error: describeError(error) });
  });
}

bootstrap();
