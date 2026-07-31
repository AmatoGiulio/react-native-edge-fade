#import "EdgeFadeCurves.h"

// ─── Preset tables ────────────────────────────────────────────────────────────
//
// 32-stop preset curves computed once via dispatch_once. Dense stops eliminate
// visible banding at any fade size.
//
//   smooth    → (1−t)³                    sharp  → (1−t)⁵
//   gentle    → (1−t)²                    soft   → cos(t·π/2)
//   smoother  → smootherstep 6t⁵−15t⁴+10t³
//   linear    → kept as a 2-stop array (1, 0) since the curve is exact.

static const int     kN              = 32;
static const CGFloat kTwoStops[2]    = {0, 1.0};
static const CGFloat kLinearAlphas[2] = {1, 0};

static CGFloat sSmoothAlphas[32], sSharpAlphas[32], sGentleAlphas[32], sSoftAlphas[32], sSmootherAlphas[32];
static CGFloat sNStops[32];

static void computePresetCurves(void) {
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    for (int i = 0; i < kN; i++) {
      CGFloat t        = (CGFloat)i / (kN - 1);
      sNStops[i]       = t;
      sSmoothAlphas[i] = pow(1 - t, 3);
      sSharpAlphas[i]  = pow(1 - t, 5);
      sGentleAlphas[i] = pow(1 - t, 2);
      sSoftAlphas[i]   = cos(t * M_PI_2);
      sSmootherAlphas[i] = 1 - (t * t * t * (t * (t * 6 - 15) + 10));
    }
  });
}

static void presetCurveData(NSString *curve,
                            const CGFloat **alphasOut,
                            const CGFloat **stopsOut,
                            size_t *countOut)
{
  computePresetCurves();
  if      ([curve isEqualToString:@"sharp"])  { *alphasOut = sSharpAlphas;  *stopsOut = sNStops;    *countOut = kN; }
  else if ([curve isEqualToString:@"gentle"]) { *alphasOut = sGentleAlphas; *stopsOut = sNStops;    *countOut = kN; }
  else if ([curve isEqualToString:@"soft"])   { *alphasOut = sSoftAlphas;   *stopsOut = sNStops;    *countOut = kN; }
  else if ([curve isEqualToString:@"smoother"]) { *alphasOut = sSmootherAlphas; *stopsOut = sNStops; *countOut = kN; }
  else if ([curve isEqualToString:@"linear"]) { *alphasOut = kLinearAlphas; *stopsOut = kTwoStops;  *countOut = 2;  }
  else                                        { *alphasOut = sSmoothAlphas; *stopsOut = sNStops;    *countOut = kN; }
}

// ─── Custom curve parsing ─────────────────────────────────────────────────────

BOOL EdgeFadeCurveIsCustom(NSString *curve) {
  return [curve containsString:@","];
}

// Global cache for parsed custom curves. The same comma-separated alpha string
// is often used on multiple edges (top, bottom, left, right) and in
// EdgeFadePresenceAt — parsing it every time allocates two malloc buffers.
// Cached entries are retained for the process lifetime; custom curves in a
// typical app number < 10, so memory growth is negligible.
static NSMutableDictionary<NSString *, NSArray<NSNumber *> *> *customAlphaCache(void) {
  static NSMutableDictionary *cache;
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    cache = [NSMutableDictionary dictionary];
  });
  return cache;
}

