import { resolveNativeProps } from '../normalize';

describe('blurProgression public naming', () => {
  test('defaults to the full fade band', () => {
    expect(
      resolveNativeProps({ bottom: true, mode: 'blur' }).frostProgression
    ).toBe(1);
  });

  test('canonical blurProgression maps to the native compatibility field', () => {
    expect(
      resolveNativeProps({
        bottom: true,
        mode: 'blur',
        blurProgression: 0.4,
      }).frostProgression
    ).toBe(0.4);
  });

  test('legacy frostProgression remains a compatibility alias', () => {
    expect(
      resolveNativeProps({
        bottom: true,
        mode: 'blur',
        frostProgression: 0.35,
      }).frostProgression
    ).toBe(0.35);
  });

  test('canonical blurProgression wins when both names are supplied', () => {
    expect(
      resolveNativeProps({
        bottom: true,
        mode: 'blur',
        blurProgression: 0.65,
        frostProgression: 0.2,
      }).frostProgression
    ).toBe(0.65);
  });
});
