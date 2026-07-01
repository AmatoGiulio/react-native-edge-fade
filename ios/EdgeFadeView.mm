#import "EdgeFadeView.h"
#import "EdgeFadeCurves.h"
#import "EdgeFadeMaskLayer.h"
#import "EdgeFadeBlurMaskLayer.h"

#import <React/RCTConversions.h>
#import <react/renderer/components/EdgeFadeViewSpec/ComponentDescriptors.h>
#import <react/renderer/components/EdgeFadeViewSpec/Props.h>
#import <react/renderer/components/EdgeFadeViewSpec/RCTComponentViewHelpers.h>
#import "RCTFabricComponentsPlugins.h"

using namespace facebook::react;

// ─── Render mode enum ────────────────────────────────────────────────────────

typedef NS_ENUM(NSInteger, EdgeFadeRenderMode) {
  EdgeFadeModeMask,    // default — alpha mask on self.layer
  EdgeFadeModeOverlay, // painted gradient strips above content
  EdgeFadeModeBlur,    // UIVisualEffectView blurred, edge-masked, optional veil
};

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

// ─── Veil colors ─────────────────────────────────────────────────────────────
//
// Builds `CAGradientLayer.colors` for a frost veil strip: transparent (inner) →
// `color` capped at VEIL_MAX_ALPHA (outer), following the curve profile. Matches
// Android's veilGradient / VEIL_MAX_ALPHA = 0.8.

static const CGFloat kVeilMaxAlpha = 0.8;

