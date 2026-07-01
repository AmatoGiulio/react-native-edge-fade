#import "EdgeFadeBlurMaskLayer.h"
#import "EdgeFadeCurves.h"

#import <UIKit/UIKit.h>

// ─── Gradient construction ────────────────────────────────────────────────────

// Builds a DeviceGray blur-presence gradient. Returns a retained CGGradientRef.
// Blur presence is 1 − alpha(t) across the ENTIRE band — the blur takes the
// place of whatever the curve dissolves, the same semantics as mask mode. The
// curve therefore governs the progression of the blur along the full width of
// the strip: no positional compression, no plateau. Components are
// { gray=1.0, alpha=1-f } where f is the curve's own alpha at that stop
// (alphas[0]=1 at the inner edge → presence 0; alphas[count-1]=0 at the outer
// edge → presence 1). Preset gradients live in the static cache below and must
// NOT be released by the caller. Custom-curve gradients are freshly allocated —
// caller releases.
static CGGradientRef buildBlurMaskGradient(NSString *curve)
{
  const CGFloat *alphas; const CGFloat *stops; size_t count;
  CGFloat *dynAlphas, *dynStops;
  EdgeFadeResolveCurve(curve, &alphas, &stops, &count, &dynAlphas, &dynStops);

  // Presence = complement of the curve at the direct index — no mirroring,
  // no compression. Draw direction in drawInContext (start=inner, end=outer)
  // is unchanged, so this alone flips presence to rise across the band.
  CGColorSpaceRef space = CGColorSpaceCreateDeviceGray();
  CGFloat *components   = (CGFloat *)malloc(count * 2 * sizeof(CGFloat));
  for (size_t i = 0; i < count; i++) {
    components[i * 2]     = 1.0;
    components[i * 2 + 1] = 1.0 - alphas[i];
  }

  CGGradientRef g = CGGradientCreateWithColorComponents(space, components, stops, count);
  free(components);
  CGColorSpaceRelease(space);
  if (dynAlphas) { free(dynAlphas); free(dynStops); }
  return g;
}

// Static preset cache — one gradient per preset, built once per process.
static CGGradientRef blurMaskGradientForPreset(NSString *curve)
{
  static CGGradientRef cache[6];
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    cache[0] = buildBlurMaskGradient(@"smooth");
    cache[1] = buildBlurMaskGradient(@"sharp");
    cache[2] = buildBlurMaskGradient(@"gentle");
    cache[3] = buildBlurMaskGradient(@"soft");
    cache[4] = buildBlurMaskGradient(@"linear");
    cache[5] = buildBlurMaskGradient(@"smoother");
  });
  if ([curve isEqualToString:@"sharp"])  return cache[1];
  if ([curve isEqualToString:@"gentle"]) return cache[2];
  if ([curve isEqualToString:@"soft"])   return cache[3];
  if ([curve isEqualToString:@"smoother"]) return cache[5];
  if ([curve isEqualToString:@"linear"]) return cache[4];
  return cache[0];
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

// Returns a gradient ref for the given curve. Presets come from the static
// process-wide cache; custom curves are cached per-edge and rebuilt only when
// that edge's curve string changes.
- (CGGradientRef)gradientForCurve:(NSString *)curve
                       cachedCurve:(NSString * __strong *)cachedCurve
                        cachedGrad:(CGGradientRef *)cachedGrad {
  if (!EdgeFadeCurveIsCustom(curve)) return blurMaskGradientForPreset(curve);
  if (![*cachedCurve isEqualToString:curve]) {
    if (*cachedGrad) CGGradientRelease(*cachedGrad);
    *cachedGrad  = buildBlurMaskGradient(curve);
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
