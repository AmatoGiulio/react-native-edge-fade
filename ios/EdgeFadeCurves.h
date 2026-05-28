#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Curve math for EdgeFadeView.
///
/// A curve is either a preset name (`smooth`, `sharp`, `gentle`, `soft`, `linear`)
/// or a comma-separated alpha string produced by the JS `serializeCurve()` helper
/// (inner → outer, values in [0,1]).

/// `YES` when the string is a comma-separated custom alpha list rather than a preset name.
BOOL EdgeFadeCurveIsCustom(NSString *curve);

/// Resolve a curve string to its alpha/stop arrays.
///
/// On return:
///   - `alphas`, `stops`, `count` describe the alpha samples and their positions.
///   - `dynAlphas` / `dynStops` are non-`NULL` only for parsed custom curves and
///     **must be `free()`'d by the caller**. Preset paths leave them `NULL`.
///   - Unparseable custom strings fall back silently to the `smooth` preset.
void EdgeFadeResolveCurve(NSString *curve,
                          const CGFloat *_Nonnull *_Nonnull alphas,
                          const CGFloat *_Nonnull *_Nonnull stops,
                          size_t *count,
                          CGFloat *_Nullable *_Nonnull dynAlphas,
                          CGFloat *_Nullable *_Nonnull dynStops);

/// Cached `NSArray<NSNumber *>` of normalized stop positions for the given curve,
/// suitable for `CAGradientLayer.locations`. Custom curves allocate a fresh array.
NSArray<NSNumber *> *EdgeFadeLocationsForCurve(NSString *curve);

NS_ASSUME_NONNULL_END
