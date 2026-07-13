/**
 * App color palettes, keyed by the system color scheme.
 *
 * The reusable dial components (DialRow/DialReadout/BezierPlot) carry their own
 * 'light'/'dark' `tint` palettes; this covers everything else — screen chrome,
 * the FadePanel surface, and the panel controls — so the whole app flips with
 * the OS theme.
 */

export type Scheme = 'light' | 'dark';

export interface AppPalette {
  /** Screen background. */
  bg: string;
  /** Image placeholder while it loads. */
  imgPlaceholder: string;
  /** Primary text. */
  text: string;
  /** Secondary / muted text. */
  subtext: string;
  /** Native header tint (back chevron + header buttons). */
  headerTint: string;

  // ── FadePanel chrome ──
  /** Panel card / sheet surface. */
  card: string;
  /** Segmented control / preset pill / toggle track background. */
  control: string;
  /** Active segment background. */
  controlActive: string;
  /** Inactive segment / section label text. */
  faintText: string;
  /** Accent for interactive native controls (e.g. Switch "on" track). */
  accent: string;
}

const LIGHT: AppPalette = {
  bg: '#FFFFFF',
  imgPlaceholder: '#ECECEE',
  text: '#0a0a0a',
  subtext: 'rgba(0,0,0,0.45)',
  headerTint: '#0a0a0a',

  card: '#FFFFFF',
  control: '#F2F2F4',
  controlActive: '#E3E3E6',
  faintText: '#8a8a8e',
  accent: '#007AFF',
};

const DARK: AppPalette = {
  bg: '#000000',
  imgPlaceholder: '#141414',
  text: '#FFFFFF',
  subtext: 'rgba(255,255,255,0.5)',
  headerTint: '#FFFFFF',

  card: '#1C1C1E',
  control: '#2C2C2E',
  controlActive: '#3A3A3C',
  faintText: '#8E8E93',
  accent: '#0A84FF',
};

export const PALETTES: Record<Scheme, AppPalette> = {
  light: LIGHT,
  dark: DARK,
};
