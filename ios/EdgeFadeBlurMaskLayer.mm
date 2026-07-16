#import "EdgeFadeBlurMaskLayer.h"
#import "EdgeFadeCurves.h"

#import <UIKit/UIKit.h>

// ─── Gradient construction ────────────────────────────────────────────────────

// Builds a DeviceGray UNIFORM-plateau mask gradient (Android parity — see
// frostGradient in EdgeFadeView.kt). Returns a retained CGGradientRef.
//
// 32 evenly-spaced positional stops along the band (inner t=0 → outer t=1):
//   u      = clamp((t − start) / rampWidth, 0, 1)   — position in the ramp window
//   weight = presenceAt(curve, u)                   — the fade curve SHAPES the
//                                                     ramp, so editing the Bézier
//                                                     reshapes the blur fade-in
// Before `start` the mask is at presence(0) (0 for standard curves); past
// `start + rampWidth` it holds at presence(1) (a solid plateau). Heavier levels
// use a later `start`, so the perceived radius grows toward the outer edge.
//
// Preset gradients live in the static cache below and must NOT be released by
// the caller. Everything else is freshly allocated — caller releases.
static const int kMaskStops = 32;

// Presence (1 − alpha) at normalized position u, lerped over pre-resolved
// curve arrays — same math as EdgeFadePresenceAt without re-resolving per stop.
static inline CGFloat presenceAtResolved(const CGFloat *alphas, const CGFloat *stops,
                                         size_t count, CGFloat u)
{
  if (u <= 0.0) return 1.0 - alphas[0];
  if (u >= 1.0) return 1.0 - alphas[count - 1];
  for (size_t i = 1; i < count; i++) {
    if (u <= stops[i]) {
      const CGFloat span = stops[i] - stops[i - 1];
      const CGFloat f = span > 0.0 ? (u - stops[i - 1]) / span : 1.0;
      return 1.0 - (alphas[i - 1] + (alphas[i] - alphas[i - 1]) * f);
    }
  }
  return 1.0 - alphas[count - 1];
}

static CGGradientRef buildBlurMaskGradient(NSString *curve, CGFloat start, CGFloat rampWidth,
                                            BOOL curveShaped)
{
  const CGFloat *alphas = NULL; const CGFloat *curveStops = NULL; size_t count = 0;
  CGFloat *dynAlphas = NULL, *dynStops = NULL;
  if (curveShaped) {
    EdgeFadeResolveCurve(curve, &alphas, &curveStops, &count, &dynAlphas, &dynStops);
  }

  // Guard against a degenerate ramp: treat it as a hard step at `start`.
  const CGFloat span = rampWidth > 0.0 ? rampWidth : 0.0;

  CGColorSpaceRef space = CGColorSpaceCreateDeviceGray();
  CGFloat components[kMaskStops * 2];
  CGFloat stops[kMaskStops];
  for (int i = 0; i < kMaskStops; i++) {
    const CGFloat t = (CGFloat)i / (kMaskStops - 1);
    CGFloat u;
    if (span > 0.0) {
      u = (t - start) / span;
    } else {
      u = (t >= start) ? 1.0 : 0.0;
    }
    if (u < 0.0) u = 0.0; else if (u > 1.0) u = 1.0;
    stops[i]              = t;
    components[i * 2]     = 1.0;
    // Level 0: curve-shaped (the visible transition). Heavy levels: zero-slope
    // smoothstep — see the header note on scroll banding.
    components[i * 2 + 1] = curveShaped
        ? presenceAtResolved(alphas, curveStops, count, u)
        : u * u * (3.0 - 2.0 * u);
  }

  CGGradientRef g = CGGradientCreateWithColorComponents(space, components, stops, kMaskStops);
  CGColorSpaceRelease(space);
  if (dynAlphas) { free(dynAlphas); free(dynStops); }
  return g;
}

// Canonical plateau windows for the two HEAVIER levels: level 1 = (start 0.35,
// ramp 0.65), level 2 = (start 0.65, ramp 0.35) — both reach weight 1 exactly
// at the outer edge, so there are no interior constant-blend plateaus. Their
// smoothstep shaping is curve-INDEPENDENT, so the static cache holds just two
// gradients. Level 0 (curve-shaped, runtime rampWidth) takes the per-instance
// path.
static const CGFloat kCanonicalStart[2] = {0.35, 0.65};
static const CGFloat kCanonicalRamp[2]  = {0.65, 0.35};

