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
   * Internal demo/test override: "auto" | "agsl" | "androidx" |
   * "androidx-gradient" | "scaled".
   * Deliberately not exposed by the public JS prop types.
   */
  progressiveBackend?: string;
  /** Android showcase-only native runtime tuner; not a public library prop. */
  progressiveNativeTuner?: boolean;
  /**
   * Internal showcase material grading. 0 keeps public progressive blur pure.
   * Deliberately not exposed by the public JS prop types.
   */
  progressiveMaterialStrength?: CodegenTypes.Float;
  /** Internal showcase neutral luminance anchor; not an opacity tint. */
  progressiveMaterialColor?: ColorValue;
  /**
   * Internal showcase source exposure before the material tone response.
   * 1 is neutral exposure. Public progressive blur never sets this.
   */
  progressiveMaterialExposure?: CodegenTypes.Float;
  /**
   * Internal showcase surface scattering; compresses the luminance range
   * independently from transmitted source chroma.
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
