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
  /** Android showcase-only native runtime tuner; not part of public JS props. */
  progressiveNativeTuner?: boolean;
  /**
   * Internal showcase material grading. 0 keeps public progressive blur pure.
   * Deliberately not exposed by the public JS prop types.
   */
  progressiveMaterialStrength?: CodegenTypes.Float;
  /** Internal showcase light-theme luminance anchor; not an opacity tint. */
  progressiveMaterialColor?: ColorValue;
  /** Internal showcase dark-theme luminance anchor. */
  progressiveMaterialColorDark?: ColorValue;
  /** Internal 0(light)..1(dark) material colour animation progress. */
  progressiveMaterialThemeProgress?: CodegenTypes.Float;
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
  /**
   * Internal "focus" theme transition: 0..1 amount of backdrop replaced by
   * the veil colour. 0 is a no-op.
   */
  progressiveMaterialVeil?: CodegenTypes.Float;
  /** Internal "focus" theme transition: 0..1 chroma removal. 0 is a no-op. */
  progressiveMaterialNeutrality?: CodegenTypes.Float;
  /**
   * Internal "focus" theme transition: 0..1 luminance range compression
   * toward the veil luminance. 0 is a no-op.
   */
  progressiveMaterialLumaFlatten?: CodegenTypes.Float;
  /** Internal low-frequency RGB field before the material tone response. */
  progressiveMaterialColorFieldEnabled?: boolean;
  /** 0 = normal material only, 1 = full low-frequency colour-field influence. */
  progressiveMaterialColorFieldMix?: CodegenTypes.Float;
  /** Internal render scale for the dedicated colour-field capture (0.05..0.25). */
  progressiveMaterialColorFieldScale?: CodegenTypes.Float;
  /** Screen-space blur radius, in px, applied after the low-res capture. */
  progressiveMaterialColorFieldBlurRadiusPx?: CodegenTypes.Float;
  /** Neutral pixels below this chroma amount are suppressed from the field. */
  progressiveMaterialColorFieldChromaGate?: CodegenTypes.Float;
  /** Multiplier applied to extracted source chroma before diffusion. */
  progressiveMaterialColorFieldChromaGain?: CodegenTypes.Float;
  /** Fraction of source luminance retained in the colour field. */
  progressiveMaterialColorFieldLumaMix?: CodegenTypes.Float;
  /**
   * Neutral-tone weight floor for the colour field (0..1). Lets low-chroma
   * page/card backgrounds diffuse too, so blurred cards fuse with the page
   * instead of only saturated image colours mixing. 0 = previous
   * chroma-only behaviour.
   */
  progressiveMaterialColorFieldNeutralWeight?: CodegenTypes.Float;
  /** Curve offset applied to the material response band (-0.35..0.35). */
  progressiveMaterialCurveOffset?: CodegenTypes.Float;
  /** Curve height applied to the material response band (0.25..1.5). */
  progressiveMaterialCurveHeight?: CodegenTypes.Float;
  /**
   * Internal living bottom-front warp: noise displacement (px, 0..400).
   * 0 reproduces the flat pre-wave front exactly.
   */
  progressiveWaveAmplitude?: CodegenTypes.Float;
  /** Internal living bottom-front parabolic dome height (px, -600..600). */
  progressiveWaveDome?: CodegenTypes.Float;
  /** Internal living bottom-front noise phase (seconds), animated per frame. */
  progressiveWaveTime?: CodegenTypes.Float;
  /** Internal light "bloom" band intensity at the front (0..1.5). */
  progressiveFrontGlow?: CodegenTypes.Float;
  /**
   * Internal debug-stage cross-fade. 0 = current rendering (byte-identical),
   * 1 = visually identical to the "agsl-debug-field" debug stage (sharp scene
   * and material strip clipped out, only the colour-field overlay remains).
   * Intermediate values cross-fade. Per-frame animatable.
   */
  progressiveFieldBlend?: CodegenTypes.Float;
  // Light wave V0 (showcase): exposure wave centre in panel depth (0 bottom
  // -> 1 top), gain in stops (0 = off) and gaussian sigma in depth units.
  progressiveLightWaveCenter?: CodegenTypes.Float;
  progressiveLightWaveStops?: CodegenTypes.Float;
  progressiveLightWaveWidth?: CodegenTypes.Float;
  // Marea V0 (showcase): signed normalised surface amplitude, press->dome
  // shape blend, x centre (0..1), dome reach (space above the panel, 1 = top
  // edge, 0 = off), dome FWHM (fraction of view width), conserved volume (0..1) and
  // meniscus drag in px at full dome (0 = off).
  progressiveTideAmount?: CodegenTypes.Float;
  progressiveTideShape?: CodegenTypes.Float;
  progressiveTideCenter?: CodegenTypes.Float;
  progressiveTideHeight?: CodegenTypes.Float;
  progressiveTideWidth?: CodegenTypes.Float;
  progressiveTideVolume?: CodegenTypes.Float;
  progressiveTideMeniscus?: CodegenTypes.Float;
  /** Marea core profile exponent: 2 = gaussian dome, lower = flame tip. */
  progressiveTideSharpness?: CodegenTypes.Float;
  /** Marea flame flicker amplitude (fraction of the space above the panel). */
  progressiveTideFlicker?: CodegenTypes.Float;
  /** Marea flicker clock in seconds (animated). */
  progressiveTideTime?: CodegenTypes.Float;
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
