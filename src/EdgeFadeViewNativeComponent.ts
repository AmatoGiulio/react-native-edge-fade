import {
  codegenNativeComponent,
  type ColorValue,
  type ViewProps,
} from 'react-native';
import type { Float } from 'react-native/Libraries/Types/CodegenTypesNamespace';

// Flat native props produced by the JS normalization layer.
// Sizes are in dp (0 = edge disabled). Curves are preset names or
// comma-separated alpha stop strings (from cubicBezier / stops serialization).
// mode: "mask" | "overlay" | "blur" | "lens"
interface NativeProps extends ViewProps {
  fadeTop?: Float;
  fadeBottom?: Float;
  fadeLeft?: Float;
  fadeRight?: Float;
  curveTop?: string;
  curveBottom?: string;
  curveLeft?: string;
  curveRight?: string;
  /** "mask" | "overlay" | "blur" | experimental "lens" */
  mode?: string;
  /** Max blur radius (dp) at the outer edge, blur mode only. */
  blurRadius?: Float;
  /** Frost vibrancy saturation multiplier (blur mode). 1 = neutral. */
  frostSaturation?: Float;
  /** Frost vibrancy brightness multiplier (blur mode). 1 = neutral. */
  frostLift?: Float;
  /** Fraction of the band over which the blur radius ramps (blur mode). */
  frostProgression?: Float;
  overlayColor?: ColorValue;
  overlayColorTop?: ColorValue;
  overlayColorBottom?: ColorValue;
  overlayColorLeft?: ColorValue;
  overlayColorRight?: ColorValue;
  fadeRadius?: Float;
}

export default codegenNativeComponent<NativeProps>('EdgeFadeView');
