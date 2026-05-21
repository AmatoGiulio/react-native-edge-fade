import { serializeCurve } from '../curves';

// ── Preset names ───────────────────────────────────────────────────────────────

describe('serializeCurve — presets', () => {
  const PRESETS = ['smooth', 'sharp', 'gentle', 'soft', 'linear'] as const;

  test.each(PRESETS)('returns "%s" unchanged', (name) => {
    expect(serializeCurve(name)).toBe(name);
  });
});

// ── cubicBezier ────────────────────────────────────────────────────────────────

describe('serializeCurve — cubicBezier', () => {
  const curve = {
    type: 'cubicBezier' as const,
    x1: 0.42,
    y1: 0,
    x2: 0.58,
    y2: 1,
  };

  test('returns a comma-separated string', () => {
    const result = serializeCurve(curve);
    expect(typeof result).toBe('string');
    expect(result).toContain(',');
  });

  test('produces 32 values', () => {
    const values = serializeCurve(curve).split(',');
    expect(values).toHaveLength(32);
  });

  test('all values are in [0, 1]', () => {
    const values = serializeCurve(curve).split(',').map(Number);
    for (const v of values) {
      expect(v).toBeGreaterThanOrEqual(0);
      expect(v).toBeLessThanOrEqual(1);
    }
  });

  test('first value ≈ 1 (fully opaque at inner edge)', () => {
    const first = Number(serializeCurve(curve).split(',')[0]);
    expect(first).toBeCloseTo(1, 3);
  });

  test('last value ≈ 0 (transparent at outer edge)', () => {
    const values = serializeCurve(curve).split(',');
    const last = Number(values[values.length - 1]);
    expect(last).toBeCloseTo(0, 3);
  });

  test('ease-in-out is symmetric: value at 50% ≈ 0.5', () => {
    // cubic-bezier(0.42, 0, 0.58, 1) is symmetric → midpoint ≈ 0.5
    const values = serializeCurve(curve).split(',').map(Number);
    const mid = values[Math.floor(values.length / 2)]!;
    expect(mid).toBeCloseTo(0.5, 1);
  });

  test('linear bezier (0,0,1,1) matches the linear preset values', () => {
    const linearBezier = serializeCurve({
      type: 'cubicBezier',
      x1: 0,
      y1: 0,
      x2: 1,
      y2: 1,
    });
    const values = linearBezier.split(',').map(Number);
    // Should go from ~1 to ~0 linearly
    expect(values[0]).toBeCloseTo(1, 2);
    expect(values[values.length - 1]).toBeCloseTo(0, 2);
    // Midpoint should be close to 0.5
    expect(values[Math.floor(values.length / 2)]!).toBeCloseTo(0.5, 1);
  });
});

// ── stops ──────────────────────────────────────────────────────────────────────

describe('serializeCurve — stops', () => {
  test('serializes explicit values', () => {
    const result = serializeCurve({ type: 'stops', values: [1, 0.5, 0] });
    expect(result).toBe('1,0.5,0');
  });

  test('clamps values outside [0, 1]', () => {
    const result = serializeCurve({ type: 'stops', values: [1.5, 0.5, -0.2] });
    const values = result.split(',').map(Number);
    expect(values[0]).toBe(1);
    expect(values[2]).toBe(0);
  });

  test('preserves order (inner → outer)', () => {
    const values = [1, 0.8, 0.6, 0.4, 0.2, 0];
    const result = serializeCurve({
      type: 'stops',
      values: values as [number, number, ...number[]],
    });
    expect(result).toBe(values.join(','));
  });

  test('two-value minimum works', () => {
    const result = serializeCurve({ type: 'stops', values: [1, 0] });
    expect(result).toBe('1,0');
  });
});
