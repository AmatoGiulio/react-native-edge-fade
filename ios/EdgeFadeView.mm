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
// Builds CGColorRef instances directly (skipping the UIColor round-trip) so
// per-rebuild cost is one CGColorCreate per stop instead of one UIColor +
// one autoreleased CGColor cast — roughly half the work for the 32-stop case.
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

  CGColorSpaceRef space = CGColorSpaceCreateDeviceRGB();
  NSMutableArray *result = [NSMutableArray arrayWithCapacity:count];
  for (NSInteger i = (NSInteger)count - 1; i >= 0; i--) {
    CGFloat components[4] = {r, g, b, a * alphas[i]};
    CGColorRef c = CGColorCreate(space, components);
    [result addObject:(__bridge_transfer id)c];
  }
  CGColorSpaceRelease(space);
  if (dynAlphas) { free(dynAlphas); free(dynStops); }
  return [result copy];
}

// Cached locations arrays — these are identical for every preset and shared
// between all four edges. The previous code re-allocated kN NSNumbers per
// edge on every overlay color rebuild.
static NSArray<NSNumber *> *cachedPresetLocations(void)
{
  static NSArray<NSNumber *> *cached;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    computePresetCurves();
    NSMutableArray *locs = [NSMutableArray arrayWithCapacity:kN];
    for (int i = 0; i < kN; i++) [locs addObject:@(sNStops[i])];
    cached = [locs copy];
  });
  return cached;
}

static NSArray<NSNumber *> *cachedLinearLocations(void)
{
  static NSArray<NSNumber *> *cached;
  static dispatch_once_t once;
  dispatch_once(&once, ^{ cached = @[@0, @1]; });
  return cached;
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
  if ([curve isEqualToString:@"linear"]) return cachedLinearLocations();
  return cachedPresetLocations();
}

// ─── EdgeFadeMaskLayer ────────────────────────────────────────────────────────
//
// Draws a combined alpha mask for all four edges into a single CALayer.
// Uses kCGBlendModeDestinationIn (R = D · Sa). The destination is initialized
// to opaque white; each edge gradient (clipped to its own strip) multiplies
// existing alpha by the gradient's alpha. Sequential passes over an overlapping
// corner therefore compound: alpha_top × alpha_left.

@interface EdgeFadeMaskLayer : CALayer
@property CGFloat fadeTop, fadeBottom, fadeLeft, fadeRight;
@property (nonatomic, copy, nullable) NSString *curveTop, *curveBottom, *curveLeft, *curveRight;
@end

@implementation EdgeFadeMaskLayer

- (instancetype)init {
  self = [super init];
  self.needsDisplayOnBoundsChange = YES;
  // contentsScale is updated to traitCollection.displayScale by the owning
  // view in didMoveToWindow:/traitCollectionDidChange:. Seed with main screen
  // for environments where the view never reaches a window (snapshot tests).
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

  // Overlay mode — one CAGradientLayer per edge, attached directly to self.layer
  // (no intermediate container view) to avoid an extra compositing pass.
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
    // Continuous corner curve matches Apple's system squircle and composes
    // more cleanly with `layer.mask` than the default circular curve.
    if (@available(iOS 13.0, *)) {
      self.layer.cornerCurve = kCACornerCurveContinuous;
    }
  }
  return self;
}

// ── Scale sync ────────────────────────────────────────────────────────────────
// Use the actual window's screen scale rather than UIScreen.mainScreen — the
// latter is wrong on iPad multi-window and external displays.

- (CGFloat)_effectiveScale {
  UIScreen *screen = self.window.screen ?: UIScreen.mainScreen;
  return screen.scale;
}

- (void)_syncLayerScales {
  const CGFloat scale = [self _effectiveScale];
  if (_maskLayer && _maskLayer.contentsScale != scale) {
    _maskLayer.contentsScale = scale;
    [_maskLayer setNeedsDisplay];
  }
  if (_overlayTop) {
    _overlayTop.contentsScale = _overlayBottom.contentsScale =
    _overlayLeft.contentsScale = _overlayRight.contentsScale = scale;
  }
}

- (void)didMoveToWindow {
  [super didMoveToWindow];
  [self _syncLayerScales];
}

- (void)traitCollectionDidChange:(UITraitCollection *)previousTraitCollection {
  [super traitCollectionDidChange:previousTraitCollection];
  [self _syncLayerScales];
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
  BOOL layerMissing = newIsMask ? (_maskLayer == nil) : (_overlayTop == nil);
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
  // Subview layers are appended to self.layer.sublayers and would otherwise
  // sit above our overlay gradient layers. Re-attaching with addSublayer:
  // moves a layer to the end of the sublayers array → back on top.
  if (!_isMaskMode && _overlayTop) {
    [self.layer addSublayer:_overlayTop];
    [self.layer addSublayer:_overlayBottom];
    [self.layer addSublayer:_overlayLeft];
    [self.layer addSublayer:_overlayRight];
  }
}

// ── Fade layer management ─────────────────────────────────────────────────────

- (void)_teardownFadeLayers {
  self.layer.mask = nil;
  _maskLayer = nil;

  [_overlayTop removeFromSuperlayer];
  [_overlayBottom removeFromSuperlayer];
  [_overlayLeft removeFromSuperlayer];
  [_overlayRight removeFromSuperlayer];
  _overlayTop = _overlayBottom = _overlayLeft = _overlayRight = nil;
  _cachedCurveTop = _cachedCurveBottom = _cachedCurveLeft = _cachedCurveRight = nil;
  _cachedColorTop = _cachedColorBottom = _cachedColorLeft = _cachedColorRight = nil;
}

