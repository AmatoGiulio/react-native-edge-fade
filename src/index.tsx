import { EdgeFadeView } from './EdgeFadeView';
import { AnimatedEdgeFadeView } from './AnimatedEdgeFadeView';

export { EdgeFadeView, AnimatedEdgeFadeView };

export type { AnimatedEdgeFadeViewProps } from './AnimatedEdgeFadeView';

// NativeEdgeFadeView (raw Fabric component) is intentionally not re-exported
// from the public barrel. Power users who need it can import it via the
// dedicated subpath:
//
//   import NativeEdgeFadeView from 'react-native-edge-fade/native';
//
// For animated use cases prefer `AnimatedEdgeFadeView`.

export type {
  EdgeFadeViewProps,
  EdgeFadeCurve,
  EdgeFadeMode,
  EdgeConfig,
  CurvePreset,
  CubicBezierCurve,
  StopsCurve,
} from './types';
