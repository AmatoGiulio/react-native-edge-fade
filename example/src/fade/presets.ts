/** Bezier curve presets offered as one-tap starting points in the panel. */

export interface Bezier {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

export const BEZIER_PRESETS: ReadonlyArray<{ label: string; value: Bezier }> = [
  // Matches the Blur Lab `smooth` curve: progress = 1 - (1 - x)^3.
  // x(t)=t with these x control points, so the cubic is exact rather than an
  // eyeballed approximation of the Lab profile.
  {
    label: 'default',
    value: { x1: 1 / 3, y1: 1, x2: 2 / 3, y2: 1 },
  },
  { label: 'linear', value: { x1: 0, y1: 0, x2: 1, y2: 1 } },
  { label: 'ease', value: { x1: 0.25, y1: 0.1, x2: 0.25, y2: 1 } },
  { label: 'soft', value: { x1: 0.4, y1: 0, x2: 0.6, y2: 1 } },
  { label: 'sharp', value: { x1: 0.7, y1: 0, x2: 0.84, y2: 0 } },
  { label: 'gentle', value: { x1: 0.25, y1: 0.46, x2: 0.45, y2: 0.94 } },
];
