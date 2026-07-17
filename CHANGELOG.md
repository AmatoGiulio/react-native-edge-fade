## Unreleased

### Breaking Changes

* **blur:** the fade curve now governs the whole blur progression across the band, not just level 0's sharp→frost onset — the curve is the envelope the perceived radius follows from sharp (inner edge) to full `blurRadius` (outer edge). `frostProgression`'s default changes from `0.35` to `1` (the envelope spans the full band); pass `frostProgression={0.35}` to keep the old crisp-onset look.

### Features

* **blur:** `mode="blur"` — progressive frosted-glass edge blur, now closed on BOTH platforms with the same UNIFORM semantics. A 3-level increasing-radius stack (radii 0.35/0.65/1 × `blurRadius`): a short, curve-shaped sharp→frost transition on the light first level, then progressively heavier blur toward the outer edge, with no visible level seams while scrolling (heavy levels ramp to the outer edge with zero-slope smoothstep onsets). Android renders via `RenderEffect.createBlurEffect` + plateau `LinearGradient` masks composited `DST_IN` (API 31+; degrades to `mask` below); iOS via per-edge masked `UIVisualEffectView`s driven by paused `UIViewPropertyAnimator`s — public API only, iOS 13+. Web degrades to `mask`.
* **blur:** `frostProgression` prop (both platforms) — fraction of the band over which the curve's blur envelope completes (clamped 0.05–1, default 1). Smaller = the envelope compresses toward the inner edge, reaching full blur sooner.
* **blur:** `frostSaturation` / `frostLift` props (**Android only** — no public-API color grade on `UIVisualEffectView`) — saturation/brightness grade applied to the blurred pixels (defaults 0.9 / 1.03) for a soft pastel frosted-glass material.
* **blur:** optional frost material veil via `color` — transparent (inner) → color (outer), 16-stop smoothstep across the full band, max opacity 0.6, aligned across platforms.
* **blur:** the fade curve is live in blur mode — editing the curve (presets or custom Bézier) reshapes the sharp→frost transition.
* **curves:** add `'smoother'` preset — a smootherstep S-curve (`6t⁵−15t⁴+10t³`) eased at both ends, so the fade eases in at the inner edge instead of breaking away immediately. Works across `mask`, `overlay`, and `blur`.
* **example:** rebuilt as a themed Photos app (bundled local images, no network) with a shared native form-sheet tuner: mode selector, Bézier curve pad, dials for blur/frost parameters, frost tint with color picker.

### Performance Improvements

* **android:** the two heavy blur levels render at half resolution (~4× fewer pixels on the expensive Gaussians; the large radius hides the bilinear upscale). Gallery-scroll bench on emulator: p50 16ms / p95 17ms / p99 18ms, 0% legacy jank.
* **ios:** per-edge band clipping — each effect view covers only its edge strip (plus sampling padding) instead of the whole view, cutting the backdrop pass area by ~70% for typical fade sizes.

### Bug Fixes

* **ios:** stop paused blur animators before dealloc — a view released without a mode flip (e.g. RN dev reload) aborted in `UIViewPropertyAnimator dealloc`.
* **blur:** scroll sync — Android re-records the content on every scroll tick so the frost tracks a fling; iOS gets this for free from the live `UIVisualEffectView` backdrop.

### Notes

* `mode="blur"` needs opaque content — give the `EdgeFadeView` (or its content) a solid `backgroundColor` so the blur backdrop has no transparent gaps (avoids dark premultiplied-alpha fringes).
* Requirements: Android 12 (API 31)+ and iOS 13+ for blur; older Android and Web degrade to `mask`.

## 0.0.1 (2026-05-28)


### Bug Fixes