// Returns heap-allocated buffers on success; caller must free().
// Returns NO and leaves buffers nil on parse failure.
static BOOL parseCustomCurve(NSString *curve,
                             CGFloat **alphasOut,
                             CGFloat **stopsOut,
                             size_t *countOut)
{
  NSArray<NSString *> *parts = [curve componentsSeparatedByString:@","];
  NSUInteger n = parts.count;
  if (n < 2) return NO;

  // Check cache first — the same curve string re-parsed on multiple edges.
  NSArray<NSNumber *> *cached = customAlphaCache()[curve];
  if (cached) {
    NSUInteger cn = cached.count;
    CGFloat *alphas = (CGFloat *)malloc(cn * sizeof(CGFloat));
    CGFloat *stops  = (CGFloat *)malloc(cn * sizeof(CGFloat));
    for (NSUInteger i = 0; i < cn; i++) {
      alphas[i] = cached[i].doubleValue;
      stops[i]  = (CGFloat)i / (cn - 1);
    }
    *alphasOut = alphas; *stopsOut = stops; *countOut = cn;
    return YES;
  }

  CGFloat *alphas = (CGFloat *)malloc(n * sizeof(CGFloat));
  CGFloat *stops  = (CGFloat *)malloc(n * sizeof(CGFloat));
  NSMutableArray<NSNumber *> *alphaCache = [NSMutableArray arrayWithCapacity:n];
  for (NSUInteger i = 0; i < n; i++) {
    CGFloat a = [parts[i] doubleValue];
    alphas[i] = a;
    stops[i] = (CGFloat)i / (n - 1);
    [alphaCache addObject:@(a)];
  }
  customAlphaCache()[curve] = alphaCache;
  *alphasOut = alphas; *stopsOut = stops; *countOut = n;
  return YES;
}

void EdgeFadeResolveCurve(NSString *curve,
                          const CGFloat **alphas,
                          const CGFloat **stops,
                          size_t *count,
                          CGFloat **dynAlphas,
                          CGFloat **dynStops)
{
  *dynAlphas = NULL; *dynStops = NULL;
  if (EdgeFadeCurveIsCustom(curve) && parseCustomCurve(curve, dynAlphas, dynStops, count)) {
    *alphas = *dynAlphas; *stops = *dynStops;
    return;
  }
  presetCurveData(EdgeFadeCurveIsCustom(curve) ? @"smooth" : curve, alphas, stops, count);
}

// ─── Presence sampling ────────────────────────────────────────────────────────

CGFloat EdgeFadePresenceAt(NSString *curve, CGFloat t)
{
  const CGFloat *alphas; const CGFloat *stops; size_t count;
  CGFloat *dynAlphas, *dynStops;
  EdgeFadeResolveCurve(curve, &alphas, &stops, &count, &dynAlphas, &dynStops);

  if (t <= 0.0) t = 0.0; else if (t >= 1.0) t = 1.0;

  CGFloat alpha = alphas[count - 1];
  for (size_t i = 1; i < count; i++) {
    if (t <= stops[i]) {
      const CGFloat span = stops[i] - stops[i - 1];
      const CGFloat f = span > 0.0 ? (t - stops[i - 1]) / span : 1.0;
      alpha = alphas[i - 1] + (alphas[i] - alphas[i - 1]) * f;
      break;
    }
  }

  if (dynAlphas) { free(dynAlphas); free(dynStops); }
  return 1.0 - alpha;
}

// ─── Locations cache ──────────────────────────────────────────────────────────

static NSArray<NSNumber *> *cachedPresetLocations(void) {
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

static NSArray<NSNumber *> *cachedLinearLocations(void) {
  static NSArray<NSNumber *> *cached;
  static dispatch_once_t once;
  dispatch_once(&once, ^{ cached = @[@0, @1]; });
  return cached;
}

NSArray<NSNumber *> *EdgeFadeLocationsForCurve(NSString *curve) {
  if (EdgeFadeCurveIsCustom(curve)) {
    NSArray<NSString *> *parts = [curve componentsSeparatedByString:@","];
    NSUInteger n = parts.count;
    NSMutableArray *locs = [NSMutableArray arrayWithCapacity:n];
    for (NSUInteger i = 0; i < n; i++) [locs addObject:@((CGFloat)i / (n - 1))];
    return [locs copy];
  }
  if ([curve isEqualToString:@"linear"]) return cachedLinearLocations();
  return cachedPresetLocations();
}