- (void)_buildFadeLayers {
  const CGFloat scale = [self _effectiveScale];
  if (_isMaskMode) {
    _maskLayer = [EdgeFadeMaskLayer layer];
    _maskLayer.contentsScale = scale;
    self.layer.mask = _maskLayer;
    [self _syncMaskLayer];
  } else {
    _overlayTop    = [self _makeGradientLayerWithScale:scale];
    _overlayBottom = [self _makeGradientLayerWithScale:scale];
    _overlayLeft   = [self _makeGradientLayerWithScale:scale];
    _overlayRight  = [self _makeGradientLayerWithScale:scale];
    [self _rebuildOverlayColors];
    [self _updateLayerFrames];
  }
}

// ── Mask mode ─────────────────────────────────────────────────────────────────

// Update the mask layer state and invalidate only the strips that actually
// changed. Each dirty rect spans MAX(old, new) so the previous fade extent is
// erased before the new gradient is drawn. CG sets the context clip to the
// dirty rect inside drawInContext: — the existing draw code restricts itself
// to that clip without any change.
- (void)_syncMaskLayer {
  if (!_maskLayer) return;

  const CGFloat oldTop    = _maskLayer.fadeTop;
  const CGFloat oldBottom = _maskLayer.fadeBottom;
  const CGFloat oldLeft   = _maskLayer.fadeLeft;
  const CGFloat oldRight  = _maskLayer.fadeRight;
  NSString *oldCurveTop    = _maskLayer.curveTop;
  NSString *oldCurveBottom = _maskLayer.curveBottom;
  NSString *oldCurveLeft   = _maskLayer.curveLeft;
  NSString *oldCurveRight  = _maskLayer.curveRight;

  _maskLayer.fadeTop    = _fadeTop;   _maskLayer.fadeBottom = _fadeBottom;
  _maskLayer.fadeLeft   = _fadeLeft;  _maskLayer.fadeRight  = _fadeRight;
  _maskLayer.curveTop   = _curveTop;  _maskLayer.curveBottom = _curveBottom;
  _maskLayer.curveLeft  = _curveLeft; _maskLayer.curveRight  = _curveRight;

  const CGFloat w = CGRectGetWidth(_maskLayer.bounds);
  const CGFloat h = CGRectGetHeight(_maskLayer.bounds);
  if (w <= 0 || h <= 0) {
    // No bounds yet — full invalidate; layoutSubviews will trigger the first draw.
    [_maskLayer setNeedsDisplay];
    return;
  }

  const BOOL topChanged    = oldTop    != _fadeTop    || ![oldCurveTop    isEqualToString:_curveTop];
  const BOOL bottomChanged = oldBottom != _fadeBottom || ![oldCurveBottom isEqualToString:_curveBottom];
  const BOOL leftChanged   = oldLeft   != _fadeLeft   || ![oldCurveLeft   isEqualToString:_curveLeft];
  const BOOL rightChanged  = oldRight  != _fadeRight  || ![oldCurveRight  isEqualToString:_curveRight];

  if (topChanged) {
    CGFloat extent = MAX(oldTop, _fadeTop);
    [_maskLayer setNeedsDisplayInRect:CGRectMake(0, 0, w, extent)];
  }
  if (bottomChanged) {
    CGFloat extent = MAX(oldBottom, _fadeBottom);
    [_maskLayer setNeedsDisplayInRect:CGRectMake(0, h - extent, w, extent)];
  }
  if (leftChanged) {
    CGFloat extent = MAX(oldLeft, _fadeLeft);
    [_maskLayer setNeedsDisplayInRect:CGRectMake(0, 0, extent, h)];
  }
  if (rightChanged) {
    CGFloat extent = MAX(oldRight, _fadeRight);
    [_maskLayer setNeedsDisplayInRect:CGRectMake(w - extent, 0, extent, h)];
  }
}

// ── Overlay mode ──────────────────────────────────────────────────────────────

- (CAGradientLayer *)_makeGradientLayerWithScale:(CGFloat)scale {
  CAGradientLayer *layer = [CAGradientLayer layer];
  layer.contentsScale = scale;
  [self.layer addSublayer:layer];
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
    // needsDisplayOnBoundsChange is YES — CA invalidates on bounds change
    // automatically. No explicit setNeedsDisplay needed for origin-only frame
    // shifts (the rendered bitmap is in layer-local coordinates).
    _maskLayer.frame = self.bounds;
    return;
  }

  if (!_overlayTop) return;

  [CATransaction begin];
  [CATransaction setDisableActions:YES];

  _overlayTop.frame      = CGRectMake(0, 0, w, _fadeTop);
  _overlayTop.startPoint = CGPointMake(0.5, 1); _overlayTop.endPoint = CGPointMake(0.5, 0);
  _overlayTop.hidden     = (_fadeTop <= 0);

  _overlayBottom.frame      = CGRectMake(0, h - _fadeBottom, w, _fadeBottom);
  _overlayBottom.startPoint = CGPointMake(0.5, 0); _overlayBottom.endPoint = CGPointMake(0.5, 1);
  _overlayBottom.hidden     = (_fadeBottom <= 0);

  _overlayLeft.frame      = CGRectMake(0, 0, _fadeLeft, h);
  _overlayLeft.startPoint = CGPointMake(1, 0.5); _overlayLeft.endPoint = CGPointMake(0, 0.5);
  _overlayLeft.hidden     = (_fadeLeft <= 0);

  _overlayRight.frame      = CGRectMake(w - _fadeRight, 0, _fadeRight, h);
  _overlayRight.startPoint = CGPointMake(0, 0.5); _overlayRight.endPoint = CGPointMake(1, 0.5);
  _overlayRight.hidden     = (_fadeRight <= 0);

  [CATransaction commit];
}

@end

Class<RCTComponentViewProtocol> EdgeFadeViewCls(void) { return EdgeFadeView.class; }
