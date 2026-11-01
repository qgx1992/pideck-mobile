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
  pairingRisk,
  normalizeBaseUrl,
  describeError,
  isAuthError,
  type PiDeckState,
  type PendingUiRequest,
} from "@core/index.ts";
import {
  savePairing,
  loadPairing,
  ensureMultiEndpointDefaults,
  clearAllPairings,
  listPairings,
  listEnabledPairings,
  setPairingEnabled,
  forgetPairing,
  renamePairing,
  pairingLabel,
} from "./platform/storage.ts";
import type { SavedPairing } from "./platform/storage.ts";
import { createAppFetch, isNativeSseEnabled } from "./platform/fetch.ts";
import { compressImageFile } from "./platform/image.ts";
import { canAddImage, formatBytes, imageFileProblem, MAX_IMAGES, toImageAttachment } from "./ui/image-attach-model.ts";
import type { PendingImage } from "./ui/image-attach-model.ts";
import { decodeQrFromImageFile, scanQrFromCamera } from "./platform/qr-scan.ts";
import type { ScanOutcome } from "./platform/qr-scan.ts";
import { describeScanMiss, pickScannedLink } from "./ui/pairing-scan-model.ts";
import { initTheme, setThemeMode, getThemeMode, themeLabel, THEME_MODES, onThemeChange } from "./platform/theme.ts";
import type { ThemeMode } from "./platform/theme.ts";
import { initSafeArea } from "./platform/safe-area.ts";
import { sleep } from "./platform/sleep.ts";
import { ChatView } from "./ui/chat-view.ts";
import { messageText } from "./ui/chat-view.ts";
import { actionsForRole, confirmTextFor } from "./ui/message-actions-model.ts";
import type { MessageAction } from "./ui/message-actions-model.ts";
import { acpToolNameOf, backendCapabilities, normalizeBackend } from "./ui/backend-capabilities.ts";
import { isPromptRejection, rejectionMessage, shouldRollbackRejection } from "./ui/acp-notice.ts";
import {
  DEFAULT_BACKEND_CHOICE,
  backendLockedHint,
  backendOptions,
  backendPatch,
  backendSwitchAllowed,
  choiceKey,
  createSessionInput,
  describeChoice,
  missingAcpTool,
} from "./ui/backend-selector.ts";
import type { BackendChoice, BackendOption } from "./ui/backend-selector.ts";
import { branchNodes, shortBranchTitle, shouldShowBranchBar } from "./ui/branch-model.ts";
import {
  buildSidebarGroups,
  projectLabel,
  resolveExpandedProjects,
  sessionCountByProject,
  sortProjectsForPicker,
} from "./ui/sidebar-model.ts";
import { createSettingsPage } from "./ui/settings-view.ts";
import type { SettingsData, SettingsHandlers, SettingsPage } from "./ui/settings-view.ts";
import {
  applyExtensionToggle,
  applySkillToggle,
  normalizeDshPlugins,
  normalizeExtensions,
  normalizeModels,
  normalizeSkills,
} from "./ui/settings-model.ts";
import type { DshPluginRow, ExtensionRow, ModelRow, SkillSection } from "./ui/settings-model.ts";
import {
  goalPhaseLabel,
  goalRoundsText,
  parseDshGoal,
  parseDshSkills,
  parseDshSubagentHistory,
  parseDshSubagents,
  DSH_EMPTY_GOAL,
  DSH_EMPTY_SKILLS,
  DSH_EMPTY_SUBAGENTS,
} from "./ui/dsh-tools-model.ts";
import {
  parseSecurityState,
  securityChipLabel,
  securityChoices,
  securityDisabledHint,
  securityLevelFor,
  composerToolChips,
} from "./ui/security-level-model.ts";
import { emptyStateSubtitle, emptyStateTitle, suggestionContext, suggestionsFor } from "./ui/empty-suggestions.ts";
import {
  filterPromptEntries,
  importedPrompts,
  promptCommandSource,
  promptInsertText,
  PROMPT_EMPTY_HINT,
  PROMPT_NO_RUNTIME_HINT,
} from "./ui/prompt-picker-model.ts";
import type { PromptEntry } from "./ui/prompt-picker-model.ts";
import { buildPlanSubmission } from "./ui/plan-mode.ts";
import { renderFlatSection, renderProjectGroup } from "./ui/sidebar-view.ts";
import {
  ACTIVITY_RECENT_LIMIT,
  SIDEBAR_TABS,
  buildActivityView,
  buildActivityViewMulti,
  chatSessionsMulti,
  endpointCatalogs,
  searchSessions,
  searchSessionsMulti,
  tabCounts,
  workspaceProjects,
} from "./ui/sidebar-tabs-model.ts";
import type { EndpointState, SidebarTab } from "./ui/sidebar-tabs-model.ts";
import { dedupeEndpoints, describeEndpointCap, endpointIdOf, endpointLabelOf, MAX_LIVE_ENDPOINTS } from "./ui/endpoint-model.ts";
import { loadSidebarTab, saveSidebarTab } from "./platform/ui-prefs.ts";
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
import { baseName, downloadBlockedReason, downloadDoneText, mimeForName } from "./ui/file-download-model.ts";
import { dirStackOf, fileNameOf, toProjectRelative } from "./ui/file-ref-model.ts";
import { isNativeShell, saveTextFile } from "./platform/save-file.ts";
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
import {
  sendModeActionLabel,
  sendModeHint,
  sendModeLabel,
  sendModeSwitchToast,
  shouldShowRunningSend,
  streamingBehaviorFor,
  toggleSendMode,
} from "./ui/send-mode.ts";
import type { SendMode } from "./ui/send-mode.ts";
import { attachPressable } from "./ui/press.ts";
import { attachDrawerGestures } from "./ui/drawer-gestures.ts";
import { attachBackButton } from "./platform/back-button.ts";
import { armSplashWatchdog, hideSplash, setSplashHint } from "./ui/splash.ts";
import {
  collectRunWatchTitles,
  consumeLaunchSession,
  consumeNotifiedKeys,
  isRunWatchActive,
  isAppInBackground,
  shouldStartRunWatch,
  splitNotifiedKeys,
  startRunWatch,
  stopRunWatch,
} from "./platform/run-watch.ts";
import {
  closeFlutterPage,
  isFlutterHostAvailable,
  onFlutterPageVisibility,
  onFlutterSettingsAction,
  openFlutterPage,
  pushFlutterNotice,
  pushFlutterSettings,
} from "./platform/flutter-host.ts";
import {
  modelChipLabel,
  modelChipTitle,
  resolveSessionPrefs,
} from "./ui/session-prefs.ts";
import { countModels, filterModelSections } from "./ui/model-picker.ts";
import { createChevron, setChevronExpanded } from "./ui/chevron.ts";
import { createTodoStrip } from "./ui/todo-strip.ts";
import { createSubagentStrip } from "./ui/subagent-strip.ts";
import { normalizeSubagents } from "./ui/subagent-model.ts";
import type { SubagentView } from "./ui/subagent-model.ts";
import { USAGE_REFRESH_COOLDOWN_MS, buildUsageView, formatResetAt } from "./ui/provider-usage-model.ts";
import type { UsageView } from "./ui/provider-usage-model.ts";
import { parseSessionTodo, shouldAutoExpand } from "./ui/todo-model.ts";
import type { TodoView } from "./ui/todo-model.ts";
import { lastUserTimestamp } from "./ui/turn-clock.ts";
import {
  historyProgressLabel,
  historyStatusLabel,
  historyTimeLabel,
  clearedTodoIdentity,
  mergeTodoHistory,
  nextTodoLiveState,
  planKeyOf,
  sealTodoHistory,
  todoRoundBaseline,
  todoSignature,
} from "./ui/todo-history-model.ts";
import type { TodoHistoryEntry, TodoLiveState } from "./ui/todo-history-model.ts";
import {
  clearTodoHistory,
  loadDismissedTodo,
  loadTodoHistory,
  saveDismissedTodo,
  saveTodoHistory,
} from "./platform/todo-history.ts";
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
import { perfRows, readPerfMetrics } from "./ui/perf-metrics.ts";
import type { PerfMetrics } from "./ui/perf-metrics.ts";
import type { LearnedPrefs } from "./ui/session-prefs.ts";
import {
  askNotificationBody,
  askNotificationTitle,
  completionBody,
  completionTitle,
  diffCompletedRuns,
  diffNewPendingRequests,
  shouldNotifyEnd,
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

/** 构建时由 vite define 注入（见 vite.config.ts），避免和 package.json 漂移 */
declare const __APP_VERSION__: string;
const APP_VERSION = typeof __APP_VERSION__ === "string" ? __APP_VERSION__ : "dev";

interface Runtime {
  /** 归一化后的 endpoint id（origin），所有按端点的缓存都用它做前缀 */
  id: string;
  /** 显示名（备注名 > host:port） */
  label: string;
  client: PiDeckClient;
  store: StateStore;
  pairing: { baseUrl: string; token: string; label?: string };
}

/** 活跃端点（界面、会话通道、header 都看它） */
let runtime: Runtime | null = null;
/** 所有已连接的端点（含活跃）—— 每条都常驻一条 /api/events */
let endpoints: Runtime[] = [];
/** 每台设备上次打开的会话，切回来时落回原处 */
const lastSessionByEndpoint = new Map<string, string>();

/**
 * 端点作用域 key。
 *
 * 草稿 / 图片 / 学到的模型偏好 / todo 折叠 / 通知去重 这些缓存都按会话 id 存，
 * 而两台机器上的会话 id 是各自生成的 —— 不前缀就会串台。
 */
function epKey(id: string): string {
  return `${runtime?.id ?? "local"}::${id}`;
}

/** 额度缓存要按「设备 + 供应商」，否则两台的 deepseek 会显示成同一个余额 */
function usageKey(provider: string): string {
  return `${runtime?.id ?? "local"}::${provider}`;
}
let channel: SessionChannel | null = null;
let activeSessionId: string | null = null;
let activeProjectId: string | null = null;
let chatView: ChatView | null = null;
let lastRunStatus: string = "idle";
/** 当前会话已加载的历史消息（供「发送历史」使用） */
let sessionHistory: WireMessage[] = [];
/** 每个会话各自的输入框草稿：切走时保存，切回来恢复，避免内容串会话 */
const sessionDrafts = new Map<string, string>();
/** 上一次的运行时快照（按端点分开）：用来检测「跑完了」 */
const previousRuntimesByEndpoint = new Map<string, RuntimeSnapshot[]>();
/** 上一次的待答卡片快照（按端点分开）：用来检测「AI 新提问」 */
const previousPendingByEndpoint = new Map<string, PendingSnapshot[]>();
/**
 * 运行中发消息的行为：排队（默认）或插话。
 * 跨会话保留：用户选了插话，切到别的会话还在跑时应该还是插话。
 */
let sendMode: SendMode = "queue";
/**
 * 计划模式开关（每个会话各自记）。
 * 一次性语义：发送成功后自动关闭，避免用户忘了还开着、后续提问全被限制成只读。
 */
let planModeOn = false;
const planModeBySession = new Map<string, boolean>();
/** 后台服务已经替我们通知过的会话（回到前台后别再弹一次） */
const notifiedByService = new Set<string>();
/**
 * 当前会话这一轮的结局（reducer 的状态，比 /api/state 更细）。
 * 用来区分「跑完」和「用户自己点了停止」——后者不该再弹通知。
 */
const runOutcome = new Map<string, string>();
/** 同理，但针对「AI 提问」 */
const notifiedAsksByService = new Set<string>();
/**
 * 从 /runtime/state 学到的模型/思考偏好。
 * catalog 里 session.model 常为 null（实测 223 个里只有 19 个有），
 * 真实模型只有运行时知道，学到后缓存起来供 chip 显示。
 */
const learnedPrefs = new Map<string, LearnedPrefs>();
let disposers: Array<() => void> = [];

/**
 * Flutter 页是否盖在 WebView 上面。
 *
 * 混合栈下 document.visibilityState 只说明「WebView 有没有被盖住」，
 * 不说明「App 在不在前台」—— 跳进 Flutter 页时它会变 hidden，而用户就在看屏幕。
 * 原生 FlutterHostActivity.onStart/onStop 会把这个真实情况报过来。
 */
let flutterPageOpen = false;

/**
 * 设置页当前是不是 Flutter 版（而不是 Web 版）。
 *
 * 两件事依赖它：① renderSettings() 要把数据推过去；② 提示语要走原生 SnackBar，
 * 因为 WebView 的 toast 在 Flutter 页盖住时根本看不见。
 */
let flutterSettingsOpen = false;

/* ------------------------------------------------------------------ */
/* 侧栏视图状态（项目分组 / 折叠 / 搜索）                                */
/* ------------------------------------------------------------------ */

const COLLAPSED_KEY = ["pideck", "app", "collapsed", "v2"].join("-");
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
  // 配对页是本地渲染，出来就说明启动完成了
  hideSplash();
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
  // 公网 / 明文地址的二次确认：扫码结果是可被伪造的，不能拿到就直连
  const confirmEl = h("div", { class: "pairing-confirm is-hidden" });

  const hideConfirm = (): void => {
    clear(confirmEl);
    confirmEl.className = "pairing-confirm is-hidden";
  };

  const setStatus = (text: string, tone: "ok" | "error" | "" = ""): void => {
    statusEl.textContent = text;
    statusEl.className = `status${tone ? ` status--${tone}` : ""}`;
  };

  /** 公网 / 明文地址的卡片式确认（不用 window.confirm，WebView 里不可控） */
  const showConnectConfirm = (messages: readonly string[], onAccept: () => void): void => {
    clear(confirmEl);
    confirmEl.className = "pairing-confirm";
    confirmEl.append(
      h("div", { class: "pairing-confirm-title", text: "要连接这台设备吗？" }),
      ...messages.map((text) => h("div", { class: "pairing-confirm-line", text })),
      h("div", { class: "pairing-confirm-actions" }, [
        h("button", {
          class: "btn btn-ghost",
          text: "取消",
          onclick: () => {
            hideConfirm();
            setStatus("已取消，没有发出任何请求");
          },
        }),
        h("button", {
          class: "btn",
          text: "仍然连接",
          onclick: () => {
            hideConfirm();
            onAccept();
          },
        }),
      ]),
    );
  };

  /** 解析链接 → 握手。链接本身坏了也要给提示，不能只挂一个未捕获的 rejection。 */
  const attemptConnect = async (
    linkText: string,
    manualBase: string,
    manualToken: string,
    options: { confirmed?: boolean } = {},
  ): Promise<void> => {
    let baseUrl = "";
    let token = "";
    try {
      if (linkText) {
        const payload = parsePairingInput(linkText);
        baseUrl = payload.baseUrl;
        token = payload.token;
      } else {
        baseUrl = manualBase;
        token = manualToken;
      }
    } catch (error) {
      setStatus(describeError(error), "error");
      return;
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

    // 出局域网（或者公网还走明文）先问一句：扫码结果可以被伪造
    if (!options.confirmed) {
      let normalized = baseUrl;
      try {
        normalized = normalizeBaseUrl(baseUrl);
      } catch {
        /* 交给下面的 connect 报具体的错 */
      }
      const risk = pairingRisk({ baseUrl: normalized });
      if (risk.needsConfirm) {
        showConnectConfirm(risk.messages, () => void attemptConnect(linkText, manualBase, manualToken, { confirmed: true }));
        setStatus("连接前请先确认这台设备");
        return;
      }
    }
    hideConfirm();

    setStatus("正在握手…");
    try {
      await connect({ baseUrl, token });
    } catch (error) {
      setStatus(describeError(error), "error");
    }
  };
  const connectBtn = h("button", { class: "btn", text: "连接" });
  connectBtn.addEventListener("click", () => {
    connectBtn.disabled = true;
    void attemptConnect(linkInput.value.trim(), baseInput.value.trim(), tokenInput.value.trim())
      .finally(() => {
        connectBtn.disabled = false;
      });
  });

  /** 扫码/选图的结果统一从这里进 */
  const applyScanOutcome = (outcome: ScanOutcome): void => {
    if (outcome.kind === "failed") {
      setStatus(outcome.message, "error");
      return;
    }
    if (outcome.kind === "cancelled") {
      setStatus("已取消扫码");
      return;
    }
    const pick = pickScannedLink([outcome.text]);
    if (!pick.link) {
      setStatus(describeScanMiss(pick), "error");
      return;
    }
    // 填进输入框再连：连不上时用户能直接在框里改地址
    linkInput.value = pick.link;
    setStatus("已识别二维码，正在握手…");
    void attemptConnect(pick.link, "", "");
  };

  const scanBtn = h("button", { class: "btn scan-camera", text: "扫码连接" });
  scanBtn.addEventListener("click", () => {
    scanBtn.disabled = true;
    setStatus("正在打开相机…");
    void scanQrFromCamera()
      .then(applyScanOutcome)
      .catch((error: unknown) => setStatus(describeError(error), "error"))
      .finally(() => {
        scanBtn.disabled = false;
      });
  });

  // 相册/截图里的二维码：不需要相机权限，纯 JS 解码（两端都能用）
  const albumBtn = h("button", { class: "btn btn-ghost scan-album", text: "从相册选图" });
  const fileInput = h("input", { type: "file" });
  fileInput.accept = "image/*";
  fileInput.hidden = true;
  fileInput.addEventListener("change", () => {
    const file = fileInput.files?.[0];
    // 清掉 value，否则同一张图选第二次不触发 change
    fileInput.value = "";
    if (!file) return;
    albumBtn.disabled = true;
    setStatus("正在识别图片…");
    void decodeQrFromImageFile(file)
      .then(applyScanOutcome)
      .catch((error: unknown) => setStatus(describeError(error), "error"))
      .finally(() => {
        albumBtn.disabled = false;
      });
  });
  albumBtn.addEventListener("click", () => fileInput.click());

  if (options.error) setStatus(options.error, "error");

  // 已经连着设备时（从「添加设备」进来的）必须能退回去，
  // 否则整页被 clear 掉，返回键也没有层可关，用户就卡死在配对页了。
  const canGoBack = endpoints.length > 0;
  const backBtn = h("button", {
    class: "pairing-back",
    text: "← 返回",
    title: "回到已连接的设备",
    onclick: () => {
      if (!runtime) return;
      teardown();
      renderMain();
    },
  });

  mount(
    h("div", { class: "pairing" }, [
      canGoBack ? h("div", { class: "pairing-head" }, [backBtn]) : null,
      h("h1", { text: "连接到你的 PiDeck" }),
      h("p", {
        text: "先在电脑上打开：设置 → Web 服务 → 局域网 Web 服务（保持令牌鉴权开启），再扫描/复制那里的链接。",
      }),
      h("div", { class: "pair-actions" }, [scanBtn, albumBtn, fileInput]),
      h("div", { class: "divider", text: "或者粘贴链接" }),
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
      confirmEl,
      h("p", {
        text: canGoBack
          ? `这是在追加设备：连接成功后会和已连接的 ${endpoints.length} 台设备一起显示，不会断开它们。`
          : isDev
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
  // 配对新设备 = 把它加进「同时连接」的集合，而不是把其它设备踢掉
  const existing = listEnabledPairings().map((item) => ({ ...item, savedAt: item.savedAt || Date.now() }));
  const incoming: SavedPairing = {
    baseUrl: pairing.baseUrl,
    token: pairing.token,
    savedAt: Date.now(),
    ...(pairing.label ? { label: pairing.label } : {}),
  };
  let list = dedupeEndpoints([...existing, incoming]);
  if (list.length > MAX_LIVE_ENDPOINTS) {
    const trimmed = list.filter((item) => endpointIdOf(item.baseUrl) !== endpointIdOf(incoming.baseUrl));
    trimmed.sort((a, b) => (b.lastUsedAt ?? b.savedAt) - (a.lastUsedAt ?? a.savedAt));
    list = dedupeEndpoints([...trimmed.slice(0, MAX_LIVE_ENDPOINTS - 1), incoming]);
    toast(`同时最多保持 ${MAX_LIVE_ENDPOINTS} 台实时连接，已自动停用最久未用的那台`);
  }
  await connectAll(list, { activeBaseUrl: pairing.baseUrl });
}

/** 单台设备的握手超时：多台吋一台关机不能把启动拖成 20s×N。 */
const ENDPOINT_HANDSHAKE_TIMEOUT_MS = 6_000;

function buildStore(client: PiDeckClient): StateStore {
  return new StateStore({
    client,
    onStatus: (status) => {
      updateConnectionDot(status);
      renderDeviceChip();
    },
    onError: (error) => {
      if (!isAuthError(error)) console.warn("[pideck]", describeError(error));
    },
  });
}

/** 建一个端点的 client/store（不 start，不渲染）。 */
async function buildEndpoint(item: SavedPairing): Promise<Runtime> {
  const baseUrl = item.baseUrl || (isDev ? window.location.origin : "");
  if (!baseUrl) throw new Error("缺少服务器地址");

  const client = new PiDeckClient({
    baseUrl,
    token: item.token,
    fetchImpl: appFetch,
    timeoutMs: ENDPOINT_HANDSHAKE_TIMEOUT_MS,
  });
  const health = await client.health();
  if (health.service !== "PiDeck") {
    throw new Error(`对面不是 PiDeck（返回 service=${health.service}），检查地址是否填错`);
  }

  const store = buildStore(client);
  await store.refresh();

  savePairing({
    baseUrl: item.baseUrl,
    token: item.token,
    savedAt: Date.now(),
    ...(item.label ? { label: item.label } : {}),
  });

  return {
    id: endpointIdOf(baseUrl),
    label: endpointLabelOf({ baseUrl, label: item.label }),
    client,
    store,
    pairing: { baseUrl: item.baseUrl, token: item.token, ...(item.label ? { label: item.label } : {}) },
  };
}

/**
 * 连接（或重建）全部端点。
 *
 * 关键点：
 *   - 先停掉旧的 store（以前切连接时旧 SSE 一直在后台重连）
 *   - 健康检查并发、单台超时 6s：5 台里有一台关机不能把启动拖死
 *   - 一台失败不影响其他台；全部失败才抛错（配对页用）
 */
async function connectAll(
  pairings: readonly SavedPairing[],
  options: { activeBaseUrl?: string } = {},
): Promise<void> {
  const unique = dedupeEndpoints(pairings.map((item) => ({ ...item, id: endpointIdOf(item.baseUrl) })));
  if (unique.length === 0) throw new Error("缺少服务器地址");

  // 重建前先全部停掉：以前切连接时旧 SSE 会在后台一直重连
  for (const endpoint of endpoints) endpoint.store.stop();
  endpoints = [];
  runtime = null;

  const settled = await Promise.allSettled(unique.map((item) => buildEndpoint(item)));
  const built: Runtime[] = [];
  const failures: unknown[] = [];
  for (const result of settled) {
    if (result.status === "fulfilled") built.push(result.value);
    else failures.push(result.reason);
  }
  if (built.length === 0) {
    throw failures[0] instanceof Error ? failures[0] : new Error(String(failures[0] ?? "连接失败"));
  }

  endpoints = built;
  const wanted = options.activeBaseUrl ? endpointIdOf(options.activeBaseUrl) : "";
  runtime = built.find((endpoint) => endpoint.id === wanted) ?? built[0]!;
  for (const endpoint of built) endpoint.store.start();

  savePairing({ baseUrl: runtime.pairing.baseUrl, token: runtime.pairing.token, savedAt: Date.now(), ...(runtime.pairing.label ? { label: runtime.pairing.label } : {}) });

  if (built.length > 1) {
    const cap = describeEndpointCap(built.length);
    if (cap) toast(cap);
  }
  if (failures.length > 0) {
    toast(`${failures.length} 台设备没连上：${describeError(failures[0])}`);
  }

  renderMain();
}

/** 切到另一台已连接的设备：不重建 store，只换活跃端点。 */
function activateEndpoint(id: string, options: { restore?: boolean } = {}): void {
  const next = endpoints.find((endpoint) => endpoint.id === id);
  if (!next || next === runtime) return;
  const previous = runtime;
  if (previous && activeSessionId) lastSessionByEndpoint.set(previous.id, activeSessionId);

  runtime = next;
  next.store.start();
  savePairing({ baseUrl: next.pairing.baseUrl, token: next.pairing.token, savedAt: Date.now(), ...(next.pairing.label ? { label: next.pairing.label } : {}) });
  closeSheet();
  renderMain();

  if (options.restore === false) return;
  const remembered = lastSessionByEndpoint.get(next.id);
  const state = next.store.state;
  const exists = remembered ? state.sessions.some((session) => session.id === remembered) : false;
  if (remembered && exists) void openSession(remembered);
}

/** 断开全部端点：每台 store 都要停，否则会留下后台 SSE 重连循环。 */
function disconnectAll(): void {
  for (const endpoint of endpoints) endpoint.store.stop();
  endpoints = [];
  runtime = null;
  activeSessionId = null;
  activeProjectId = null;
  channel?.close();
  channel = null;
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
  /** 侧栏分段按钮容器 */
  sideTabs: HTMLElement;
  /** 顶栏的设备切换 chip（只有多台设备时可见） */
  deviceBtn: HTMLButtonElement;
  chatRoot: HTMLElement;
  /** 分支家族条（fork 过的会话才显示） */
  branchBar: HTMLElement;
  composer: HTMLTextAreaElement;
  /** 主按钮：空闲时「发送」，运行中变「停止」 */
  primaryBtn: HTMLButtonElement;
  modelBtn: HTMLButtonElement;
  thinkingBtn: HTMLButtonElement;
  /** 安全级别 chip（只有 pi 会话 + 桌面端 0.9.9+ 才显示） */
  securityBtn: HTMLButtonElement;
  /** DSH 工具面板入口（只有 dsh 会话才显示） */
  dshBtn: HTMLButtonElement;
  /** 上下文用量：有数据才显示 */
  contextBtn: HTMLButtonElement;
  contextRing: HTMLElement;
  contextText: HTMLElement;
  /** 运行中且输入框有东西时的「排队 / 插话」发送按钮 */
  queueSendBtn: HTMLButtonElement;
  /** 计划模式开关（发完自动关） */
  planBtn: HTMLButtonElement;
  /** 待发送图片的缩略图容器 */
  attachments: HTMLElement;
  /** 任务清单条带（agent 维护的 todo） */
  todoStrip: ReturnType<typeof createTodoStrip>;
  /** 子代理活动条（pi-subagents 插件） */
  subagentStrip: ReturnType<typeof createSubagentStrip>;
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
  const settingsBtn = h("button", {
    class: "icon-btn",
    title: "设置",
    "aria-label": "设置",
  });
  settingsBtn.append(gearIcon());
  const newSessionBtn = h("button", { class: "icon-btn", title: "新建会话（先选工作区）", text: "+" });

  const searchInput = h("input", {
    class: "sidebar-search",
    type: "search",
    placeholder: "搜索会话…",
    value: sidebarQuery,
  });

  const sidebarScroll = h("div", { class: "sidebar-scroll" });
  /** 活动 / 聊天 / 项目 分段（对齐桌面端侧栏顶部） */
  const sideTabs = h("div", { class: "side-tabs" });
  const themeBtn = h("button", { class: "side-foot-btn", text: "外观" });
  const pairBtn = h("button", { class: "side-foot-btn", text: "连接" });
  const hideBtn = h("button", { class: "side-foot-btn", text: "收起" });

  const sidebar = h("aside", { class: "sidebar" }, [
    h("div", { class: "sidebar-top" }, [brand, settingsBtn, newSessionBtn]),
    searchInput,
    sideTabs,
    sidebarScroll,
    h("div", { class: "sidebar-foot" }, [themeBtn, pairBtn, hideBtn]),
  ]);

  /* ---------------------------- 主区 ---------------------------- */

  const headerTitle = h("div", { class: "title", text: "PiDeck" });
  const headerMeta = h("div", { class: "meta-line", text: "" });
  /** 标题下面那行：原来是「模型名 · 思考 高」纯文本，现在是两个可点的 chip */
  const headerSub = h("div", { class: "sub sub--chips" });
  const menuBtn = h("button", { class: "icon-btn", title: "项目与会话", text: "☰" });
  const workspaceBtn = h("button", { class: "icon-btn", title: "工作区（Git / 文件）", text: "▤" });
  const deviceBtn = h("button", { class: "device-chip is-hidden", title: "切换设备" });
  const header = h("header", { class: "header" }, [
    menuBtn,
    h("div", { class: "header-text" }, [
      h("div", { class: "title-row" }, [headerTitle, headerMeta]),
      headerSub,
    ]),
    deviceBtn,
    workspaceBtn,
  ]);

  const chatRoot = h("div", { class: "chat-body" });
  /** 分支家族条：会话被 fork 过才出现 */
  const branchBar = h("div", { class: "branch-bar is-hidden" });
  const composer = h("textarea", { placeholder: "输入消息…", style: `height:${COMPOSER_MIN_H}px` });
  const primaryBtn = h("button", { class: "btn btn-primary-action", text: "发送", title: "发送（Enter）" });
  const mentionBtn = h("button", { class: "chip chip--icon", text: "@", title: "引用项目内文件" });
  const slashBtn = h("button", { class: "chip chip--icon", text: "/", title: "斜线命令" });
  const modelBtn = h("button", { class: "chip chip--header", text: "模型", title: "选择模型" });
  const thinkingBtn = h("button", { class: "chip chip--header", text: "思考", title: "选择思考档位" });
  // 跳转历史消息：原来在输入框右下角，现在也搬到这行
  const historyBtn = h("button", {
    class: "chip chip--header",
    title: "跳转到历史消息",
    text: "历史",
    "aria-label": "跳转到历史消息",
  });
  // 顶栏第二行：模型 / 思考档位 / 历史（都从输入框区域搬上来）
  // 不放在右侧动作区：多设备时那里还要摆设备名 + 工作区，会直接把标题挤没
  headerSub.append(modelBtn, thinkingBtn, historyBtn);
  // 安全级别（pi 会话的会话级工具权限）：只有桌面端 0.9.9+ 下发 state.security 时才出现
  const securityBtn = h("button", {
    class: "chip is-hidden",
    text: "安全",
    title: "选择本会话的安全级别",
    "aria-label": "安全级别",
  });
  // DSH 会话的工具面板入口（目标 / 子代理 / 技能）；pi 会话不显示
  const dshBtn = h("button", {
    class: "chip is-hidden",
    text: "DSH",
    title: "DSH 工具：目标 / 子代理 / 技能",
    "aria-label": "DSH 工具",
  });
  const contextRing = h("span", { class: "ctx-ring" });
  const contextText = h("span", { class: "ctx-text", text: "上下文" });
  const contextBtn = h(
    "button",
    { class: "chip chip--ctx is-hidden", title: "上下文用量", "aria-label": "上下文用量" },
    [contextRing, contextText],
  );
  // 运行中且输入框有东西时，在「历史」左侧出现一个「排队 / 插话」按钮：
  // 点一下按当前模式发送，长按切换模式（主按钮此刻是「停止」，不能用来发）
  const queueSendBtn = h("button", {
    class: "composer-fab composer-fab--queue is-hidden",
    text: sendModeLabel(sendMode),
    "aria-label": "运行中发送",
  });
  // @ / 放在输入框内部左下角，历史按钮固定在右下角
  // （输入框底部预留一条整宽的空白带，左右按钮都落在带子里，不会压到文字）
  const promptBtn = h("button", { class: "chip chip--icon", title: "提示词", "aria-label": "提示词" });
  promptBtn.append(bookmarkIcon());
  // 计划模式：一次性，发完自动关（桌面端同规则）
  const planBtn = h("button", {
    class: "chip chip--plan",
    text: "计划",
    title: "计划模式：只读分析并输出 Plan: 编号计划，发完自动关闭",
    "aria-label": "计划模式",
  });
  const attachBtn = h("button", { class: "chip chip--icon", text: "图", title: "发送图片（相册 / 拍照）" });
  const imageInput = h("input", { type: "file" });
  imageInput.accept = "image/*";
  imageInput.multiple = true;
  imageInput.hidden = true;
  const inputTools = h("div", { class: "composer-inline-tools" }, [mentionBtn, promptBtn, planBtn, slashBtn, attachBtn]);
  /** 输入框右下角的按钮组：只剩「排队 / 插话」（历史已上提到顶栏） */
  const fabs = h("div", { class: "composer-fabs" }, [queueSendBtn]);
  const composerInput = h("div", { class: "composer-input" }, [composer, inputTools, fabs]);
  /** 待发送的图片缩略图（有图才占位） */
  const attachmentsEl = h("div", { class: "composer-attachments is-hidden" });
  const chips = h("div", { class: "composer-chips" }, [securityBtn, dshBtn, contextBtn]);
  const todoStrip = createTodoStrip();
  const subagentStrip = createSubagentStrip();
  // 记住用户手动折叠的选择（优先于「有进行中就自动展开」）
  todoStrip.el.querySelector(".todo-head")?.addEventListener("click", () => {
    if (!activeSessionId) return;
    const current = todoCollapsed.get(epKey(activeSessionId)) ?? !shouldAutoExpand(todoView);
    todoCollapsed.set(epKey(activeSessionId), !current);
  });
  subagentStrip.el.querySelector(".subagent-head")?.addEventListener("click", () => {
    if (!activeSessionId) return;
    const current = subagentCollapsed.get(epKey(activeSessionId)) ?? true;
    subagentCollapsed.set(epKey(activeSessionId), !current);
  });
  const composerEl = h("div", { class: "composer" }, [
    todoStrip.el,
    attachmentsEl,
    composerInput,
    h("div", { class: "composer-row" }, [chips, primaryBtn]),
  ]);
  // 子代理条钉在顶栏下方：它在滚动区之外，不占输入框的空间
  const chat = h("section", { class: "chat" }, [header, branchBar, subagentStrip.el, chatRoot, composerEl]);

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
    onMessageAction: (messageId, role) => openMessageActions(messageId, role),
    onFileRef: (path, line) => void openChatFileRef(path, line),
  });
  renderEmptyState(null);

  refs = {
    headerTitle,
    headerMeta,
    headerSub,
    headerDot: dot,
    main,
    body,
    sidebarScroll,
    sideTabs,
    deviceBtn,
    chatRoot,
    branchBar,
    composer,
    primaryBtn,
    modelBtn,
    thinkingBtn,
  securityBtn,
  dshBtn,
    contextBtn,
    contextRing,
    contextText,
    queueSendBtn,
    planBtn,
    attachments: attachmentsEl,
    todoStrip,
    subagentStrip,
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
      // 全屏设置页优先级最高：它盖住了抽屉和面板
      settingsOpen: settingsPage !== null,
      // 底部面板都存在 .sheet-backdrop 里，直接看 DOM 最准（面板可能由别处打开）
      sheetOpen: document.querySelector(".sheet-backdrop") !== null,
      workspaceOpen: refs?.hostBody?.classList.contains("show-workspace") ?? false,
      sidebarOpen: refs?.hostBody?.classList.contains("show-sidebar") ?? false,
    }),
    onIntent: (intent) => {
      if (intent === "close-settings") closeSettings();
      else if (intent === "close-sheet") closeSheet();
      else if (intent === "close-workspace") closeWorkspacePanel();
      else closeDrawer();
    },
    onLeave: () => toast("再按一次返回退出"),
  }).then((dispose) => disposers.push(dispose));

  // App 从后台回到前台：SSE 常被系统冻死（不报错也不来数据），
  // 这里主动补一次状态、重拉历史、并强制重连会话流。
  const onVisibleAgain = (): void => {
    if (typeof document === "undefined") return;
    // 可见性不再上报给原生看护服务：只有原生的 Activity 计数能判断「App 在不在前台」，
    // WebView 的 visibilityState 在 Flutter 页盖住时会误报后台（踩过：在 Flutter 页停留时照弹通知）。
    if (document.visibilityState !== "visible") return;
    // WebView 重新可见 = Flutter 页肯定已经不在上面了。
    // 兼当兵底：万一 onStop 那条事件因为 WebView 被暂停而丢失，标志也不会卡在 true。
    flutterPageOpen = false;
    flutterSettingsOpen = false;
    void resumeAfterBackground();
  };
  document.addEventListener("visibilitychange", onVisibleAgain);
  disposers.push(() => document.removeEventListener("visibilitychange", onVisibleAgain));

  // 跳进 Flutter 页时 WebView 被 pause，visibilityState 变 hidden，但 App 还在前台。
  // 用原生回传的标志把「被盖住」和「真后台」分开，否则会在用户看着屏幕时弹完成通知。
  void onFlutterPageVisibility((open) => {
    flutterPageOpen = open;
    // Flutter 页关掉了 —— 不管它是设置页还是别的，设置页都不在上面了。
    // 不依赖 Dart 侧记得发 close 动作：用户按返回键、被系统回收都要能复位。
    if (!open) flutterSettingsOpen = false;
  }).then((detach) => disposers.push(detach));

  // Flutter 设置页上的一次操作，回传给与 Web 版共用的 handler 执行。
  void onFlutterSettingsAction((action) => {
    dispatchFlutterSettingsAction(action);
  }).then((detach) => disposers.push(detach));

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
  newSessionBtn.addEventListener("click", () => openWorkspacePicker());
  settingsBtn.addEventListener("click", () => openSettings());
  workspaceBtn.addEventListener("click", () => toggleWorkspacePanel());
  deviceBtn.addEventListener("click", () => openEndpointSheet());
  wsScrim.addEventListener("click", () => closeWorkspacePanel());
  historyBtn.addEventListener("click", () => openSentHistorySheet());
  mentionBtn.addEventListener("click", () => void openMentionSheet());
  slashBtn.addEventListener("click", () => void openSlashSheet());
  modelBtn.addEventListener("click", () => void openModelSheet());
  thinkingBtn.addEventListener("click", () => void openThinkingSheet());
  securityBtn.addEventListener("click", () => void openSecuritySheet());
  dshBtn.addEventListener("click", () => void openDshToolsSheet());
  contextBtn.addEventListener("click", () => openContextSheet());
  // 一个按钮两种行为：运行中=停止，否则=发送
  primaryBtn.addEventListener("click", () => {
    if (isRunActive()) void stop();
    else void send();
  });
  // 输入框右下角的「排队 / 插话」：点一下发送，长按切换模式
  attachPressable(queueSendBtn, {
    onTap: () => void send(),
    onLongPress: () => {
      // ACP 没有「插话」：只有一个排队行为，长按切换只会给人假选择
      if (!backendCapabilities(activeBackend()).steer) {
        toast("这个后端不支持插话，运行中只能排队");
        return;
      }
      sendMode = toggleSendMode(sendMode);
      renderComposerState();
      toast(sendModeSwitchToast(sendMode));
    },
  });
  promptBtn.addEventListener("click", () => void openPromptPicker());
  planBtn.addEventListener("click", () => {
    planModeOn = !planModeOn;
    renderComposerState();
    toast(planModeOn ? "计划模式已开启：本轮只做只读分析并给计划" : "已切回普通模式");
  });
  attachBtn.addEventListener("click", () => imageInput.click());
  imageInput.addEventListener("change", () => {
    const files = [...(imageInput.files ?? [])];
    // 清掉 value，否则同一张图选第二次不触发 change
    imageInput.value = "";
    if (files.length === 0) return;
    void addImageFiles(files);
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
    // 输入变化会影响「运行中发送」按钮的显隐，所以走完整的 composer 状态
    renderComposerState();
    if (activeSessionId) {
      const text = composer.value;
      if (text.trim()) sessionDrafts.set(epKey(activeSessionId), text);
      else sessionDrafts.delete(epKey(activeSessionId));
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
  previousRuntimesByEndpoint.clear();
  previousPendingByEndpoint.clear();
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
    detectCompletedRuns(runtime.id, state);
    syncTurnWithState(state);
    renderSidebar(state);
    renderHeader(state);
    if (activeSessionId) restorePending(activeSessionId);
    else chatView?.setPending([]);
  });
  disposers.push(unsubscribe);

  // 非活跃端的状态变化也要重画侧栏（聚合视图靠它），顺带跑一遍「跑完了」检测
  for (const endpoint of endpoints) {
    if (endpoint === runtime) continue;
    disposers.push(
      endpoint.store.subscribe(() => {
        if (!runtime) return;
        detectCompletedRuns(endpoint.id, endpoint.store.state);
        renderSidebar(runtime.store.state);
        renderDeviceChip();
      }),
    );
  }

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
  renderAttachments();
  updateConnectionDot(store.getStatus());

  // 启动后停在会话界面，不再自动抽出侧栏：
  // 空态里已经写清「选择左侧会话，或直接输入消息新建一个」，比强制弹抽屉更少打扰
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

/* ---------------------------- 待发送图片 ---------------------------- */

/**
 * 每个会话各自的待发送图片（与草稿同一思路）：切走再切回来不应该丢。
 * 键用 sessionId，未选会话时用空串（新建会话时那些图会跟着新会话走）。
 */
const sessionImages = new Map<string, PendingImage[]>();

function imageKey(): string {
  return `${runtime?.id ?? "local"}::${activeSessionId ?? ""}`;
}

function currentImages(): PendingImage[] {
  const key = imageKey();
  let list = sessionImages.get(key);
  if (!list) {
    list = [];
    sessionImages.set(key, list);
  }
  return list;
}

function clearCurrentImages(): void {
  sessionImages.delete(imageKey());
}

/** 重画缩略图行；没有图就整行隐藏，不占输入框高度。 */
function renderAttachments(): void {
  if (!refs) return;
  const list = currentImages();
  clear(refs.attachments);
  refs.attachments.classList.toggle("is-hidden", list.length === 0);

  list.forEach((image, index) => {
    const thumb = h("img", { class: "attach-img", "aria-label": image.name ?? `图片 ${index + 1}` });
    thumb.src = image.dataUrl;
    thumb.alt = image.name ?? `图片 ${index + 1}`;
    const remove = h("button", { class: "attach-remove", text: "×", "aria-label": "移除这张图片" });
    remove.addEventListener("click", () => {
      currentImages().splice(index, 1);
      renderAttachments();
      renderComposerState();
    });
    const item = h("div", { class: "attach" }, [
      thumb,
      remove,
      h("span", { class: "attach-meta", text: formatBytes(image.bytes) }),
    ]);
    refs?.attachments.append(item);
  });
}

/** 选完图就压：宁可现在花一秒，也不要发送时因为 413 失败。 */
async function addImageFiles(files: readonly File[]): Promise<void> {
  const list = currentImages();
  for (const file of files) {
    const problem = imageFileProblem(file);
    if (problem) {
      toast(problem);
      continue;
    }
    if (!canAddImage(list.length)) {
      toast(`最多 ${MAX_IMAGES} 张图`);
      break;
    }
    try {
      const image = await compressImageFile(file);
      list.push(image);
      renderAttachments();
      renderComposerState();
    } catch (error) {
      toast(describeError(error));
    }
  }
}

function renderHeader(state: PiDeckState): void {
  if (!refs || !runtime) return;
  const learned = activeSessionId ? learnedPrefs.get(epKey(activeSessionId)) : undefined;
  const view = buildHeaderView(state, activeSessionId, activeProjectId, learned);
  // ACP 会话：标题旁必须说清楚背后是哪个 CLI，否则看标题根本不知道在跟谁对话
  const acpSession = activeSessionId ? runtime.store.session(activeSessionId) : undefined;
  if (normalizeBackend(acpSession?.backend) === "acp") {
    const tool = acpToolNameOf(state.acpTools, acpSession?.acpToolId);
    view.meta = [`ACP${tool ? ` · ${tool}` : ""}`, view.meta].filter(Boolean).join(" · ");
  }

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
  // 副标题已经是两个 chip（模型 / 思考），纯文本只留给无障碍读屏
  if (view.subtitle) refs.headerSub.setAttribute("aria-label", view.subtitle);
  else refs.headerSub.removeAttribute("aria-label");

  if (refs.headerDot) {
    refs.headerDot.classList.toggle("is-running", view.isRunning);
    refs.headerDot.title = view.statusLabel ? `会话状态：${view.statusLabel}` : "";
  }

  if (document.title !== view.docTitle) document.title = view.docTitle;

  renderBranchBar(state);
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
  renderSideTabs(state);
  renderDeviceChip();
  clear(refs.sidebarScroll);

  const states = endpointStates();
  const multi = states.length > 1;

  /** 在指定端点执行动作：不是当前活跃端就先切过去 */
  const onEndpoint = (endpointId: string, action: () => void): void => {
    if (runtime?.id !== endpointId) activateEndpoint(endpointId, { restore: false });
    if (runtime?.id === endpointId) action();
  };

  const makeCtx = (endpoint: EndpointState | null) => {
    const ownState = endpoint ? endpoint.state : state;
    const isActive = !endpoint || endpoint.id === runtime?.id;
    const run = endpoint?.id ?? runtime?.id ?? "";
    return {
      activeSessionId: isActive ? activeSessionId : null,
      activeProjectId: isActive ? activeProjectId : null,
      expandedProjects: resolveExpandedProjects(ownState, expandedSessions, isActive ? activeSessionId : null),
      runningSessionIds: new Set(
        ownState.runtimes.filter((item) => item.status === "running").map((item) => item.sessionId),
      ),
      // acp 会话列表里只能看到 acpToolId，徽标要靠它翻成 CLI 名字
      acpTools: ownState.acpTools,
      handlers: {
        onToggleProject: (projectId: string) => toggleProject(projectId),
        onOpenSession: (sessionId: string) => onEndpoint(run, () => void openSession(sessionId)),
        // 项目行上的「+」是快捷新建：沿用上次选的后端（ACP 工具被删则自动退回 pi）
        onCreateSession: (projectId: string) =>
          onEndpoint(run, () =>
            void createSession(projectId, loadLastBackendChoice(backendOptions(ownState.acpTools))),
          ),
        onSessionMenu: (sessionId: string) => onEndpoint(run, () => openSessionMenu(sessionId)),
        onProjectMenu: (projectId: string) => onEndpoint(run, () => openProjectMenu(projectId)),
        onToggleSessions: (projectId: string) => toggleSessionsExpanded(projectId),
      },
    };
  };

  // 搜索时忽略分段：窄屏上按项目分组反而难扫，直接给一张扁平清单
  const query = sidebarQuery.trim();
  if (query) {
    const rows = multi ? searchSessionsMulti(states, query) : searchSessions(state, query);
    const section = renderFlatSection(rows, makeCtx(null), { empty: "没有匹配的会话" });
    if (section) refs.sidebarScroll.append(section);
    return;
  }

  if (sidebarTab === "active") {
    const view = multi ? buildActivityViewMulti(states) : buildActivityView(state);
    const live = renderFlatSection(view.live, makeCtx(null), {
      title: "运行中",
      hideWhenEmpty: true,
      preview: 6,
      expanded: flatExpanded.has("active-live"),
      onToggle: () => toggleFlat("active-live"),
    });
    if (live) refs.sidebarScroll.append(live);

    const recent = renderFlatSection(view.recent, makeCtx(null), {
      title: "最近会话",
      preview: ACTIVITY_RECENT_LIMIT,
      empty: "还没有会话；发一条消息就会出现在这里",
      expanded: flatExpanded.has("active-recent"),
      onToggle: () => toggleFlat("active-recent"),
    });
    if (recent) refs.sidebarScroll.append(recent);
    return;
  }

  if (sidebarTab === "chats") {
    const rows = multi
      ? chatSessionsMulti(states)
      : chatSessionsMulti([{ id: runtime.id, label: runtime.label, state }]);
    const section = renderFlatSection(rows, makeCtx(null), {
      preview: 12,
      empty: "没有聊天会话（桌面端没有启用内置 Chat 项目）",
      expanded: flatExpanded.has("chats"),
      onToggle: () => toggleFlat("chats"),
    });
    if (section) refs.sidebarScroll.append(section);
    return;
  }

  // 项目分段：内置 Chat 归到「聊天」；多设备时按设备分块
  const catalogs = multi
    ? endpointCatalogs(states)
    : [{ endpointId: runtime.id, endpointLabel: runtime.label, projects: workspaceProjects(state.projects), sessions: state.sessions, running: 0 }];

  let rendered = 0;
  for (const catalog of catalogs) {
    const groups = buildSidebarGroups(
      { projects: catalog.projects, sessions: catalog.sessions },
      "",
      collapsedProjects,
    );
    if (groups.length === 0) continue;
    rendered += groups.length;

    if (multi) {
      refs.sidebarScroll.append(
        h("div", { class: `device-head${catalog.endpointId === runtime?.id ? " is-active" : ""}` }, [
          h("span", { class: "device-head-name", text: catalog.endpointLabel }),
          h("span", { class: "device-head-count", text: String(catalog.projects.length) }),
          ...(catalog.running > 0 ? [h("span", { class: "pulse-dot pulse-dot--sm" })] : []),
        ]),
      );
    }

    const endpointState = states.find((item) => item.id === catalog.endpointId) ?? null;
    const endpointCtx = makeCtx(endpointState);
    refs.sidebarScroll.append(...groups.map((group) => renderProjectGroup(group, endpointCtx)));
  }

  if (rendered === 0) {
    refs.sidebarScroll.append(
      h("p", { class: "side-empty", text: "还没有项目，请先在桌面端添加目录" }),
    );
  }
}

/* ---------------------------- 侧栏分段 ---------------------------- */

/**
 * 侧栏当前分段；用户选过就记住，下次打开还在同一段。
 * 首次打开默认落在「活动」：与桌面端一致，先看运行中的任务和最近会话。
 */
let sidebarTab: SidebarTab = loadSidebarTab() ?? "active";
/** 扁平列表（活动 / 聊天）的「展开其余」状态 */
const flatExpanded = new Set<string>();

function toggleFlat(key: string): void {
  if (flatExpanded.has(key)) flatExpanded.delete(key);
  else flatExpanded.add(key);
  if (runtime) renderSidebar(runtime.store.state);
}

function renderSideTabs(state: PiDeckState): void {
  if (!refs) return;
  clear(refs.sideTabs);
  // 计数直接算聚合后的数量（多设备时就是全部设备加起来）
  const states = endpointStates();
  const counts = states.length > 1 ? null : tabCounts(state);
  const multiActivity = states.length > 1 ? buildActivityViewMulti(states) : null;
  const merged: Record<SidebarTab, number> = counts ?? {
    active: (multiActivity?.live.length ?? 0) + (multiActivity?.recent.length ?? 0),
    chats: chatSessionsMulti(states).length,
    projects: endpointCatalogs(states).reduce((sum, catalog) => sum + catalog.projects.length, 0),
  };
  for (const tab of SIDEBAR_TABS) {
    const active = sidebarTab === tab.id;
    const count = merged[tab.id];
    refs.sideTabs.append(
      h("button", {
        class: `side-tab${active ? " is-active" : ""}`,
        text: tab.label,
        title: count > 0 ? `${tab.label}（${count}）` : tab.label,
        "aria-label": count > 0 ? `${tab.label}，${count} 项` : tab.label,
        onclick: () => {
          if (sidebarTab === tab.id) return;
          sidebarTab = tab.id;
          saveSidebarTab(sidebarTab);
          if (runtime) renderSidebar(runtime.store.state);
        },
      }),
    );
  }
}

/* ---------------------------- 多设备 ---------------------------- */

/** 给聚合视图用的端点快照（非活跃端也用各自 store 的最新 state）。 */
function endpointStates(): EndpointState[] {
  return endpoints.map((endpoint) => ({
    id: endpoint.id,
    label: endpoint.label,
    state: endpoint.store.state,
  }));
}

function runningsOn(endpoint: Runtime): number {
  return endpoint.store.state.runtimes.filter((item) => item.status === "running").length;
}

/** 其它设备上的待答卡片总数（提醒用户切过去看）。 */
function pendingOnOthers(): number {
  let total = 0;
  for (const endpoint of endpoints) {
    if (endpoint === runtime) continue;
    total += endpoint.store.state.pendingUiRequests.length;
  }
  return total;
}

/** 顶栏设备 chip：多台设备时才出现，带运行中圆点与「别处有待答」提示。 */
function renderDeviceChip(): void {
  if (!refs) return;
  const multiple = endpoints.length > 1;
  refs.deviceBtn.classList.toggle("is-hidden", !multiple);
  if (!multiple || !runtime) return;

  const running = endpoints.reduce((sum, endpoint) => sum + runningsOn(endpoint), 0);
  clear(refs.deviceBtn);
  refs.deviceBtn.append(h("span", { class: "device-name", text: runtime.label }));
  if (running > 0) refs.deviceBtn.append(h("span", { class: "pulse-dot pulse-dot--sm" }));
  if (pendingOnOthers() > 0) refs.deviceBtn.append(h("span", { class: "device-pending", title: "别的设备有待答提问" }));
  refs.deviceBtn.title = `${endpoints.length} 台设备 · ${running} 个会话运行中（点一下切换）`;
}

function openEndpointSheet(): void {
  if (endpoints.length === 0) return;
  openSheet(`设备（${endpoints.length}）`, (sheet) => {
    const cap = describeEndpointCap(endpoints.length);
    if (cap) sheet.append(h("p", { class: "sheet-note", text: cap }));

    for (const endpoint of endpoints) {
      const isCurrent = endpoint === runtime;
      const running = runningsOn(endpoint);
      const pending = endpoint.store.state.pendingUiRequests.length;
      const row = h(
        "button",
        { class: `sheet-item sheet-item--two sheet-conn${isCurrent ? " is-active" : ""}` },
        [
          h("span", { class: "conn-name", text: endpoint.label }),
          h("span", { class: "conn-url", text: endpoint.pairing.baseUrl }),
          h("span", { class: "conn-badges" }, [
            ...(running > 0 ? [h("span", { class: "conn-badge conn-badge--run", text: `${running} 运行中` })] : []),
            ...(pending > 0 ? [h("span", { class: "conn-badge conn-badge--ask", text: `${pending} 待答` })] : []),
            ...(isCurrent ? [h("span", { class: "conn-badge", text: "当前" })] : []),
          ]),
        ],
      );
      attachPressable(row, {
        onTap: () => activateEndpoint(endpoint.id),
        onLongPress: () => openPairingMenu(
          {
            baseUrl: endpoint.pairing.baseUrl,
            token: endpoint.pairing.token,
            savedAt: Date.now(),
            ...(endpoint.pairing.label ? { label: endpoint.pairing.label } : {}),
          },
          isCurrent,
        ),
      });
      sheet.append(row);
    }

    sheet.append(
      h("button", {
        class: "sheet-item sheet-item--primary",
        text: "＋ 添加设备",
        onclick: () => {
          closeSheet();
          renderPairing();
        },
      }),
    );
  });
}

function renderComposerState(): void {
  if (!refs || !runtime) return;
  const session = activeSessionId ? runtime.store.session(activeSessionId) : undefined;
  // 后端能力：ACP 会话的模型/思考/计划入口都是假的（点了没变化，或必被拒），整行隐藏
  const caps = backendCapabilities(session?.backend);

  const prefs = resolveSessionPrefs(session, activeSessionId ? learnedPrefs.get(epKey(activeSessionId)) : undefined);
  refs.modelBtn.classList.toggle("is-hidden", !caps.model);
  refs.modelBtn.textContent = modelChipLabel(prefs, 18);
  refs.modelBtn.title = modelChipTitle(prefs);
  refs.modelBtn.classList.toggle("chip--muted", prefs.placeholder);

  const level = prefs.thinkingLevel;
  refs.thinkingBtn.classList.toggle("is-hidden", !caps.thinking);
  refs.thinkingBtn.textContent = level ? `思考 ${thinkingLabel(level)}` : "思考";
  refs.thinkingBtn.title = level ? `思考档位：${level}（${thinkingHint(level)}）` : "选择思考档位";
  refs.thinkingBtn.classList.toggle("chip--muted", !level);

  // 安全级别：0.9.9 才下发 state.security；dsh/acp 会话改不了（服务端 400），入口直接不显示
  const securityState = parseSecurityState(runtime.store.state.security);
  const showSecurity = composerToolChips(session?.backend, securityState, activeSessionId !== null).security;
  refs.securityBtn.classList.toggle("is-hidden", !showSecurity);
  if (showSecurity && securityState) {
    const { level: securityLevel, inherited } = securityLevelFor(securityState, activeSessionId);
    refs.securityBtn.textContent = securityChipLabel(securityLevel);
    refs.securityBtn.title = securityLevel
      ? `安全级别：${securityLevel.name}${inherited ? "（全局默认）" : "（本会话）"}${securityState.enabled ? "" : " · 已关闭切换"}`
      : "选择本会话的安全级别";
    refs.securityBtn.classList.toggle("chip--muted", !securityLevel);
  }

  // DSH 会话才有目标/子代理/技能面板
  refs.dshBtn.classList.toggle("is-hidden", !composerToolChips(session?.backend, securityState, activeSessionId !== null).dsh);

  // 运行中 + 输入框有东西（文字或图片）时，才在「历史」左侧出现「排队 / 插话」
  const running = isRunActive();
  // 计划模式：给 agent 注入隐藏指令，ACP 侧必被拒（acp.hostInstructionsUnsupported），直接不给入口
  if (!caps.plan && planModeOn) {
    planModeOn = false;
    if (activeSessionId) planModeBySession.set(epKey(activeSessionId), false);
  }
  refs.planBtn.classList.toggle("is-hidden", !caps.plan);
  // 计划模式按钮：开启时高亮，标题说明「发完自动关」
  refs.planBtn.classList.toggle("is-active", planModeOn);
  refs.planBtn.title = planModeOn
    ? "计划模式已开启：本轮只读分析并输出 Plan: 编号计划，发完自动关闭"
    : "计划模式：只读分析并输出 Plan: 编号计划，发完自动关闭";
  refs.planBtn.setAttribute("aria-pressed", planModeOn ? "true" : "false");

  const hasContent = refs.composer.value.trim().length > 0 || currentImages().length > 0;
  const showRunningSend = shouldShowRunningSend(running, hasContent);
  // ACP 没有插话：一律降级成排队，按钮文案也跟着改，免得用户以为戳进去会插话
  const effectiveMode: SendMode = caps.steer ? sendMode : "queue";
  refs.queueSendBtn.classList.toggle("is-hidden", !showRunningSend);
  refs.queueSendBtn.classList.toggle("is-steer", effectiveMode === "steer");
  refs.queueSendBtn.textContent = sendModeLabel(effectiveMode);
  refs.queueSendBtn.title = `${sendModeHint(effectiveMode)}${caps.steer ? "；长按切换模式" : ""}`;
  refs.queueSendBtn.setAttribute(
    "aria-label",
    `${sendModeActionLabel(effectiveMode)}${caps.steer ? "（长按切换模式）" : ""}`,
  );

  renderPrimaryButton();
}

/** 当前是否有正在进行的轮次（决定主按钮是「发送」还是「停止」）。 */
function isRunActive(): boolean {
  return channel?.reducer.status === "running";
}

/**
 * 以服务端状态为准收口本地回合。
 *
 * 从后台回来、点任务完成通知进来时最容易撞上：后台 socket 被系统冻死，
 * 结束帧没收到，`reducer.status` 就永久停在 running —— 表现是回答下方
 * 一直转圈、计时不停。这里只看「服务端是否还在跑」，跑完了就收口。
 */
function settleStaleTurn(): void {
  if (!runtime || !activeSessionId) return;
  const info = runtime.store.state.runtimes.find((item) => item.sessionId === activeSessionId);
  if (info?.status === "running") return;
  if (channel?.reducer.status === "running") channel.reducer.reset();
  // 流式区要一并收掉（内容已经进历史了），否则会和 reloadHistory 出来的正式消息重叠
  chatView?.clearLive();
  chatView?.settleTurn();
  // 收口的同时对一次真实历史：后台那一轮的消息可能还没落到本机
  void reloadHistory(activeSessionId);
  renderComposerState();
  renderPrimaryButton();
}

/** 上一次看到「这个会话真的在跑」的时刻（给状态推送流失的宽限）。 */
let lastRunActiveSeenAt = 0;

/**
 * 状态里的「在不在跑」比流（reducer）更权威：流断了会永远停在 running。
 *
 * 给 8 秒宽限：刚发出的一段很短时间里，服务端的 runtimes 还没这个会话，
 * 不能因此就把刚开始的计时直接判成结束。
 */
function syncTurnWithState(state: PiDeckState): void {
  if (!activeSessionId || !chatView) return;
  if (channel?.reducer.status !== "running") {
    lastRunActiveSeenAt = 0;
    return;
  }
  const info = state.runtimes.find((item) => item.sessionId === activeSessionId);
  if (info?.status === "running") {
    lastRunActiveSeenAt = Date.now();
    return;
  }
  if (lastRunActiveSeenAt === 0) lastRunActiveSeenAt = Date.now();
  if (Date.now() - lastRunActiveSeenAt < 8_000) return;
  settleStaleTurn();
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
  const hasText = refs.composer.value.trim().length > 0 || currentImages().length > 0;

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
    if (pendingText.trim()) sessionDrafts.set(epKey(activeSessionId), pendingText);
    else sessionDrafts.delete(epKey(activeSessionId));
  }

  channel?.close();
  channel = null;
  stopContextPolling();
  stopTodoPolling();
  stopSubagentPolling();
  activeSessionId = sessionId;
  sessionHistory = [];
  activeProjectId = store.session(sessionId)?.projectId ?? activeProjectId;
  chatView.clear();
  chatView.setEmpty("加载中…");

  // 计划模式也是每会话各自记（发送成功后会被置回 false）
  planModeOn = planModeBySession.get(epKey(sessionId)) ?? false;

  // 恢复这个会话自己的草稿（没有就清空，不能留着上一个会话的内容）
  if (refs) {
    refs.composer.value = sessionDrafts.get(epKey(sessionId)) ?? "";
    autosize(refs.composer);
    renderAttachments();
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
    // 这个会话在别处已经跑起来了（比如桌面端发的，或者切走时发出去的那轮）：
    // 拿最后一条提问的时间当回合起点，计时才不会从 0 开始数
    const runningOnServer =
      runtime?.store.state.runtimes.find((item) => item.sessionId === sessionId)?.status === "running";
    if (runningOnServer) chatView.seedTurn(lastUserTimestamp(page.messages));
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
          runOutcome.set(epKey(sessionId), c.reducer.status);
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
  // 历史清单按「设备 + 会话」缓存，切会话时自动换成这一份
  renderTodoHistory();
  startTodoPolling(sessionId);
  startSubagentPolling(sessionId);

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
function detectCompletedRuns(endpointId: string, state: PiDeckState): void {
  const previousRuntimes = previousRuntimesByEndpoint.get(endpointId) ?? [];
  const previousPending = previousPendingByEndpoint.get(endpointId) ?? [];
  const endpointLabel = endpoints.find((item) => item.id === endpointId)?.label ?? endpointId;
  /** 多设备时通知上标设备名，否则分辨不出是哪台跑完了 */
  const prefix = endpoints.length > 1 ? `【${endpointLabel}】` : "";

  const next: RuntimeSnapshot[] = state.runtimes.map((item) => ({
    sessionId: item.sessionId,
    status: String(item.status ?? ""),
  }));

  const completed = diffCompletedRuns(previousRuntimes, next);
  previousRuntimesByEndpoint.set(endpointId, next);

  // 有会话在跑就拉起后台看护（必须在 App 还可见时启动，见 run-watch.ts 的说明）
  // 多设备时只有一台能当看护源（原生服务单端点），这里统一挑「真正在跑的那台」
  syncRunWatchForActiveRunner();

  // 活跃会话刚拿到运行时（比如刚发出第一条消息）→ 补学一次模型偏好
  if (activeSessionId && !learnedPrefs.has(epKey(activeSessionId))) {
    void learnRuntimePrefs(activeSessionId);
  }

  // 「App 真的退到后台」：WebView 被盖住 **且** 不是被 Flutter 页盖住。
  // Flutter 页盖住时用户就在看屏幕，属于前台，不能当成后台去弹通知。
  const hidden = isAppInBackground({
    visibilityHidden: typeof document !== "undefined" && document.visibilityState === "hidden",
    flutterPageOpen,
  });

  // AI 提问：agent 会一直阻塞等回答，所以这个通知比「跑完了」更该及时
  const nextPending: PendingSnapshot[] = state.pendingUiRequests.map((item) => ({
    sessionId: item.sessionId,
    requestId: item.requestId,
    title: item.title,
  }));
  const newAsks = diffNewPendingRequests(previousPending, nextPending);
  previousPendingByEndpoint.set(endpointId, nextPending);

  for (const ask of newAsks) {
    // 后台服务已经发过这条了
    if (notifiedAsksByService.delete(epKey(ask.sessionId))) continue;
    // 正在看这个会话时不打扰（卡片就在眼前）
    if (!hidden && ask.sessionId === activeSessionId) continue;
    // App 在后台且看护服务在跑 → 交给原生发（我们随时可能被冻结）
    if (hidden && isRunWatchActive()) continue;
    const session = state.sessions.find((item) => item.id === ask.sessionId);
    void notifyTaskFinished({
      sessionId: ask.sessionId,
      title: askNotificationTitle(),
      body: prefix + askNotificationBody(ask, session?.title),
    });
  }

  if (completed.length === 0) return;

  for (const run of completed) {
    // 后台服务已经发过这条了，回到前台别再弹一次
    if (notifiedByService.has(epKey(run.sessionId))) {
      notifiedByService.delete(epKey(run.sessionId));
      continue;
    }
    const session = state.sessions.find((item) => item.id === run.sessionId);
    // 正在看的会话不提醒；App 在后台时都提醒
    if (!hidden && run.sessionId === activeSessionId) continue;
    // App 在后台且看护服务在跑 → 交给原生发（我们这边随时可能被冻结）
    if (hidden && isRunWatchActive()) continue;

    // reducer 的结局更准（能区分用户主动停止），没有就用 state 里的状态
    const endedAs = runOutcome.get(epKey(run.sessionId)) ?? run.endedAs;
    runOutcome.delete(epKey(run.sessionId));
    if (!shouldNotifyEnd(endedAs)) continue;

    void notifyTaskFinished({
      sessionId: run.sessionId,
      title: completionTitle(endedAs),
      body: prefix + completionBody(session?.title, endedAs),
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
  for (const id of notified.runs) notifiedByService.add(epKey(id));
  for (const id of notified.asks) notifiedAsksByService.add(epKey(id));

  await runtime.store.refresh().catch(() => undefined);

  // 点通知进来的话，直接跳到那个会话
  const launchSession = await consumeLaunchSession();
  if (launchSession && launchSession !== activeSessionId && runtime.store.session(launchSession)) {
    await openSession(launchSession);
    // 这里原本直接 return，于是「服务端已经跑完、本地还挂着 running」没人收口，
    // 点通知进来就是一整个永远转圈的计时器
    settleStaleTurn();
    void learnRuntimePrefs(launchSession, true);
    void refreshContextUsage(launchSession);
    void refreshTodo(launchSession);
    void refreshSubagents(launchSession);
    return;
  }
  if (!sessionId || sessionId !== activeSessionId) return;

  // 历史永远重拉一次：既覆盖「跑完了」，也覆盖「期间有新消息」
  void reloadHistory(sessionId);

  // 服务端已经不认为它在跑了 → 收口本地状态，别让按钮停在「停止」、计时器还在转
  // 注意：跑完后 runtime 通常还在（status=idle），所以要看 status 而不是「有没有 runtime」
  settleStaleTurn();

  channel?.forceReconnect();
  void learnRuntimePrefs(sessionId, true);
  void refreshContextUsage(sessionId);
  void refreshTodo(sessionId);
  void refreshSubagents(sessionId);
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
  if (!force && learnedPrefs.has(epKey(sessionId))) return;
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

    learnedPrefs.set(epKey(sessionId), next);
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
  // 历史按会话缓存，切会话时不清（清掉就会出现「切回去空了」）
  renderTodoStrip();
}

/**
 * 拉一次任务清单。
 * 服务端没有清单时返回 null，条带整体隐藏（不占高度）。
 */
/* ---------------------------- 待办历史 ---------------------------- */

/**
 * 待办历史按「设备 + 会话」缓存。
 *
 * 以前是一个全局数组，切会话时清空再靠 localStorage 重读 —— 任何写入时序问题
 * 都会表现成「切回去历史空/时间变了」。改成按会话缓存后，切来切去不会丢东西。
 */
const todoHistoryByScope = new Map<string, TodoHistoryEntry[]>();

/**
 * 已收口的清单（按会话记）。
 *
 * 记的是 planId + 内容签名：服务端在下一轮开始前会继续返回上一轮那份，
 * 只拦「完全没变」的那份；同一 planId 但内容变了说明是新的一轮，要重新显示。
 */
const dismissedTodoPlans = new Map<string, { planId: string; signature: string }>();

/**
 * 记住「这个会话上一份清单已经被收口/清除」。
 *
 * 以前只放内存 Map，重启 App 就丢了：重新打开会话时 5s 轮询把清掉的清单
 * 又写回「待办」，发出新一轮时那份旧清单还会被当成新一轮顶到输入框上方。
 * 现在内存 + localStorage 双写。
 */
function dismissedFor(scope: string): { planId: string; signature: string } | undefined {
  const cached = dismissedTodoPlans.get(scope);
  if (cached) return cached;
  const stored = loadDismissedTodo(scope);
  if (stored) dismissedTodoPlans.set(scope, stored);
  return stored ?? undefined;
}

function setDismissed(scope: string, value: { planId: string; signature: string } | null): void {
  if (value) {
    dismissedTodoPlans.set(scope, value);
    saveDismissedTodo(scope, value);
    return;
  }
  dismissedTodoPlans.delete(scope);
  saveDismissedTodo(scope, null);
}

/**
 * 每份清单「算不算本轮」（条带只在有本轮清单时显示）。
 *
 * 服务端对老会话也会返回它上一轮那份清单（可能是几天前、早就 6/6 了），
 * 以前不区分就整条画到输入框上方，看起来像「刚跑完还没收」。现在只有本轮
 * 运行过、或本次启动里亲眼看到它变化过的那份才上条带，其余只进「待办」。
 */
const todoLiveByScope = new Map<string, TodoLiveState>();

/**
 * 「清除所有」的内联二次确认状态。
 *
 * 不用 openSheet：那个函数为了不让遮罩吃掉触摸会顺手关掉工作区抽屉，
 * 确认完抽屉就没了，用户还得重新打开一次。
 */
let todoClearConfirming = false;

function todoScope(sessionId: string): string {
  return epKey(sessionId);
}

function currentTodoScope(): string {
  return todoScope(activeSessionId ?? "");
}

function historyFor(scope: string): TodoHistoryEntry[] {
  const cached = todoHistoryByScope.get(scope);
  if (cached) return cached;
  const loaded = loadTodoHistory(scope);
  todoHistoryByScope.set(scope, loaded);
  return loaded;
}

function setHistory(scope: string, entries: TodoHistoryEntry[]): void {
  todoHistoryByScope.set(scope, entries);
  saveTodoHistory(scope, entries);
}

/** 当前会话的历史（渲染用）。 */
function currentTodoHistory(): TodoHistoryEntry[] {
  return historyFor(currentTodoScope());
}

/** 把当前清单快照写进历史（同一 planId 就地更新进度）。 */
function rememberTodo(view: TodoView | null, sessionId: string): void {
  if (!view) return;
  const scope = todoScope(sessionId);
  setHistory(scope, mergeTodoHistory(historyFor(scope), view, Date.now()));
}

/**
 * 发出新提问前调用：把这一轮的清单**收口**，并记下它的 planId，
 * 让服务端继续返回旧清单时也不再显示、不再更新那条历史。
 *
 * 注意：已完成**不是**结束条件 —— 用户明确要求「完成也要留到下一轮」，
 * 所以只有这里（真正发出下一轮）才撤条带。
 */
function archiveTodoBeforeSend(sessionId: string | null): void {
  if (!sessionId) return;
  const view = todoView;
  if (view) {
    const scope = todoScope(sessionId);
    setHistory(scope, sealTodoHistory(historyFor(scope), view, Date.now()));
    setDismissed(scope, { planId: planKeyOf(view), signature: todoSignature(view) });
  }
  todoView = null;
  // 下一轮开始了：把服务端「现在这份」记成基线并降级为非本轮。
  // 不能直接 delete —— 删了就没有可比对的基线，新一轮真写出来的清单
  // 也会因为「第一次见到」而被判成旧清单。
  const scope = todoScope(sessionId);
  todoLiveByScope.set(scope, todoRoundBaseline(todoLiveByScope.get(scope)));
  renderTodoStrip();
  renderTodoHistory();
}

/** 右侧工作区「待办」Tab：当前会话的全部历史清单，最新的在前。 */
function renderTodoHistoryTab(content: HTMLElement): void {
  const entries = currentTodoHistory();
  if (entries.length === 0) {
    content.append(
      h("p", {
        class: "ws-empty",
        text: "还没有历史待办清单。agent 每列一次任务清单都会记在这里；发出新提问后，上一份会归档。",
      }),
    );
    return;
  }

  // 顶部工具行：条数 + 清除所有（内联二次确认，避免关掉抽屉）
  const toolbar = h("div", { class: "todo-history-toolbar" });
  toolbar.append(h("span", { class: "todo-history-count", text: `共 ${entries.length} 份` }));
  if (todoClearConfirming) {
    toolbar.append(
      h("button", {
        class: "todo-clear is-confirming",
        text: "确认清除",
        onclick: () => clearAllTodoHistory(),
      }),
      h("button", {
        class: "todo-clear-cancel",
        text: "取消",
        onclick: () => {
          todoClearConfirming = false;
          void renderWorkspacePanel();
        },
      }),
    );
  } else {
    toolbar.append(
      h("button", {
        class: "todo-clear",
        text: "清除所有",
        title: "清空当前会话的全部历史清单（只影响本机记录）",
        onclick: () => {
          todoClearConfirming = true;
          void renderWorkspacePanel();
        },
      }),
    );
  }
  content.append(toolbar);

  const list = h("div", { class: "todo-history" });
  // 最新的默认展开，其余收起：历史多了以后一屏全是条目没法扫
  entries.forEach((entry, index) => {
    const chevron = createChevron(index === 0, 12);
    const items = h("ul", { class: "todo-history-items" });
    for (const item of entry.items) {
      items.append(
        h("li", { class: `todo-history-item is-${item.status}` }, [
          h("span", {
            class: "todo-history-mark",
            text: item.status === "completed" ? "✓" : item.status === "in_progress" ? "▶" : "○",
          }),
          h("span", { class: "todo-history-text", text: item.text }),
        ]),
      );
    }

    const head = h("summary", { class: "todo-history-head" }, [
      chevron,
      h("span", { class: "todo-history-progress", text: historyProgressLabel(entry) }),
      h("span", { class: "todo-history-time", text: historyTimeLabel(entry) }),
      h("span", {
        class: `todo-history-badge${entry.finished ? " is-done" : " is-active"}${entry.sealed ? " is-sealed" : ""}`,
        text: historyStatusLabel(entry),
      }),
    ]);

    const section = h("details", { class: "todo-history-entry" }, [head, items]);
    if (index === 0) section.setAttribute("open", "");
    section.addEventListener("toggle", () => setChevronExpanded(chevron, section.hasAttribute("open")));
    list.append(section);
  });
  content.append(list);
}

/**
 * 清空当前会话的全部待办历史。
 *
 * 只清本机记录 —— 服务端本来就不保留历史（只给最新的 planId），所以这是纯粹的本地清理。
 *
 * 关键：如果当前还有一份清单在，必须把它记进 dismissed，否则 5 秒后的轮询会立刻把它
 * 又写回历史，看起来像「没清干净」。之后清单内容一旦变化（进度推进或新一轮），
 * 屏蔽会自动解除并重新出现 —— 这是有意的：真有动静了就该让用户看到。
 */
function clearAllTodoHistory(): void {
  const scope = currentTodoScope();
  // 条带隐藏时 todoView 是 null，但服务端那份清单还在轮询里 —— 用「上次见到的身份」
  // 兜底，否则一清历史立刻被 5s 轮询写回来。
  const known = todoLiveByScope.get(scope);
  const identity = todoView
    ? { planId: planKeyOf(todoView), signature: todoSignature(todoView) }
    : known
      ? { planId: known.planId, signature: known.signature }
      : null;
  const result = clearedTodoIdentity(identity);

  todoHistoryByScope.set(scope, result.history);
  clearTodoHistory(scope);

  if (result.dismissed) {
    // 当前这份一起压掉，否则 5s 后的轮询会立刻把它写回历史
    setDismissed(scope, result.dismissed);
    todoLiveByScope.set(scope, { planId: result.dismissed.planId, signature: result.dismissed.signature, live: false });
    todoView = null;
    renderTodoStrip();
  }

  todoClearConfirming = false;
  void renderWorkspacePanel();
  toast("已清除待办历史");
}

/**
 * 待办历史变化后的收尾。
 *
 * 关键：工作区抽屉正开着「待办」Tab 时必须跟着重绘，
 * 否则切会话后看到的还是上一个会话的残留 DOM（用户报的「切回去记录不对」）。
 */
function renderTodoHistory(): void {
  if (!refs) return;
  refs.workspacePanel.dataset.todoHistory = currentTodoHistory().length > 0 ? "1" : "0";
  if (workspaceOpen && workspaceTab === "todos") void renderWorkspacePanel();
}

async function refreshTodo(sessionId: string): Promise<void> {
  if (!runtime || sessionId !== activeSessionId) return;
  // ACP 会话没有 pi 条目：/api/sessions/:id/todo 恒为 null（清单在 CLI 自己手里），
  // 不再打这个必然空转的接口，条带自然不显示
  if (!backendCapabilities(runtime.store.session(sessionId)?.backend).todo) {
    todoView = null;
    renderTodoStrip();
    return;
  }
  try {
    const raw = await runtime.client.getTodo(sessionId);
    if (sessionId !== activeSessionId) return;
    const view = parseSessionTodo(raw);
    const scope = todoScope(sessionId);
    const dismissed = dismissedFor(scope);

    // 完全没变的那份旧清单 → 继续隐藏（下一轮还没真正开始）
    if (view && dismissed && planKeyOf(view) === dismissed.planId && todoSignature(view) === dismissed.signature) {
      todoView = null;
      todoLiveByScope.set(scope, { planId: planKeyOf(view), signature: todoSignature(view), live: false });
      renderTodoStrip();
      renderTodoHistory();
      return;
    }
    // planId 相同但内容变了 = 新的一轮复用同名计划，解除屏蔽
    if (view && dismissed) setDismissed(scope, null);

    // 老会话里那份上一轮的清单是历史，不该占输入框上方：只记进「待办」
    let live = view !== null;
    if (view) {
      const state = nextTodoLiveState(todoLiveByScope.get(scope), view);
      todoLiveByScope.set(scope, state);
      live = state.live;
    }

    todoView = live ? view : null;
    rememberTodo(view, sessionId);
    renderTodoStrip();
    renderTodoHistory();
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

/* ---------------------------- 子代理活动条 ---------------------------- */

let subagentView: SubagentView[] = [];
let subagentPollTimer: number | null = null;
let subagentPollSession: string | null = null;
const subagentCollapsed = new Map<string, boolean>();

function stopSubagentPolling(): void {
  if (subagentPollTimer !== null) {
    window.clearTimeout(subagentPollTimer);
    subagentPollTimer = null;
  }
  subagentPollSession = null;
  subagentView = [];
  renderSubagentStrip();
}

/**
 * 拉一次子代理列表。
 *
 * 没装 pi-subagents 插件的机器会返回 503，这里当作「没有子代理」静默处理：
 * 这是能力缺失，不是错误，不该弹 toast。
 */
async function refreshSubagents(sessionId: string): Promise<void> {
  if (!runtime || sessionId !== activeSessionId) return;
  try {
    const raw = await runtime.client.listSubagents(sessionId);
    if (sessionId !== activeSessionId) return;
    subagentView = normalizeSubagents(raw);
    renderSubagentStrip();
  } catch {
    if (sessionId !== activeSessionId) return;
    subagentView = [];
    renderSubagentStrip();
  }
}

function startSubagentPolling(sessionId: string): void {
  stopSubagentPolling();
  subagentPollSession = sessionId;

  const schedule = (): void => {
    if (subagentPollSession !== sessionId) return;
    subagentPollTimer = window.setTimeout(() => void tick(), 5_000);
  };

  const tick = async (): Promise<void> => {
    if (subagentPollSession !== sessionId) return;
    const hidden = typeof document !== "undefined" && document.visibilityState === "hidden";
    if (!hidden) await refreshSubagents(sessionId);
    schedule();
  };

  void tick();
}

function renderSubagentStrip(): void {
  if (!refs) return;
  const sessionId = activeSessionId;
  const remembered = sessionId ? subagentCollapsed.get(epKey(sessionId)) : undefined;
  const collapsed = remembered ?? true;
  refs.subagentStrip.update(subagentView, { collapsed });
}

/* ---------------------------- 上下文用量 ---------------------------- */

let contextUsage: ContextUsage | null = null;
/** 和 contextUsage 同一次请求拿到的吞吐指标（面板里要显示会话平均） */
let perfMetrics: PerfMetrics | null = null;
let contextPollTimer: number | null = null;
let contextPollSession: string | null = null;

function stopContextPolling(): void {
  if (contextPollTimer !== null) {
    window.clearTimeout(contextPollTimer);
    contextPollTimer = null;
  }
  contextPollSession = null;
  contextUsage = null;
  perfMetrics = null;
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
    // 同一次请求里就带着吞吐指标，不用再发一次
    perfMetrics = readPerfMetrics(raw);
    chatView?.setRunStats(perfMetrics);
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
    // 上下文 + 吞吐（首 token / 总耗时 / 单条速度 / 会话平均），标签互不重叠
    for (const row of [...contextRows(usage), ...perfRows(perfMetrics)]) {
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
    sheet.append(buildUsageSection(sessionId, usage));
  });
}

/* ---------------------------- 供应商额度 ---------------------------- */

/** 查询结果缓存：同一个会话重新打开面板不该再打一次供应商计费接口 */
const usageCache = new Map<string, { view: UsageView; at: number }>();

function providerOf(sessionId: string, usage: ContextUsage | null): string {
  const session = runtime?.store.session(sessionId);
  return (usage?.provider || session?.model?.provider || "").trim();
}

function backendOf(sessionId: string): "pi" | "dsh" {
  return runtime?.store.session(sessionId)?.backend === "dsh" ? "dsh" : "pi";
}

/** 当前活跃会话的 backend（没会话时 undefined，能力矩阵按 pi 处理）。 */
function activeBackend(): string | undefined {
  const session = activeSessionId ? runtime?.store.session(activeSessionId) : undefined;
  const kind = session?.backend;
  return typeof kind === "string" ? kind : undefined;
}

function renderUsageBody(body: HTMLElement, view: UsageView | null, loading: boolean): void {
  clear(body);
  if (loading) {
    body.append(h("div", { class: "usage-hint", text: "正在查询…" }));
    return;
  }
  if (!view) {
    body.append(h("div", { class: "usage-hint", text: "点下面的按钮查询（会访问供应商的计费接口）" }));
    return;
  }
  if (!view.available) {
    body.append(h("div", { class: "usage-hint", text: view.hint ?? "没有可展示的额度" }));
    return;
  }
  const list = h("ul", { class: "ctx-rows" });
  for (const line of view.lines) {
    list.append(
      h("li", { class: "ctx-row" }, [
        h("span", { class: "ctx-row-label", text: line.label }),
        h("span", { class: "ctx-row-value", text: line.value }),
      ]),
    );
  }
  body.append(list);
  if (view.at) {
    body.append(h("div", { class: "usage-hint", text: `数据时间：${formatResetAt(new Date(view.at).toISOString())}` }));
  }
}

/**
 * 上下文面板里的「供应商额度」块。
 *
 * 查询是用户显式动作：探针会真的访问供应商计费接口，不能每次开面板都打。
 * 手动刷新有 30s 冷却，避免连点。
 */
function buildUsageSection(sessionId: string, usage: ContextUsage | null): HTMLElement {
  const box = h("div", { class: "usage-box" });
  // ACP 会话的 provider 是转发层名 "acp"，供应商计费接口查不到任何东西（桌面端 Web 也不渲染）
  if (!backendCapabilities(runtime?.store.session(sessionId)?.backend).usage) {
    box.append(h("div", { class: "usage-title", text: "供应商额度" }));
    box.append(
      h("div", { class: "usage-hint", text: "这个会话由电脑上的外部 agent CLI 记账，PiDeck 这边没有供应商额度可查" }),
    );
    return box;
  }
  const provider = providerOf(sessionId, usage);
  if (!provider) {
    box.append(h("div", { class: "usage-title", text: "供应商额度" }));
    box.append(h("div", { class: "usage-hint", text: "当前会话没有模型供应商信息" }));
    return box;
  }

  box.append(h("div", { class: "usage-title", text: `供应商额度 · ${provider}` }));
  const body = h("div", { class: "usage-body" });
  const btn = h("button", { class: "btn usage-refresh", text: "查询额度" });
  box.append(body, btn);

  const cached = usageCache.get(usageKey(provider));
  renderUsageBody(body, cached?.view ?? null, false);

  let lastAt = cached?.at ?? 0;
  btn.addEventListener("click", () => {
    const now = Date.now();
    if (now - lastAt < USAGE_REFRESH_COOLDOWN_MS) {
      toast("刚查过，稍等一下再刷（保护供应商接口）");
      return;
    }
    lastAt = now;
    void (async () => {
      if (!runtime) return;
      btn.disabled = true;
      renderUsageBody(body, null, true);
      try {
        const raw = await runtime.client.getProviderUsage(provider, backendOf(sessionId));
        const view = buildUsageView(raw, provider);
        usageCache.set(usageKey(provider), { view, at: Date.now() });
        renderUsageBody(body, view, false);
      } catch (error) {
        renderUsageBody(body, { available: false, provider, lines: [], hint: describeError(error) }, false);
      } finally {
        btn.disabled = false;
      }
    })();
  });

  return box;
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
/**
 * 后台看护只支持一个端点（原生 RunWatchService 守一条 /api/events）。
 * 所以挑「运行中会话最多的那台」当看护源，而不是无脑用活跃端 ——
 * 否则用户停在没跑东西的设备上时，另一台跑完根本不会通知。
 */
function syncRunWatchForActiveRunner(): void {
  const candidates = endpoints.length > 0 ? endpoints : runtime ? [runtime] : [];
  if (candidates.length === 0) return;

  const runningOn = (endpoint: Runtime): RuntimeSnapshot[] =>
    endpoint.store.state.runtimes
      .filter((item) => String(item.status ?? "") === "running")
      .map((item) => ({ sessionId: item.sessionId, status: "running" }));

  const scored = candidates
    .map((endpoint) => ({ endpoint, runtimes: runningOn(endpoint) }))
    .sort((left, right) => right.runtimes.length - left.runtimes.length);
  const best = scored[0];
  if (!best || !shouldStartRunWatch(best.runtimes)) {
    void stopRunWatch();
    return;
  }
  if (isRunWatchActive()) return;

  void startRunWatch({
    baseUrl: best.endpoint.client.baseUrl,
    token: best.endpoint.client.token,
    titles: collectRunWatchTitles(best.runtimes, (sessionId) => best.endpoint.store.session(sessionId)),
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
    if (page.messages.length === 0) {
      // 空会话：给引导页（标题 + 三条提示词）。
      // 已经有内容时不要把画面清掉 —— 这多半是落盘延迟或回放还没到。
      if (!chatView.hasContent()) renderEmptyState(sessionId);
      return;
    }
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

async function createSession(projectId?: string, choice: BackendChoice = DEFAULT_BACKEND_CHOICE): Promise<void> {
  if (!runtime) return;
  const targetProject = projectId ?? activeProjectId ?? runtime.store.projects()[0]?.id;
  if (!targetProject) {
    toast("没有可用项目：请先在桌面端添加项目目录");
    return;
  }
  if (missingAcpTool(choice)) {
    toast("选了 ACP 后端就得先指定一个 CLI 工具");
    return;
  }
  try {
    const session = await runtime.client.createSession(createSessionInput(targetProject, choice));
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
  const images = currentImages().slice();
  const typed = refs.composer.value.trim();
  // 只发图不写字时给服务端一句自然的话：/prompt 的 message 不能为空（400 messageRequired）
  const text = typed || (images.length > 0 ? "看下这张图" : "");
  if (!text) return;
  refs.composer.value = "";
  autosize(refs.composer);
  renderPrimaryButton();
  if (activeSessionId) sessionDrafts.delete(epKey(activeSessionId));

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

    // 计划模式：可见消息保持原文，另塞一条带隐藏标记的 agentMessage（与桌面端逐字一致）
    const submission = buildPlanSubmission(text, planModeOn);
    if (submission.planMode) {
      // 一次性语义：发出去就回普通模式，免得后续提问被一直限制成只读
      planModeOn = false;
      if (activeSessionId) planModeBySession.set(epKey(activeSessionId), false);
      renderComposerState();
    }

    // 发出新提问 = 上一轮结束：把当前清单归入历史，并从输入框上方撤掉
    archiveTodoBeforeSend(activeSessionId);

    // 发送前就先拉起后台看护：用户常常发完立刻上滑回桌面，
    // 等 SSE 把 running 推下来再启动，可能已经来不及（App 一旦被冻结就启动不了前台服务）
    kickRunWatch(activeSessionId);

    // 先把自己的提问显示出来，不要等服务端落盘
    // 运行中插话/排队时这里不能把回合计时清零 —— 同一轮还在跑，清零会让「用时」骗人
    const continuingTurn = isRunActive();
    chatView?.clearLive({ keepClock: continuingTurn });
    chatView?.appendLocalUser(typed || submission.message, images.map((image) => image.dataUrl));
    if (!continuingTurn) chatView?.startTurn();
    const caps = backendCapabilities(activeBackend());
    const result = await current.send(submission.message, {
      // 运行中才带：空闲时排队/插话都等于普通发送；不支持插话的后端一律降级成排队
      streamingBehavior: streamingBehaviorFor(isRunActive(), caps.steer ? sendMode : "queue"),
      ...(images.length > 0 ? { images: images.map(toImageAttachment) } : {}),
      ...(submission.agentMessage ? { extra: { agentMessage: submission.agentMessage } } : {}),
    });
    // 服务端可能压根没收：以前这里不看返回值，于是出现「气泡上屏了但什么都没发生」的幽灵提问。
    // ACP 尤其容易撞上（回合中发消息、带图、计划模式注入）。
    if (isPromptRejection(result)) {
      const message = rejectionMessage(result);
      if (shouldRollbackRejection(result)) {
        chatView?.dropLocalEcho();
        // 图还在输入框里，方便直接重试
        toast(message);
      } else {
        // 服务端也不确定发没发出去：气泡留着，等历史落盘再校正
        toast(`${message}\n（这条可能已经发出去了，稍后以历史为准）`);
      }
      renderComposerState();
      return;
    }
    // 发出去了才清掉缩略图（发送失败时图还在，可以直接重试）
    clearCurrentImages();
    renderAttachments();
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

type WorkspaceTab = "git" | "files" | "todos";

let workspaceTab: WorkspaceTab = "git";
let workspaceOpen = false;
/** 文件 Tab 的当前目录栈（相对项目根） */
let workspaceStack: string[] = [];
let workspacePreview: { path: string; content: string; line?: number } | null = null;

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
  const todoCount = currentTodoHistory().length;
  const todosTab = h("button", {
    class: `ws-tab${workspaceTab === "todos" ? " is-active" : ""}`,
    text: todoCount > 0 ? `待办 ${todoCount}` : "待办",
    title: "当前会话的历史待办清单",
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
  todosTab.addEventListener("click", () => {
    if (workspaceTab === "todos") return;
    workspaceTab = "todos";
    void renderWorkspacePanel();
  });
  panel.append(h("div", { class: "ws-tabs" }, [gitTab, filesTab, todosTab]));

  const content = h("div", { class: "ws-content" });
  panel.append(content);

  // 待办历史不依赖项目路径（服务端不保留旧计划，数据全在本机）
  if (workspaceTab === "todos") {
    renderTodoHistoryTab(content);
    return;
  }

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
  // 预览是一页，不再接在列表下面：文件一多就要先拉到底才能看到内容
  if (workspacePreview) {
    renderFilePreviewPage(content, projectId);
    return;
  }
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
    attachPressable(row, {
      onTap: () => {
        if (isDir) {
          // 只取最后一段：万一服务端 name 里带了路径，也不会让面包屑越点越长
          const segment = node.name.split(/[\/]/).filter(Boolean).pop() ?? node.name;
          workspaceStack = [...workspaceStack, segment];
          workspacePreview = null;
          void renderWorkspacePanel();
        } else {
          void openFilePreview(projectId, node.relativePath);
        }
      },
      // 目录的「长按」没有额外动作（想下载的是文件），所以只给文件挂菜单
      onLongPress: isDir ? undefined : () => openFileMenu(projectId, node),
    });
    content.append(row);
  }
}

/**
 * 文件预览页。
 *
 * 以前预览是「文件列表下面再接一段 pre」，文件一多就要先拉到底才能看到内容；
 * 现在预览是一页，自带返回（← 回文件列表）与下载，列表不会再被顶到下面。
 */
function renderFilePreviewPage(content: HTMLElement, projectId: string): void {
  const preview = workspacePreview;
  if (!preview) return;

  const back = h("button", { class: "icon-btn ws-preview-back", title: "返回文件列表", text: "←" });
  back.addEventListener("click", () => {
    workspacePreview = null;
    void renderWorkspacePanel();
  });
  const title = h("span", { class: "ws-preview-name", text: fileNameOf(preview.path), title: preview.path });
  const download = h("button", { class: "icon-btn", title: "下载到手机", text: "⤓" });
  download.addEventListener("click", () => {
    void downloadWorkspaceFile(projectId, preview.path);
  });
  content.append(h("div", { class: "ws-preview-head" }, [back, title, h("span", { class: "spacer" }), download]));

  const body = h("div", { class: "ws-preview ws-preview--page" });
  content.append(body);

  // 从聊天里带行号点进来时：只拆行（大文件不拆，几千个 span 会卡）
  const target = preview.line;
  if (target && preview.content.length <= 80_000) {
    const lines = preview.content.split("\n");
    let hit: HTMLElement | null = null;
    lines.forEach((line, index) => {
      const row = h("div", { class: "ws-line", text: line || " " });
      if (index + 1 === target) {
        row.classList.add("is-target");
        hit = row;
      }
      body.append(row);
    });
    if (hit) {
      const anchor = hit as HTMLElement;
      requestAnimationFrame(() => anchor.scrollIntoView({ block: "center" }));
    }
  } else {
    body.textContent = preview.content;
  }
}

/** 预览正文：二进制 / 超限 / 被截断都说清楚，不要默默展示半截。 */
function previewTextOf(file: { kind: string; size: number; content: string }): string {
  const limitKb = Math.round(FILE_PREVIEW_MAX_BYTES / 1024);
  switch (file.kind) {
    case "binary":
      return `（二进制文件，${formatBytes(file.size)}，桌面端接口只提供文本，看不了内容）`;
    case "tooLarge":
      return `（文件 ${formatBytes(file.size)}，超过 2 MB 读不全，不预览）`;
    case "truncated":
      return `${file.content}\n\n…（文件共 ${formatBytes(file.size)}，这里只显示前 512 KB）`;
    default:
      if (!shouldPreviewFile(file.size)) {
        return `文件过大（${formatBytes(file.size)}），超过 ${limitKb} KB 不预览。`;
      }
      return file.content || "（空文件）";
  }
}

async function openFilePreview(projectId: string, path: string, line?: number): Promise<void> {
  if (!runtime) return;
  workspaceTab = "files";
  workspaceStack = dirStackOf(path);
  workspacePreview = { path, content: "读取中…", line };
  await renderWorkspacePanel();

  try {
    const file = await runtime.client.fileContent(projectId, path);
    workspacePreview = { path, content: previewTextOf(file), line };
  } catch (error) {
    workspacePreview = { path, content: `读取失败：${describeError(error)}`, line };
  }
  await renderWorkspacePanel();
}

/**
 * 聊天里点了文件路径。
 *
 * 先请求一次再开抽屉：路径是「长得像」才变成链接的，真不存在时
 * 不该把抽屉拉出来只为了显示一个 404 —— 直接说没找到。
 */
async function openChatFileRef(rawPath: string, line?: number): Promise<void> {
  if (!runtime) return;
  const projectId = currentWorkspaceProjectId();
  if (!projectId) {
    toast("没有可用项目：请先在桌面端添加项目目录");
    return;
  }
  const relative = toProjectRelative(rawPath, currentWorkspaceProjectPath());
  let file: { kind: string; size: number; content: string };
  try {
    file = await runtime.client.fileContent(projectId, relative);
  } catch {
    toast(`没找到 ${relative}`);
    return;
  }
  workspaceTab = "files";
  workspaceStack = dirStackOf(relative);
  workspacePreview = { path: relative, content: previewTextOf(file), line };
  // 左右抽屉互斥，避免预览页被侧栏压住
  refs?.hostBody?.classList.remove("show-sidebar");
  await openWorkspacePanel();
}

/** 长按文件行弹出的菜单（目录不长按：目录用来进入）。 */
function openFileMenu(projectId: string, node: FileNode): void {
  openSheet(node.name, (sheet) => {
    sheet.append(
      h("button", {
        class: "sheet-item sheet-item--primary",
        text: "下载到手机",
        onclick: () => {
          closeSheet();
          void downloadWorkspaceFile(projectId, node.relativePath);
        },
      }),
    );
    sheet.append(
      h("button", {
        class: "sheet-item",
        text: "预览内容",
        onclick: () => {
          closeSheet();
          void openFilePreview(projectId, node.relativePath);
        },
      }),
    );
    sheet.append(
      h("button", {
        class: "sheet-item",
        text: "引用到输入框",
        onclick: () => {
          closeSheet();
          insertAtCursor(mentionText(node));
          toast("已引用到输入框");
        },
      }),
    );
  });
}

/**
 * 下载文件到手机。
 *
 * 服务端只会吐文本（二进制 / 超 2MB 有限制），所以先看 kind：
 * 下不了就说明原因，绝不自作主张存一个半截文件。
 */
async function downloadWorkspaceFile(projectId: string, relativePath: string): Promise<void> {
  if (!runtime) return;
  const name = baseName(relativePath);
  toast(`正在读取 ${name}…`);
  try {
    const result = await runtime.client.fileContent(projectId, relativePath);
    const blocked = downloadBlockedReason(result, name);
    if (blocked) {
      toast(blocked);
      return;
    }
    const outcome = await saveTextFile(name, mimeForName(name), result.content);
    toast(outcome.ok ? downloadDoneText(name, outcome.location) : `保存失败：${outcome.message}`);
  } catch (error) {
    toast(`下载失败：${describeError(error)}`);
  }
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
 * 之前是「等网络请求回来才弹面板」，其中 /api/prompts 还要跨域且服务端
 * 不带 CORS 头，浏览器要等预检/超时，所以点一下卡好几秒。
 * 现在：先用内置命令立刻弹出，再异步补齐运行时命令（里面已含提示词模板）。
 * /api/prompts 是中文商店库，与输入框的模板无关，这里不再拉它。
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

  // 后台补齐运行时命令（提示词模板也在里面：source=prompt 的那些）
  const patch = async (): Promise<void> => {
    const runtimeCommands =
      activeSessionId && target ? await client.listRuntimeCommands(activeSessionId, target).catch(() => []) : [];

    all = mergeSlashItems({ runtime: runtimeCommands, builtin: BUILTIN_SLASH });
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
        text: "＋ 添加设备",
        onclick: () => {
          // 不主动停 store：其它设备还要继续实时跑，配对完成后 connect() 会合并进去
          closeSheet();
          renderPairing();
        },
      }),
    );

    if (current) {
      sheet.append(
        h("button", {
          class: "sheet-item",
          text: "断开全部连接",
          onclick: () => {
            closeSheet();
            // 断开 = 真的不留痕迹：配对记录、历史令牌、后台看护一起清
            clearAllPairings();
            void stopRunWatch();
            disconnectAll();
            toast("已断开，并清除了本机保存的令牌");
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
        text: item.enabled === false ? "加入同时连接" : "不再同时连接",
        onclick: () => {
          const next = item.enabled === false;
          setPairingEnabled(item.baseUrl, next);
          toast(next ? "已加入同时连接（重开 App 生效）" : "已取消同时连接（重开 App 生效）");
          closeSheet();
          openConnectionSheet();
        },
      }),
      h("button", {
        class: "sheet-item",
        text: "更新令牌 / 重新配对",
        onclick: () => {
          closeSheet();
          disconnectAll();
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
  const id = endpointIdOf(item.baseUrl);
  // 已经在连着 → 只换活跃端，不重连（否则 5 台切一下就重建 5 条 SSE）
  if (endpoints.some((endpoint) => endpoint.id === id)) {
    activateEndpoint(id);
    return;
  }
  try {
    // 没连着 → 把它加进「同时连接」的集合里重建（手上的其它台不会掉）
    const next = dedupeEndpoints([
      ...endpoints.map((endpoint) => ({
        baseUrl: endpoint.pairing.baseUrl,
        token: endpoint.pairing.token,
        savedAt: Date.now(),
        ...(endpoint.pairing.label ? { label: endpoint.pairing.label } : {}),
      })),
      { ...item, enabled: true },
    ]);
    setPairingEnabled(item.baseUrl, true);
    await connectAll(next, { activeBaseUrl: item.baseUrl });
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

/**
 * 会话安全级别选择（桌面端 0.9.9 的 SecurityLevelMenu）。
 *
 * 第一项固定是「跟随全局默认」—— 否则用户切了覆盖就找不到撤销入口。
 * `state.security.enabled === false` 时仍可看列表，但不提交（与桌面端置灰一致）。
 */
function openSecuritySheet(): void {
  if (!runtime || !activeSessionId) {
    toast("先选一个会话");
    return;
  }
  const sessionId = activeSessionId;
  const state = parseSecurityState(runtime.store.state.security);
  const choices = securityChoices(state, sessionId);
  const hint = securityDisabledHint(state);

  openSheet("安全级别", (sheet) => {
    if (hint) sheet.append(h("p", { class: "sheet-note", text: hint }));
    if (choices.length === 0) {
      sheet.append(h("p", { class: "sheet-note", text: "没有可用的安全级别" }));
      return;
    }
    for (const choice of choices) {
      const disabled = state !== null && !state.enabled;
      sheet.append(
        h(
          "button",
          {
            class: `sheet-item sheet-item--two${choice.active ? " is-active" : ""}${disabled ? " is-disabled" : ""}`,
            onclick: disabled ? undefined : () => void applySecurityLevel(choice.id),
          },
          [
            h("span", { class: "conn-name", text: choice.active ? `✓ ${choice.label}` : choice.label }),
            h("span", { class: "conn-url", text: choice.description }),
          ],
        ),
      );
    }
  });
}

async function applySecurityLevel(levelId: string | null): Promise<void> {
  if (!runtime || !activeSessionId) return;
  const sessionId = activeSessionId;
  closeSheet();
  try {
    await runtime.client.setSessionSecurityLevel(sessionId, levelId);
    await runtime.store.refresh();
    renderComposerState();
    const name = levelId
      ? (parseSecurityState(runtime.store.state.security)?.levels.find((level) => level.id === levelId)?.name ?? levelId)
      : null;
    toast(name ? `本会话安全级别：${name}` : "已恢复跟随全局默认");
  } catch (error) {
    toast("切换安全级别失败：" + describeError(error));
  }
}

/**
 * DSH 会话的工具面板（目标 / 子代理 / 技能）。
 *
 * 服务端在没有活跃 runtime 时直接回空集合，所以「空」要当作正常状态提示。
 */
async function openDshToolsSheet(): Promise<void> {
  if (!runtime || !activeSessionId) return;
  const client = runtime.client;
  const sessionId = activeSessionId;

  openSheet("DSH 工具", (sheet) => {
    sheet.append(h("p", { class: "sheet-note", text: "加载中…" }));
  });

  const [goal, subs, skills] = await Promise.allSettled([
    client.getDshGoal(sessionId),
    client.getDshSubagents(sessionId),
    client.getDshSkills(sessionId),
  ]);
  // 加载期间用户可能切了会话或关了面板，别把陈数据画到别的会话上
  if (activeSessionId !== sessionId) return;

  const goalView = goal.status === "fulfilled" ? parseDshGoal(goal.value) : null;
  const subagentRows = subs.status === "fulfilled" ? parseDshSubagents(subs.value) : [];
  const skillRows = skills.status === "fulfilled" ? parseDshSkills(skills.value) : [];
  const failure = [goal, subs, skills].find((result) => result.status === "rejected");

  openSheet("DSH 工具", (sheet) => {
    if (failure && failure.status === "rejected") {
      sheet.append(h("p", { class: "sheet-note", text: `读取失败：${describeError(failure.reason)}` }));
    }

    sheet.append(h("div", { class: "sheet-group", text: "目标" }));
    if (!goalView) {
      sheet.append(h("p", { class: "sheet-note", text: DSH_EMPTY_GOAL }));
    } else {
      const meta = [goalPhaseLabel(goalView.phase), goalRoundsText(goalView)].filter(Boolean).join(" · ");
      sheet.append(
        h("div", { class: "sheet-item sheet-item--two" }, [
          h("span", { class: "conn-name", text: goalView.objective }),
          h("span", { class: "conn-url", text: meta }),
        ]),
      );
    }

    sheet.append(h("div", { class: "sheet-group", text: `子代理（${subagentRows.length}）` }));
    if (subagentRows.length === 0) {
      sheet.append(h("p", { class: "sheet-note", text: DSH_EMPTY_SUBAGENTS }));
    } else {
      for (const subagent of subagentRows) {
        const meta = [subagent.running ? "运行中" : "空闲", subagent.diagnostic ? "诊断" : "", subagent.modeLabel]
          .filter(Boolean)
          .join(" · ");
        sheet.append(
          h(
            "button",
            {
              class: "sheet-item sheet-item--two",
              onclick: () => void openDshSubagentHistory(sessionId, subagent.id, subagent.label),
            },
            [h("span", { class: "conn-name", text: subagent.label }), h("span", { class: "conn-url", text: meta })],
          ),
        );
      }
    }

    sheet.append(h("div", { class: "sheet-group", text: `技能（${skillRows.length}）` }));
    if (skillRows.length === 0) {
      sheet.append(h("p", { class: "sheet-note", text: DSH_EMPTY_SKILLS }));
    } else {
      for (const skill of skillRows) {
        const describe = [skill.description, skill.modelInvocable ? "" : "仅用户可调", skill.whenToUse]
          .filter(Boolean)
          .join(" · ");
        sheet.append(
          h("div", { class: "sheet-item sheet-item--two" }, [
            h("span", { class: "conn-name", text: `/${skill.name}` }),
            h("span", { class: "conn-url", text: describe }),
          ]),
        );
      }
    }
  });
}

/** 子代理的历史消息（只读，按角色区分前缀）。 */
async function openDshSubagentHistory(sessionId: string, childSessionId: string, label: string): Promise<void> {
  if (!runtime) return;
  const client = runtime.client;
  openSheet(label, (sheet) => {
    sheet.append(h("p", { class: "sheet-note", text: "加载中…" }));
  });

  let lines: Array<{ role: string; text: string }> = [];
  let error = "";
  try {
    lines = parseDshSubagentHistory(await client.getDshSubagentHistory(sessionId, childSessionId));
  } catch (error_) {
    error = describeError(error_);
  }

  openSheet(label, (sheet) => {
    if (error) {
      sheet.append(h("p", { class: "sheet-note", text: `读取失败：${error}` }));
      return;
    }
    if (lines.length === 0) {
      sheet.append(h("p", { class: "sheet-note", text: "这个子代理还没有历史消息" }));
      return;
    }
    for (const line of lines) {
      const role = line.role === "user" ? "我" : line.role === "assistant" ? "子代理" : line.role;
      sheet.append(
        h("div", { class: "sheet-item sheet-item--two" }, [
          h("span", { class: "conn-name", text: role }),
          h("span", { class: "conn-url", text: line.text }),
        ]),
      );
    }
  });
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
    learnedPrefs.set(epKey(sessionId), { ...(learnedPrefs.get(epKey(sessionId)) ?? {}), provider, modelId, modelName });
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
    learnedPrefs.set(epKey(sessionId), { ...(learnedPrefs.get(epKey(sessionId)) ?? {}), thinkingLevel: level });
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

/* ---------------------------- 分支家族条 ---------------------------- */

/**
 * 会话被 fork 过时，在对话上方显示家族链（桌面端 WebBranchBar 同语义）。
 * 只有一条会话时整条隐藏，不占屏幕。
 */
function renderBranchBar(state: PiDeckState): void {
  if (!refs) return;
  const nodes = branchNodes(state.sessions ?? [], activeSessionId);
  if (!shouldShowBranchBar(nodes)) {
    refs.branchBar.classList.add("is-hidden");
    if (refs.branchBar.childElementCount > 0) clear(refs.branchBar);
    return;
  }

  refs.branchBar.classList.remove("is-hidden");
  clear(refs.branchBar);
  refs.branchBar.append(h("span", { class: "branch-icon", text: "⑂", "aria-hidden": "true" }));
  for (const node of nodes) {
    const active = node.id === activeSessionId;
    refs.branchBar.append(
      h("button", {
        class: `branch-chip${active ? " is-active" : ""}${node.forked ? " is-forked" : ""}`,
        text: shortBranchTitle(node.title),
        title: `${node.title}${node.forked ? "（分叉）" : ""}`,
        onclick: () => {
          if (!active) void openSession(node.id);
        },
      }),
    );
  }
}

/* ---------------------------- 单条消息的动作 ---------------------------- */

function findMessageById(messageId: string): WireMessage | undefined {
  return sessionHistory.find((message) => message.id === messageId || message.entryId === messageId);
}

async function copyText(text: string): Promise<void> {
  const value = text.trim();
  if (!value) {
    toast("这条消息没有可复制的文本");
    return;
  }
  try {
    await navigator.clipboard?.writeText(value);
    toast("已复制");
  } catch {
    toast("复制失败：系统剪贴板不可用");
  }
}

function openMessageActions(messageId: string, role: string): void {
  // ACP 会话只给「复制」：历史由 agent CLI 的归档主导，运行时没有 fork/改/删命令
  const actions = actionsForRole(role, activeBackend());
  if (actions.length === 0) return;
  const message = findMessageById(messageId);
  const text = message ? messageText(message) : "";

  openSheet(role === "user" ? "这条提问" : "这条回答", (sheet) => {
    for (const action of actions) {
      sheet.append(
        h("button", {
          class: `sheet-item${action.danger ? " sheet-item--danger" : ""}`,
          text: action.label,
          onclick: () => {
            if (action.id === "edit") {
              openEditMessageSheet(messageId, text);
              return;
            }
            void runMessageAction(action.id, messageId, text);
          },
        }),
      );
    }
  });
}

/** 编辑：直接在弹层里改文本，保存后重拉历史。 */
function openEditMessageSheet(messageId: string, current: string): void {
  openSheet("编辑这条消息", (sheet) => {
    const area = h("textarea", { class: "sheet-textarea", value: current });
    sheet.append(
      area,
      h("button", {
        class: "sheet-item sheet-item--primary",
        text: "保存",
        onclick: () => {
          const next = area.value.trim();
          if (!next) {
            toast("内容不能为空");
            return;
          }
          if (next === current.trim()) {
            closeSheet();
            return;
          }
          void runMessageAction("edit", messageId, next);
        },
      }),
    );
    setTimeout(() => area.focus(), 60);
  });
}

/** 从 prepare-resend / fork 的返回里宽松取字段（形状未在文档里固定）。 */
function pickResultId(result: unknown, keys: readonly string[]): string | null {
  if (!result || typeof result !== "object") return null;
  const record = result as Record<string, unknown>;
  for (const key of keys) {
    const value = record[key];
    if (typeof value === "string" && value.trim()) return value.trim();
    if (value && typeof value === "object") {
      const nested = (value as Record<string, unknown>).id;
      if (typeof nested === "string" && nested.trim()) return nested.trim();
    }
  }
  return null;
}

async function runMessageAction(action: MessageAction, messageId: string, text: string): Promise<void> {
  if (!runtime || !activeSessionId) return;
  if (action === "copy") {
    closeSheet();
    await copyText(text);
    return;
  }

  // 删除不可恢复，先要一次确认
  if (action === "delete") {
    openSheet("删除这条消息？", (sheet) => {
      sheet.append(
        h("p", { class: "sheet-note", text: confirmTextFor("delete") }),
        h("button", {
          class: "sheet-item sheet-item--danger",
          text: "确认删除",
          onclick: () => void applyMessageAction("delete", messageId, text),
        }),
      );
    });
    return;
  }

  await applyMessageAction(action, messageId, text);
}

async function applyMessageAction(action: MessageAction, messageId: string, text: string): Promise<void> {
  if (!runtime || !activeSessionId) return;
  const sessionId = activeSessionId;
  closeSheet();
  try {
    const target = await ensureRuntimeTarget(sessionId);

    if (action === "resend") {
      const result = await runtime.client.prepareResend(sessionId, target, messageId);
      const prefill = pickResultId(result, ["text", "prefill", "message"]) ?? text;
      if (refs && prefill) {
        refs.composer.value = prefill;
        autosize(refs.composer);
        renderComposerState();
      }
      toast("已填回输入框，改完再发");
      return;
    }

    if (action === "fork") {
      const message = findMessageById(messageId);
      const entryId =
        message && typeof message.entryId === "string" && message.entryId ? message.entryId : messageId;
      const result = await runtime.client.fork(sessionId, target, entryId);
      await runtime.store.refresh();
      renderSidebar(runtime.store.state);
      const nextId = pickResultId(result, ["sessionId", "session", "id"]);
      if (nextId) {
        await openSession(nextId);
        toast("已从这条消息分叉");
      } else {
        toast("已分叉，在侧栏里选新会话");
      }
      return;
    }

    if (action === "edit") {
      await runtime.client.editMessage(sessionId, target, messageId, text);
    } else if (action === "delete") {
      await runtime.client.deleteMessage(sessionId, target, messageId);
    }

    await reloadHistory(sessionId);
    toast(action === "edit" ? "已保存" : "已删除");
  } catch (error) {
    toast(describeError(error));
  }
}

/** 书签图标（提示词面板），与齿轮同一套内联 SVG 写法。 */
function bookmarkIcon(): SVGSVGElement {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("width", "15");
  svg.setAttribute("height", "15");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "1.9");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("aria-hidden", "true");
  const pathEl = document.createElementNS(ns, "path");
  pathEl.setAttribute("d", "M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z");
  svg.append(pathEl);
  return svg;
}

/* ---------------------------- 提示词 ---------------------------- */

/**
 * 「提示词」面板：列出**已导入**的提示词模板（`/runtime/commands` 里 source=prompt 的条目）。
 *
 * 数据只走运行时命令表：模板是 pi 从 `~/.pi/agent/prompts`、`<项目>/.pi/prompts`、npm 包
 * 与内置目录里加载出来的，名字就是输入框里敲的 `/名字`。选中后插 `/名字 `，正文由 pi
 * 在发送时展开（与电脑端输入框一致）—— Web API 没有「读模板正文」的路由，
 * `/api/prompts` 是另一套中文商店库，不是这里要的东西。
 */
async function openPromptPicker(): Promise<void> {
  if (!runtime) return;
  const { client, store } = runtime;

  // 模板与具体会话无关：当前会话没有运行实例时借一个（否则新建会话首次打开永远是空的）
  const sourceSession = promptCommandSource(store.state.runtimes, activeSessionId);
  let query = "";
  let entries: PromptEntry[] = [];
  /** 递增序号：快速输入时只认最新一次请求的结果 */
  let seq = 0;
  let debounce: ReturnType<typeof setTimeout> | undefined;

  const listWrap = h("div", { class: "prompt-list" });

  const retryButton = (): HTMLElement =>
    h("button", { class: "sheet-item", text: "重试", onclick: () => void load() });

  const note = (text: string): void => {
    if (!listWrap.isConnected) return;
    listWrap.replaceChildren(h("p", { class: "sheet-note", text }));
  };

  /** 只重画列表，不动搜索框 —— 中文输入法组字时重建 input 会丢字。 */
  const paintList = (): void => {
    if (!listWrap.isConnected) return;
    listWrap.replaceChildren();
    const filtered = filterPromptEntries(entries, query);
    if (filtered.length === 0) {
      listWrap.append(
        h("p", { class: "sheet-note", text: entries.length === 0 ? PROMPT_EMPTY_HINT : "没有匹配的提示词" }),
      );
      if (entries.length === 0) listWrap.append(retryButton());
      return;
    }
    for (const entry of filtered) {
      listWrap.append(
        h(
          "button",
          {
            class: "sheet-item sheet-item--two",
            onclick: () => {
              closeSheet();
              insertAtCursor(promptInsertText(entry));
              toast(`已插入 /${entry.name}`);
            },
          },
          [
            h("span", { class: "conn-name", text: `/${entry.name}` }),
            h("span", { class: "conn-url", text: entry.description || " " }),
          ],
        ),
      );
    }
  };

  const load = async (): Promise<void> => {
    const mine = ++seq;
    const target = sourceSession ? store.targetOf(sourceSession) : undefined;
    if (!sourceSession || !target) {
      note(PROMPT_NO_RUNTIME_HINT);
      return;
    }
    try {
      const commands = await client.listRuntimeCommands(sourceSession, target);
      if (mine !== seq) return;
      entries = importedPrompts(commands);
      paintList();
    } catch (error) {
      if (mine !== seq) return;
      note(`读取提示词失败：${describeError(error)}`);
      listWrap.append(retryButton());
    }
  };

  const search = h("input", {
    class: "sheet-input",
    type: "search",
    placeholder: "搜索提示词…",
  });
  search.addEventListener("input", () => {
    query = search.value;
    if (debounce) clearTimeout(debounce);
    // 纯本地过滤，防抖只为少重画几十个 DOM 节点
    debounce = setTimeout(paintList, 120);
  });

  openSheet("提示词", (sheet) => {
    sheet.append(search, listWrap);
    note("正在读取提示词…");
  });
  await load();
}

/* ---------------------------- 设置页 ---------------------------- */

let settingsPage: SettingsPage | null = null;
let settingsModels: ModelRow[] = [];
let settingsSkills: SkillSection[] = [];
let settingsExtensions: ExtensionRow[] = [];
let settingsDshPlugins: DshPluginRow[] = [];
/** DSH 插件清单的单独错误（桌面端 /api/dsh/plugins 会 500，不能拖黑整页） */
let settingsDshError: string | undefined;
let settingsLoading = false;
let settingsError: string | undefined;

/** 齿轮图标（lucide settings），与 chevron 一样内联 SVG，避免字体差异。 */
function gearIcon(): SVGSVGElement {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("width", "18");
  svg.setAttribute("height", "18");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "1.8");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("aria-hidden", "true");
  for (const d of [
    "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z",
    "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z",
  ]) {
    const pathEl = document.createElementNS(ns, "path");
    pathEl.setAttribute("d", d);
    svg.append(pathEl);
  }
  return svg;
}

/** 空态：按会话所属项目类型给不同的三条提示词（Chat 里不该出现「审查未提交的改动」）。 */
function renderEmptyState(sessionId: string | null): void {
  if (!chatView) return;
  const session = sessionId ? runtime?.store.session(sessionId) : undefined;
  const project = session ? runtime?.store.projects().find((item) => item.id === session.projectId) : undefined;
  const context = suggestionContext(project);
  chatView.setEmptyState(emptyStateSubtitle(context), {
    title: emptyStateTitle(),
    suggestions: suggestionsFor(context),
    onSuggestion: (prompt) => {
      if (!refs) return;
      refs.composer.value = prompt;
      autosize(refs.composer);
      renderComposerState();
      refs.composer.focus();
    },
  });
}

/** 新建会话前先选工作区：Chat 置顶，其余按最近使用时间。 */
/* ------------------------------------------------------------------ */
/* 后端选择：新建会话时选 pi / DSH / 某个 ACP CLI                        */
/* ------------------------------------------------------------------ */

const LAST_BACKEND_KEY = ["pideck", "app", "last-backend", "v1"].join("-");

/**
 * 上次选的后后端。
 *
 * 一个人常用哪家 CLI 基本固定，每次新建都要重选太烦；但记下的工具可能已经被删掉
 * （或电脑关了 ACP），不在当前列表里就退回 pi，否则新建会直接 400。
 */
function loadLastBackendChoice(options: readonly BackendOption[]): BackendChoice {
  try {
    const raw = localStorage.getItem(LAST_BACKEND_KEY);
    const parsed = raw ? (JSON.parse(raw) as { backend?: unknown; acpToolId?: unknown }) : null;
    if (!parsed) return DEFAULT_BACKEND_CHOICE;
    const choice: BackendChoice = {
      backend: normalizeBackend(parsed.backend),
      acpToolId: typeof parsed.acpToolId === "string" ? parsed.acpToolId : null,
    };
    return options.some((option) => option.key === choiceKey(choice)) ? choice : DEFAULT_BACKEND_CHOICE;
  } catch {
    return DEFAULT_BACKEND_CHOICE;
  }
}

function rememberBackendChoice(choice: BackendChoice): void {
  try {
    localStorage.setItem(LAST_BACKEND_KEY, JSON.stringify(choice));
  } catch {
    /* 隐私模式下写不进去，忽略即可 */
  }
}

/** 一排可切换的后端 chip；点击只改选择，不关弹层。 */
function buildBackendChips(
  options: readonly BackendOption[],
  current: () => BackendChoice,
  onPick: (choice: BackendChoice) => void,
): HTMLElement {
  const row = h("div", { class: "backend-chips", role: "group", "aria-label": "后端" });
  const buttons: HTMLElement[] = [];
  const sync = (): void => {
    const key = choiceKey(current());
    for (const button of buttons) {
      const active = button.dataset.key === key;
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-pressed", active ? "true" : "false");
    }
  };
  for (const option of options) {
    const button = h("button", {
      class: "chip backend-chip",
      dataset: { key: option.key },
      title: option.hint,
      text: option.label,
      onclick: () => {
        onPick(option.choice);
        sync();
      },
    });
    buttons.push(button);
    row.append(button);
  }
  sync();
  return row;
}

function openWorkspacePicker(): void {
  if (!runtime) return;
  const state = runtime.store.state;
  const projects = sortProjectsForPicker(state.projects ?? [], state.sessions ?? []);
  if (projects.length === 0) {
    toast("没有可用项目：请先在桌面端添加项目目录");
    return;
  }
  const counts = sessionCountByProject(state.sessions ?? []);
  const options = backendOptions(state.acpTools);
  let choice: BackendChoice = loadLastBackendChoice(options);

  openSheet("新建会话", (sheet) => {
    // 只有 pi 一个选项时不占地方；有 ACP 工具时平铺成一行行 chip
    if (options.length > 1) {
      sheet.append(
        h("div", { class: "sheet-label", text: "后端" }),
        buildBackendChips(options, () => choice, (next) => {
          choice = next;
        }),
      );
    }
    for (const project of projects) {
      const label = projectLabel(project) ?? project.id;
      const isChat = project.kind === "chat";
      const count = counts.get(project.id) ?? 0;
      sheet.append(
        h(
          "button",
          {
            class: "sheet-item sheet-item--two",
            onclick: () => {
              rememberBackendChoice(choice);
              closeSheet();
              void createSession(project.id, choice);
            },
          },
          [
            h("span", { class: "conn-name", text: isChat ? label + "（内置）" : label }),
            h("span", {
              class: "conn-url",
              text: (isChat ? "内置聊天" : (project.path ?? project.id)) + " · " + count + " 个会话",
            }),
          ],
        ),
      );
    }
  });
}

/**
 * 草稿期切换后端。
 *
 * 会话已经有 runtime 时不允许改：桌面端同样把锁定放在 create 之后（`backendLocked`），
 * 因为模型/思考档位/工具清单都跟着旧后端缓存着，改了也只会得到一次「没生效」。
 */
function openBackendSheet(sessionId: string): void {
  if (!runtime) return;
  const session = runtime.store.session(sessionId);
  if (!session) return;
  const state = runtime.store.state;
  const options = backendOptions(state.acpTools);
  let choice: BackendChoice = {
    backend: normalizeBackend(session.backend),
    acpToolId: typeof session.acpToolId === "string" ? session.acpToolId : null,
  };
  const before: BackendChoice = { ...choice };

  openSheet("切换后端", (sheet) => {
    sheet.append(
      h("div", {
        class: "sheet-label",
        text: `当前：${describeChoice(before, state.acpTools)}`,
      }),
      buildBackendChips(options, () => choice, (next) => {
        choice = next;
      }),
      h("button", {
        class: "sheet-item sheet-item--primary",
        text: "应用",
        onclick: () => {
          if (choiceKey(choice) === choiceKey(before)) {
            closeSheet();
            return;
          }
          if (missingAcpTool(choice)) {
            toast("选了 ACP 后端就得先指定一个 CLI 工具");
            return;
          }
          rememberBackendChoice(choice);
          const target = describeChoice(choice, state.acpTools);
          // 换后端后旧会话的内容不再适用：切完直接重开这个会话，让标题/能力重新拉
          void runSessionAction(sessionId, "update", `已切到 ${target} 后端`, backendPatch(choice, before)).then(
            () => {
              if (sessionId === activeSessionId) void openSession(sessionId);
            },
          );
        },
      }),
    );
  });
}

function settingsData(): SettingsData {
  return {
    theme: {
      mode: getThemeMode(),
      options: THEME_MODES.map((mode) => ({ value: mode, label: themeLabel(mode) })),
    },
    notify: { enabled: isTaskNotifyEnabled() },
    connection: {
      serviceUrl: runtime?.pairing.baseUrl ?? "",
      token: runtime?.pairing.token ?? "",
      status: runtime?.store.getStatus() ?? "closed",
      version: APP_VERSION,
      sseNative: isNativeSseEnabled(),
    },
    devices: endpoints.map((endpoint) => ({
      id: endpoint.id,
      label: endpoint.label,
      url: endpoint.pairing.baseUrl,
      active: endpoint === runtime,
      running: runningsOn(endpoint),
      pending: endpoint.store.state.pendingUiRequests.length,
      enabled: true,
    })),
    models: settingsModels,
    skills: settingsSkills,
    extensions: settingsExtensions,
    dshPlugins: settingsDshPlugins,
    dshTarget: dshSession()?.label ?? "",
    loading: settingsLoading,
    ...(settingsDshError ? { dshError: settingsDshError } : {}),
    ...(settingsError ? { loadError: settingsError } : {}),
  };
}

function renderSettings(): void {
  const data = settingsData();
  if (settingsPage) settingsPage.update(data);
  // Flutter 版是纯视图，数据变了就整份推过去（它没有回拉的能力）。
  if (flutterSettingsOpen) void pushFlutterSettings(JSON.stringify(data));
}

/**
 * 拉一遍 pi 概况。
 *
 * 四个接口互不依赖，用 allSettled：旧版 PiDeck 可能没有 /api/skills 或 /api/dsh/plugins，
 * 一个失败不该让整页空白。
 */
async function refreshSettings(): Promise<void> {
  if (!runtime) return;
  const client = runtime.client;
  settingsLoading = true;
  settingsError = undefined;
  renderSettings();

  const [models, skills, extensions, plugins] = await Promise.allSettled([
    client.listModels(true),
    client.listSkills(),
    client.listExtensions(),
    client.listDshPlugins(),
  ]);

  settingsModels = models.status === "fulfilled" ? normalizeModels(models.value) : [];
  settingsSkills = skills.status === "fulfilled" ? normalizeSkills(skills.value) : [];
  settingsExtensions = extensions.status === "fulfilled" ? normalizeExtensions(extensions.value) : [];
  settingsDshPlugins = plugins.status === "fulfilled" ? normalizeDshPlugins(plugins.value) : [];
  settingsDshError =
    plugins.status === "rejected"
      ? `DSH 插件清单读取失败：${describeError(plugins.reason)}`
      : undefined;

  const failures = [models, skills, extensions].filter((result) => result.status === "rejected");
  const firstFailure = failures[0];
  settingsError =
    firstFailure && firstFailure.status === "rejected"
      ? "部分数据读取失败：" + describeError(firstFailure.reason)
      : undefined;

  settingsLoading = false;
  renderSettings();
}

function openSettings(): void {
  if (!runtime) return;
  closeSheet();
  void openSettingsView();
}

/**
 * 设置页有两套实现：原生壳里是 Flutter（迁移目标），浏览器 / 老包里是 Web 版。
 *
 * 走「视图迁移」：数据仍由本文件组装（`settingsData()`），动作仍由下面这套 handler 执行，
 * Flutter 只负责画 —— 主题、通知、设备切换这些状态本来就在 WebView 里，
 * 让 Flutter 再存一份只会两套状态打架。
 */
async function openSettingsView(): Promise<void> {
  if (await isFlutterHostAvailable()) {
    await openFlutterSettingsPage();
    return;
  }
  openWebSettingsPage();
}

/**
 * 打开 Flutter 设置页。
 *
 * 关键在顺序：**先 push 数据，再 startActivity**。原生把 JSON 缓存在插件里，
 * Flutter 首帧用 getSettings() 同步取走，不会先白一下再等一次往返。
 * 概况类数据（模型/技能/扩展）要请求服务端，等 `refreshSettings()` 回来会再推一次。
 */
async function openFlutterSettingsPage(): Promise<void> {
  flutterSettingsOpen = true;
  await pushFlutterSettings(JSON.stringify(settingsData()));
  const opened = await openFlutterPage("settings");
  if (!opened) {
    // 插件在、但 Activity 没起来（极端情况），不能让用户对着空屏。
    flutterSettingsOpen = false;
    openWebSettingsPage();
    return;
  }
  void refreshSettings();
}

function openWebSettingsPage(): void {
  settingsPage?.destroy();
  const page = createSettingsPage(getSettingsHandlers());
  settingsPage = page;
  document.body.append(page.el);
  renderSettings();
  void refreshSettings();
}

/**
 * Flutter 设置页回传的动作分发。
 *
 * 只做「把 JSON 摊平成 handler 调用」，不含任何业务逻辑 —— 这样两个界面的行为
 * 不会分叉（改一处两边都变）。
 */
function dispatchFlutterSettingsAction(action: Record<string, unknown>): void {
  if (!runtime) return;
  const handlers = getSettingsHandlers();
  const type = typeof action.type === "string" ? action.type : "";
  const text = (key: string): string => (typeof action[key] === "string" ? (action[key] as string) : "");

  switch (type) {
    case "close":
      handlers.onClose();
      return;
    case "refresh":
      handlers.onRefresh();
      return;
    case "theme": {
      const value = text("value");
      if (value) handlers.onTheme(value);
      return;
    }
    case "notify":
      handlers.onToggleNotify(action.enabled === true);
      return;
    case "deviceActivate": {
      const id = text("id");
      if (id) handlers.onDeviceActivate(id);
      return;
    }
    case "addDevice":
      handlers.onAddDevice();
      return;
    case "disconnect":
      handlers.onDisconnect();
      return;
    case "openUsage":
      handlers.onOpenUsage();
      return;
    case "sseProbe":
      // 调试用：流式探针页要拿当前会话 id 才能测会话流
      void openFlutterPage("sse-probe", activeSessionId ?? undefined);
      return;
    case "toggleSkill": {
      const sourceId = text("sourceId");
      const name = text("name");
      const section = settingsSkills.find((item) => item.sourceId === sourceId);
      if (section && name) handlers.onToggleSkill(section, name, action.enabled === true);
      return;
    }
    case "toggleExtension": {
      const key = text("key");
      const extension = settingsExtensions.find((item) => item.key === key);
      if (extension) handlers.onToggleExtension(extension, action.enabled === true);
      return;
    }
    case "dshAction": {
      const id = text("id");
      const verb = text("action");
      const plugin = settingsDshPlugins.find((item) => item.id === id);
      if (plugin && (verb === "run" || verb === "stop" || verb === "uninstall")) {
        handlers.onDshPluginAction(plugin, verb);
      }
      return;
    }
    case "dshInstall": {
      const idPrefix = text("idPrefix");
      const name = text("name");
      if (!idPrefix || !name) return;
      handlers.onDshPluginInstall({ idPrefix, name, purpose: text("purpose") });
      return;
    }
    default:
      return;
  }
}

let settingsHandlers: SettingsHandlers | null = null;

function getSettingsHandlers(): SettingsHandlers {
  if (!settingsHandlers) settingsHandlers = createSettingsHandlers();
  return settingsHandlers;
}

/** 两个界面共用同一套动作，行为不会分叉。 */
function createSettingsHandlers(): SettingsHandlers {
  /**
   * 提示：Flutter 页盖在 WebView 上时，WebView 的 toast 在下面的窗口里，用户看不见。
   * 所以设置页是 Flutter 版时改走原生推送到 Dart 侧的 SnackBar。
   */
  const notify = (message: string): void => {
    if (flutterSettingsOpen) {
      void pushFlutterNotice(message);
      return;
    }
    toast(message);
  };

  return {
    onClose: () => closeSettings(),    onTheme: (mode) => {
      setThemeMode(mode as ThemeMode);
      renderSettings();
    },
    onToggleNotify: (enabled) => {
      setTaskNotifyEnabled(enabled);
      renderSettings();
      if (enabled) {
        void ensureNotificationPermission().then((granted) => {
          if (!granted) notify("通知权限未授予，任务完成提醒不会弹出");
        });
      }
    },
    onDeviceActivate: (id) => {
      activateEndpoint(id);
      renderSettings();
    },
    onDeviceMenu: (id) => {
      const endpoint = endpoints.find((item) => item.id === id);
      if (!endpoint) return;
      openPairingMenu(
        {
          baseUrl: endpoint.pairing.baseUrl,
          token: endpoint.pairing.token,
          savedAt: Date.now(),
          ...(endpoint.pairing.label ? { label: endpoint.pairing.label } : {}),
        },
        endpoint === runtime,
      );
    },
    onAddDevice: () => {
      closeSettings();
      renderPairing();
    },
    onDisconnect: () => {
      closeSettings();
      clearAllPairings();
      void stopRunWatch();
      disconnectAll();
      notify("已断开，并清除了本机保存的令牌");
      renderPairing();
    },
    onToggleSkill: (section, name, enabled) => {
      void (async () => {
        if (!runtime) return;
        try {
          await runtime.client.toggleSkill({ name, sourceId: section.sourceId, enabled });
          settingsSkills = applySkillToggle(settingsSkills, section.sourceId + "::" + name, enabled);
          renderSettings();
        } catch (error) {
          notify("技能开关失败：" + describeError(error));
        }
      })();
    },
    onToggleExtension: (extension, enabled) => {
      void (async () => {
        if (!runtime) return;
        try {
          await runtime.client.toggleExtension({
            source: extension.source,
            enabled,
            scope: extension.scope,
            ...(extension.projectId ? { projectId: extension.projectId } : {}),
          });
          settingsExtensions = applyExtensionToggle(settingsExtensions, extension.key, enabled);
          renderSettings();
        } catch (error) {
          notify("扩展开关失败：" + describeError(error));
        }
      })();
    },
    onOpenUsage: () => {
      closeSettings();
      openContextSheet();
    },
    onRefresh: () => void refreshSettings(),
    onDshPluginAction: (plugin: DshPluginRow, action: "run" | "stop" | "uninstall") => {
      void (async () => {
        if (!runtime) return;
        const target = dshSession();
        if (!target) {
          notify("先在手机上打开一个 DSH 会话");
          return;
        }
        try {
          await runtime.client.dshPluginAction(plugin.id, action, {
            sessionId: target.id,
            ...(plugin.packageId ? { packageId: plugin.packageId } : {}),
          });
          notify(
            action === "run" ? `已启动 ${plugin.name}` : action === "stop" ? `已停止 ${plugin.name}` : `已卸载 ${plugin.name}`,
          );
          void refreshSettings();
        } catch (error) {
          notify(`${action === "run" ? "启动" : action === "stop" ? "停止" : "卸载"}失败：${describeError(error)}`);
        }
      })();
    },
    onDshPluginInstall: (input: { idPrefix: string; name: string; purpose: string }) => {
      void (async () => {
        if (!runtime) return;
        const target = dshSession();
        if (!target) {
          notify("先在手机上打开一个 DSH 会话");
          return;
        }
        try {
          await runtime.client.installDshPlugin({ sessionId: target.id, ...input });
          notify(`已安装 ${input.name}`);
          void refreshSettings();
        } catch (error) {
          notify("安装失败：" + describeError(error));
        }
      })();
    },
  };
}

function closeSettings(): void {
  settingsPage?.destroy();
  settingsPage = null;
  // Web 版关掉后什么都没了；Flutter 版还盖在上面，得让原生 finish 掉。
  if (flutterSettingsOpen) {
    flutterSettingsOpen = false;
    void closeFlutterPage();
  }
}

/**
 * 当前可用于 DSH 插件操作的会话。
 *
 * 插件启停/安装都要求带 sessionId（每个 DSH host 一份清单），优先用当前打开的会话，
 * 其次拿状态里第一个 DSH 会话 —— 用户在聊天页没开 DSH 会话时也能在设置里管理。
 */
function dshSession(): { id: string; label: string } | null {
  if (!runtime) return null;
  const sessions = runtime.store.state.sessions;
  const asDsh = (id: string | null | undefined): { id: string; label: string } | null => {
    if (!id) return null;
    const session = sessions.find((item) => item.id === id);
    if (!session) return null;
    if (String(session.backend ?? "").trim().toLowerCase() !== "dsh") return null;
    return { id: session.id, label: `会话「${session.title}」` };
  };
  const active = asDsh(activeSessionId);
  if (active) return active;
  for (const session of sessions) {
    if (String(session.backend ?? "").trim().toLowerCase() === "dsh") {
      return { id: session.id, label: `会话「${session.title}」` };
    }
  }
  return null;
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

  const switchable = backendSwitchAllowed(runtime.store.state.runtimes ?? [], sessionId);
  const currentChoice: BackendChoice = {
    backend: normalizeBackend(session.backend),
    acpToolId: typeof session.acpToolId === "string" ? session.acpToolId : null,
  };
  const acpTools = runtime.store.state.acpTools;
  const lockedHint = backendLockedHint(currentChoice, acpTools);

  openSheet(session.title || "会话", (sheet) => {
    sheet.append(
      h("button", {
        class: "sheet-item",
        text: "重命名",
        onclick: () => openRenameSheet(sessionId, session.title ?? ""),
      }),
      h("button", {
        class: switchable ? "sheet-item" : "sheet-item is-disabled",
        text: `后端：${describeChoice(currentChoice, acpTools)}${switchable ? "（可切换）" : "（已锁定）"}`,
        title: switchable ? "会话还没开始过，可以换成别的后端" : lockedHint,
        onclick: () => {
          // 锁定态不报错、只提示原因：直接标 disabled 会让用户不知道为什么点不动
          if (!switchable) {
            toast(lockedHint);
            return;
          }
          openBackendSheet(sessionId);
        },
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
      renderEmptyState(null);
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
  const title = findSessionTitle(sessionId);

  // 浏览器 / 开发态：没有 CapacitorHttp，跨域 GET 带 Authorization 会被预检拦掉，
  // 只能退回浏览器直接打开（token 走 query 仅此一路）。
  if (!isNativeShell()) {
    const url = `${client.baseUrl}/api/sessions/${encodeURIComponent(sessionId)}/export-html?token=${encodeURIComponent(client.token)}`;
    window.open(url, "_blank");
    toast("正在导出 HTML…");
    return;
  }

  toast("正在导出 HTML…");
  void (async () => {
    try {
      // 带 Authorization 头取字节：不让 token 落到 URL / 下载记录 / 系统 Intent 里
      const html = await client.sessionExportHtml(sessionId);
      const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, "-");
      const saved = await saveTextFile(`PiDeck-${safeFileStem(title)}-${stamp}.html`, "text/html", html);
      toast(saved.ok ? `已保存到 ${saved.location}` : saved.message);
    } catch (error) {
      toast(describeError(error));
    }
  })();
}

/** 文件名里不能出现的字符统一换成短横线。 */
function safeFileStem(value: string): string {
  const cleaned = value.replace(/[\\/:*?"<>|\s]+/g, "-").replace(/^-+|-+$/g, "");
  return (cleaned || "session").slice(0, 40);
}

function findSessionTitle(sessionId: string): string {
  const session = runtime?.store.state.sessions.find((item) => item.id === sessionId);
  return session?.title?.trim() || "session";
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
  // 万一某个初始化分支挂住，启动动画也必须自己收掉（否则界面被挡死）
  armSplashWatchdog();
  // 首次跑多端版本：只让当前这台参与同时连接，其余等用户主动开
  ensureMultiEndpointDefaults();

  const saved = loadPairing();
  // 同时连接的是「启用的那几台」；一台都没启用就退回单台（兼容老数据）
  const enabled = listEnabledPairings();
  const list = enabled.length > 0 ? enabled : saved ? [saved] : [];

  if (list.length === 0) {
    renderPairing();
    return;
  }
  setSplashHint(list.length > 1 ? `正在连接 ${list.length} 台设备…` : "正在连接 PiDeck…");
  void connectAll(list, { activeBaseUrl: saved?.baseUrl }).then(
    () => hideSplash(),
    (error: unknown) => {
      renderPairing({
        link: saved?.baseUrl ? `${saved.baseUrl}/?token=${encodeURIComponent(saved.token)}` : "",
        error: describeError(error),
      });
    },
  );
}

bootstrap();
