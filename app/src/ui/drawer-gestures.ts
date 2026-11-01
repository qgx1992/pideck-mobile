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
 *   - 只有「真的要横向滚动」的容器和输入类控件不接管；正文里的列表、思考块、工具块
 *     一律可以滑（曾经按选择器一刀切，结果会话页大半面积滑不出侧栏）
 *   - 松手时进度 ≥ openRatio（默认 35%）或速度够快即完成，否则回弹
 *   - 手指越过起点往回走（反悔）就作废，不会在回弹后又按原方向弹开
 *   - 速度必须和手势方向同向：往回缩的那一下不算轻扫
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

/**
 * 松手时是否完成本次动作（距离够或轻扫够快）。
 *
 * `velocity` 必须是**沿手势方向**的速度（px/ms），用 velocityAlong() 算。
 * 传绝对值会踩坑：手指拉到一半又往回缩时，最后一步的速度是反的，
 * 取了绝对值反而被判成「快速轻扫」，于是回弹后抽屉又弹出来。
 */
export function shouldFinish(
  travel: number,
  width: number,
  velocity: number,
  openRatio = 0.35,
  flingVelocity = 0.4,
): boolean {
  if (travel <= 0) return false;
  if (swipeProgress(travel, width) >= openRatio) return true;
  if (!Number.isFinite(velocity) || velocity <= 0) return false;
  return velocity >= flingVelocity;
}

/**
 * 一步位移 → 沿手势方向的速度（px/ms）。
 *
 * 正数 = 还在往目标方向走；负数 = 正在往回缩。
 */
export function velocityAlong(stepPx: number, plan: SwipePlan, dtMs: number): number {
  if (!Number.isFinite(dtMs) || dtMs <= 0) return 0;
  return travelFor(stepPx, plan) / dtMs;
}

/** 手指越过起点这么多像素，判定为「反悔」，本次手势作废。 */
export const REVERSE_CANCEL_PX = 8;

/** 越过起点往回走了吗（留几个像素的抖动余量）。 */
export function isReversePastOrigin(travel: number): boolean {
  return travel < -REVERSE_CANCEL_PX;
}

/** 松手前这么久没动过，就不算轻扫（按住不动时残留的旧速度不能拿来判）。 */
export const FLING_STALE_MS = 120;

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

/**
 * 这些区域里的横向滑动有自己的语义（选文本、横向滚动、自己的拖拽），不要抢。
 *
 * 只留**输入类控件**和**自带手势的面板**。正文里的 `.md-list` / `.md-table` /
 * `.process-body` / `.reasoning-body` 之类曾经也在这张名单里，代价是整个会话页
 * 一半以上的面积滑不出侧栏（思考块、工具块、所有列表都算进去了）。
 * 真正需要独占横向手势的「宽表格 / 长代码行」由 hasHorizontalScrollerAncestor()
 * 在运行时按实际滚动量判断，不用写死；横向滚动的 chips 更是本身就 overflow-x: auto。
 */
const INTERACTIVE_SELECTOR = [
  "textarea",
  "input",
  "select",
  '[contenteditable]:not([contenteditable="false"])',
  ".ask-card",
  ".ask-input",
  ".ask-review",
  ".ws-preview",   // 代码预览面板：两轴都能滚，自己占着手势
  ".sheet",
].join(",");

/**
 * 页面 / 面板级滚动容器：内容横向溢出（长单词、宽图、没折行的行）是噪声，
 * 不能因此判定「这里归内容自己管」，否则整页都滑不出侧栏。
 */
const SCROLL_ROOT_SELECTOR = [
  ".chat-scroll",
  ".sidebar-scroll",
  ".ws-content",
  ".sheet",
  ".sheet-body",
  "body",
  "html",
].join(",");

/** 这个节点是不是页面级滚动容器（横向溢出属于噪声）。 */
export function isScrollRoot(el: Element): boolean {
  return typeof el.matches === "function" && el.matches(SCROLL_ROOT_SELECTOR);
}

