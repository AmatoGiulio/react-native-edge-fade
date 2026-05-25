import { useColorScheme } from 'react-native';
import { palette, type AppTheme } from '../constants/theme';

/**
 * Returns the current palette (dark/light) based on the system color scheme.
 * Falls back to dark when the system preference is unavailable.
 */
export function useAppTheme(): AppTheme {
  const scheme = useColorScheme();
  return palette[scheme === 'light' ? 'light' : 'dark'];
}
