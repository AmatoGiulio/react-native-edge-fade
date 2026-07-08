/**
 * Shared edge-fade tuning state for the demo, split into two contexts for
 * performance:
 *
 *   - `FadeStore`  — SharedValues (bezier points + edge sizes), discrete config
 *     (mode/tint/showBands) and their setters. Its identity changes only on a
 *     tap, so consumers (the panel, the screens) don't re-render while dragging.
 *   - `FadeRender` — the throttled `curve`/`blurRadius` JS mirrors that
 *     EdgeFadeView needs as plain props. These change ~12×/s during a drag, so
 *     only the screens (which paint the fade) subscribe; the panel does not.
 *
 * SharedValue-first: the panel's dial rows / pad write straight into the
 * SharedValues on the UI thread, so edge sizes animate without a React render.
 */

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
} from 'react';
import type { ReactNode } from 'react';
import { useSharedValue } from 'react-native-reanimated';
import type { SharedValue } from 'react-native-reanimated';
import type { CubicBezierCurve, EdgeFadeMode } from 'react-native-edge-fade';

import { useDialCurve, useThrottledMirror } from '@/components/dial';

export interface FadeStore {
  x1: SharedValue<number>;
  y1: SharedValue<number>;
  x2: SharedValue<number>;
  y2: SharedValue<number>;
  top: SharedValue<number>;
  bottom: SharedValue<number>;
  left: SharedValue<number>;
  right: SharedValue<number>;
  blur: SharedValue<number>;
  radius: SharedValue<number>;

  mode: EdgeFadeMode;
  setMode: (mode: EdgeFadeMode) => void;
  tint: string | undefined;
  setTint: (tint: string | undefined) => void;
  showBands: boolean;
  setShowBands: (show: boolean) => void;

  /** Name of the active curve preset, or 'custom' after a manual edit. */
  preset: string;
  setPreset: (preset: string) => void;

  /** Restore every value to its default (used by the panel header's Reset). */
  reset: () => void;
}

export interface FadeRender {
  curve: CubicBezierCurve;
  blurRadius: number;
}

const FadeStoreContext = createContext<FadeStore | null>(null);
const FadeRenderContext = createContext<FadeRender | null>(null);

export function FadeProvider({ children }: { children: ReactNode }) {
  // Default curve — the steep-shouldered S Giulio picked: stays sharp inside,
  // then dives to fully faded near the edge.
  const x1 = useSharedValue(0.78);
  const y1 = useSharedValue(0.14);
  const x2 = useSharedValue(0.15);
  const y2 = useSharedValue(0.78);
  const top = useSharedValue(110);
  const bottom = useSharedValue(110);
  const left = useSharedValue(0);
  const right = useSharedValue(0);
  const blur = useSharedValue(28);
  const radius = useSharedValue(0);

  const [mode, setMode] = useState<EdgeFadeMode>('blur');
  // No frost tint by default: a pure content-derived Gaussian blur that adapts
  // to any photo. Dark/Light/custom tints are opt-in via the panel.
  const [tint, setTint] = useState<string | undefined>(undefined);
  const [showBands, setShowBands] = useState(false);
  // Starts on the named default curve; a manual edit flips it to 'custom'.
  const [preset, setPreset] = useState<string>('default');

  const reset = useCallback(() => {
    x1.set(0.78);
    y1.set(0.14);
    x2.set(0.15);
    y2.set(0.78);
    top.set(110);
    bottom.set(110);
    left.set(0);
    right.set(0);
    blur.set(28);
    radius.set(0);
    setMode('blur');
    setTint(undefined);
    setShowBands(false);
    setPreset('default');
  }, [x1, y1, x2, y2, top, bottom, left, right, blur, radius]);

  const store = useMemo<FadeStore>(
    () => ({
      x1,
      y1,
      x2,
      y2,
      top,
      bottom,
      left,
      right,
      blur,
      radius,
      mode,
      setMode,
      tint,
      setTint,
      showBands,
      setShowBands,
      preset,
      setPreset,
      reset,
    }),
    // SharedValues and setters are stable refs; only the discrete config flips.
    [
      x1,
      y1,
      x2,
      y2,
      top,
      bottom,
      left,
      right,
      blur,
      radius,
      mode,
      tint,
      showBands,
      preset,
      reset,
    ]
  );

  const curve = useDialCurve(x1, y1, x2, y2);
  const readBlur = useCallback((): number => {
    'worklet';
    return blur.get();
  }, [blur]);
  const blurRadius = useThrottledMirror(readBlur, 28);

  const render = useMemo<FadeRender>(
    () => ({ curve, blurRadius }),
    [curve, blurRadius]
  );

  return (
    <FadeStoreContext.Provider value={store}>
      <FadeRenderContext.Provider value={render}>
        {children}
      </FadeRenderContext.Provider>
    </FadeStoreContext.Provider>
  );
}

export function useFadeStore(): FadeStore {
  const ctx = useContext(FadeStoreContext);
  if (!ctx) throw new Error('useFadeStore must be used within a FadeProvider');
  return ctx;
}

export function useFadeRender(): FadeRender {
  const ctx = useContext(FadeRenderContext);
  if (!ctx) throw new Error('useFadeRender must be used within a FadeProvider');
  return ctx;
}
