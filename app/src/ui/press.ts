/**
 * 长按 / 右键手势。
 *
 * 移动端要长按，桌面端要右键，两者共用一套回调：
 *   - 触摸或鼠标按下后计时，超过 delay 触发 onLongPress，并抑制随后的 click
 *   - 手指/鼠标移动超过位移阈值就取消（避免滚动列表时误触发）
 *   - contextmenu 直接走 onLongPress
 */

export interface PressableOptions {
  onTap?: () => void;
  onLongPress?: () => void;
  /** 长按判定时长，默认 450ms */
  delay?: number;
  /** 允许的最大位移（px），默认 10 */
  moveTolerance?: number;
}

export function attachPressable(element: HTMLElement, options: PressableOptions): () => void {
  const delay = options.delay ?? 450;
  const tolerance = options.moveTolerance ?? 10;

  let timer: ReturnType<typeof setTimeout> | null = null;
  let startX = 0;
  let startY = 0;
  let longPressFired = false;

  const clearTimer = (): void => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  };

  const start = (x: number, y: number): void => {
    if (!options.onLongPress) return;
    startX = x;
    startY = y;
    longPressFired = false;
    clearTimer();
    timer = setTimeout(() => {
      timer = null;
      longPressFired = true;
      // 轻微震动，让用户确认「长按生效了」（WebView 支持时）
      try {
        navigator.vibrate?.(8);
      } catch {
        /* 不支持就跳过 */
      }
      options.onLongPress?.();
    }, delay);
  };

  const move = (x: number, y: number): void => {
    if (timer === null) return;
    if (Math.abs(x - startX) > tolerance || Math.abs(y - startY) > tolerance) clearTimer();
  };

  const onTouchStart = (event: TouchEvent): void => {
    const touch = event.touches[0];
    if (touch) start(touch.clientX, touch.clientY);
  };
  const onTouchMove = (event: TouchEvent): void => {
    const touch = event.touches[0];
    if (touch) move(touch.clientX, touch.clientY);
  };
  const onTouchEnd = (): void => {
    clearTimer();
  };

  const onMouseDown = (event: MouseEvent): void => {
    if (event.button !== 0) return;
    start(event.clientX, event.clientY);
  };
  const onMouseMove = (event: MouseEvent): void => {
    move(event.clientX, event.clientY);
  };
  const onMouseUp = (): void => {
    clearTimer();
  };

  const onClick = (event: MouseEvent): void => {
    if (longPressFired) {
      // 长按已经处理过，抑制这次 click
      longPressFired = false;
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    options.onTap?.();
  };

  const onContextMenu = (event: MouseEvent): void => {
    if (!options.onLongPress) return;
    event.preventDefault();
    clearTimer();
    options.onLongPress();
  };

  element.addEventListener("touchstart", onTouchStart, { passive: true });
  element.addEventListener("touchmove", onTouchMove, { passive: true });
  element.addEventListener("touchend", onTouchEnd);
  element.addEventListener("touchcancel", onTouchEnd);
  element.addEventListener("mousedown", onMouseDown);
  element.addEventListener("mousemove", onMouseMove);
  element.addEventListener("mouseup", onMouseUp);
  element.addEventListener("mouseleave", onMouseUp);
  element.addEventListener("click", onClick);
  element.addEventListener("contextmenu", onContextMenu);

  return () => {
    clearTimer();
    element.removeEventListener("touchstart", onTouchStart);
    element.removeEventListener("touchmove", onTouchMove);
    element.removeEventListener("touchend", onTouchEnd);
    element.removeEventListener("touchcancel", onTouchEnd);
    element.removeEventListener("mousedown", onMouseDown);
    element.removeEventListener("mousemove", onMouseMove);
    element.removeEventListener("mouseup", onMouseUp);
    element.removeEventListener("mouseleave", onMouseUp);
    element.removeEventListener("click", onClick);
    element.removeEventListener("contextmenu", onContextMenu);
  };
}
