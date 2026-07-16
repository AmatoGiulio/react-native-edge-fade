#import "EdgeFadeView.h"
#import "EdgeFadeCurves.h"
#import "EdgeFadeMaskLayer.h"
#import "EdgeFadeBlurMaskLayer.h"

#import <React/RCTConversions.h>


// ─── Lightweight os_log timing helpers ────────────────────────────────────
// Usage:  EF_BENCH_START;  ...work...  EF_BENCH_LOG("my_tag");
// Read from host: log stream --predicate 'subsystem == "com.edgefade.bench"'

#define EF_BENCH_START() \
  CFAbsoluteTime _ef_bench_t0 = CFAbsoluteTimeGetCurrent()

#define EF_BENCH_LOG(tag) do { \
  CFAbsoluteTime _now = CFAbsoluteTimeGetCurrent(); \
  double _el_us = (_now - _ef_bench_t0) * 1000000.0; \
  _ef_bench_t0 = _now; \
  static NSString *_ef_path; \
  static dispatch_once_t _ef_once; \
  dispatch_once(&_ef_once, ^{ \
    NSString *dir = [NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES) firstObject]; \
    _ef_path = [[dir stringByAppendingPathComponent:@"edgefade_bench.csv"] copy]; \
  }); \
  if (_ef_path) { \
    int _ef_fd = open(_ef_path.UTF8String, O_WRONLY | O_CREAT | O_APPEND, 0644); \
    if (_ef_fd >= 0) { \
      char _ef_buf[128]; \
      int _ef_n = snprintf(_ef_buf, sizeof(_ef_buf), "%s,%.0f\n", (tag), _el_us); \
      if (_ef_n > 0) write(_ef_fd, _ef_buf, MIN((size_t)_ef_n, sizeof(_ef_buf))); \
      close(_ef_fd); \
    } \
  } \
} while(0)
// ───────────────────────────────────────────────────────────────────────────
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

// ─── Progressive-blur model (blur mode) ───────────────────────────────────────
//
// True progressive blur (Apple Music) is not one blur cross-faded — it is a
// stack of increasing-radius blurs, each visible only over its own slice of the
// band. We model presence(t) = 1 − alpha(t) (the curve's own complement, rising
// inner → outer) and cut the presence range into three fixed fractions
//   F = { 1/3, 2/3, 1 }.
// Level k (0-based) owns the slice [F[k-1], F[k]] (F[-1] = 0) and its mask weight
// is weight_k(t) = clamp((presence(t) − lo) / (hi − lo), 0, 1), staying 1 past
// hi so the next-higher level cross-fades over it. The three views stack in
// increasing radius: the sharp content shows through where all weights are 0
// (band interior), a light blur near the inner ramp, the heaviest blur at the
// outer edge — a smooth radius ramp instead of a single hard blur.
//
// Per-level blur *intensity* is tied to _blurRadius scaled by F[k]: level 0
// tops out at a third of the radius, level 2 at the full radius, so at
// blurRadius = 0 every level's fraction is 0 and the stack is visually neutral.
//
// ── Band clipping (cost) ──
// A UIVisualEffectView's backdrop blur is a per-frame gaussian sample of
// everything behind it, and its cost scales with the *area* of the view. The
// fade only ever occupies thin strips along the edges, so covering the whole
// bounds with each effect view wastes the backdrop pass over the (sharp,
// weight-0) interior. Instead of 3 full-bounds views we render a matrix of
// per-edge × per-level effect views, each clipped to just its edge's fade strip
// (expanded by an inner padding, below). Total blurred area drops from
//   3·w·h   to   Σ_edge (fade_edge + p)·(strip length),
// roughly −70% for typical fade sizes — the backdrop cost tracks the shrunken
// area. Views for an edge whose fade is 0 are `hidden` and cost nothing:
// CoreAnimation does not composite a hidden layer, so it never triggers a
// backdrop pass (a hidden UIVisualEffectView is free).
//
// ── Inner padding p_k ──
// The backdrop blur samples only pixels *inside* the effect view and clamps at
// the view's edge. A strip sized exactly to `fade_edge` would show a hard seam
// at its inner border where the clamp meets the sharp interior. We over-extend
// each strip inward by p_k = _blurRadius · F[k] (clamped to bounds) so the blur
// has real content to sample across the seam. The mask weight is 0 throughout
// that padding (the ramp only occupies the outer `fade_edge` pt), so no extra
// blur becomes visible — the padding exists purely to feed the sampler.
//
// ── Corners (tradeoff) ──
// Where two edges' fades overlap (a corner) their two per-edge stacks composite
// independently. The result is ~the stronger of the two contributions rather
// than an exact 2-D blend, which is visually indistinguishable in practice and
// accepted for the large area win.

