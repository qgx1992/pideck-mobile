/**
 * pideck-mobile —— PiDeck Web 服务的可移植客户端核心。
 *
 * 不绑定框架：Node / React Native / Capacitor / Flutter(通过 HTTP 桥) 都能用。
 * 只依赖全局 fetch、AbortController、URL、TextDecoder（后两者带降级实现）。
 */

export { PiDeckClient, normalizeState, emptyState, safeParseFrame, collectRun, combineSignals } from "./api/client.ts";
export type { PiDeckClientOptions, PromptInput, SseOptions, StateResponse, RuntimeAction } from "./api/client.ts";

export { ChatReducer, blocksToPlainText } from "./api/frames.ts";
export type { FrameApplyResult } from "./api/frames.ts";

export { SseParser, Utf8StreamDecoder, parseSseText, SSE_DONE_MARKER } from "./api/sse.ts";
export type { SseEvent } from "./api/sse.ts";

export { Backoff, sleep } from "./api/backoff.ts";
export type { BackoffOptions } from "./api/backoff.ts";

export {
  PiDeckError,
  PiDeckAuthError,
  PiDeckOfflineError,
  PiDeckRuntimeError,
  PiDeckHttpError,
  isAuthError,
  isOfflineError,
  isRuntimeStaleError,
  describeError,
} from "./api/errors.ts";

export { StateStore } from "./state/store.ts";
export type { StateStoreOptions, StoreStatus } from "./state/store.ts";

export { SessionChannel } from "./session.ts";
export type { SessionChannelOptions, ChannelStatus } from "./session.ts";

export { findTarget, requireTarget, isTargetStale, sameTarget } from "./runtime/target.ts";

export { parsePairingInput, buildPairingUrl, looksLikePairingUrl } from "./pairing.ts";
export type { PairingPayload } from "./pairing.ts";

export type * from "./types.ts";
