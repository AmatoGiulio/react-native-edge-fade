#import "EdgeFadeMaskLayer.h"
#import "EdgeFadeCurves.h"

#import <UIKit/UIKit.h>

// ─── Gradient construction ────────────────────────────────────────────────────

// Builds a DeviceGray mask gradient. Returns a retained CGGradientRef; preset
// gradients live in the static cache below and must NOT be released by the
// caller. Custom-curve gradients are freshly allocated — caller releases.
static CGGradientRef buildMaskGradient(NSString *curve)
{
  const CGFloat *alphas; const CGFloat *stops; size_t count;
  CGFloat *dynAlphas, *dynStops;
  EdgeFadeResolveCurve(curve, &alphas, &stops, &count, &dynAlphas, &dynStops);

  // Components are { gray, alpha } pairs. We use `1 - alphas[count-1-i]` so the
  // mask curve matches the overlay direction: content stays visible until near
  // the outer edge, then fades quickly.
  CGColorSpaceRef space = CGColorSpaceCreateDeviceGray();
  CGFloat *components   = (CGFloat *)malloc(count * 2 * sizeof(CGFloat));
  for (size_t i = 0; i < count; i++) {
    components[i * 2]     = 1.0;
    components[i * 2 + 1] = 1.0 - alphas[count - 1 - i];
  }
  CGGradientRef g = CGGradientCreateWithColorComponents(space, components, stops, count);
  free(components);
  CGColorSpaceRelease(space);
  if (dynAlphas) { free(dynAlphas); free(dynStops); }
  return g;
}

// Static preset cache — one gradient per preset, built once per process.
static CGGradientRef maskGradientForPreset(NSString *curve)
{
  static CGGradientRef cache[5];
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    cache[0] = buildMaskGradient(@"smooth");
    cache[1] = buildMaskGradient(@"sharp");
    cache[2] = buildMaskGradient(@"gentle");
    cache[3] = buildMaskGradient(@"soft");
    cache[4] = buildMaskGradient(@"linear");
  });
  if ([curve isEqualToString:@"sharp"])  return cache[1];
  if ([curve isEqualToString:@"gentle"]) return cache[2];
  if ([curve isEqualToString:@"soft"])   return cache[3];
  if ([curve isEqualToString:@"linear"]) return cache[4];
  return cache[0];
}

// ─── Layer ────────────────────────────────────────────────────────────────────

@implementation EdgeFadeMaskLayer

- (instancetype)init {
  self = [super init];
  self.needsDisplayOnBoundsChange = YES;
  // contentsScale is updated to traitCollection.displayScale by the owning view
  // in didMoveToWindow:/traitCollectionDidChange:. Seed with the main screen
  // for environments where the view never reaches a window (snapshot tests).
  self.contentsScale = UIScreen.mainScreen.scale;
  return self;
}

// Returns a gradient ref for the given curve.
// `mustRelease` is set to YES for custom curves — caller must CGGradientRelease.
- (CGGradientRef)gradientForCurve:(NSString *)curve mustRelease:(BOOL *)mustRelease {
  if (EdgeFadeCurveIsCustom(curve)) {
    *mustRelease = YES;
    return buildMaskGradient(curve);
  }
  *mustRelease = NO;
  return maskGradientForPreset(curve);
}

- (void)drawInContext:(CGContextRef)ctx {
  const CGFloat w = CGRectGetWidth(self.bounds);
  const CGFloat h = CGRectGetHeight(self.bounds);
  if (w <= 0 || h <= 0) return;

  CGContextSetFillColorWithColor(ctx, UIColor.whiteColor.CGColor);
  CGContextFillRect(ctx, self.bounds);
  CGContextSetBlendMode(ctx, kCGBlendModeDestinationIn);

  BOOL release = NO;
  CGGradientRef grad;

  if (self.fadeTop > 0) {
    grad = [self gradientForCurve:self.curveTop ?: @"smooth" mustRelease:&release];
    CGContextSaveGState(ctx);
    CGContextClipToRect(ctx, CGRectMake(0, 0, w, self.fadeTop));
    CGContextDrawLinearGradient(ctx, grad,
      CGPointMake(0, self.fadeTop), CGPointMake(0, 0),
      kCGGradientDrawsBeforeStartLocation | kCGGradientDrawsAfterEndLocation);
    CGContextRestoreGState(ctx);
    if (release) CGGradientRelease(grad);
  }
  if (self.fadeBottom > 0) {
    grad = [self gradientForCurve:self.curveBottom ?: @"smooth" mustRelease:&release];
    CGContextSaveGState(ctx);
    CGContextClipToRect(ctx, CGRectMake(0, h - self.fadeBottom, w, self.fadeBottom));
    CGContextDrawLinearGradient(ctx, grad,
      CGPointMake(0, h - self.fadeBottom), CGPointMake(0, h),
      kCGGradientDrawsBeforeStartLocation | kCGGradientDrawsAfterEndLocation);
    CGContextRestoreGState(ctx);
    if (release) CGGradientRelease(grad);
  }
  if (self.fadeLeft > 0) {
    grad = [self gradientForCurve:self.curveLeft ?: @"smooth" mustRelease:&release];
    CGContextSaveGState(ctx);
    CGContextClipToRect(ctx, CGRectMake(0, 0, self.fadeLeft, h));
    CGContextDrawLinearGradient(ctx, grad,
      CGPointMake(self.fadeLeft, 0), CGPointMake(0, 0),
      kCGGradientDrawsBeforeStartLocation | kCGGradientDrawsAfterEndLocation);
    CGContextRestoreGState(ctx);
    if (release) CGGradientRelease(grad);
  }
  if (self.fadeRight > 0) {
    grad = [self gradientForCurve:self.curveRight ?: @"smooth" mustRelease:&release];
    CGContextSaveGState(ctx);
    CGContextClipToRect(ctx, CGRectMake(w - self.fadeRight, 0, self.fadeRight, h));
    CGContextDrawLinearGradient(ctx, grad,
      CGPointMake(w - self.fadeRight, 0), CGPointMake(w, 0),
      kCGGradientDrawsBeforeStartLocation | kCGGradientDrawsAfterEndLocation);
    CGContextRestoreGState(ctx);
    if (release) CGGradientRelease(grad);
  }
}

@end
