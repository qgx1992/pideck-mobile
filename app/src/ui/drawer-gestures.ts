/**
 * 抽屉滑动手势（窄屏）：左右两个抽屉由同一个控制器管理。
 *
 * 规则（关键：**同时只能开一个，反方向是关闭而不是开另一个**）：
 *
 *   都没开   → 右滑开左抽屉，左滑开右抽屉
 *   左已开   → 左滑关闭左抽屉；右滑无意义
 *   右已开   → 右滑关闭右抽屉；左滑无意义
 *
 * 其它细节：
 *   - 边缘优先区（默认 48px）内起手立即跟手；区外也允许，但需要横向位移
 *     达到 engagePx（默认 18px）才确认——绕开 Android 抢边缘的返回手势
 *   - 竖向位移更大时判定为滚动，手势作废
 *   - 输入框、代码块、横向滚动的 chips 等区域不接管
 *   - 松手时进度 ≥ openRatio（默认 35%）或速度够快即完成，否则回弹
 *
 * 判定逻辑全部抽成纯函数（planSwipe / drawerPercent / shouldFinish），便于单测。
 */

/** 当前打开的抽屉。 */
export type DrawerOpenState = "none" | "left" | "right";

/** 手指的移动方向。 */
export type SwipeAxis = "right" | "left";

export interface SwipePlan {
  /** 这次手势驱动哪个抽屉 */
  drawer: "left" | "right";
  /** 完成后是打开还是关闭 */
  action: "open" | "close";
}

/**
 * 根据「滑动方向 + 当前打开状态」决定这次手势驱动谁、做什么。
 * 返回 null 表示这次手势不接管（避免出现两个抽屉同时打开）。
 */
export function planSwipe(axis: SwipeAxis, open: DrawerOpenState): SwipePlan | null {
  if (open === "none") {
    return axis === "right"
      ? { drawer: "left", action: "open" }
      : { drawer: "right", action: "open" };
  }
  if (open === "left") {
    // 左抽屉是右滑打开的，反方向（左滑）负责关闭它；此时右滑不该去开右抽屉
    return axis === "left" ? { drawer: "left", action: "close" } : null;
  }
  // open === "right"
  return axis === "right" ? { drawer: "right", action: "close" } : null;
}

/** 位移 → 抽屉行程比例（0..1）。travel 为「朝手势方向」的正位移。 */
export function swipeProgress(travel: number, width: number): number {
  if (!Number.isFinite(width) || width <= 0) return 0;
  return Math.min(1, Math.max(0, travel / width));
}

/**
 * 抽屉位移百分比。
 * 左抽屉：-100%（收起）→ 0；右抽屉：+100%（收起）→ 0。
 */
export function drawerPercent(progress: number, direction: "left" | "right" = "left"): number {
  const clamped = Math.min(1, Math.max(0, progress));
  const value = direction === "left" ? clamped - 1 : 1 - clamped;
  return Math.round(value * 1000) / 10;
}

/**
 * 拖动过程中抽屉应该处在什么进度。
 * - 打开手势：进度 = travel / width
 * - 关闭手势：起点是全开（1），往回收（1 - travel / width）
 */
export function progressForAction(
  travel: number,
  width: number,
  action: SwipePlan["action"],
): number {
  const p = swipeProgress(travel, width);
  return action === "close" ? 1 - p : p;
}

/**
 * 把 dx 换算成「沿手势方向的行程」。
 *
 * 向右滑对「开左抽屉 / 关右抽屉」是正行程；
 * 向左滑对「开右抽屉 / 关左抽屉」是正行程。
 */
export function travelFor(dx: number, plan: SwipePlan): number {
  const wantsRightward =
    (plan.drawer === "left" && plan.action === "open") ||
    (plan.drawer === "right" && plan.action === "close");
  return wantsRightward ? dx : -dx;
}

/** 松手时是否完成本次动作（距离够或轻扫够快）。 */
export function shouldFinish(
  travel: number,
  width: number,
  velocity: number,
  openRatio = 0.35,
  flingVelocity = 0.4,
): boolean {
  if (travel <= 0) return false;
  if (swipeProgress(travel, width) >= openRatio) return true;
  return velocity >= flingVelocity;
}

/**
 * 是否应该在这一点起手跟踪。
 *
 * @param edgeDistance 到「抽屉那一侧」边缘的距离
 * @param allowAnywhere 是否允许非边缘起手（默认允许，因为系统会抢边缘手势）
 * @param inInteractive 起点是否落在输入框/代码块等有自己语义的区域
 */
export function shouldTrackGesture(
  edgeDistance: number,
  edgePx: number,
  allowAnywhere: boolean,
  inInteractive: boolean,
): boolean {
  if (inInteractive) return false;
  return edgeDistance <= edgePx || allowAnywhere;
}

export interface GestureDrawer {
  /** 抽屉节点（inline transform 由控制器接管） */
  drawer: HTMLElement;
  /** 该抽屉的遮罩 */
  scrim: HTMLElement;
  /** 完成打开 */
  applyOpen: () => void;
  /** 完成关闭 */
  applyClose: () => void;
}

export interface DrawerGesturesOptions {
  left: GestureDrawer;
  right: GestureDrawer;
  /** 当前打开状态（拖动期间不会被外力改变） */
  currentOpen: () => DrawerOpenState;
  /** 是否允许手势（一般是「窄屏」） */
  isEnabled: () => boolean;
  edgePx?: number;
  engagePx?: number;
  openRatio?: number;
  flingVelocity?: number;
}

