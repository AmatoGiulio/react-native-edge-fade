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
   * Internal demo/test override: "auto" | "agsl" | "androidx" | "scaled".
   * Deliberately not exposed by the public JS prop types.
   */
  progressiveBackend?: string;
  /**
   * Internal showcase material grading. 0 keeps public progressive blur pure.
   * Deliberately not exposed by the public JS prop types.
   */
  progressiveMaterialStrength?: CodegenTypes.Float;
  /** Internal showcase material tint paired with progressiveMaterialStrength. */
  progressiveMaterialColor?: ColorValue;
  /**
   * Internal showcase exposure multiplier for the fully materialized region.
   * 1 keeps luminance unchanged. Public progressive blur never sets this.
   */
  progressiveMaterialExposure?: CodegenTypes.Float;
  /**
   * Internal showcase surface density. Blends the blurred scene toward the
   * material color over a broader field than the grading pass.
   */
  progressiveMaterialSurface?: CodegenTypes.Float;
  /**
   * Internal showcase material geometry. Smaller values make the translucent
   * sheet reach full density earlier across the edge band.
   */
  progressiveMaterialSurfaceProgression?: CodegenTypes.Float;
  /** Frost vibrancy saturation multiplier (blur mode). 1 = neutral. */
  frostSaturation?: CodegenTypes.Float;
  /** Frost vibrancy brightness multiplier (blur mode). 1 = neutral. */
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
