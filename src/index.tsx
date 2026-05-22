import { EdgeFadeView } from './EdgeFadeView';
import { AnimatedEdgeFadeView } from './AnimatedEdgeFadeView';

export { EdgeFadeView, AnimatedEdgeFadeView };

export type { AnimatedEdgeFadeViewProps } from './AnimatedEdgeFadeView';

/**
 * Raw Fabric native component — escape hatch for power users who want full
 * control over the flat native props (`fadeTop`, `fadeBottom`, etc.) and the
 * Reanimated wrapping themselves. Prefer `AnimatedEdgeFadeView` for animated
 * use cases.
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