/** 这些区域里的横向滑动有自己的语义（选文本、横向滚动），不要抢。 */
const INTERACTIVE_SELECTOR = [
  "textarea",
  "input",
  "pre",
  ".composer-chips",
  ".ask-card",
  ".ask-input",
  ".ws-preview",
  ".sheet",
  ".process-body",
].join(",");

function isInteractiveTarget(target: EventTarget | null): boolean {
  const el = target as Element | null;
  if (!el || typeof el.closest !== "function") return false;
  return el.closest(INTERACTIVE_SELECTOR) !== null;
}

export function attachDrawerGestures(options: DrawerGesturesOptions): () => void {
  const edgePx = options.edgePx ?? 48;
  const engagePx = options.engagePx ?? 18;
  const openRatio = options.openRatio ?? 0.35;
  const flingVelocity = options.flingVelocity ?? 0.4;

  const doc = options.left.drawer.ownerDocument;

  let pending = false;   // 已 touchstart，等待确认方向
  let engaged = false;   // 已确认是横向手势
  let plan: SwipePlan | null = null;
  let startX = 0;
  let startY = 0;
  let lastX = 0;
  let lastTime = 0;
  let velocity = 0;
  let width = 0;
  let fromEdge = false;
  let active: GestureDrawer | null = null;

  const release = (): void => {
    pending = false;
    engaged = false;
    fromEdge = false;
    plan = null;
    if (active) {
      active.drawer.style.transition = "";
      active.drawer.style.transform = "";
      active.scrim.style.opacity = "";
      active.scrim.style.display = "";
    }
    active = null;
  };

  const targetFor = (drawer: "left" | "right"): GestureDrawer =>
    drawer === "left" ? options.left : options.right;

  const onStart = (event: TouchEvent): void => {
    if (event.touches.length !== 1) return;
    if (!options.isEnabled()) return;
    const touch = event.touches[0];
    if (!touch) return;
    if (isInteractiveTarget(event.target)) return;

    // 方向要到 touchmove 才决定，这里只记录起点
    const innerWidth = doc.defaultView?.innerWidth ?? 0;
    const nearestEdge = Math.min(touch.clientX, innerWidth - touch.clientX);

    pending = true;
    fromEdge = nearestEdge <= edgePx;
    engaged = false;
    plan = null;
    startX = touch.clientX;
    startY = touch.clientY;
    lastX = touch.clientX;
    lastTime = Date.now();
    velocity = 0;
  };

  const onMove = (event: TouchEvent): void => {
    if (!pending) return;
    const touch = event.touches[0];
    if (!touch) return;

    const dx = touch.clientX - startX;
    const dy = touch.clientY - startY;

    if (!engaged) {
      // 边缘起手更灵敏（约 1/3 阈值），非边缘要求更明确的横向意图
      const need = fromEdge ? Math.max(6, Math.round(engagePx / 3)) : engagePx;
      if (Math.abs(dx) < need && Math.abs(dy) < need) return;
      if (Math.abs(dy) > Math.abs(dx)) {
        release();
        return;
      }
      const axis: SwipeAxis = dx > 0 ? "right" : "left";
      plan = planSwipe(axis, options.currentOpen());
      if (!plan) {
        // 已有抽屉开着且滑的是「开另一个」的方向 → 不接管
        pending = false;
        return;
      }
      active = targetFor(plan.drawer);
      width = active.drawer.getBoundingClientRect().width || (doc.defaultView?.innerWidth ?? 320) * 0.86;
      active.drawer.style.transition = "none";
      active.scrim.style.display = "block";
      engaged = true;
    }

    if (!plan || !active) return;
    if (event.cancelable) event.preventDefault();

    const now = Date.now();
    const dt = now - lastTime;
    const step = touch.clientX - lastX;
    if (dt > 0) velocity = step / dt;
    lastX = touch.clientX;
    lastTime = now;

    const travel = travelFor(dx, plan);
    const progress = progressForAction(travel, width, plan.action);
    active.drawer.style.transform = `translateX(${drawerPercent(progress, plan.drawer)}%)`;
    active.scrim.style.opacity = String(Math.min(1, progress * 1.4));
  };

  const onEnd = (): void => {
    if (!engaged || !plan || !active) {
      release();
      return;
    }

    const drawer = active;
    const currentPlan = plan;
    const travel = travelFor(lastX - startX, currentPlan);
    const finished = shouldFinish(travel, width, Math.abs(velocity), openRatio, flingVelocity);

    // 先清 inline，让 CSS 类接管，才有吸附动画
    drawer.drawer.style.transition = "";
    drawer.drawer.style.transform = "";
    drawer.scrim.style.opacity = "";
    drawer.scrim.style.display = "";

    if (finished) {
      if (currentPlan.action === "open") drawer.applyOpen();
      else drawer.applyClose();
    }
    // 没完成就什么都不做：class 未变，CSS 会把抽屉弹回原位

    engaged = false;
    pending = false;
    plan = null;
    active = null;
  };

  doc.addEventListener("touchstart", onStart, { passive: true });
  doc.addEventListener("touchmove", onMove, { passive: false });
  doc.addEventListener("touchend", onEnd, { passive: true });
  doc.addEventListener("touchcancel", onEnd, { passive: true });

  return () => {
    doc.removeEventListener("touchstart", onStart);
    doc.removeEventListener("touchmove", onMove);
    doc.removeEventListener("touchcancel", onEnd);
    doc.removeEventListener("touchend", onEnd);
    release();
  };
}
