#import "EdgeFadeView.h"

#import <React/RCTConversions.h>
#import <react/renderer/components/EdgeFadeViewSpec/ComponentDescriptors.h>
#import <react/renderer/components/EdgeFadeViewSpec/Props.h>
#import <react/renderer/components/EdgeFadeViewSpec/RCTComponentViewHelpers.h>
#import "RCTFabricComponentsPlugins.h"

using namespace facebook::react;

// ─── Curve data ───────────────────────────────────────────────────────────────
//
// 32-stop arrays computed once via dispatch_once:
//   smooth → (1−t)³   sharp  → (1−t)⁵
//   gentle → (1−t)²   soft   → cos(t·π/2)
// Dense stops eliminate visible banding at any fade size.

static const int    kN          = 32;
static const CGFloat kTwoStops[2] = {0, 1.0};

typedef CGFloat (*CurveFn)(CGFloat);

static CGFloat smoothFn(CGFloat t) { return pow(1-t, 3); }
static CGFloat sharpFn (CGFloat t) { return pow(1-t, 5); }
static CGFloat gentleFn(CGFloat t) { return pow(1-t, 2); }
static CGFloat softFn  (CGFloat t) { return cos(t * M_PI_2); }

static CGFloat sSmoothAlphas[32], sSharpAlphas[32], sGentleAlphas[32], sSoftAlphas[32];
static CGFloat sNStops[32];
static const CGFloat kLinearAlphas[2] = {1, 0};

static void computePresetCurves(void) {
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    for (int i = 0; i < kN; i++) {
      CGFloat t      = (CGFloat)i / (kN - 1);
      sNStops[i]      = t;
      sSmoothAlphas[i] = smoothFn(t);
      sSharpAlphas[i]  = sharpFn(t);
      sGentleAlphas[i] = gentleFn(t);
      sSoftAlphas[i]   = softFn(t);
    }
  });
}

// Fills preset pointers only — does NOT handle custom curves.
static void presetCurveData(NSString *curve,
                             const CGFloat **alphasOut,
                             const CGFloat **stopsOut,
                             size_t *countOut)
{
  computePresetCurves();
  if      ([curve isEqualToString:@"sharp"])  { *alphasOut = sSharpAlphas;  *stopsOut = sNStops; *countOut = kN; }
  else if ([curve isEqualToString:@"gentle"]) { *alphasOut = sGentleAlphas; *stopsOut = sNStops; *countOut = kN; }
  else if ([curve isEqualToString:@"soft"])   { *alphasOut = sSoftAlphas;   *stopsOut = sNStops; *countOut = kN; }
  else if ([curve isEqualToString:@"linear"]) { *alphasOut = kLinearAlphas; *stopsOut = kTwoStops; *countOut = 2; }
  else                                        { *alphasOut = sSmoothAlphas; *stopsOut = sNStops; *countOut = kN; }
}

static BOOL isCustomCurve(NSString *curve) {
  return [curve containsString:@","];
}

// ─── Custom curve parsing ─────────────────────────────────────────────────────
// Returns heap-allocated buffers when successful; caller must free().
// Returns NO and leaves buffers nil if parsing fails.

static BOOL parseCustomCurve(NSString *curve,
                              CGFloat **alphasOut,
                              CGFloat **stopsOut,
                              size_t *countOut)
{
  NSArray<NSString *> *parts = [curve componentsSeparatedByString:@","];
  NSUInteger n = parts.count;
  if (n < 2) return NO;

  CGFloat *alphas = (CGFloat *)malloc(n * sizeof(CGFloat));
  CGFloat *stops  = (CGFloat *)malloc(n * sizeof(CGFloat));
  for (NSUInteger i = 0; i < n; i++) {
    alphas[i] = [parts[i] doubleValue];
    stops[i]  = (CGFloat)i / (n - 1);
  }
  *alphasOut = alphas; *stopsOut = stops; *countOut = n;
  return YES;
}

// ─── Gradient factories ───────────────────────────────────────────────────────

