import { PixelRatio, Platform } from 'react-native';

import { BLUR_LAB_DEFAULTS } from './presets';

/**
 * Canonical progressive-blur envelope for the Expo example.
 *
 * AndroidX spatial BlurRadiusSpec caps the resolved radius at 150 physical px,
 * and the AGSL/GLES backends intentionally use the same envelope. Keep manual
 * tuning and perf routes derived from these values so the example remains the
 * single test surface instead of drifting from the native reference harness.
 */
export const ANDROID_PROGRESSIVE_BLUR_MAX_RADIUS_PX = 150;
export const ANDROID_PROGRESSIVE_BLUR_DENSITY = PixelRatio.get();

// Public React Native props are dp; Android native code converts them to px.
export const MAX_DEMO_BLUR =
  Platform.OS === 'android'
    ? Math.floor(
        ANDROID_PROGRESSIVE_BLUR_MAX_RADIUS_PX /
          ANDROID_PROGRESSIVE_BLUR_DENSITY
      )
    : 100;

export const DEFAULT_DEMO_BLUR = Math.min(
  BLUR_LAB_DEFAULTS.blurRadius,
  MAX_DEMO_BLUR
);
