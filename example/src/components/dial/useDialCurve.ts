/**
 * Throttled UI-thread → React-state mirrors for non-animatable props.
 *
 * EdgeFadeView's `curve` (and `blurRadius`) are plain JS props, so SharedValue
 * changes must be mirrored into React state. The mirror is throttled with a
 * trailing flush, so the last value of a drag always lands.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  runOnJS,
  useAnimatedReaction,
  type SharedValue,
} from 'react-native-reanimated';
import type { CubicBezierCurve } from 'react-native-edge-fade';

// Mirrored values are numbers or small plain objects (a bezier curve): compare
// by content so a fresh object with the same numbers never re-renders.
function sameValue(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true;
  if (typeof a !== 'object' || typeof b !== 'object' || !a || !b) return false;
  const ka = Object.keys(a);
  if (ka.length !== Object.keys(b).length) return false;
  return ka.every((k) =>
    Object.is(
      (a as Record<string, unknown>)[k],
      (b as Record<string, unknown>)[k]
    )
  );
}

/**
 * Mirrors a UI-thread computed value into React state, throttled.
 * `read` MUST be a worklet function (declare `'worklet'` in its body).
 */
export function useThrottledMirror<T>(
  read: () => T,
  initial: T,
  throttleMs = 80
): T {
  const [state, setState] = useState<T>(initial);
  const lastRef = useRef(0);
  const pendingRef = useRef<T>(initial);
  // Last value handed to React: the reaction re-fires whenever it is
  // re-registered, and an unchanged value must not cost a render.
  const sentRef = useRef<T>(initial);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const push = useCallback(
    (v: T) => {
      pendingRef.current = v;
      if (timerRef.current === null && sameValue(v, sentRef.current)) return;
      const elapsed = Date.now() - lastRef.current;
      if (elapsed >= throttleMs) {
        lastRef.current = Date.now();
        sentRef.current = v;
        setState(v);
      } else if (timerRef.current === null) {
        // Trailing flush: guarantees the final drag value is not dropped.
        timerRef.current = setTimeout(() => {
          timerRef.current = null;
          lastRef.current = Date.now();
          if (sameValue(pendingRef.current, sentRef.current)) return;
          sentRef.current = pendingRef.current;
          setState(pendingRef.current);
        }, throttleMs - elapsed);
      }
    },
    [throttleMs]
  );

  useEffect(
    () => () => {
      if (timerRef.current !== null) clearTimeout(timerRef.current);
    },
    []
  );

  // No dependency array: on native Reanimated tracks the worklet closures
  // itself (and warns on every render when one is passed).
  useAnimatedReaction(read, (v) => {
    runOnJS(push)(v);
  });

  return state;
}

/**
 * Mirrors four bezier SharedValues into a `CubicBezierCurve` object suitable
 * for EdgeFadeView's `curve` prop, throttled.
 */
export function useDialCurve(
  x1: SharedValue<number>,
  y1: SharedValue<number>,
  x2: SharedValue<number>,
  y2: SharedValue<number>,
  throttleMs = 80
): CubicBezierCurve {
  const initialRef = useRef<CubicBezierCurve | null>(null);
  if (initialRef.current === null) {
    initialRef.current = {
      type: 'cubicBezier',
      x1: x1.get(),
      y1: y1.get(),
      x2: x2.get(),
      y2: y2.get(),
    };
  }

  const read = useCallback((): CubicBezierCurve => {
    'worklet';
    return {
      type: 'cubicBezier',
      x1: x1.get(),
      y1: y1.get(),
      x2: x2.get(),
      y2: y2.get(),
    };
  }, [x1, y1, x2, y2]);

  return useThrottledMirror(read, initialRef.current, throttleMs);
}
