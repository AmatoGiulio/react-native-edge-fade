/**
 * Worklet-safe cubic-bezier evaluation for the dial components.
 *
 * IMPORTANT: `bezierEval` is a faithful copy of the Horner + Newton
 * implementation in `src/curves.ts` (library root) so the plot renders exactly
 * the ramp the native layer receives. Keep the two implementations aligned if
 * the library math ever changes.
 */

/** Sample count used by the library when serializing curves (src/curves.ts). */
export const SAMPLE_N = 32;

export function bezierEval(
  x1: number,
  y1: number,
  x2: number,
  y2: number,
  x: number
): number {
  'worklet';
  if (x <= 0) return 0;
  if (x >= 1) return 1;

  const cx = 3 * x1,
    bx = 3 * (x2 - x1) - cx,
    ax = 1 - cx - bx;
  const cy = 3 * y1,
    by = 3 * (y2 - y1) - cy,
    ay = 1 - cy - by;

  let t = x;
  for (let i = 0; i < 8; i++) {
    const bxt = ((ax * t + bx) * t + cx) * t;
    const dbxt = (3 * ax * t + 2 * bx) * t + cx;
    if (Math.abs(dbxt) < 1e-6) break;
    t = Math.max(0, Math.min(1, t - (bxt - x) / dbxt));
  }

  return ((ay * t + by) * t + cy) * t;
}
