// ── App palette ───────────────────────────────────────────────────────────────

export const palette = {
  dark: {
    bg: '#080808',
    surface: '#111111',
    surfaceAlt: '#1a1a1a',
    border: '#1e1e1e',
    text: '#f2f2f2',
    sub: '#666666',
  },
  light: {
    bg: '#f8f8f8',
    surface: '#ffffff',
    surfaceAlt: '#f0f0f0',
    border: '#e5e5e5',
    text: '#0d0d0d',
    sub: '#999999',
  },
} as const;

export type AppTheme = (typeof palette)['dark'];
export type ColorScheme = keyof typeof palette;

// ── Category accents ──────────────────────────────────────────────────────────

export const CATEGORY_ACCENT = {
  Architecture: '#60a5fa',
  Portrait: '#fb923c',
  Animals: '#34d399',
  Abstract: '#a78bfa',
  Nature: '#22c55e',
  Landscape: '#f59e0b',
  Sports: '#94a3b8',
  Underwater: '#06b6d4',
} as const;

export type Category = keyof typeof CATEGORY_ACCENT;
