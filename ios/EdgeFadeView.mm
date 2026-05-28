#import "EdgeFadeView.h"
#import "EdgeFadeCurves.h"
#import "EdgeFadeMaskLayer.h"

#import <React/RCTConversions.h>
#import <react/renderer/components/EdgeFadeViewSpec/ComponentDescriptors.h>
#import <react/renderer/components/EdgeFadeViewSpec/Props.h>
#import <react/renderer/components/EdgeFadeViewSpec/RCTComponentViewHelpers.h>
#import "RCTFabricComponentsPlugins.h"

using namespace facebook::react;

// ─── Overlay colors ───────────────────────────────────────────────────────────
//
// Builds the `CAGradientLayer.colors` array for the given curve and base color:
// transparent (inner, reversed curve) → color (outer). Allocates `CGColorRef`
// instances directly to skip the UIColor round-trip — roughly half the work of
// going through `[UIColor colorWithRed:...].CGColor` for a 32-stop curve.

static NSArray<id> *overlayColors(NSString *curve, UIColor *color)
{
  const CGFloat *alphas; const CGFloat *stops; size_t count;
  CGFloat *dynAlphas, *dynStops;
  EdgeFadeResolveCurve(curve, &alphas, &stops, &count, &dynAlphas, &dynStops);
  (void)stops; // overlay uses EdgeFadeLocationsForCurve(); stops are consumed by the mask path only.

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

// ─── EdgeFadeView ─────────────────────────────────────────────────────────────

// invalidateLayer is implemented in RCTViewComponentView but not exposed in any
// header. Forward-declare so the override below can call super.
@interface RCTViewComponentView (EdgeFadeInternal)
- (void)invalidateLayer;
@end

@implementation EdgeFadeView {
  // Mask mode
  EdgeFadeMaskLayer *_maskLayer;

  // Overlay mode — one CAGradientLayer per edge, attached directly to self.layer
  // (no intermediate container view) to avoid an extra compositing pass.
  CAGradientLayer *_overlayTop, *_overlayBottom, *_overlayLeft, *_overlayRight;

  // Per-layer color cache — avoid rebuilding colors on unrelated prop changes.
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
    // Continuous corner curve matches Apple's system squircle and composes more
    // cleanly with `layer.mask` than the default circular curve.
    if (@available(iOS 13.0, *)) {
      self.layer.cornerCurve = kCACornerCurveContinuous;
    }
  }
  return self;
}

// ─── Scale sync ──────────────────────────────────────────────────────────────
// Use the actual window's screen scale rather than `UIScreen.mainScreen` — the
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

// ─── Props update ────────────────────────────────────────────────────────────

- (void)updateProps:(Props::Shared const &)props oldProps:(Props::Shared const &)oldProps {
  const auto &p  = *std::static_pointer_cast<EdgeFadeViewProps const>(props);
  // `oldProps` may be null on the first updateProps call. `_props` is always
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
  const BOOL modeChanged   = p.mode       != op.mode;
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

  // Rebuild layers when the mode flips OR when the layer for the current mode
  // is still missing (first updateProps call — `_props` defaults don't trigger
  // a mode flip when the user picks the default mode).
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

// ─── Layout ──────────────────────────────────────────────────────────────────

- (void)layoutSubviews {
  [super layoutSubviews];
  [self _updateLayerFrames];
}

// RCTViewComponentView.invalidateLayer resets self.currentContainerView.layer.mask
// to nil during its border/clipping pipeline. Re-apply our mask after super has
// finished, otherwise mask mode never paints.
- (void)invalidateLayer {
  [super invalidateLayer];
  if (_isMaskMode && _maskLayer && self.layer.mask != _maskLayer) {
    self.layer.mask = _maskLayer;
  }
}

- (void)didAddSubview:(UIView *)subview {
  [super didAddSubview:subview];
  // Subview layers are appended to self.layer.sublayers and would otherwise sit
  // above our overlay gradient layers. Re-attaching with addSublayer: moves a
  // layer to the end of the sublayers array → back on top.
  if (!_isMaskMode && _overlayTop) {
    [self.layer addSublayer:_overlayTop];
    [self.layer addSublayer:_overlayBottom];
    [self.layer addSublayer:_overlayLeft];
    [self.layer addSublayer:_overlayRight];
  }
}

// ─── Fade layer management ───────────────────────────────────────────────────

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

// ─── Mask mode ───────────────────────────────────────────────────────────────

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
    // No bounds yet — full invalidate; layoutSubviews triggers the first draw.
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

// ─── Overlay mode ────────────────────────────────────────────────────────────

- (CAGradientLayer *)_makeGradientLayerWithScale:(CGFloat)scale {
  CAGradientLayer *layer = [CAGradientLayer layer];
  layer.contentsScale = scale;
  [self.layer addSublayer:layer];
  return layer;
}

// per-edge override → global overlayColor → opaque black fallback
- (UIColor *)_effectiveColorForEdge:(UIColor *)edgeColor {
  return edgeColor ?: _overlayColor ?: UIColor.blackColor;
}

// Rebuild gradient colors only when the relevant color or curve changed.
- (void)_rebuildOverlayColors {
  UIColor *cTop    = [self _effectiveColorForEdge:_overlayColorTop];
  UIColor *cBottom = [self _effectiveColorForEdge:_overlayColorBottom];
  UIColor *cLeft   = [self _effectiveColorForEdge:_overlayColorLeft];
  UIColor *cRight  = [self _effectiveColorForEdge:_overlayColorRight];

  if (![_curveTop isEqualToString:_cachedCurveTop] || ![cTop isEqual:_cachedColorTop]) {
    _overlayTop.colors    = overlayColors(_curveTop, cTop);
    _overlayTop.locations = EdgeFadeLocationsForCurve(_curveTop);
    _cachedCurveTop = _curveTop; _cachedColorTop = cTop;
  }
  if (![_curveBottom isEqualToString:_cachedCurveBottom] || ![cBottom isEqual:_cachedColorBottom]) {
    _overlayBottom.colors    = overlayColors(_curveBottom, cBottom);
    _overlayBottom.locations = EdgeFadeLocationsForCurve(_curveBottom);
    _cachedCurveBottom = _curveBottom; _cachedColorBottom = cBottom;
  }
  if (![_curveLeft isEqualToString:_cachedCurveLeft] || ![cLeft isEqual:_cachedColorLeft]) {
    _overlayLeft.colors    = overlayColors(_curveLeft, cLeft);
    _overlayLeft.locations = EdgeFadeLocationsForCurve(_curveLeft);
    _cachedCurveLeft = _curveLeft; _cachedColorLeft = cLeft;
  }
  if (![_curveRight isEqualToString:_cachedCurveRight] || ![cRight isEqual:_cachedColorRight]) {
    _overlayRight.colors    = overlayColors(_curveRight, cRight);
    _overlayRight.locations = EdgeFadeLocationsForCurve(_curveRight);
    _cachedCurveRight = _curveRight; _cachedColorRight = cRight;
  }
}

// ─── Frame sync ──────────────────────────────────────────────────────────────

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
