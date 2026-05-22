// Power-user escape hatch — the raw Fabric native component.
//
// Most consumers should use `EdgeFadeView` (static) or `AnimatedEdgeFadeView`
// (Reanimated-driven). Import from here only when you need to wire the flat
// native props (`fadeTop`, `fadeBottom`, `fadeLeft`, `fadeRight`, `fadeRadius`,
// `curveTop`, …, `mode`, `overlayColor*`) yourself.

export { default as NativeEdgeFadeView } from './EdgeFadeViewNativeComponent';