static const NSInteger kEdgeFadeBlurLevels = 3;

// Edge indices into the per-edge × per-level matrices below.
typedef NS_ENUM(NSInteger, EdgeFadeEdge) {
  EdgeFadeEdgeTop = 0,
  EdgeFadeEdgeBottom = 1,
  EdgeFadeEdgeLeft = 2,
  EdgeFadeEdgeRight = 3,
};
static const NSInteger kEdgeFadeEdgeCount = 4;

// Upper presence boundary F[k] for each level; lower boundary is F[k-1] (F[-1]=0).
static const CGFloat kEdgeFadeLevelFractions[kEdgeFadeBlurLevels] = {1.0 / 3.0, 2.0 / 3.0, 1.0};

// Convenience: window [lo, hi] for level k.
static inline CGFloat EdgeFadeLevelLo(NSInteger k) { return k == 0 ? 0.0 : kEdgeFadeLevelFractions[k - 1]; }
static inline CGFloat EdgeFadeLevelHi(NSInteger k) { return kEdgeFadeLevelFractions[k]; }

// ─── Overlay colors ───────────────────────────────────────────────────────────
//
// Builds the `CAGradientLayer.colors` array for the given curve and base color:
// transparent (inner) → color (outer), opacity(t) = 1 − alpha(t). Allocates
// `CGColorRef` instances directly to skip the UIColor round-trip — roughly half
// the work of going through `[UIColor colorWithRed:...].CGColor` for a 32-stop
// curve. Stops run inner (i=0) → outer (i=count-1), matching the gradient's
// startPoint/endPoint set in _updateLayerFrames, so a forward loop suffices.

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
  for (NSInteger i = 0; i < (NSInteger)count; i++) {
    CGFloat components[4] = {r, g, b, a * (1.0 - alphas[i])};
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
// `color` capped at VEIL_MAX_ALPHA (outer), opacity(t) = (1 − alpha(t)) ·
// VEIL_MAX_ALPHA. Matches Android's veilGradient / VEIL_MAX_ALPHA = 0.8. Stops
// run inner (i=0) → outer (i=count-1), matching the gradient's startPoint/
// endPoint set in _updateLayerFrames, so a forward loop suffices.

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
  for (NSInteger i = 0; i < (NSInteger)count; i++) {
    // Each stop's alpha = (1 - curve_alpha) * base_alpha * VEIL_MAX_ALPHA.
    // Cap so even the outer edge stays slightly translucent — a hint of blurred
    // content shows through, like iOS frosted material.
    CGFloat components[4] = {r, g, b, a * (1.0 - alphas[i]) * kVeilMaxAlpha};
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

  // Blur mode — a per-edge × per-level matrix of progressive-blur strips
  // (Apple-Music style). For each of the 4 edges (top/bottom/left/right) there is
  // a 3-level stack; each cell is a UIVisualEffectView clipped to that edge's fade
  // strip (see the band-clipping note above), masked by its own windowed
  // EdgeFadeBlurMaskLayer, driven by a paused UIViewPropertyAnimator for
  // fractional intensity. Level 0 (smallest radius, presence window [0,1/3]) sits
  // lowest within an edge; level 2 (largest, [2/3,1]) sits on top. All 12 views
  // exist for the lifetime of the build; an edge whose fade is 0 has its views
  // `hidden` (a hidden effect view triggers no backdrop pass). Fixed C matrices
  // rather than NSArray: the counts are compile-time constants (4×3), the elements
  // are strong-held ivars anyway, and index access keeps the loops terse.
  UIVisualEffectView     *_blurViews[kEdgeFadeEdgeCount][kEdgeFadeBlurLevels];
  UIViewPropertyAnimator *_blurAnimators[kEdgeFadeEdgeCount][kEdgeFadeBlurLevels];
  EdgeFadeBlurMaskLayer  *_blurMaskLayers[kEdgeFadeEdgeCount][kEdgeFadeBlurLevels];

  // Frost veil — optional per-edge CAGradientLayers on top of the blur stack,
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
  for (NSInteger e = 0; e < kEdgeFadeEdgeCount; e++) {
    for (NSInteger k = 0; k < kEdgeFadeBlurLevels; k++) {
      EdgeFadeBlurMaskLayer *m = _blurMaskLayers[e][k];
      if (m && m.contentsScale != scale) {
        m.contentsScale = scale;
        [m setNeedsDisplay];
      }
    }
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
  EF_BENCH_START();
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
  EF_BENCH_LOG("up_diff");

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
    case EdgeFadeModeBlur:    layerMissing = (_blurViews[0][0] == nil); break;
    default:                  layerMissing = NO;                   break;
  }
  EF_BENCH_LOG("up_switch");

  if ((modeChanged && newMode != _renderMode) || layerMissing) {
    _renderMode = newMode;
    [self _teardownFadeLayers];
    EF_BENCH_LOG("up_teardown");
    [self _buildFadeLayers];
    EF_BENCH_LOG("up_build");
  } else if (_renderMode == EdgeFadeModeMask) {
    if (sizeChanged || curveChanged) [self _syncMaskLayer];
    EF_BENCH_LOG("up_mask");
  } else if (_renderMode == EdgeFadeModeOverlay) {
    if (colorChanged || curveChanged) [self _rebuildOverlayColors];
    EF_BENCH_LOG("up_overlay_color");
    if (sizeChanged) [self _updateLayerFrames];
    EF_BENCH_LOG("up_overlay_size");
  } else {
    // Blur mode — incremental updates.
    if (sizeChanged || curveChanged) [self _syncBlurMaskLayers];
    EF_BENCH_LOG("up_blur_sync");
    if (sizeChanged) {
      [self _updateLayerFrames];
      [self _invalidateBlurMaskLayers];
    }
    EF_BENCH_LOG("up_blur_size");
    if (curveChanged) {
      [self _invalidateBlurMaskLayers];
      if (_overlayColor) [self _rebuildVeilColors];
    }
    EF_BENCH_LOG("up_blur_curve");
    if (colorChanged) {
      if (_overlayColor) {
        if (!_frostTop) [self _buildFrostVeil];
        else            [self _rebuildVeilColors];
      } else {
        [self _teardownFrostVeil];
      }
    }
    EF_BENCH_LOG("up_blur_color");
    if (blurRadiusChanged) {
      [self _updateLayerFrames];
      [self _applyBlurFraction];
    }
    EF_BENCH_LOG("up_blur_radius");
  }

  if (radiusChanged) {
    _fadeRadius = (CGFloat)p.fadeRadius;
    self.layer.cornerRadius  = _fadeRadius;
    self.layer.masksToBounds = (_fadeRadius > 0);
  }
  EF_BENCH_LOG("up_radius");

  [super updateProps:props oldProps:oldProps];
  EF_BENCH_LOG("updateProps");
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
  } else if (_renderMode == EdgeFadeModeBlur && _blurViews[0][0] && ![self _isBlurView:subview]) {
    // Keep all blur strips (and frost veil layers on their superlayer) above
    // content. Re-add edge by edge, in level order so radius stacks low → high
    // (0 under 2) within each edge. Ordering *between* edges is indifferent —
    // where two edges overlap at a corner their stacks composite independently.
    for (NSInteger e = 0; e < kEdgeFadeEdgeCount; e++) {
      for (NSInteger k = 0; k < kEdgeFadeBlurLevels; k++) {
        if (_blurViews[e][k]) [self addSubview:_blurViews[e][k]];
      }
    }
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

  // Blur mode — frost veil first, then each level's animator + view + mask.
  [self _teardownFrostVeil];

  [self _neutralizeBlurAnimators];
  for (NSInteger e = 0; e < kEdgeFadeEdgeCount; e++) {
    for (NSInteger k = 0; k < kEdgeFadeBlurLevels; k++) {
      [_blurViews[e][k] removeFromSuperview];
      _blurViews[e][k] = nil;
      _blurMaskLayers[e][k] = nil;
    }
  }
}

// Stop + finish every paused blur animator before it can be released — a
// UIViewPropertyAnimator deallocated while active/paused raises an
// NSException (rdar:// FB11963912; reproduced on RN dev reload, where the
// view deallocs without a mode flip ever running _teardownFadeLayers).
// The legal sequence is stopAnimation:NO (→ .stopped) followed by
// finishAnimationAtPosition: — finish on an animator stopped with
// `withoutFinishing:YES` (→ .inactive) itself raises.
// An `.inactive` animator needs neither call, so it is skipped.
- (void)_neutralizeBlurAnimators {
  for (NSInteger e = 0; e < kEdgeFadeEdgeCount; e++) {
    for (NSInteger k = 0; k < kEdgeFadeBlurLevels; k++) {
      UIViewPropertyAnimator *animator = _blurAnimators[e][k];
      if (animator != nil) {
        if (animator.state == UIViewAnimatingStateActive) {
          [animator stopAnimation:NO];
        }
        if (animator.state == UIViewAnimatingStateStopped) {
          [animator finishAnimationAtPosition:UIViewAnimatingPositionCurrent];
        }
        _blurAnimators[e][k] = nil;
      }
    }
  }
}

- (void)dealloc {
  [self _neutralizeBlurAnimators];
}

- (void)_buildFadeLayers {
  EF_BENCH_START();
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
    EF_BENCH_LOG("bld_buildBlurView");
    if (_overlayColor) [self _buildFrostVeil];
    EF_BENCH_LOG("bld_buildFrostVeil");
    [self _updateLayerFrames];
    EF_BENCH_LOG("bld_updateLayerFrames");
    [self _applyBlurFraction];
    EF_BENCH_LOG("bld_applyBlurFraction");
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

// YES if `view` is one of the blur-level effect views. Used by didAddSubview: to
// avoid re-adding a blur view in response to its own insertion.
- (BOOL)_isBlurView:(UIView *)view {
  for (NSInteger e = 0; e < kEdgeFadeEdgeCount; e++) {
    for (NSInteger k = 0; k < kEdgeFadeBlurLevels; k++) {
      if (view == _blurViews[e][k]) return YES;
    }
  }
  return NO;
}

// Each mask layer's fade/curve properties are only ever set here — the layers
// themselves never read _fadeTop/_curveTop etc. directly — so without this,
// property changes after the initial build would leave the masks stale and
// the fade sliders / curve chips would have no visible effect. levelLo/levelHi
// are set once at build time and never touched here (they define the fixed
// presence window of each level). Callers invalidate via _invalidateBlurMaskLayers.
//
// Per-edge masks: each strip's mask draws in the STRIP's local coordinates and
// carries ONLY its own edge's fade (the other three are 0), so the mask paints a
// single ramp anchored to the strip's outer edge and leaves the inner padding
// transparent (weight 0). The blur-mask layer already anchors each edge's ramp
// to the matching side of its own bounds (top ramp to the top, bottom ramp to
// the bottom, etc.), so setting fade<edge> = _fade<edge> produces the correct
// ramp regardless of where the strip sits inside the view.
- (void)_syncBlurMaskLayers {
  EF_BENCH_START();
  for (NSInteger e = 0; e < kEdgeFadeEdgeCount; e++) {
    for (NSInteger k = 0; k < kEdgeFadeBlurLevels; k++) {
      EdgeFadeBlurMaskLayer *m = _blurMaskLayers[e][k];
      if (!m) continue;
      m.fadeTop = m.fadeBottom = m.fadeLeft = m.fadeRight = 0;
      switch (e) {
        case EdgeFadeEdgeTop:    m.fadeTop    = _fadeTop;    m.curveTop    = _curveTop;    break;
        case EdgeFadeEdgeBottom: m.fadeBottom = _fadeBottom; m.curveBottom = _curveBottom; break;
        case EdgeFadeEdgeLeft:   m.fadeLeft   = _fadeLeft;   m.curveLeft   = _curveLeft;   break;
        case EdgeFadeEdgeRight:  m.fadeRight  = _fadeRight; m.curveRight  = _curveRight;  break;
      }
    }
  }
  EF_BENCH_LOG("_syncBlurMaskLayers");
}

- (void)_invalidateBlurMaskLayers {
  for (NSInteger e = 0; e < kEdgeFadeEdgeCount; e++) {
    for (NSInteger k = 0; k < kEdgeFadeBlurLevels; k++) {
      [_blurMaskLayers[e][k] setNeedsDisplay];
    }
  }
}

// Build the per-edge × per-level progressive-blur matrix: for each of the 4
// edges and 3 levels, a windowed EdgeFadeBlurMaskLayer + a nil-effect
// UIVisualEffectView + a paused animator. The views are inserted as subviews so
// RN's layout system ignores them; each mask layer is assigned to its own view's
// layer.mask. Within an edge, levels are added in increasing radius order (0
// first → lowest), so higher radii stack on top. All 12 views are created up
// front; _updateLayerFrames later hides the edges whose fade is 0.
- (void)_buildBlurView {
  EF_BENCH_START();
  const CGFloat scale = [self _effectiveScale];

  for (NSInteger e = 0; e < kEdgeFadeEdgeCount; e++) {
    for (NSInteger k = 0; k < kEdgeFadeBlurLevels; k++) {
      // Windowed blur mask — grayscale bitmap gating this level's slice of the band.
      EdgeFadeBlurMaskLayer *mask = [EdgeFadeBlurMaskLayer layer];
      mask.contentsScale = scale;
      mask.levelLo = EdgeFadeLevelLo(k);
      mask.levelHi = EdgeFadeLevelHi(k);
      _blurMaskLayers[e][k] = mask;
      EF_BENCH_LOG("bbv_mask");

      // Effect view with nil effect; the animator drives the effect below.
      UIVisualEffectView *view = [[UIVisualEffectView alloc] initWithEffect:nil];
      view.userInteractionEnabled = NO;
      view.layer.mask = mask;
      _blurViews[e][k] = view;
      EF_BENCH_LOG("bbv_view");

      [self addSubview:view];
      EF_BENCH_LOG("bbv_addSubview");

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
      // progressive blur), which _stripVisualEffectTintOn: then cleans up further.
      __weak UIVisualEffectView *weakView = view;
      UIBlurEffect *effect = [UIBlurEffect effectWithStyle:UIBlurEffectStyleRegular];
      UIViewPropertyAnimator *animator = [[UIViewPropertyAnimator alloc] initWithDuration:1
                                                                                   curve:UIViewAnimationCurveLinear
                                                                              animations:^{
        weakView.effect = effect;
      }];
      animator.pausesOnCompletion = YES;
      _blurAnimators[e][k] = animator;
      EF_BENCH_LOG("bbv_animator");

      // Deferred activation: the animator stays `.inactive` until the first
      // `_applyBlurFraction` call with a non-zero fraction (see _applyBlurFraction).
      // This avoids the ~180µs cost of startAnimation+pauseAnimation per animator
      // when blurRadius is 0 (initial mount). The effect's tint subviews are not
      // instantiated until activation, so _stripVisualEffectTintOn: is a no-op here.
      EF_BENCH_LOG("bbv_animatorStartPause");

      [self _stripVisualEffectTintOn:view];
      EF_BENCH_LOG("bbv_stripTint");
    }
  }

  [self _syncBlurMaskLayers];
  EF_BENCH_LOG("bbv_syncMasks");
  [self _applyBlurFraction];
  EF_BENCH_LOG("bbv_applyFraction");
}

// UIVisualEffectView composes its blur effect out of several private subviews
// (backdrop blur + tint + luminosity), stacked to approximate system materials.
// The tint/luminosity layers are what produces the milky glow at partial
// intensity — hiding everything except the backdrop blur subview leaves a pure
// gaussian blur behind. This only inspects public class-name strings (no
// NSClassFromString, no KVC on private keys), so it stays within documented,
// App Store-safe introspection.
- (void)_stripVisualEffectTintOn:(UIVisualEffectView *)blurView {
  for (UIView *subview in blurView.subviews) {
    subview.hidden = ![NSStringFromClass(subview.class) containsString:@"Backdrop"];
  }
}

// Map blurRadius (default 28, range 0–∞) to a per-level fraction in [0, 1] for
// each animator. Level k tops out at _blurRadius * F[k]; 40 pt radius → level 2
// (F=1) reaches fraction 1.0, level 0 (F=1/3) reaches ~0.33 — a rising radius
// ramp across the stack. At blurRadius = 0 every level's fraction is 0, so the
// whole stack is visually neutral.
- (void)_applyBlurFraction {
  EF_BENCH_START();
  for (NSInteger e = 0; e < kEdgeFadeEdgeCount; e++) {
    for (NSInteger k = 0; k < kEdgeFadeBlurLevels; k++) {
      UIViewPropertyAnimator *animator = _blurAnimators[e][k];
      if (!animator) continue;

      const float fraction = MIN(MAX(_blurRadius * kEdgeFadeLevelFractions[k] / 40.0, 0.0), 1.0);

      // Lazy activation: a freshly-built animator is `.inactive`; fractionComplete
      // is a no-op in that state (though iOS 26.1 silently activates the animator
      // when fractionComplete is set, even at 0 — see benchmark note). Activate only
      // when a non-zero fraction is actually needed; skip everything when fraction=0
      // and the animator hasn't been activated yet, so the initial mount with
      // blurRadius=0 stays completely free.
      if (animator.state == UIViewAnimatingStateInactive) {
        if (fraction == 0) continue;
        [animator startAnimation];
        [animator pauseAnimation];
        [self _stripVisualEffectTintOn:_blurViews[e][k]];
        EF_BENCH_LOG("lazy_activated");
      }

      // UIKit can re-instantiate the effect's tint/luminosity subviews whenever it
      // re-applies the effect (e.g. after a fractionComplete scrub), so re-strip on
      // every call. The subview list is short (2-3 entries), so this is cheap.
      [self _stripVisualEffectTintOn:_blurViews[e][k]];
      animator.fractionComplete = fraction;
    }
  }
  EF_BENCH_LOG("_applyBlurFraction");
}

// ─── Frost veil (blur mode only) ─────────────────────────────────────────────
//
// Four CAGradientLayers stacked above the whole blur-level stack, one per edge,
// transparent (inner) → overlayColor (outer, capped at VEIL_MAX_ALPHA). Opt-in:
// only created when _overlayColor is non-nil, replicating Android's drawFrostVeil.

- (void)_buildFrostVeil {
  if (_frostTop) return; // already built
  EF_BENCH_START();
  const CGFloat scale = [self _effectiveScale];

  _frostTop    = [self _makeFrostLayerWithScale:scale];
  EF_BENCH_LOG("fr_makeLayer");
  _frostBottom = [self _makeFrostLayerWithScale:scale];
  EF_BENCH_LOG("fr_makeLayer");
  _frostLeft   = [self _makeFrostLayerWithScale:scale];
  EF_BENCH_LOG("fr_makeLayer");
  _frostRight  = [self _makeFrostLayerWithScale:scale];
  EF_BENCH_LOG("fr_makeLayer");

  [self _rebuildVeilColors];
  EF_BENCH_LOG("fr_rebuildColors");
  [self _updateLayerFrames];
  EF_BENCH_LOG("fr_updateFrames");
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
  EF_BENCH_START();
  const CGFloat w = CGRectGetWidth(self.bounds);
  const CGFloat h = CGRectGetHeight(self.bounds);

  if (_renderMode == EdgeFadeModeMask && _maskLayer) {
    // needsDisplayOnBoundsChange is YES — CA invalidates on bounds change
    // automatically. No explicit setNeedsDisplay needed for origin-only frame
    // shifts (the rendered bitmap is in layer-local coordinates).
    _maskLayer.frame = self.bounds;
    EF_BENCH_LOG("_updateLayerFrames_mask");
    return;
  }

  if (_renderMode == EdgeFadeModeBlur) {
    if (!_blurViews[0][0]) {
      return;
    }
    [CATransaction begin];
    [CATransaction setDisableActions:YES];

    // Per-edge fade extents; an edge whose fade is 0 hides its whole stack.
    const CGFloat fades[kEdgeFadeEdgeCount] = {_fadeTop, _fadeBottom, _fadeLeft, _fadeRight};

    for (NSInteger e = 0; e < kEdgeFadeEdgeCount; e++) {
      const BOOL edgeActive = (fades[e] > 0);
      for (NSInteger k = 0; k < kEdgeFadeBlurLevels; k++) {
        UIVisualEffectView *view = _blurViews[e][k];
        if (!edgeActive) {
          // Hidden effect views trigger no backdrop pass — free.
          view.hidden = YES;
          continue;
        }
        view.hidden = NO;

        // Inner padding for this level: p_k = _blurRadius·F[k], clamped so the
        // strip never exceeds the view. Feeds the backdrop sampler across the
        // strip's inner seam; the mask weight is 0 throughout the padding.
        const CGFloat pad = _blurRadius * kEdgeFadeLevelFractions[k];
        CGRect frame;
        switch (e) {
          case EdgeFadeEdgeTop:
            frame = CGRectMake(0, 0, w, MIN(h, _fadeTop + pad));
            break;
          case EdgeFadeEdgeBottom: {
            const CGFloat stripH = MIN(h, _fadeBottom + pad);
            frame = CGRectMake(0, MAX(0, h - _fadeBottom - pad), w, stripH);
            break;
          }
          case EdgeFadeEdgeLeft:
            frame = CGRectMake(0, 0, MIN(w, _fadeLeft + pad), h);
            break;
          case EdgeFadeEdgeRight: {
            const CGFloat stripW = MIN(w, _fadeRight + pad);
            frame = CGRectMake(MAX(0, w - _fadeRight - pad), 0, stripW, h);
            break;
          }
          default:
            frame = self.bounds;
            break;
        }
        view.frame                  = frame;
        _blurMaskLayers[e][k].frame = view.bounds;
      }
    }

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
    EF_BENCH_LOG("_updateLayerFrames_blur");
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
