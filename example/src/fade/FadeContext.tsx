import { DEFAULT_DEMO_BLUR } from './limits';
import { BLUR_LAB_DEFAULTS, DEFAULT_BEZIER } from './presets';
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
  useEffect,
  useMemo,
  useState,
} from 'react';
import type { ReactNode } from 'react';
import {
  Easing,
  cancelAnimation,
  useSharedValue,
  withRepeat,
  withSequence,
  withTiming,
} from 'react-native-reanimated';
import type { SharedValue } from 'react-native-reanimated';
import type { EdgeFadeCurve, EdgeFadeMode } from 'react-native-edge-fade';

import { useDialCurve, useThrottledMirror } from '@/components/dial';

export type DemoBlurRenderer =
  | 'auto'
  | 'agsl'
  | 'androidx'
  | 'androidx-gradient'
  | 'scaled';

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
  frostSat: SharedValue<number>;
  frostLift: SharedValue<number>;
  frostProg: SharedValue<number>;

  mode: EdgeFadeMode;
  setMode: (mode: EdgeFadeMode) => void;
  tint: string | undefined;
  setTint: (tint: string | undefined) => void;
  showBands: boolean;
  setShowBands: (show: boolean) => void;

  /** Android demo-only override for comparing progressive renderers. */
  blurRenderer: DemoBlurRenderer;
  setBlurRenderer: (renderer: DemoBlurRenderer) => void;

  /**
   * Auto-demo: when on, the top/bottom fade region gently breathes on a loop so
   * the effect is self-evident without any interaction (used for the hero / video
   * preview). Turning it off cancels the loop and eases the region back to the
   * Blur Lab default geometry.
   */
  autoDemo: boolean;
  setAutoDemo: (on: boolean) => void;

  /** Name of the active curve preset, or 'custom' after a manual edit. */
  preset: string;
  setPreset: (preset: string) => void;

  /** Restore every value to its default (used by the panel header's Reset). */
  reset: () => void;
}

export interface FadeRender {
  curve: EdgeFadeCurve;
  blurRadius: number;
  frostSaturation: number;
  frostLift: number;
  frostProgression: number;
}

const FadeStoreContext = createContext<FadeStore | null>(null);
const FadeRenderContext = createContext<FadeRender | null>(null);

export function FadeProvider({ children }: { children: ReactNode }) {
  // Start from the same visual profile as Blur Lab so the Gallery is the real
  // public-API comparison surface rather than a separately tuned approximation.
  const x1 = useSharedValue(DEFAULT_BEZIER.x1);
  const y1 = useSharedValue(DEFAULT_BEZIER.y1);
  const x2 = useSharedValue(DEFAULT_BEZIER.x2);
  const y2 = useSharedValue(DEFAULT_BEZIER.y2);
  const top = useSharedValue(BLUR_LAB_DEFAULTS.top);
  const bottom = useSharedValue(BLUR_LAB_DEFAULTS.bottom);
  const left = useSharedValue(BLUR_LAB_DEFAULTS.left);
  const right = useSharedValue(BLUR_LAB_DEFAULTS.right);
  const blur = useSharedValue(DEFAULT_DEMO_BLUR);
  const radius = useSharedValue(0);
  const frostSat = useSharedValue(0.9);
  const frostLift = useSharedValue(1.03);
  const frostProg = useSharedValue(BLUR_LAB_DEFAULTS.progression);

  const [mode, setMode] = useState<EdgeFadeMode>('blur');
  // No frost tint by default: a pure content-derived Gaussian blur that adapts
  // to any photo. Dark/Light/custom tints are opt-in via the panel.
  const [tint, setTint] = useState<string | undefined>(undefined);
  const [showBands, setShowBands] = useState(false);
  const [blurRenderer, setBlurRenderer] = useState<DemoBlurRenderer>('auto');
  const [autoDemo, setAutoDemo] = useState(false);
  // Starts on the named default curve; a manual edit flips it to 'custom'.
  const [preset, setPreset] = useState<string>('default');

  // Drive the breathing loop on the UI thread while auto-demo is on; ease the
  // fade region back to its default and cancel the loop when it's turned off.
  useEffect(() => {
    if (autoDemo) {
      const cfg = { duration: 1600, easing: Easing.inOut(Easing.ease) };
      const loop = () =>
        withRepeat(
          withSequence(withTiming(30, cfg), withTiming(170, cfg)),
          -1,
          true
        );
      top.set(loop());
      bottom.set(loop());
    } else {
      cancelAnimation(top);
      cancelAnimation(bottom);
      top.set(withTiming(BLUR_LAB_DEFAULTS.top));
      bottom.set(withTiming(BLUR_LAB_DEFAULTS.bottom));
    }
    return () => {
      cancelAnimation(top);
      cancelAnimation(bottom);
    };
  }, [autoDemo, top, bottom]);

  const reset = useCallback(() => {
    x1.set(DEFAULT_BEZIER.x1);
    y1.set(DEFAULT_BEZIER.y1);
    x2.set(DEFAULT_BEZIER.x2);
    y2.set(DEFAULT_BEZIER.y2);
    top.set(BLUR_LAB_DEFAULTS.top);
    bottom.set(BLUR_LAB_DEFAULTS.bottom);
    left.set(BLUR_LAB_DEFAULTS.left);
    right.set(BLUR_LAB_DEFAULTS.right);
    blur.set(DEFAULT_DEMO_BLUR);
    radius.set(0);
    frostSat.set(0.9);
    frostLift.set(1.03);
    frostProg.set(BLUR_LAB_DEFAULTS.progression);
    setMode('blur');
    setTint(undefined);
    setShowBands(false);
    setBlurRenderer('auto');
    setAutoDemo(false);
    setPreset('default');
  }, [
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
    frostSat,
    frostLift,
    frostProg,
  ]);

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
      frostSat,
      frostLift,
      frostProg,
      mode,
      setMode,
      tint,
      setTint,
      showBands,
      setShowBands,
      blurRenderer,
      setBlurRenderer,
      autoDemo,
      setAutoDemo,
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
      frostSat,
      frostLift,
      frostProg,
      mode,
      tint,
      showBands,
      blurRenderer,
      autoDemo,
      preset,
      reset,
    ]
  );

  const dialCurve = useDialCurve(x1, y1, x2, y2);
  // The default preset uses the same native `smooth` curve string as Blur Lab.
  // Once a preset/manual edit changes it, the panel's cubic curve is used.
  const curve: EdgeFadeCurve = preset === 'default' ? 'smooth' : dialCurve;

  const readBlur = useCallback((): number => {
    'worklet';
    return blur.get();
  }, [blur]);
  const blurRadius = useThrottledMirror(readBlur, DEFAULT_DEMO_BLUR);

  const readFrostSat = useCallback((): number => {
    'worklet';
    return frostSat.get();
  }, [frostSat]);
  const frostSaturation = useThrottledMirror(readFrostSat, 0.9);

  const readFrostLift = useCallback((): number => {
    'worklet';
    return frostLift.get();
  }, [frostLift]);
  const frostLiftValue = useThrottledMirror(readFrostLift, 1.03);

  const readFrostProg = useCallback((): number => {
    'worklet';
    return frostProg.get();
  }, [frostProg]);
  const frostProgression = useThrottledMirror(
    readFrostProg,
    BLUR_LAB_DEFAULTS.progression
  );

  const render = useMemo<FadeRender>(
    () => ({
      curve,
      blurRadius,
      frostSaturation,
      frostLift: frostLiftValue,
      frostProgression,
    }),
    [curve, blurRadius, frostSaturation, frostLiftValue, frostProgression]
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
