import { EdgeFadeView } from './EdgeFadeView';

export { EdgeFadeView };

/**
 * Raw Fabric native component — for advanced Reanimated usage.
 *
 * Animate `fadeTop` / `fadeBottom` / `fadeLeft` / `fadeRight` directly as
 * native props so Reanimated's UI-thread shadow-node mutation targets the
 * correct prop instead of Yoga's `top` / `bottom` layout properties.
 *
 * Use `Animated.createAnimatedComponent(NativeEdgeFadeView)` and drive
 * `fadeTop` (not `top`) with `useAnimatedProps`.
 *
 * On web this is a plain View — no visual effects apply.
 */
export { default as NativeEdgeFadeView } from './EdgeFadeViewNativeComponent';

export type {
  EdgeFadeViewProps,
  EdgeFadeCurve,
  EdgeFadeMode,
  EdgeConfig,
  CurvePreset,
  CubicBezierCurve,
  StopsCurve,
} from './types';
