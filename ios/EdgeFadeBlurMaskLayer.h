#import <QuartzCore/QuartzCore.h>

NS_ASSUME_NONNULL_BEGIN

/// CALayer that draws a combined blur-presence mask for all four edges into a
/// single grayscale bitmap.
///
/// Uses `kCGBlendModeLighten` so overlapping corner regions take the MAX
/// presence value rather than multiplying (which would over-darken corners and
/// hide blur exactly where both edges contribute). Result is used as the mask
/// of a `UIVisualEffectView` so alpha = blur presence: 0 → sharp, 1 → full blur.
///
/// UNIFORM plateau windowing (parity with the Android pipeline): along the
/// band (inner t=0 → outer t=1) the mask stays 0 until `levelStart`, ramps
/// 0 → 1 over the next `rampWidth` fraction — the ramp SHAPED by the fade
/// curve's presence profile (`EdgeFadePresenceAt`) — then holds 1 to the outer
/// edge. Three layers with starts {0, 0.35, 0.65} back a set of
/// increasing-radius blur views: each heavier level fades in further out, so
/// the perceived radius grows toward the edge while the sharp↔blur cross-fade
/// stays on the light first level only. Level 0's `rampWidth` is the
/// `frostProgression` prop. See EdgeFadeView.mm.
@interface EdgeFadeBlurMaskLayer : CALayer

@property CGFloat fadeTop, fadeBottom, fadeLeft, fadeRight;
@property (nonatomic, copy, nullable) NSString *curveTop, *curveBottom, *curveLeft, *curveRight;

/// Plateau window for this level:
/// `weight(t) = shape(clamp((t − levelStart)/rampWidth, 0, 1))`.
/// Defaults: levelStart = 0, rampWidth = 1 (ramp across the full band).
@property CGFloat levelStart, rampWidth;

/// Ramp shaping. YES (default) shapes the fade-in by the fade curve's presence
/// profile — used by level 0, the visible sharp→frost transition, so editing
/// the curve reshapes it. NO uses a zero-slope smoothstep — used by the heavier
/// levels, whose fade-ins are internal cross-fades between two blur radii: a
/// non-zero-slope entry draws a visible onset line ("band") on scrolling
/// content, and their ramps extend to the outer edge so there are no interior
/// constant-blend plateaus.
@property BOOL curveShaped;

@end

NS_ASSUME_NONNULL_END
