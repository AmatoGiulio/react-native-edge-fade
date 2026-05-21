import type { CubicBezierCurve, EdgeFadeCurve, StopsCurve } from './types';

// 32 evenly-spaced sample positions — dense enough to eliminate visible banding.
const SAMPLE_N = 32;
const SAMPLE_X = Array.from({ length: SAMPLE_N }, (_, i) => i / (SAMPLE_N - 1));

// ── Cubic bezier ──────────────────────────────────────────────────────────────
// Chrome's polynomial form (Horner's method) + Newton iteration.
// Control points: P0=(0,0), P1=(x1,y1), P2=(x2,y2), P3=(1,1).

function cubicBezierEval(
  x1: number,
  y1: number,
  x2: number,
  y2: number,
  x: number
): number {
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

function sampleCubicBezier(curve: CubicBezierCurve): number[] {
  const { x1, y1, x2, y2 } = curve;
  // alpha = 1 - progress: fully opaque at inner edge, transparent at outer edge.
  return SAMPLE_X.map((x) =>
    parseFloat((1 - cubicBezierEval(x1, y1, x2, y2, x)).toFixed(4))
  );
}

// ── Stops curve ───────────────────────────────────────────────────────────────

function normalizeStops(curve: StopsCurve): number[] {
  return curve.values.map((v) => Math.max(0, Math.min(1, v)));
}

// ── Public API ────────────────────────────────────────────────────────────────

/**
 * Converts any EdgeFadeCurve to a string the native layer accepts.
 * - Preset names are returned as-is.
 * - cubicBezier and stops are sampled and serialized as "a0,a1,...,aN".
 */
export function serializeCurve(curve: EdgeFadeCurve): string {
  if (typeof curve === 'string') return curve;
  const alphas =
    curve.type === 'cubicBezier'
      ? sampleCubicBezier(curve)
      : normalizeStops(curve);
  return alphas.join(',');
}
