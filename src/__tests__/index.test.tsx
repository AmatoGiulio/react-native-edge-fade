import { describe, expect, it } from '@jest/globals';

import { serializeCurve } from '../curves';
import { resolveNativeProps } from '../normalize';

describe('serializeCurve', () => {
  it('keeps preset curves as native preset names', () => {
    expect(serializeCurve('smooth')).toBe('smooth');
    expect(serializeCurve('linear')).toBe('linear');
  });

  it('serializes explicit alpha stops in inner-to-outer order', () => {
    expect(serializeCurve({ type: 'stops', values: [1, 0.75, 0.25, 0] })).toBe(
      '1,0.75,0.25,0'
    );
  });

  it('samples cubic-bezier curves to a native alpha stop string', () => {
    const serialized = serializeCurve({
      type: 'cubicBezier',
      x1: 0.16,
      y1: 1,
      x2: 0.3,
      y2: 1,
    });

    const stops = serialized.split(',').map(Number);
    expect(stops).toHaveLength(32);
    expect(stops[0]).toBe(1);
    expect(stops[31]).toBe(0);
  });
});

describe('resolveNativeProps', () => {
  it('defaults to mask mode when no overlay color is provided', () => {
    expect(resolveNativeProps({ bottom: true }).mode).toBe('mask');
  });

  it('infers overlay mode when a global color is provided', () => {
    const props = resolveNativeProps({ left: 48, color: '#000' });

    expect(props.mode).toBe('overlay');
    expect(props.fadeLeft).toBe(48);
    expect(props.overlayColor).toBe('#000');
  });

  it('resolves per-edge size, curve, and color overrides', () => {
    const props = resolveNativeProps({
      size: 80,
      curve: 'smooth',
      mode: 'overlay',
      right: { size: 120, curve: 'gentle', color: '#111' },
    });

    expect(props.fadeRight).toBe(120);
    expect(props.curveRight).toBe('gentle');
    expect(props.overlayColorRight).toBe('#111');
  });
});
