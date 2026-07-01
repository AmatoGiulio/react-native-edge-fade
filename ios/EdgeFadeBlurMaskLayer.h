#import <QuartzCore/QuartzCore.h>

NS_ASSUME_NONNULL_BEGIN

/// CALayer that draws a combined blur-presence mask for all four edges into a
/// single grayscale bitmap.
///
/// Uses `kCGBlendModeLighten` so overlapping corner regions take the MAX
/// presence value rather than multiplying (which would over-darken corners and
/// hide blur exactly where both edges contribute). Result is used as the mask
/// of a `UIVisualEffectView` so alpha = blur presence: 0 → sharp, 1 → full blur.
@interface EdgeFadeBlurMaskLayer : CALayer

@property CGFloat fadeTop, fadeBottom, fadeLeft, fadeRight;
@property (nonatomic, copy, nullable) NSString *curveTop, *curveBottom, *curveLeft, *curveRight;

@end

NS_ASSUME_NONNULL_END
