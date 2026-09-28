import {
  codegenNativeComponent,
  type ColorValue,
  type CodegenTypes,
  type ViewProps,
} from 'react-native';

// Flat native props produced by the JS normalization layer.
// Sizes are in dp (0 = edge disabled). Curves are preset names or
// comma-separated alpha stop strings (from cubicBezier / stops serialization).
// mode: "mask" | "overlay" | "blur"
interface NativeProps extends ViewProps {
  fadeTop?: CodegenTypes.Float;
  fadeBottom?: CodegenTypes.Float;
  fadeLeft?: CodegenTypes.Float;
  fadeRight?: CodegenTypes.Float;
  curveTop?: string;
  curveBottom?: string;
  curveLeft?: string;
  curveRight?: string;
  /** "mask" | "overlay" | "blur" */
  mode?: string;
  /** Max blur radius (dp) at the outer edge, blur mode only. */
  blurRadius?: CodegenTypes.Float;
  /**
   * Example-only comparison switch: "auto" | "androidx" (official AndroidX
   * BlurRadiusSpec, only when the app is built with edgeFadeAndroidxBlur).
   * Deliberately not exposed by the public JS prop types.
   */
  progressiveBackend?: string;
  /** iOS frost saturation (blur mode). Ignored on Android. */
  frostSaturation?: CodegenTypes.Float;
  /** iOS frost brightness (blur mode). Ignored on Android. */
  frostLift?: CodegenTypes.Float;
  /** Fraction of the band over which the blur radius ramps (blur mode). */
  frostProgression?: CodegenTypes.Float;
  overlayColor?: ColorValue;
  overlayColorTop?: ColorValue;
  overlayColorBottom?: ColorValue;
  overlayColorLeft?: ColorValue;
  overlayColorRight?: ColorValue;
  fadeRadius?: CodegenTypes.Float;
}

export default codegenNativeComponent<NativeProps>('EdgeFadeView');
