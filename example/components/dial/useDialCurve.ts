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
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const push = useCallback(
    (v: T) => {
      pendingRef.current = v;
      const elapsed = Date.now() - lastRef.current;
      if (elapsed >= throttleMs) {
        lastRef.current = Date.now();
        setState(v);
      } else if (timerRef.current === null) {
        // Trailing flush: guarantees the final drag value is not dropped.
        timerRef.current = setTimeout(() => {
          timerRef.current = null;
          lastRef.current = Date.now();
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

  useAnimatedReaction(
    read,
    (v) => {
      runOnJS(push)(v);
    },
    [read, push]
  );

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