// Builds a DeviceGray mask gradient for any curve (preset or custom).
// The returned CGGradientRef is always retained — caller is responsible for
// releasing custom-curve gradients. Preset gradients live in the static cache.
static CGGradientRef _buildMaskGradient(NSString *curve)
{
  const CGFloat *alphas; const CGFloat *stops; size_t count;
  CGFloat *dynAlphas = NULL; CGFloat *dynStops = NULL;

  if (isCustomCurve(curve)) {
    if (!parseCustomCurve(curve, &dynAlphas, &dynStops, &count)) {
      presetCurveData(@"smooth", &alphas, &stops, &count);
    } else {
      alphas = dynAlphas; stops = dynStops;
    }
  } else {
    presetCurveData(curve, &alphas, &stops, &count);
  }

  // Use 1−alphas[count−1−i] so the mask curve matches overlay direction:
  // content stays visible until near the outer edge, then fades quickly.
  CGColorSpaceRef space = CGColorSpaceCreateDeviceGray();
  CGFloat *components   = (CGFloat *)malloc(count * 2 * sizeof(CGFloat));
  for (size_t i = 0; i < count; i++) { components[i*2] = 1.0; components[i*2+1] = 1.0 - alphas[count - 1 - i]; }
  CGGradientRef g = CGGradientCreateWithColorComponents(space, components, stops, count);
  free(components);
  CGColorSpaceRelease(space);
  if (dynAlphas) { free(dynAlphas); free(dynStops); }
  return g;
}

// Static cache: one gradient per preset, built once for the process lifetime.
static CGGradientRef maskGradientForPreset(NSString *curve)
{
  static CGGradientRef cache[5];
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    cache[0] = _buildMaskGradient(@"smooth");
    cache[1] = _buildMaskGradient(@"sharp");
    cache[2] = _buildMaskGradient(@"gentle");
    cache[3] = _buildMaskGradient(@"soft");
    cache[4] = _buildMaskGradient(@"linear");
  });
  if ([curve isEqualToString:@"sharp"])  return cache[1];
  if ([curve isEqualToString:@"gentle"]) return cache[2];
  if ([curve isEqualToString:@"soft"])   return cache[3];
  if ([curve isEqualToString:@"linear"]) return cache[4];
  return cache[0];
}

// Overlay colors: transparent (inner, reversed curve) → color (outer).
static NSArray<id> *overlayColors(NSString *curve, UIColor *color)
{
  const CGFloat *alphas; size_t count;
  const CGFloat *stops;
  CGFloat *dynAlphas = NULL; CGFloat *dynStops = NULL;

  if (isCustomCurve(curve)) {
    if (!parseCustomCurve(curve, &dynAlphas, &dynStops, &count)) {
      presetCurveData(@"smooth", &alphas, &stops, &count);
    } else {
      alphas = dynAlphas;
    }
  } else {
    presetCurveData(curve, &alphas, &stops, &count);
  }

  CGFloat r, g, b, a;
  [color getRed:&r green:&g blue:&b alpha:&a];

  NSMutableArray *result = [NSMutableArray arrayWithCapacity:count];
  for (NSInteger i = (NSInteger)count - 1; i >= 0; i--) {
    UIColor *c = [UIColor colorWithRed:r green:g blue:b alpha:a * alphas[i]];
    [result addObject:(id)c.CGColor];
  }
  if (dynAlphas) { free(dynAlphas); free(dynStops); }
  return [result copy];
}

static NSArray<NSNumber *> *locationsForCurve(NSString *curve)
{
  if (isCustomCurve(curve)) {
    NSArray<NSString *> *parts = [curve componentsSeparatedByString:@","];
    NSUInteger n = parts.count;
    NSMutableArray *locs = [NSMutableArray arrayWithCapacity:n];
    for (NSUInteger i = 0; i < n; i++) [locs addObject:@((CGFloat)i / (n - 1))];
    return [locs copy];
  }
  if ([curve isEqualToString:@"linear"]) return @[@0, @1];
  // Preset: kN evenly-spaced locations
  computePresetCurves();
  NSMutableArray *locs = [NSMutableArray arrayWithCapacity:kN];
  for (int i = 0; i < kN; i++) [locs addObject:@(sNStops[i])];
  return [locs copy];
}

