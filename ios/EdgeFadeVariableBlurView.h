#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * A true progressive blur strip for one edge: a single backdrop pass whose
 * radius varies per pixel, read from a mask built from the fade curve — the
 * iOS counterpart of the Android AGSL renderer.
 *
 * It runs Core Animation's `variableBlur` backdrop filter (the one behind the
 * system's soft scroll-edge effect) on the backdrop layer of a plain
 * UIVisualEffectView. The filter is not public API: `isSupported` probes it
 * once, and EdgeFadeView falls back to its multi-level UIVisualEffectView stack
 * when it is missing.
 */
@interface EdgeFadeVariableBlurView : UIView

/** Whether the variableBlur backdrop filter can be created on this system. */
+ (BOOL)isSupported;

/** `edge`: 0 top, 1 bottom, 2 left, 3 right (EdgeFadeEdge order). */
- (instancetype)initWithEdge:(NSInteger)edge NS_DESIGNATED_INITIALIZER;
- (instancetype)initWithFrame:(CGRect)frame NS_UNAVAILABLE;
- (instancetype)initWithCoder:(NSCoder *)coder NS_UNAVAILABLE;

/**
 * Band geometry in points: the blur ramps over `fade` from the outer edge, and
 * `pad` extra points of zero radius on the inner side feed the kernel across
 * the seam. The mask is rebuilt only when these, the curve or the progression
 * change.
 */
- (void)setFade:(CGFloat)fade
            pad:(CGFloat)pad
          curve:(NSString *)curve
    progression:(CGFloat)progression;

/** Radius at the outer edge, in points. */
@property (nonatomic) CGFloat radius;

@end

NS_ASSUME_NONNULL_END