* **android:** convert edge fade properties from dp to pixels for accurate rendering ([9e5a6de](https://github.com/AmatoGiulio/react-native-edge-fade/commit/9e5a6dea13b93bd7411442c39d368f7abce2b977))
* **android:** resolve AGSL rendering issue ([fb782c6](https://github.com/AmatoGiulio/react-native-edge-fade/commit/fb782c6851b645349e257ffa72219139f0d2d8ac))
* **android:** uniform reuse runtimeShader ([2077b92](https://github.com/AmatoGiulio/react-native-edge-fade/commit/2077b9297dfb629c7b6488b34026fbcc0e6ace1e))
* demo ([10bfe5d](https://github.com/AmatoGiulio/react-native-edge-fade/commit/10bfe5d96ff9cc8a892fa215c9c667b34c7fe89a))
* demo app ([883b361](https://github.com/AmatoGiulio/react-native-edge-fade/commit/883b3612300c980481a5a817bf343d0356737d94))
* **example:** add nestedScrollEnabled to inner ScrollViews ([228cf6e](https://github.com/AmatoGiulio/react-native-edge-fade/commit/228cf6e6d960711940567d848311708d9aaf85a8))
* **ios:** mask mode crash and rendering on new architecture ([4dbb27a](https://github.com/AmatoGiulio/react-native-edge-fade/commit/4dbb27afbbd9253258a758f2b0b80a276fcf751f))
* layout ([d3f4bcc](https://github.com/AmatoGiulio/react-native-edge-fade/commit/d3f4bccfdd557a5e26a578eab1c226278ac87852))
* make animated edge fade compiler-safe ([91d2cbc](https://github.com/AmatoGiulio/react-native-edge-fade/commit/91d2cbc18a7018716d059d0dd8e5a087d2b64ae9))


### Features

* add AnimatedEdgeFadeView for Reanimated-driven fades ([df18453](https://github.com/AmatoGiulio/react-native-edge-fade/commit/df1845394567c926465cbe3125fc383f8210ce2e))
* add logical start/end edge props for RTL ([80f0abf](https://github.com/AmatoGiulio/react-native-edge-fade/commit/80f0abf707a5eef0671a3a3d29a357d02e1eabfc))
* **android:** add AGSL edge fade shader ([6e67fb7](https://github.com/AmatoGiulio/react-native-edge-fade/commit/6e67fb78f22ed626259ef6e78108053989e36792))
* **android:** add demo images ([9816de7](https://github.com/AmatoGiulio/react-native-edge-fade/commit/9816de7bd7770570b268459152ae913ab2246b9b))
* **android:** bachmark screen and unit test + Custom curve AGSL via LUT ([86db748](https://github.com/AmatoGiulio/react-native-edge-fade/commit/86db748d70b76f5c4adb0d6cb00486bfaddc1849))
* **android:** improve edge fade rendering ([0a3f11d](https://github.com/AmatoGiulio/react-native-edge-fade/commit/0a3f11dcfb23040cf41234246a996fc2f3582e69))
* app example improve ([6fec762](https://github.com/AmatoGiulio/react-native-edge-fade/commit/6fec7627330c598d6581444f241c18cc9335fef7))
* **example:** add custom curve demo section ([f7341f1](https://github.com/AmatoGiulio/react-native-edge-fade/commit/f7341f1f1d1d32a73385a07d5fdeeef847b2dfd0))
* make `radius` the only corner-radius source ([84b401e](https://github.com/AmatoGiulio/react-native-edge-fade/commit/84b401edd27f9ed7d0e3c003b16635fbd45a2438))
* modern demo UI + full README ([23f53dd](https://github.com/AmatoGiulio/react-native-edge-fade/commit/23f53dd655bd1f22e3f971a6cae62007f0074a62))
* warn when color is set with explicit mode="mask" ([1e95557](https://github.com/AmatoGiulio/react-native-edge-fade/commit/1e9555774453e197c59f78a0f901811bded206fe))


### Performance Improvements

* **android:** shrink mask saveLayer to edge rect for single-edge fades ([d66abae](https://github.com/AmatoGiulio/react-native-edge-fade/commit/d66abae46a0c981963963aaac4a805c90b330f6f))
* **example:** switch masonry to FlashList virtualization ([1507abf](https://github.com/AmatoGiulio/react-native-edge-fade/commit/1507abf7c7bb6ec6eebc5a6e2ef13b60d33552da))
* **ios:** step 1 — partial-redraw mask + leaner overlay path ([611c436](https://github.com/AmatoGiulio/react-native-edge-fade/commit/611c43605ffbddef2a69dca06dd945b021706e6d)), closes [hi#DPI](https://github.com/hi/issues/DPI)
