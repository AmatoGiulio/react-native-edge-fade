/** Hooks that resolve the live system theme. */

import { useColorScheme } from 'react-native';

import { PALETTES, type AppPalette, type Scheme } from './palette';

/** Current system scheme, defaulting to 'light' when unset. */
export function useScheme(): Scheme {
  return useColorScheme() === 'dark' ? 'dark' : 'light';
}

/** Current system palette. */
export function useTheme(): AppPalette {
  return PALETTES[useScheme()];
}
