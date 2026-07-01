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
/// Progressive-blur windowing: `levelLo`/`levelHi` restrict this mask to a slice
/// of the presence range. With the defaults (0 and 1) the layer reproduces the
/// full-band gradient exactly (backward compatible — a single uniform blur). For
/// a multi-level Apple-Music-style stack, three layers with windows {0,1/3},
/// {1/3,2/3}, {2/3,1} back a set of increasing-radius blur views, each showing
/// only where its slice of the presence curve is active. See EdgeFadeView.mm.
@interface EdgeFadeBlurMaskLayer : CALayer

@property CGFloat fadeTop, fadeBottom, fadeLeft, fadeRight;
@property (nonatomic, copy, nullable) NSString *curveTop, *curveBottom, *curveLeft, *curveRight;

/// Presence window for this level. `weight(t) = clamp((presence(t) − lo)/(hi − lo), 0, 1)`
/// where `presence(t) = 1 − alpha(t)`. Defaults: lo = 0, hi = 1 (full band).
@property CGFloat levelLo, levelHi;

@end

NS_ASSUME_NONNULL_END
