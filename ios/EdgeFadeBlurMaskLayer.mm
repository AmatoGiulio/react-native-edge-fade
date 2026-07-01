#import "EdgeFadeBlurMaskLayer.h"
#import "EdgeFadeCurves.h"

#import <UIKit/UIKit.h>

// ─── Gradient construction ────────────────────────────────────────────────────

// Builds a DeviceGray blur-presence gradient. Returns a retained CGGradientRef.
// Blur presence is 1 − alpha(t) across the ENTIRE band — the blur takes the
// place of whatever the curve dissolves, the same semantics as mask mode. The
// curve therefore governs the progression of the blur along the full width of
// the strip: no positional compression, no plateau.
//
// Progressive-blur windowing: this mask only exposes the slice of the presence
// range [lo, hi]. Each stop's alpha is
//   weight = clamp((presence − lo) / (hi − lo), 0, 1),  presence = 1 − alphas[i].
// Weight stays 1 for presence > hi so higher-radius levels cross-fade over the
// top of this one (they own the region past hi). With lo=0, hi=1 this collapses
// to the plain complement { gray=1, alpha=1-f } — the original full-band mask.
//
// Preset gradients live in the static cache below and must NOT be released by
// the caller. Custom-curve gradients are freshly allocated — caller releases.
static CGGradientRef buildBlurMaskGradient(NSString *curve, CGFloat lo, CGFloat hi)
{
  const CGFloat *alphas; const CGFloat *stops; size_t count;
  CGFloat *dynAlphas, *dynStops;
  EdgeFadeResolveCurve(curve, &alphas, &stops, &count, &dynAlphas, &dynStops);

  // Guard against a degenerate window (hi <= lo): fall back to a step at lo so
  // the level is fully present past its lower boundary rather than dividing by 0.
  const CGFloat span = (hi > lo) ? (hi - lo) : 0.0;

  // Presence = complement of the curve at the direct index — no mirroring,
  // no compression. Draw direction in drawInContext (start=inner, end=outer)
  // is unchanged, so this alone flips presence to rise across the band.
  CGColorSpaceRef space = CGColorSpaceCreateDeviceGray();
  CGFloat *components   = (CGFloat *)malloc(count * 2 * sizeof(CGFloat));
  for (size_t i = 0; i < count; i++) {
    const CGFloat presence = 1.0 - alphas[i];
    CGFloat w;
    if (span > 0.0) {
      w = (presence - lo) / span;
    } else {
      w = (presence >= lo) ? 1.0 : 0.0;
    }
    if (w < 0.0) w = 0.0; else if (w > 1.0) w = 1.0;
    components[i * 2]     = 1.0;
    components[i * 2 + 1] = w;
  }

  CGGradientRef g = CGGradientCreateWithColorComponents(space, components, stops, count);
  free(components);
  CGColorSpaceRelease(space);
  if (dynAlphas) { free(dynAlphas); free(dynStops); }
  return g;
}

// Fixed level boundaries F = {0, 1/3, 2/3, 1}. Level k (0-based) spans
// [kLevelBounds[k], kLevelBounds[k+1]]. Three levels total.
static const CGFloat kLevelBounds[4] = {0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0};

// Maps a canonical (lo, hi) window to its 0-based level index, or -1 if the
// window is not one of the three canonical slices (e.g. the default 0..1 full
// band). Non-canonical windows are built ad hoc via the per-instance custom
// cache instead of the shared preset matrix.
static NSInteger levelIndexForWindow(CGFloat lo, CGFloat hi)
{
  const CGFloat eps = 1e-4;
  for (NSInteger k = 0; k < 3; k++) {
    if (fabs(lo - kLevelBounds[k]) < eps && fabs(hi - kLevelBounds[k + 1]) < eps) {
      return k;
    }
  }
  return -1;
}

// Static preset cache — 6 presets × 3 canonical levels, built once per process.
// The full-band (0..1) window is intentionally not cached here; it is a
// non-canonical window and takes the per-instance custom path (see the layer's
// gradientForCurve:), which caches it once per layer.
static CGGradientRef blurMaskGradientForPreset(NSString *curve, NSInteger level)
{
  static CGGradientRef cache[6][3];
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    NSArray<NSString *> *presets = @[ @"smooth", @"sharp", @"gentle", @"soft", @"linear", @"smoother" ];
    for (NSUInteger p = 0; p < presets.count; p++) {
      for (NSInteger k = 0; k < 3; k++) {
        cache[p][k] = buildBlurMaskGradient(presets[p], kLevelBounds[k], kLevelBounds[k + 1]);
      }
    }
  });
  if (level < 0 || level > 2) level = 0;
  if ([curve isEqualToString:@"sharp"])    return cache[1][level];
  if ([curve isEqualToString:@"gentle"])   return cache[2][level];
  if ([curve isEqualToString:@"soft"])     return cache[3][level];
  if ([curve isEqualToString:@"linear"])   return cache[4][level];
  if ([curve isEqualToString:@"smoother"]) return cache[5][level];
  return cache[0][level];
}

// ─── Layer ────────────────────────────────────────────────────────────────────

@implementation EdgeFadeBlurMaskLayer {
  // Per-edge cache for custom-curve gradients. Presets are process-wide static
  // (see blurMaskGradientForPreset); without this, a custom curve would re-parse
  // the string and rebuild its CGGradientRef on every drawInContext: call.
  NSString     *_cachedCustomTop,    *_cachedCustomBottom,
               *_cachedCustomLeft,   *_cachedCustomRight;
  CGGradientRef _customGradTop,       _customGradBottom,
                _customGradLeft,      _customGradRight;
}