static NSArray<id> *veilColors(NSString *curve, UIColor *color)
{
  const CGFloat *alphas; const CGFloat *stops; size_t count;
  CGFloat *dynAlphas, *dynStops;
  EdgeFadeResolveCurve(curve, &alphas, &stops, &count, &dynAlphas, &dynStops);
  (void)stops;

  CGFloat r, g, b, a;
  [color getRed:&r green:&g blue:&b alpha:&a];

  CGColorSpaceRef space = CGColorSpaceCreateDeviceRGB();
  NSMutableArray *result = [NSMutableArray arrayWithCapacity:count];
  for (NSInteger i = (NSInteger)count - 1; i >= 0; i--) {
    // Each stop's alpha = curve_alpha * base_alpha * VEIL_MAX_ALPHA.
    // Cap so even the outer edge stays slightly translucent — a hint of blurred
    // content shows through, like iOS frosted material.
    CGFloat components[4] = {r, g, b, a * alphas[i] * kVeilMaxAlpha};
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

  // Blur mode — UIVisualEffectView covering bounds, masked by EdgeFadeBlurMaskLayer,
  // driven by a paused UIViewPropertyAnimator to allow fractional blur intensity.
  UIVisualEffectView    *_blurView;
  UIViewPropertyAnimator *_blurAnimator;
  EdgeFadeBlurMaskLayer *_blurMaskLayer;

  // Frost veil — optional per-edge CAGradientLayers on top of _blurView,
  // painted only when _overlayColor is set (opt-in, replicating Android behavior).
  CAGradientLayer *_frostTop, *_frostBottom, *_frostLeft, *_frostRight;

  // Per-layer color cache — avoid rebuilding colors on unrelated prop changes.
  NSString *_cachedCurveTop, *_cachedCurveBottom, *_cachedCurveLeft, *_cachedCurveRight;
  UIColor  *_cachedColorTop, *_cachedColorBottom, *_cachedColorLeft, *_cachedColorRight;

  // Per-frost-layer veil color cache.
  NSString *_cachedVeilCurveTop, *_cachedVeilCurveBottom, *_cachedVeilCurveLeft, *_cachedVeilCurveRight;
  UIColor  *_cachedVeilColorTop, *_cachedVeilColorBottom, *_cachedVeilColorLeft, *_cachedVeilColorRight;

  // Current config
  EdgeFadeRenderMode _renderMode;
  CGFloat   _fadeTop, _fadeBottom, _fadeLeft, _fadeRight;
  NSString *_curveTop, *_curveBottom, *_curveLeft, *_curveRight;
  UIColor  *_overlayColor;
  UIColor  *_overlayColorTop, *_overlayColorBottom, *_overlayColorLeft, *_overlayColorRight;
  CGFloat   _fadeRadius;
  CGFloat   _blurRadius;
}

+ (ComponentDescriptorProvider)componentDescriptorProvider {
  return concreteComponentDescriptorProvider<EdgeFadeViewComponentDescriptor>();
}

- (instancetype)initWithFrame:(CGRect)frame {
  if (self = [super initWithFrame:frame]) {
    static const auto defaultProps = std::make_shared<const EdgeFadeViewProps>();
    _props = defaultProps;
    _renderMode = EdgeFadeModeMask;
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
  if (_blurMaskLayer && _blurMaskLayer.contentsScale != scale) {
    _blurMaskLayer.contentsScale = scale;
    [_blurMaskLayer setNeedsDisplay];
  }
  if (_frostTop) {
    _frostTop.contentsScale = _frostBottom.contentsScale =
    _frostLeft.contentsScale = _frostRight.contentsScale = scale;
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
  const BOOL modeChanged        = p.mode       != op.mode;
  const BOOL radiusChanged      = p.fadeRadius != op.fadeRadius;
  const BOOL blurRadiusChanged  = p.blurRadius != op.blurRadius;

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
  _blurRadius = (CGFloat)p.blurRadius;

  // Resolve the new render mode.
  NSString *modeStr = [NSString stringWithUTF8String:p.mode.c_str()];
  EdgeFadeRenderMode newMode;
  if ([@"overlay" isEqualToString:modeStr])    newMode = EdgeFadeModeOverlay;
  else if ([@"blur" isEqualToString:modeStr])  newMode = EdgeFadeModeBlur;
  else                                         newMode = EdgeFadeModeMask;

  // Rebuild layers when the mode flips OR when the layer for the current mode
  // is still missing (first updateProps call — `_props` defaults don't trigger
  // a mode flip when the user picks the default mode).
  BOOL layerMissing;
  switch (newMode) {
    case EdgeFadeModeMask:    layerMissing = (_maskLayer == nil);  break;
    case EdgeFadeModeOverlay: layerMissing = (_overlayTop == nil); break;
    case EdgeFadeModeBlur:    layerMissing = (_blurView == nil);   break;
    default:                  layerMissing = NO;                   break;
  }

  if ((modeChanged && newMode != _renderMode) || layerMissing) {
    _renderMode = newMode;
    [self _teardownFadeLayers];
    [self _buildFadeLayers];
  } else if (_renderMode == EdgeFadeModeMask) {
    if (sizeChanged || curveChanged) [self _syncMaskLayer];
  } else if (_renderMode == EdgeFadeModeOverlay) {
    if (colorChanged || curveChanged) [self _rebuildOverlayColors];
    if (sizeChanged) [self _updateLayerFrames];
  } else {
    // Blur mode — incremental updates.
    if (sizeChanged || curveChanged) [self _syncBlurMaskLayer];
    if (sizeChanged) {
      [self _updateLayerFrames];
      [_blurMaskLayer setNeedsDisplay];
    }
    if (curveChanged) {
      [_blurMaskLayer setNeedsDisplay];
      if (_overlayColor) [self _rebuildVeilColors];
    }
    if (colorChanged) {
      if (_overlayColor) {
        // Ensure frost veil layers exist if color just turned on.
        if (!_frostTop) [self _buildFrostVeil];
        else            [self _rebuildVeilColors];
      } else {
        // color turned off — tear down the veil.
        [self _teardownFrostVeil];
      }
    }
    if (blurRadiusChanged) {
      [self _applyBlurFraction];
    }
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
  if (_renderMode == EdgeFadeModeMask && _maskLayer && self.layer.mask != _maskLayer) {
    self.layer.mask = _maskLayer;
  }
}

- (void)didAddSubview:(UIView *)subview {
  [super didAddSubview:subview];
  // Subview layers are appended to self.layer.sublayers and would otherwise sit
  // above our overlay / blur layers. Re-attaching with addSublayer: / addSubview:
  // moves the layer to the end of the sublayers array → back on top.
  if (_renderMode == EdgeFadeModeOverlay && _overlayTop) {
    [self.layer addSublayer:_overlayTop];
    [self.layer addSublayer:_overlayBottom];
    [self.layer addSublayer:_overlayLeft];
    [self.layer addSublayer:_overlayRight];
  } else if (_renderMode == EdgeFadeModeBlur && _blurView && subview != _blurView) {
    // Keep _blurView (and frost veil layers on its superlayer) above all content.
    [self addSubview:_blurView];
    if (_frostTop) {
      [self.layer addSublayer:_frostTop];
      [self.layer addSublayer:_frostBottom];
      [self.layer addSublayer:_frostLeft];
      [self.layer addSublayer:_frostRight];
    }
  }
}

// ─── Fade layer management ───────────────────────────────────────────────────

- (void)_teardownFadeLayers {
  // Mask mode
  self.layer.mask = nil;
  _maskLayer = nil;

  // Overlay mode
  [_overlayTop removeFromSuperlayer];
  [_overlayBottom removeFromSuperlayer];
  [_overlayLeft removeFromSuperlayer];
  [_overlayRight removeFromSuperlayer];
  _overlayTop = _overlayBottom = _overlayLeft = _overlayRight = nil;
  _cachedCurveTop = _cachedCurveBottom = _cachedCurveLeft = _cachedCurveRight = nil;
  _cachedColorTop = _cachedColorBottom = _cachedColorLeft = _cachedColorRight = nil;

  // Blur mode — frost veil first, then animator, then blur view.
  [self _teardownFrostVeil];

  if (_blurAnimator) {
    _blurAnimator.fractionComplete = 0;
    [_blurAnimator stopAnimation:YES];
    _blurAnimator = nil;
  }
  [_blurView removeFromSuperview];
  _blurView = nil;
  _blurMaskLayer = nil;
}

- (void)_buildFadeLayers {
  const CGFloat scale = [self _effectiveScale];
  if (_renderMode == EdgeFadeModeMask) {
    _maskLayer = [EdgeFadeMaskLayer layer];
    _maskLayer.contentsScale = scale;
    self.layer.mask = _maskLayer;
    [self _syncMaskLayer];
  } else if (_renderMode == EdgeFadeModeOverlay) {
    _overlayTop    = [self _makeGradientLayerWithScale:scale];
    _overlayBottom = [self _makeGradientLayerWithScale:scale];
    _overlayLeft   = [self _makeGradientLayerWithScale:scale];
    _overlayRight  = [self _makeGradientLayerWithScale:scale];
    [self _rebuildOverlayColors];
    [self _updateLayerFrames];
  } else {
    // Blur mode — build blur view + mask, then optionally the frost veil.
    [self _buildBlurView];
    if (_overlayColor) [self _buildFrostVeil];
    [self _updateLayerFrames];
    [self _applyBlurFraction];
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

// ─── Blur mode ───────────────────────────────────────────────────────────────

// _blurMaskLayer's fade/curve properties are only ever set here — the layer
// itself never reads _fadeTop/_curveTop etc. directly — so without this,
// property changes after the initial build would leave the mask stale and
// the fade sliders / curve chips would have no visible effect. Callers are
// responsible for invalidating (setNeedsDisplay) after syncing.
- (void)_syncBlurMaskLayer {
  if (!_blurMaskLayer) return;
  _blurMaskLayer.fadeTop    = _fadeTop;   _blurMaskLayer.fadeBottom = _fadeBottom;
  _blurMaskLayer.fadeLeft   = _fadeLeft;  _blurMaskLayer.fadeRight  = _fadeRight;
  _blurMaskLayer.curveTop   = _curveTop;  _blurMaskLayer.curveBottom = _curveBottom;
  _blurMaskLayer.curveLeft  = _curveLeft; _blurMaskLayer.curveRight  = _curveRight;
}

// Build the UIVisualEffectView + paused animator + EdgeFadeBlurMaskLayer.
// The blur view is inserted as a subview so RN's layout system ignores it, while
// the mask layer is assigned to _blurView.layer.mask.
- (void)_buildBlurView {
  const CGFloat scale = [self _effectiveScale];

  // Blur mask layer — grayscale bitmap that gates blur visibility per edge.
  _blurMaskLayer = [EdgeFadeBlurMaskLayer layer];
  _blurMaskLayer.contentsScale = scale;
  [self _syncBlurMaskLayer];

  // Create the blur view with nil effect; effect is driven by the animator below.
  _blurView = [[UIVisualEffectView alloc] initWithEffect:nil];
  _blurView.userInteractionEnabled = NO;
  _blurView.layer.mask = _blurMaskLayer;

  [self addSubview:_blurView];

  // Paused UIViewPropertyAnimator trick: set fractionComplete to drive blur
  // intensity without animating. Must retain the animator — paused animators
  // dealloc mid-flight if released, producing a visual glitch.
  //
  // Style is unified to UIBlurEffectStyleRegular on every OS version — the
  // `.systemXThinMaterial` family is not a pure gaussian blur: UIVisualEffectView
  // layers extra tint/luminosity subviews on top of the backdrop blur to match
  // system chrome, and at partial fractionComplete those subviews still show
  // through as a milky white glow, even with the frost veil disabled. `.regular`
  // gets us the closest thing to a plain backdrop blur (à la Apple Music's
  // progressive blur), which _stripVisualEffectTint then cleans up further below.
  __weak UIVisualEffectView *weakBlurView = _blurView;
  UIBlurEffect *effect = [UIBlurEffect effectWithStyle:UIBlurEffectStyleRegular];
  _blurAnimator = [[UIViewPropertyAnimator alloc] initWithDuration:1
                                                             curve:UIViewAnimationCurveLinear
                                                        animations:^{
    weakBlurView.effect = effect;
  }];
  _blurAnimator.pausesOnCompletion = YES;

  // A UIViewPropertyAnimator is `.inactive` after init; setting fractionComplete
  // on an inactive animator is a no-op. Start then immediately pause to move it
  // to the paused/active state so fractionComplete scrubbing takes effect, then
  // apply the initial blur intensity.
  [_blurAnimator startAnimation];
  [_blurAnimator pauseAnimation];

  // The effect's tint/luminosity subviews are only instantiated once the effect
  // has actually been applied to the view, which just happened above.
  [self _stripVisualEffectTint];
  [self _applyBlurFraction];
}

// UIVisualEffectView composes its blur effect out of several private subviews
// (backdrop blur + tint + luminosity), stacked to approximate system materials.
// The tint/luminosity layers are what produces the milky glow at partial
// intensity — hiding everything except the backdrop blur subview leaves a pure
// gaussian blur behind. This only inspects public class-name strings (no
// NSClassFromString, no KVC on private keys), so it stays within documented,
// App Store-safe introspection.
- (void)_stripVisualEffectTint {
  for (UIView *subview in _blurView.subviews) {
    subview.hidden = ![NSStringFromClass(subview.class) containsString:@"Backdrop"];
  }
}

// Expose _blurView as a property so the animator block can reference it via
// the weak self pattern without triggering a "direct ivar access in block" warning.
- (UIVisualEffectView *)blurView { return _blurView; }

// Map blurRadius (default 28, range 0–∞) to a fraction in [0, 1] for the
// animator. 40 pt radius → fraction 1.0 (full effect); default 28 → ~0.7.
- (void)_applyBlurFraction {
  if (!_blurAnimator) return;
  // UIKit can re-instantiate the effect's tint/luminosity subviews whenever it
  // re-applies the effect (e.g. after a fractionComplete scrub), so re-strip on
  // every call. The subview list is short (2-3 entries), so this is cheap.
  [self _stripVisualEffectTint];
  const CGFloat fraction = MIN(MAX(_blurRadius / 40.0, 0.0), 1.0);
  _blurAnimator.fractionComplete = fraction;
}

// ─── Frost veil (blur mode only) ─────────────────────────────────────────────
//
// Four CAGradientLayers on top of _blurView's layer, one per edge, transparent
// (inner) → overlayColor (outer, capped at VEIL_MAX_ALPHA). Opt-in: only created
// when _overlayColor is non-nil, replicating Android's drawFrostVeil behavior.

- (void)_buildFrostVeil {
  if (_frostTop) return; // already built
  const CGFloat scale = [self _effectiveScale];

  _frostTop    = [self _makeFrostLayerWithScale:scale];
  _frostBottom = [self _makeFrostLayerWithScale:scale];
  _frostLeft   = [self _makeFrostLayerWithScale:scale];
  _frostRight  = [self _makeFrostLayerWithScale:scale];

  [self _rebuildVeilColors];
  [self _updateLayerFrames];
}

- (CAGradientLayer *)_makeFrostLayerWithScale:(CGFloat)scale {
  CAGradientLayer *layer = [CAGradientLayer layer];
  layer.contentsScale = scale;
  [self.layer addSublayer:layer];
  return layer;
}

- (void)_teardownFrostVeil {
  [_frostTop    removeFromSuperlayer];
  [_frostBottom removeFromSuperlayer];
  [_frostLeft   removeFromSuperlayer];
  [_frostRight  removeFromSuperlayer];
  _frostTop = _frostBottom = _frostLeft = _frostRight = nil;
  _cachedVeilCurveTop = _cachedVeilCurveBottom = _cachedVeilCurveLeft = _cachedVeilCurveRight = nil;
  _cachedVeilColorTop = _cachedVeilColorBottom = _cachedVeilColorLeft = _cachedVeilColorRight = nil;
}

- (void)_rebuildVeilColors {
  if (!_frostTop || !_overlayColor) return;
  UIColor *color = _overlayColor;

  if (![_curveTop isEqualToString:_cachedVeilCurveTop] || ![color isEqual:_cachedVeilColorTop]) {
    _frostTop.colors    = veilColors(_curveTop, color);
    _frostTop.locations = EdgeFadeLocationsForCurve(_curveTop);
    _cachedVeilCurveTop = _curveTop; _cachedVeilColorTop = color;
  }
  if (![_curveBottom isEqualToString:_cachedVeilCurveBottom] || ![color isEqual:_cachedVeilColorBottom]) {
    _frostBottom.colors    = veilColors(_curveBottom, color);
    _frostBottom.locations = EdgeFadeLocationsForCurve(_curveBottom);
    _cachedVeilCurveBottom = _curveBottom; _cachedVeilColorBottom = color;
  }
  if (![_curveLeft isEqualToString:_cachedVeilCurveLeft] || ![color isEqual:_cachedVeilColorLeft]) {
    _frostLeft.colors    = veilColors(_curveLeft, color);
    _frostLeft.locations = EdgeFadeLocationsForCurve(_curveLeft);
    _cachedVeilCurveLeft = _curveLeft; _cachedVeilColorLeft = color;
  }
  if (![_curveRight isEqualToString:_cachedVeilCurveRight] || ![color isEqual:_cachedVeilColorRight]) {
    _frostRight.colors    = veilColors(_curveRight, color);
    _frostRight.locations = EdgeFadeLocationsForCurve(_curveRight);
    _cachedVeilCurveRight = _curveRight; _cachedVeilColorRight = color;
  }
}

// ─── Frame sync ──────────────────────────────────────────────────────────────

- (void)_updateLayerFrames {
  const CGFloat w = CGRectGetWidth(self.bounds);
  const CGFloat h = CGRectGetHeight(self.bounds);

  if (_renderMode == EdgeFadeModeMask && _maskLayer) {
    // needsDisplayOnBoundsChange is YES — CA invalidates on bounds change
    // automatically. No explicit setNeedsDisplay needed for origin-only frame
    // shifts (the rendered bitmap is in layer-local coordinates).
    _maskLayer.frame = self.bounds;
    return;
  }

  if (_renderMode == EdgeFadeModeBlur) {
    if (!_blurView) return;
    [CATransaction begin];
    [CATransaction setDisableActions:YES];

    _blurView.frame      = self.bounds;
    _blurMaskLayer.frame = _blurView.bounds;

    // Frost veil layers: same frame/startPoint/endPoint as overlay strips.
    if (_frostTop) {
      _frostTop.frame      = CGRectMake(0, 0, w, _fadeTop);
      _frostTop.startPoint = CGPointMake(0.5, 1); _frostTop.endPoint = CGPointMake(0.5, 0);
      _frostTop.hidden     = (_fadeTop <= 0);

      _frostBottom.frame      = CGRectMake(0, h - _fadeBottom, w, _fadeBottom);
      _frostBottom.startPoint = CGPointMake(0.5, 0); _frostBottom.endPoint = CGPointMake(0.5, 1);
      _frostBottom.hidden     = (_fadeBottom <= 0);

      _frostLeft.frame      = CGRectMake(0, 0, _fadeLeft, h);
      _frostLeft.startPoint = CGPointMake(1, 0.5); _frostLeft.endPoint = CGPointMake(0, 0.5);
      _frostLeft.hidden     = (_fadeLeft <= 0);

      _frostRight.frame      = CGRectMake(w - _fadeRight, 0, _fadeRight, h);
      _frostRight.startPoint = CGPointMake(0, 0.5); _frostRight.endPoint = CGPointMake(1, 0.5);
      _frostRight.hidden     = (_fadeRight <= 0);
    }

    [CATransaction commit];
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
