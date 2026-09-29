import { PixelRatio, Platform } from 'react-native';

import { BLUR_LAB_DEFAULTS } from './presets';

/**
 * Largest radius the Android renderers accept, in physical px: 300 on API 33+
 * (half-resolution strips), 150 on the API 31-32 GLES backend.
 */
export const ANDROID_PROGRESSIVE_BLUR_MAX_RADIUS_PX =
  Platform.OS === 'android' && Number(Platform.Version) < 33 ? 150 : 300;
export const ANDROID_PROGRESSIVE_BLUR_DENSITY = PixelRatio.get();

// Public React Native props are dp; Android native code converts them to px.
export const MAX_DEMO_BLUR =
  Platform.OS === 'android'
    ? Math.floor(
        ANDROID_PROGRESSIVE_BLUR_MAX_RADIUS_PX /
          ANDROID_PROGRESSIVE_BLUR_DENSITY
      )
    : 100;

// Android demo default: 150 physical px.
export const DEFAULT_DEMO_BLUR =
  Platform.OS === 'android'
    ? 150 / ANDROID_PROGRESSIVE_BLUR_DENSITY
    : Math.min(BLUR_LAB_DEFAULTS.blurRadius, MAX_DEMO_BLUR);
