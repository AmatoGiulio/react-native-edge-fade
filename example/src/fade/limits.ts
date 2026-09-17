import { PixelRatio, Platform } from 'react-native';

// Android progressive renderers accept up to 150 physical pixels.
export const MAX_DEMO_BLUR =
  Platform.OS === 'android' ? Math.floor(150 / PixelRatio.get()) : 100;
export const DEFAULT_DEMO_BLUR = Math.min(40, MAX_DEMO_BLUR);
