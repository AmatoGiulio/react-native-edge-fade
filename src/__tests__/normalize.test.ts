import { resolveNativeProps } from '../normalize';

// ── Defaults ───────────────────────────────────────────────────────────────────

describe('resolveNativeProps — defaults', () => {
  test('all edges disabled when no edge props passed', () => {
    const n = resolveNativeProps({});
    expect(n.fadeTop).toBe(0);
    expect(n.fadeBottom).toBe(0);
    expect(n.fadeLeft).toBe(0);
    expect(n.fadeRight).toBe(0);
  });

  test('default mode is mask when no color', () => {
    expect(resolveNativeProps({ bottom: true }).mode).toBe('mask');
  });

  test('default curve is smooth', () => {
    const n = resolveNativeProps({ bottom: true });
    expect(n.curveBottom).toBe('smooth');
  });

  test('default size is 80', () => {
    const n = resolveNativeProps({ bottom: true });
    expect(n.fadeBottom).toBe(80);
  });
});

// ── Edge prop shapes ───────────────────────────────────────────────────────────

describe('resolveNativeProps — edge prop shapes', () => {
  test('boolean true uses default size', () => {
    expect(resolveNativeProps({ top: true }).fadeTop).toBe(80);
  });

  test('boolean false disables edge', () => {
    expect(resolveNativeProps({ top: false }).fadeTop).toBe(0);
  });

  test('number sets exact size', () => {
    expect(resolveNativeProps({ right: 120 }).fadeRight).toBe(120);
  });

  test('EdgeConfig with size override', () => {
    expect(resolveNativeProps({ left: { size: 60 } }).fadeLeft).toBe(60);
  });

  test('EdgeConfig without size falls back to global size', () => {
    expect(
      resolveNativeProps({ size: 40, left: { curve: 'sharp' } }).fadeLeft
    ).toBe(40);
  });

  test('EdgeConfig curve overrides global curve', () => {
    const n = resolveNativeProps({ curve: 'gentle', top: { curve: 'sharp' } });
    expect(n.curveTop).toBe('sharp');
  });

  test('global curve applies to all edges without override', () => {
    const n = resolveNativeProps({ curve: 'soft', top: true, bottom: true });
    expect(n.curveTop).toBe('soft');
    expect(n.curveBottom).toBe('soft');
  });

  test('global size applies to all edges without override', () => {
    const n = resolveNativeProps({ size: 50, top: true, left: true });
    expect(n.fadeTop).toBe(50);
    expect(n.fadeLeft).toBe(50);
  });
});

// ── Mode inference ─────────────────────────────────────────────────────────────

describe('resolveNativeProps — mode inference', () => {
  test('mode defaults to mask with no color', () => {
    expect(resolveNativeProps({ bottom: true }).mode).toBe('mask');
  });

  test('mode infers overlay when global color is set', () => {
    expect(resolveNativeProps({ bottom: true, color: '#000' }).mode).toBe(
      'overlay'
    );
  });

  test('mode infers overlay when per-edge color is set', () => {
    expect(resolveNativeProps({ bottom: { color: '#000' } }).mode).toBe(
      'overlay'
    );
  });

  test('explicit mode="mask" overrides color inference', () => {
    expect(
      resolveNativeProps({ bottom: true, color: '#000', mode: 'mask' }).mode
    ).toBe('mask');
  });

  test('explicit mode="overlay" overrides no-color default', () => {
    expect(resolveNativeProps({ bottom: true, mode: 'overlay' }).mode).toBe(
      'overlay'
    );
  });
});

// ── Colors ─────────────────────────────────────────────────────────────────────

describe('resolveNativeProps — colors', () => {
  test('global color is forwarded', () => {
    const n = resolveNativeProps({ bottom: true, color: '#ff0000' });
    expect(n.overlayColor).toBe('#ff0000');
  });

  test('per-edge color is forwarded to correct field', () => {
    const n = resolveNativeProps({
      top: { color: '#aaa' },
      bottom: { color: '#bbb' },
    });
    expect(n.overlayColorTop).toBe('#aaa');
    expect(n.overlayColorBottom).toBe('#bbb');
    expect(n.overlayColorLeft).toBeUndefined();
    expect(n.overlayColorRight).toBeUndefined();
  });

  test('edges without color have undefined color fields', () => {
    const n = resolveNativeProps({ bottom: true });
    expect(n.overlayColor).toBeUndefined();
    expect(n.overlayColorBottom).toBeUndefined();
  });
});

// ── Curve serialization ────────────────────────────────────────────────────────

describe('resolveNativeProps — curve serialization', () => {
  test('preset string curves are passed through unchanged', () => {
    const n = resolveNativeProps({ bottom: true, curve: 'sharp' });
    expect(n.curveBottom).toBe('sharp');
  });

  test('cubicBezier curve is serialized to comma-separated string', () => {
    const n = resolveNativeProps({
      bottom: true,
      curve: { type: 'cubicBezier', x1: 0.25, y1: 0.1, x2: 0.25, y2: 1 },
    });
    expect(n.curveBottom).toContain(',');
    expect(n.curveBottom.split(',').length).toBe(32);
  });

  test('stops curve is serialized to comma-separated string', () => {
    const n = resolveNativeProps({
      bottom: true,
      curve: { type: 'stops', values: [1, 0.5, 0] },
    });
    expect(n.curveBottom).toBe('1,0.5,0');
  });

  test('independent edges can have different serialized curves', () => {
    const n = resolveNativeProps({
      top: { curve: 'sharp' },
      bottom: { curve: 'gentle' },
    });
    expect(n.curveTop).toBe('sharp');
    expect(n.curveBottom).toBe('gentle');
  });
});

// ── Edge independence ──────────────────────────────────────────────────────────

describe('resolveNativeProps — edge independence', () => {
  test('inactive edges have fadeSize=0', () => {
    const n = resolveNativeProps({ top: true });
    expect(n.fadeBottom).toBe(0);
    expect(n.fadeLeft).toBe(0);
    expect(n.fadeRight).toBe(0);
  });

  test('all four edges active simultaneously', () => {
    const n = resolveNativeProps({ top: 10, bottom: 20, left: 30, right: 40 });
    expect(n.fadeTop).toBe(10);
    expect(n.fadeBottom).toBe(20);
    expect(n.fadeLeft).toBe(30);
    expect(n.fadeRight).toBe(40);
  });
});
