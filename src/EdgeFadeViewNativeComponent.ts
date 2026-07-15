import {
  codegenNativeComponent,
  type ColorValue,
  type ViewProps,
} from 'react-native';
import type { Float } from 'react-native/Libraries/Types/CodegenTypesNamespace';

// Flat native props produced by the JS normalization layer.
// Sizes are in dp (0 = edge disabled). Curves are preset names or
// comma-separated alpha stop strings (from cubicBezier / stops serialization).
// mode: "mask" | "overlay" | "blur"
interface NativeProps extends ViewProps {
  fadeTop?: Float;
  fadeBottom?: Float;
  fadeLeft?: Float;
  fadeRight?: Float;
  curveTop?: string;
  curveBottom?: string;
  curveLeft?: string;
  curveRight?: string;
  /** "mask" | "overlay" | "blur" */
  mode?: string;
  /** Max blur radius (dp) at the outer edge, blur mode only. */
  blurRadius?: Float;
  /** Frost vibrancy saturation multiplier (blur mode). 1 = neutral. */
  frostSaturation?: Float;
  /** Frost vibrancy brightness multiplier (blur mode). 1 = neutral. */
  frostLift?: Float;
  /** Fraction of the band over which the blur radius ramps (blur mode). */
  frostProgression?: Float;
  /** Lens mode (liquid glass): refraction strength at the rim. */
  lensRefraction?: Float;
  /** Lens mode: chromatic dispersion amount. */
  lensDispersion?: Float;
  /** Lens mode: saturation multiplier of the refracted content. */
  lensSaturation?: Float;
  /** Lens mode: contrast multiplier of the refracted content. */
  lensContrast?: Float;
  /** Lens mode: specular / reflection highlight strength. */
  lensSpecular?: Float;
  /** Lens mode: specular light direction, in degrees. */
  lensAngle?: Float;
  overlayColor?: ColorValue;
  overlayColorTop?: ColorValue;
  overlayColorBottom?: ColorValue;
  overlayColorLeft?: ColorValue;
  overlayColorRight?: ColorValue;
  fadeRadius?: Float;
}

export default codegenNativeComponent<NativeProps>('EdgeFadeView');
