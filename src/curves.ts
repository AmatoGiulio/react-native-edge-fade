import type { CubicBezierCurve, EdgeFadeCurve, StopsCurve } from './types';

const SAMPLE_COUNT = 32;
const SAMPLE_X = Array.from(
  { length: SAMPLE_COUNT },
  (_, index) => index / (SAMPLE_COUNT - 1)
);

function cubicBezierEval(
  x1: number,
  y1: number,
  x2: number,
  y2: number,
  x: number
): number {
  if (x <= 0) return 0;
  if (x >= 1) return 1;

  const cx = 3 * x1;
  const bx = 3 * (x2 - x1) - cx;
  const ax = 1 - cx - bx;
  const cy = 3 * y1;
  const by = 3 * (y2 - y1) - cy;
  const ay = 1 - cy - by;

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

  return SAMPLE_X.map((x) =>
    parseFloat((1 - cubicBezierEval(x1, y1, x2, y2, x)).toFixed(4))
  );
}

function normalizeStops(curve: StopsCurve): number[] {
  return curve.values.map((value) => Math.max(0, Math.min(1, value)));
}

export function serializeCurve(curve: EdgeFadeCurve): string {
  if (typeof curve === 'string') return curve;

  const alphas =
    curve.type === 'cubicBezier'
      ? sampleCubicBezier(curve)
      : normalizeStops(curve);

  return alphas.join(',');
}