// ─── EdgeFadeMaskLayer ────────────────────────────────────────────────────────
//
// Draws a combined alpha mask for all four edges into a single CALayer.
// kCGBlendModeMultiply ensures corners receive the product of both adjacent
// curves: Ra = Sa * Da → alpha_top × alpha_left at each corner pixel.

@interface EdgeFadeMaskLayer : CALayer
@property CGFloat fadeTop, fadeBottom, fadeLeft, fadeRight;
@property (nonatomic, copy, nullable) NSString *curveTop, *curveBottom, *curveLeft, *curveRight;
@end

@implementation EdgeFadeMaskLayer

- (instancetype)init {
  self = [super init];
  self.needsDisplayOnBoundsChange = YES;
  self.contentsScale = UIScreen.mainScreen.scale;
  return self;
}

// Returns a gradient ref for the given curve.
// mustRelease is set to YES for custom curves — caller must CGGradientRelease.
- (CGGradientRef)_gradForCurve:(NSString *)curve mustRelease:(BOOL *)mustRelease {
  if (isCustomCurve(curve)) {
    *mustRelease = YES;
    return _buildMaskGradient(curve);
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
  // DestinationIn: R = D * Sa. Each edge gradient (clipped to its own rect)
  // multiplies the mask alpha by the gradient's alpha. Sequential passes on
  // overlapping corners compound (Sa_top * Sa_left), matching the intended
  // per-corner falloff.
  CGContextSetBlendMode(ctx, kCGBlendModeDestinationIn);

  BOOL release = NO;
  CGGradientRef grad;

  if (self.fadeTop > 0) {
    grad = [self _gradForCurve:self.curveTop ?: @"smooth" mustRelease:&release];
    CGContextSaveGState(ctx);
    CGContextClipToRect(ctx, CGRectMake(0, 0, w, self.fadeTop));
    CGContextDrawLinearGradient(ctx, grad,
      CGPointMake(0, self.fadeTop), CGPointMake(0, 0),
      kCGGradientDrawsBeforeStartLocation | kCGGradientDrawsAfterEndLocation);
    CGContextRestoreGState(ctx);
    if (release) CGGradientRelease(grad);
  }
  if (self.fadeBottom > 0) {
    grad = [self _gradForCurve:self.curveBottom ?: @"smooth" mustRelease:&release];
    CGContextSaveGState(ctx);
    CGContextClipToRect(ctx, CGRectMake(0, h - self.fadeBottom, w, self.fadeBottom));
    CGContextDrawLinearGradient(ctx, grad,
      CGPointMake(0, h - self.fadeBottom), CGPointMake(0, h),
      kCGGradientDrawsBeforeStartLocation | kCGGradientDrawsAfterEndLocation);
    CGContextRestoreGState(ctx);
    if (release) CGGradientRelease(grad);
  }
  if (self.fadeLeft > 0) {
    grad = [self _gradForCurve:self.curveLeft ?: @"smooth" mustRelease:&release];
    CGContextSaveGState(ctx);
    CGContextClipToRect(ctx, CGRectMake(0, 0, self.fadeLeft, h));
    CGContextDrawLinearGradient(ctx, grad,
      CGPointMake(self.fadeLeft, 0), CGPointMake(0, 0),
      kCGGradientDrawsBeforeStartLocation | kCGGradientDrawsAfterEndLocation);
    CGContextRestoreGState(ctx);
    if (release) CGGradientRelease(grad);
  }
  if (self.fadeRight > 0) {
    grad = [self _gradForCurve:self.curveRight ?: @"smooth" mustRelease:&release];
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

// ─── EdgeFadeView ─────────────────────────────────────────────────────────────

// invalidateLayer is implemented in RCTViewComponentView but not exposed in any
// header. Forward-declare here so the override below can call super.
@interface RCTViewComponentView (EdgeFadeInternal)
- (void)invalidateLayer;
@end

@implementation EdgeFadeView {
  // Mask mode
  EdgeFadeMaskLayer *_maskLayer;

  // Overlay mode — one CAGradientLayer per edge
  UIView          *_overlayContainer;
  CAGradientLayer *_overlayTop, *_overlayBottom, *_overlayLeft, *_overlayRight;

  // Per-layer color cache — avoid rebuilding colors on unrelated prop changes
  NSString *_cachedCurveTop, *_cachedCurveBottom, *_cachedCurveLeft, *_cachedCurveRight;
  UIColor  *_cachedColorTop, *_cachedColorBottom, *_cachedColorLeft, *_cachedColorRight;

  // Current config
  BOOL      _isMaskMode;
  CGFloat   _fadeTop, _fadeBottom, _fadeLeft, _fadeRight;
  NSString *_curveTop, *_curveBottom, *_curveLeft, *_curveRight;
  UIColor  *_overlayColor;
  UIColor  *_overlayColorTop, *_overlayColorBottom, *_overlayColorLeft, *_overlayColorRight;
  CGFloat   _fadeRadius;
}

+ (ComponentDescriptorProvider)componentDescriptorProvider {
  return concreteComponentDescriptorProvider<EdgeFadeViewComponentDescriptor>();
}

- (instancetype)initWithFrame:(CGRect)frame {
  if (self = [super initWithFrame:frame]) {
    static const auto defaultProps = std::make_shared<const EdgeFadeViewProps>();
    _props = defaultProps;
    _isMaskMode = YES;
  }
  return self;
}

// ── Props update ──────────────────────────────────────────────────────────────

- (void)updateProps:(Props::Shared const &)props oldProps:(Props::Shared const &)oldProps {
  const auto &p  = *std::static_pointer_cast<EdgeFadeViewProps const>(props);
  // `oldProps` may be null on the first updateProps call. `_props` is guaranteed
  // valid (initialized to defaultProps in initWithFrame) and reflects the last
  // applied props after [super updateProps:].
  const auto &op = *std::static_pointer_cast<EdgeFadeViewProps const>(_props);

  const BOOL sizeChanged  = p.fadeTop    != op.fadeTop    || p.fadeBottom != op.fadeBottom
                         || p.fadeLeft   != op.fadeLeft   || p.fadeRight  != op.fadeRight;
  const BOOL curveChanged = p.curveTop   != op.curveTop   || p.curveBottom != op.curveBottom
                         || p.curveLeft  != op.curveLeft  || p.curveRight  != op.curveRight;
  const BOOL colorChanged = p.overlayColor       != op.overlayColor
                         || p.overlayColorTop    != op.overlayColorTop
                         || p.overlayColorBottom != op.overlayColorBottom
                         || p.overlayColorLeft   != op.overlayColorLeft
                         || p.overlayColorRight  != op.overlayColorRight;
  const BOOL modeChanged  = p.mode != op.mode;
  const BOOL radiusChanged = p.fadeRadius != op.fadeRadius;

  _fadeTop    = (CGFloat)p.fadeTop;    _fadeBottom = (CGFloat)p.fadeBottom;
  _fadeLeft   = (CGFloat)p.fadeLeft;   _fadeRight  = (CGFloat)p.fadeRight;
  _curveTop    = [NSString stringWithUTF8String:p.curveTop.c_str()];
  _curveBottom = [NSString stringWithUTF8String:p.curveBottom.c_str()];
  _curveLeft   = [NSString stringWithUTF8String:p.curveLeft.c_str()];
  _curveRight  = [NSString stringWithUTF8String:p.curveRight.c_str()];
  _overlayColor       = p.overlayColor       ? RCTUIColorFromSharedColor(p.overlayColor)       : nil;
  _overlayColorTop    = p.overlayColorTop    ? RCTUIColorFromSharedColor(p.overlayColorTop)    : nil;
  _overlayColorBottom = p.overlayColorBottom ? RCTUIColorFromSharedColor(p.overlayColorBottom) : nil;
  _overlayColorLeft   = p.overlayColorLeft   ? RCTUIColorFromSharedColor(p.overlayColorLeft)   : nil;
  _overlayColorRight  = p.overlayColorRight  ? RCTUIColorFromSharedColor(p.overlayColorRight)  : nil;

  NSString *modeStr = [NSString stringWithUTF8String:p.mode.c_str()];
  BOOL newIsMask = ![@"overlay" isEqualToString:modeStr];

  // Build (or rebuild) layers when the mode flips, OR when the layer for the
  // current mode is still missing (first updateProps call, since _props
  // defaults don't trigger a mode flip when the user picks the default mode).
  BOOL layerMissing = newIsMask ? (_maskLayer == nil) : (_overlayContainer == nil);
  if ((modeChanged && newIsMask != _isMaskMode) || layerMissing) {
    _isMaskMode = newIsMask;
    [self _teardownFadeLayers];
    [self _buildFadeLayers];
  } else if (_isMaskMode) {
    if (sizeChanged || curveChanged) [self _syncMaskLayer];
  } else {
    if (colorChanged || curveChanged) [self _rebuildOverlayColors];
    if (sizeChanged) [self _updateLayerFrames];
  }

  if (radiusChanged) {
    _fadeRadius = (CGFloat)p.fadeRadius;
    self.layer.cornerRadius  = _fadeRadius;
    self.layer.masksToBounds = (_fadeRadius > 0);
  }

  [super updateProps:props oldProps:oldProps];
}

// ── Layout ────────────────────────────────────────────────────────────────────

- (void)layoutSubviews {
  [super layoutSubviews];
  [self _updateLayerFrames];
}

// RCTViewComponentView.invalidateLayer resets self.currentContainerView.layer.mask
// to nil during its border/clipping pipeline. We must re-apply our mask after
// super has finished, otherwise mask mode never paints.
- (void)invalidateLayer {
  [super invalidateLayer];
  if (_isMaskMode && _maskLayer && self.layer.mask != _maskLayer) {
    self.layer.mask = _maskLayer;
  }
}

- (void)didAddSubview:(UIView *)subview {
  [super didAddSubview:subview];
  if (_overlayContainer && subview != _overlayContainer) {
    [self bringSubviewToFront:_overlayContainer];
  }
}

// ── Fade layer management ─────────────────────────────────────────────────────

- (void)_teardownFadeLayers {
  self.layer.mask = nil;
  _maskLayer = nil;

  [_overlayContainer removeFromSuperview];
  _overlayContainer = nil;
  _overlayTop = _overlayBottom = _overlayLeft = _overlayRight = nil;
  _cachedCurveTop = _cachedCurveBottom = _cachedCurveLeft = _cachedCurveRight = nil;
  _cachedColorTop = _cachedColorBottom = _cachedColorLeft = _cachedColorRight = nil;
}

- (void)_buildFadeLayers {
  if (_isMaskMode) {
    _maskLayer = [EdgeFadeMaskLayer layer];
    self.layer.mask = _maskLayer;
    [self _syncMaskLayer];
  } else {
    _overlayContainer = [[UIView alloc] init];
    _overlayContainer.userInteractionEnabled = NO;
    _overlayContainer.backgroundColor = UIColor.clearColor;
    [self addSubview:_overlayContainer];
    _overlayTop    = [self _makeGradientLayer];
    _overlayBottom = [self _makeGradientLayer];
    _overlayLeft   = [self _makeGradientLayer];
    _overlayRight  = [self _makeGradientLayer];
    [self _rebuildOverlayColors];
    [self _updateLayerFrames];
  }
}

// ── Mask mode ─────────────────────────────────────────────────────────────────

- (void)_syncMaskLayer {
  _maskLayer.fadeTop    = _fadeTop;   _maskLayer.fadeBottom = _fadeBottom;
  _maskLayer.fadeLeft   = _fadeLeft;  _maskLayer.fadeRight  = _fadeRight;
  _maskLayer.curveTop   = _curveTop;  _maskLayer.curveBottom = _curveBottom;
  _maskLayer.curveLeft  = _curveLeft; _maskLayer.curveRight  = _curveRight;
  [_maskLayer setNeedsDisplay];
}

// ── Overlay mode ──────────────────────────────────────────────────────────────

- (CAGradientLayer *)_makeGradientLayer {
  CAGradientLayer *layer = [CAGradientLayer layer];
  layer.contentsScale = UIScreen.mainScreen.scale;
  [_overlayContainer.layer addSublayer:layer];
  return layer;
}

// Returns the effective overlay color for a given edge:
// per-edge override → global overlayColor → opaque black fallback.
- (UIColor *)_effectiveColorForEdge:(UIColor *)edgeColor {
  return edgeColor ?: _overlayColor ?: UIColor.blackColor;
}

// Rebuilds gradient colors only when the relevant color or curve changed.
- (void)_rebuildOverlayColors {
  UIColor *cTop    = [self _effectiveColorForEdge:_overlayColorTop];
  UIColor *cBottom = [self _effectiveColorForEdge:_overlayColorBottom];
  UIColor *cLeft   = [self _effectiveColorForEdge:_overlayColorLeft];
  UIColor *cRight  = [self _effectiveColorForEdge:_overlayColorRight];

  if (![_curveTop isEqualToString:_cachedCurveTop] || ![cTop isEqual:_cachedColorTop]) {
    _overlayTop.colors    = overlayColors(_curveTop, cTop);
    _overlayTop.locations = locationsForCurve(_curveTop);
    _cachedCurveTop = _curveTop; _cachedColorTop = cTop;
  }
  if (![_curveBottom isEqualToString:_cachedCurveBottom] || ![cBottom isEqual:_cachedColorBottom]) {
    _overlayBottom.colors    = overlayColors(_curveBottom, cBottom);
    _overlayBottom.locations = locationsForCurve(_curveBottom);
    _cachedCurveBottom = _curveBottom; _cachedColorBottom = cBottom;
  }
  if (![_curveLeft isEqualToString:_cachedCurveLeft] || ![cLeft isEqual:_cachedColorLeft]) {
    _overlayLeft.colors    = overlayColors(_curveLeft, cLeft);
    _overlayLeft.locations = locationsForCurve(_curveLeft);
    _cachedCurveLeft = _curveLeft; _cachedColorLeft = cLeft;
  }
  if (![_curveRight isEqualToString:_cachedCurveRight] || ![cRight isEqual:_cachedColorRight]) {
    _overlayRight.colors    = overlayColors(_curveRight, cRight);
    _overlayRight.locations = locationsForCurve(_curveRight);
    _cachedCurveRight = _curveRight; _cachedColorRight = cRight;
  }
}

// ── Frame sync ────────────────────────────────────────────────────────────────

- (void)_updateLayerFrames {
  const CGFloat w = CGRectGetWidth(self.bounds);
  const CGFloat h = CGRectGetHeight(self.bounds);

  if (_isMaskMode && _maskLayer) {
    _maskLayer.frame = self.bounds;
    [_maskLayer setNeedsDisplay];
    return;
  }

  if (!_overlayContainer) return;
  _overlayContainer.frame = self.bounds;

  [CATransaction begin];
  [CATransaction setDisableActions:YES];

  if (_overlayTop) {
    _overlayTop.frame      = CGRectMake(0, 0, w, _fadeTop);
    _overlayTop.startPoint = CGPointMake(0.5, 1); _overlayTop.endPoint = CGPointMake(0.5, 0);
    _overlayTop.hidden     = (_fadeTop <= 0);
  }
  if (_overlayBottom) {
    _overlayBottom.frame      = CGRectMake(0, h - _fadeBottom, w, _fadeBottom);
    _overlayBottom.startPoint = CGPointMake(0.5, 0); _overlayBottom.endPoint = CGPointMake(0.5, 1);
    _overlayBottom.hidden     = (_fadeBottom <= 0);
  }
  if (_overlayLeft) {
    _overlayLeft.frame      = CGRectMake(0, 0, _fadeLeft, h);
    _overlayLeft.startPoint = CGPointMake(1, 0.5); _overlayLeft.endPoint = CGPointMake(0, 0.5);
    _overlayLeft.hidden     = (_fadeLeft <= 0);
  }
  if (_overlayRight) {
    _overlayRight.frame      = CGRectMake(w - _fadeRight, 0, _fadeRight, h);
    _overlayRight.startPoint = CGPointMake(0, 0.5); _overlayRight.endPoint = CGPointMake(1, 0.5);
    _overlayRight.hidden     = (_fadeRight <= 0);
  }

  [CATransaction commit];
}

@end

Class<RCTComponentViewProtocol> EdgeFadeViewCls(void) { return EdgeFadeView.class; }
