import { PiDeckError } from "../api/errors.ts";
import type { PiDeckState, RuntimeTarget } from "../types.ts";

/** 从最新状态里取运行时目标。所有 /runtime/* 调用都必须用它，不要缓存。 */
export function findTarget(state: PiDeckState, sessionId: string): RuntimeTarget | undefined {
  const runtime = state.runtimes.find((item) => item.sessionId === sessionId);
  if (!runtime) return undefined;
  return {
    sessionId: runtime.sessionId,
    agentId: runtime.agentId,
    runtimeGeneration: runtime.runtimeGeneration,
  };
}

export function requireTarget(state: PiDeckState, sessionId: string): RuntimeTarget {
  const target = findTarget(state, sessionId);
  if (!target) {
    throw new PiDeckError({
      kind: "runtime",
      message: "该会话当前没有运行实例：先发一条消息或调用 activate 再操作",
    });
  }
  return target;
}

/** 服务端返回 409 之后，用最新状态判断手里的 target 是否已过期。 */
export function isTargetStale(state: PiDeckState, target: RuntimeTarget): boolean {
  const latest = findTarget(state, target.sessionId);
  if (!latest) return true;
  return latest.agentId !== target.agentId || latest.runtimeGeneration !== target.runtimeGeneration;
}

export function sameTarget(a: RuntimeTarget | null | undefined, b: RuntimeTarget | null | undefined): boolean {
  if (!a || !b) return false;
  return a.sessionId === b.sessionId && a.agentId === b.agentId && a.runtimeGeneration === b.runtimeGeneration;
}
