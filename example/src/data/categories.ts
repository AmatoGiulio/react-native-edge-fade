/** Photo categories and their accent colors, shared by the catalog. */

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