- (instancetype)init {
  self = [super init];
  self.needsDisplayOnBoundsChange = YES;
  // Full-band window by default → this layer reproduces the original single
  // uniform-blur mask. EdgeFadeView overrides lo/hi for multi-level stacks.
  _levelLo = 0.0;
  _levelHi = 1.0;
  // Seed with the main screen for snapshot/offscreen environments; the owning
  // view updates this via _syncLayerScales whenever the window/trait changes.
  self.contentsScale = UIScreen.mainScreen.scale;
  return self;
}

- (void)dealloc {
  if (_customGradTop)    CGGradientRelease(_customGradTop);
  if (_customGradBottom) CGGradientRelease(_customGradBottom);
  if (_customGradLeft)   CGGradientRelease(_customGradLeft);
  if (_customGradRight)  CGGradientRelease(_customGradRight);
}

// Returns a gradient ref for the given curve, windowed to this layer's
// [levelLo, levelHi]. A preset curve with a canonical level window comes from
// the static process-wide 6×3 matrix. Everything else — custom curves, or a
// non-canonical window such as the default full band (0..1) — is cached
// per-edge and rebuilt only when that edge's curve string changes (lo/hi are
// fixed for the lifetime of a given layer, so they need not enter the cache key).
- (CGGradientRef)gradientForCurve:(NSString *)curve
                       cachedCurve:(NSString * __strong *)cachedCurve
                        cachedGrad:(CGGradientRef *)cachedGrad {
  const NSInteger level = levelIndexForWindow(self.levelLo, self.levelHi);
  if (level >= 0 && !EdgeFadeCurveIsCustom(curve)) {
    return blurMaskGradientForPreset(curve, level);
  }
  if (![*cachedCurve isEqualToString:curve]) {
    if (*cachedGrad) CGGradientRelease(*cachedGrad);
    *cachedGrad  = buildBlurMaskGradient(curve, self.levelLo, self.levelHi);
    *cachedCurve = curve;
  }
  return *cachedGrad;
}

- (void)drawInContext:(CGContextRef)ctx {
  const CGFloat w = CGRectGetWidth(self.bounds);
  const CGFloat h = CGRectGetHeight(self.bounds);
  if (w <= 0 || h <= 0) return;

  // Start fully transparent — blur shows only where an edge ramp writes non-zero
  // alpha. Center stays at alpha=0 (sharp) with no action needed.
  CGContextClearRect(ctx, self.bounds);

  // Lighten blend: overlapping corners take MAX(presence_top, presence_left)
  // rather than multiplying, preventing double-application of the curve.
  CGContextSetBlendMode(ctx, kCGBlendModeLighten);

  CGGradientRef grad;

  if (self.fadeTop > 0) {
    grad = [self gradientForCurve:self.curveTop ?: @"smooth"
                       cachedCurve:&_cachedCustomTop
                        cachedGrad:&_customGradTop];
    CGContextSaveGState(ctx);
    CGContextClipToRect(ctx, CGRectMake(0, 0, w, self.fadeTop));
    // start = inner edge (low presence), end = outer edge (full presence).
    CGContextDrawLinearGradient(ctx, grad,
      CGPointMake(0, self.fadeTop), CGPointMake(0, 0),
      kCGGradientDrawsBeforeStartLocation | kCGGradientDrawsAfterEndLocation);
    CGContextRestoreGState(ctx);
  }
  if (self.fadeBottom > 0) {
    grad = [self gradientForCurve:self.curveBottom ?: @"smooth"
                       cachedCurve:&_cachedCustomBottom
                        cachedGrad:&_customGradBottom];
    CGContextSaveGState(ctx);
    CGContextClipToRect(ctx, CGRectMake(0, h - self.fadeBottom, w, self.fadeBottom));
    CGContextDrawLinearGradient(ctx, grad,
      CGPointMake(0, h - self.fadeBottom), CGPointMake(0, h),
      kCGGradientDrawsBeforeStartLocation | kCGGradientDrawsAfterEndLocation);
    CGContextRestoreGState(ctx);
  }
  if (self.fadeLeft > 0) {
    grad = [self gradientForCurve:self.curveLeft ?: @"smooth"
                       cachedCurve:&_cachedCustomLeft
                        cachedGrad:&_customGradLeft];
    CGContextSaveGState(ctx);
    CGContextClipToRect(ctx, CGRectMake(0, 0, self.fadeLeft, h));
    CGContextDrawLinearGradient(ctx, grad,
      CGPointMake(self.fadeLeft, 0), CGPointMake(0, 0),
      kCGGradientDrawsBeforeStartLocation | kCGGradientDrawsAfterEndLocation);
    CGContextRestoreGState(ctx);
  }
  if (self.fadeRight > 0) {
    grad = [self gradientForCurve:self.curveRight ?: @"smooth"
                       cachedCurve:&_cachedCustomRight
                        cachedGrad:&_customGradRight];
    CGContextSaveGState(ctx);
    CGContextClipToRect(ctx, CGRectMake(w - self.fadeRight, 0, self.fadeRight, h));
    CGContextDrawLinearGradient(ctx, grad,
      CGPointMake(w - self.fadeRight, 0), CGPointMake(w, 0),
      kCGGradientDrawsBeforeStartLocation | kCGGradientDrawsAfterEndLocation);
    CGContextRestoreGState(ctx);
  }
}

@end