/**
 * 横向可滚动量（像素差）。jsdom 里没有布局，所以单独抽出来便于测试。
 */
export function horizontalScrollRange(scrollWidth: number, clientWidth: number): number {
  if (!Number.isFinite(scrollWidth) || !Number.isFinite(clientWidth)) return 0;
  return Math.max(0, scrollWidth - clientWidth);
}

/**
 * 这个元素当前能不能横向滚动。
 * overflow-x 必须是 auto/scroll（hidden 只是裁剪，拖它也没意义）。
 */
export function isHorizontalScroller(
  overflowX: string,
  scrollWidth: number,
  clientWidth: number,
): boolean {
  const scrollable = overflowX === "auto" || overflowX === "scroll" || overflowX === "overlay";
  if (!scrollable) return false;
  return horizontalScrollRange(scrollWidth, clientWidth) > 1;
}

/**
 * 从触点往上找：有没有「能横向滚动」的祖先。
 *
 * 这是为了让宽表格 / 长代码行能左右拖，而不是把侧边栏拽出来——
 * 之前只按选择器排除，`.md-table-wrap` 这种运行时才产生滚动条的容器漏掉了。
 *
 * 但页面级滚动容器（`.chat-scroll` 等）必须排除在外：`overflow-y: auto` 会让
 * 计算出来的 `overflow-x` 也变成 auto，只要有一条消息横向溢出（长 URL、宽图），
 * 整个会话页就被判成「内容自己管手势」，于是哪里都滑不出侧栏。
 */
export function hasHorizontalScrollerAncestor(target: EventTarget | null, root?: Element | null): boolean {
  const start = target as Element | null;
  if (!start || typeof start.closest !== "function") return false;

  const view = start.ownerDocument?.defaultView;
  let node: Element | null = start;
  const stop = root ?? start.ownerDocument?.body ?? null;

  while (node && node !== stop) {
    if (isScrollRoot(node)) return false;
    const style = view?.getComputedStyle?.(node);
    if (style && isHorizontalScroller(style.overflowX, node.scrollWidth, node.clientWidth)) {
      return true;
    }
    node = node.parentElement;
  }
  return false;
}

/** 起点落在「不该接管」的元素里吗。 */
export function isInteractiveTarget(target: EventTarget | null): boolean {
  const el = target as Element | null;
  if (!el || typeof el.closest !== "function") return false;
  if (el.closest(INTERACTIVE_SELECTOR) !== null) return true;
  // 表格/代码块这类容器只有内容超宽时才滚，选择器覆盖不到，必须看实际滚动量
  return hasHorizontalScrollerAncestor(el);
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
  /** 上一次「时间真的前进过」的采样点：算速度用，避免同一毫秒内的多步把位移吞了 */
  let sampleX = 0;
  let sampleTime = 0;
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
    sampleX = touch.clientX;
    sampleTime = lastTime;
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

    const travel = travelFor(dx, plan);

    // 手指越过起点往回走 = 反悔：立刻收手，让抽屉弹回去。
    // 不处理的话，回弹过程中松手仍会按原方向弹开（就是「拉一半反向还弹侧栏」）。
    if (isReversePastOrigin(travel)) {
      release();
      return;
    }

    if (event.cancelable) event.preventDefault();

    const now = Date.now();
    lastX = touch.clientX;
    lastTime = now;
    // 速度按「上一次时间真的前进过的采样点」算：同一毫秒内的多步只累位移，不造出无穷大速度
    const dt = now - sampleTime;
    if (dt > 0) {
      velocity = velocityAlong(touch.clientX - sampleX, plan, dt);
      sampleX = touch.clientX;
      sampleTime = now;
    }

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
    // 松手前手指已经按住不动了：那条旧速度不能拿来判「轻扫」
    const freshVelocity = Date.now() - lastTime > FLING_STALE_MS ? 0 : velocity;
    const finished = shouldFinish(travel, width, freshVelocity, openRatio, flingVelocity);

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
