#import "EdgeFadeVariableBlurView.h"
#import "EdgeFadeCurves.h"

#import <objc/message.h>

// Mask resolution along the band. The filter samples the mask with linear
// filtering across the strip, so 256 steps are smooth at any fade depth.
static const size_t kMaskSteps = 256;

// Private names are assembled at runtime rather than kept as literals.
static NSString *filterClassName(void) { return [@[ @"CA", @"Filter" ] componentsJoinedByString:@""]; }
static NSString *filterType(void) { return [@[ @"variable", @"Blur" ] componentsJoinedByString:@""]; }

static id makeVariableBlurFilter(void)
{
  Class cls = NSClassFromString(filterClassName());
  SEL make = NSSelectorFromString(@"filterWithType:");
  if (!cls || ![cls respondsToSelector:make]) return nil;
  return ((id (*)(id, SEL, NSString *))objc_msgSend)(cls, make, filterType());
}

@implementation EdgeFadeVariableBlurView {
  NSInteger _edge;
  UIVisualEffectView *_effectView;
  id _filter;
  CGImageRef _mask;
  CGFloat _fade, _pad, _progression;
  NSString *_curve;
}

+ (BOOL)isSupported
{
  static BOOL supported;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    supported = makeVariableBlurFilter() != nil;
  });
  return supported;
}

- (instancetype)initWithEdge:(NSInteger)edge
{
  if (self = [super initWithFrame:CGRectZero]) {
    _edge = edge;
    _progression = 1.0;
    self.userInteractionEnabled = NO;
    // Any blur style works: its backdrop layer's own filter chain (blur plus
    // saturation and tint) is replaced wholesale by the single variableBlur.
    _effectView = [[UIVisualEffectView alloc] initWithEffect:[UIBlurEffect effectWithStyle:UIBlurEffectStyleRegular]];
    _effectView.userInteractionEnabled = NO;
    _effectView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [self addSubview:_effectView];
    _filter = makeVariableBlurFilter();
    [_filter setValue:@YES forKey:@"inputNormalizeEdges"];
  }
  return self;
}

- (void)dealloc
{
  if (_mask) CGImageRelease(_mask);
}

- (void)setRadius:(CGFloat)radius
{
  if (radius == _radius) return;
  _radius = radius;
  [self _applyFilter];
}

- (void)setFade:(CGFloat)fade pad:(CGFloat)pad curve:(NSString *)curve progression:(CGFloat)progression
{
  if (fade == _fade && pad == _pad && progression == _progression && [curve isEqualToString:_curve]) return;
  _fade = fade;
  _pad = pad;
  _curve = [curve copy];
  _progression = progression;
  [self _rebuildMask];
  [self _applyFilter];
}

- (void)layoutSubviews
{
  [super layoutSubviews];
  _effectView.frame = self.bounds;
  // UIKit may rebuild the effect's subviews (trait changes, re-attach), which
  // restores their stock filters: re-apply ours after every layout.
  [self _applyFilter];
}

- (void)didMoveToWindow
{
  [super didMoveToWindow];
  [self _applyFilter];
}

// Alpha mask along the strip, outer edge → inner side: the curve's presence
// over the fade band (compressed into its inner `progression` fraction, like
// the Android renderer and the level masks), zero over the inner padding.
- (void)_rebuildMask
{
  if (_mask) {
    CGImageRelease(_mask);
    _mask = NULL;
  }
  const CGFloat strip = _fade + _pad;
  if (_fade <= 0 || strip <= 0) return;

  const BOOL vertical = (_edge == 0 || _edge == 1);
  const size_t w = vertical ? 1 : kMaskSteps;
  const size_t h = vertical ? kMaskSteps : 1;
  uint8_t *data = (uint8_t *)calloc(kMaskSteps, 1);
  const CGFloat fp = MAX(_progression, 0.05);
  for (size_t i = 0; i < kMaskSteps; i++) {
    // Distance from the outer edge, in points, at this sample's centre.
    const CGFloat pos = ((CGFloat)i + 0.5) / kMaskSteps * strip;
    const BOOL fromEnd = (_edge == 1 || _edge == 3); // bottom/right: outer edge is at the end
    const CGFloat fromOuter = fromEnd ? strip - pos : pos;
    CGFloat weight = 0;
    if (fromOuter < _fade) {
      const CGFloat t = 1.0 - fromOuter / _fade; // inner 0 → outer 1
      weight = EdgeFadePresenceAt(_curve ?: @"smooth", MIN(t / fp, 1.0));
    }
    data[i] = (uint8_t)lround(MIN(MAX(weight, 0.0), 1.0) * 255.0);
  }
  CGColorSpaceRef gray = CGColorSpaceCreateDeviceGray();
  CGContextRef ctx = CGBitmapContextCreate(data, w, h, 8, w, NULL, (CGBitmapInfo)kCGImageAlphaOnly);
  CGColorSpaceRelease(gray);
  if (ctx) {
    _mask = CGBitmapContextCreateImage(ctx);
    CGContextRelease(ctx);
  }
  free(data);
}

- (void)_applyFilter
{
  if (!_filter) return;
  const BOOL active = (_mask != NULL && _radius > 0);

  // The effect view keeps one backdrop subview (the layer that samples what is
  // behind) plus tint/luminosity subviews; only the backdrop stays, carrying
  // our filter alone.
  for (UIView *subview in _effectView.subviews) {
    const BOOL backdrop = [NSStringFromClass(subview.class) containsString:@"Backdrop"];
    subview.hidden = !backdrop;
    if (!backdrop) continue;
    CALayer *layer = subview.layer;
    if (!active) {
      layer.filters = @[];
      continue;
    }
    // Core Animation keeps its own copy of an installed filter and ignores a
    // re-assigned array holding an equally named one: install once, then
    // update the live copy through its key path.
    const BOOL installed = [[layer.filters.firstObject valueForKey:@"name"] isEqual:filterType()];
    if (!installed) {
      [_filter setValue:@(_radius) forKey:@"inputRadius"];
      [_filter setValue:(__bridge id)_mask forKey:@"inputMaskImage"];
      layer.filters = @[ _filter ];
    } else {
      NSString *base = [@"filters." stringByAppendingString:filterType()];
      [layer setValue:@(_radius) forKeyPath:[base stringByAppendingString:@".inputRadius"]];
      [layer setValue:(__bridge id)_mask forKeyPath:[base stringByAppendingString:@".inputMaskImage"]];
    }
  }
}

@end