// Maps a canonical (start, rampWidth) heavy window to 0 or 1 (index into the
// static cache), or -1 for anything else.
static NSInteger canonicalWindowIndex(CGFloat start, CGFloat rampWidth)
{
  const CGFloat eps = 1e-4;
  for (NSInteger k = 0; k < 2; k++) {
    if (fabs(start - kCanonicalStart[k]) < eps && fabs(rampWidth - kCanonicalRamp[k]) < eps) {
      return k;
    }
  }
  return -1;
}

static CGGradientRef blurMaskGradientForHeavyWindow(NSInteger window)
{
  static CGGradientRef cache[2];
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    for (NSInteger k = 0; k < 2; k++) {
      cache[k] = buildBlurMaskGradient(nil, kCanonicalStart[k], kCanonicalRamp[k], NO);
    }
  });
  if (window < 0 || window > 1) window = 0;
  return cache[window];
}

// ─── Layer ────────────────────────────────────────────────────────────────────

@implementation EdgeFadeBlurMaskLayer {
  // Per-edge cache for gradients not served by the static preset matrix
  // (custom curves, and level 0 whose rampWidth is runtime-variable). Without
  // this, drawInContext: would rebuild its CGGradientRef on every call.
  NSString     *_cachedCustomTop,    *_cachedCustomBottom,
               *_cachedCustomLeft,   *_cachedCustomRight;
  CGGradientRef _customGradTop,       _customGradBottom,
                _customGradLeft,      _customGradRight;
  // rampWidth the per-edge cache entries were built for. Level 0's rampWidth
  // (frostProgression) can change at runtime; a mismatch busts all four.
  CGFloat _cachedRampWidth;
}

- (instancetype)init {
  self = [super init];
  self.needsDisplayOnBoundsChange = YES;
  // Full-band ramp by default → the plain curve-shaped fade-in with no
  // plateau offset. EdgeFadeView overrides start/rampWidth per level.
  _levelStart = 0.0;
  _rampWidth  = 1.0;
  _curveShaped = YES;
  _cachedRampWidth = -1.0;
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
// (levelStart, rampWidth) plateau. A preset curve with a canonical heavy-level
// window comes from the static process-wide 6×2 matrix. Everything else —
// custom curves, or level 0 whose rampWidth (frostProgression) is runtime-
// variable — is cached per-edge and rebuilt when that edge's curve string OR
// the rampWidth changes (levelStart is fixed for a given layer's lifetime).
- (CGGradientRef)gradientForCurve:(NSString *)curve
                       cachedCurve:(NSString * __strong *)cachedCurve
                        cachedGrad:(CGGradientRef *)cachedGrad {
  if (!self.curveShaped) {
    const NSInteger window = canonicalWindowIndex(self.levelStart, self.rampWidth);
    if (window >= 0) return blurMaskGradientForHeavyWindow(window);
    // Non-canonical smoothstep window — curve is irrelevant, cache per-edge.
  }
  if (self.rampWidth != _cachedRampWidth) {
    // rampWidth changed (frostProgression) — every cached edge gradient is stale.
    if (_customGradTop)    { CGGradientRelease(_customGradTop);    _customGradTop = NULL; }
    if (_customGradBottom) { CGGradientRelease(_customGradBottom); _customGradBottom = NULL; }
    if (_customGradLeft)   { CGGradientRelease(_customGradLeft);   _customGradLeft = NULL; }
    if (_customGradRight)  { CGGradientRelease(_customGradRight);  _customGradRight = NULL; }
    _cachedCustomTop = _cachedCustomBottom = _cachedCustomLeft = _cachedCustomRight = nil;
    _cachedRampWidth = self.rampWidth;
  }
  if (*cachedGrad == NULL || ![*cachedCurve isEqualToString:curve]) {
    if (*cachedGrad) CGGradientRelease(*cachedGrad);
    *cachedGrad  = buildBlurMaskGradient(curve, self.levelStart, self.rampWidth,
                                         self.curveShaped);
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
