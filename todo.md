# Roadmap

## Current branch — `app-example`

Goal: rebuild the example app into a polished showcase (Margelo-style)
demonstrating both `EdgeFadeView` and `AnimatedEdgeFadeView` on a real
masonry of images + looping videos, with proper navigation and detail
screens.

**Status: in progress, nothing committed yet.** All the work below sits
as uncommitted changes / new files on the `app-example` branch.

### Uncommitted state (what's already done locally)

The next session should review the working tree, then commit in small
steps as outlined under "Next commits" below.

- **expo-router scaffolded** (`example/app/_layout.tsx`,
  `example/app/index.tsx`, `example/app/benchmark.tsx`,
  `example/app/item/[id].tsx`). Old `example/index.js` and
  `example/src/` deleted.
- **package.json** updated with: `expo-router`, `@shopify/flash-list`
  (v2.0.2), `expo-video`, `react-native-safe-area-context`,
  `react-native-screens`, `expo-linking`, `expo-constants`,
  `@expo/log-box@55.0.12` (pinned to SDK 55 — the `^56` default that
  `expo install` picked broke runtime with `NoClassDefFoundError`),
  `@babel/runtime`. `main` switched to `expo-router/entry`.
- **app.json** got `scheme: "edgefade"`, `experiments.typedRoutes: true`,
  and `plugins: ["expo-router", "expo-video"]`.
- **example/tsconfig.json** drops the inherited
  `customConditions: ["react-native-strict-api"]` so expo-video's
  `VideoView` class component accepts `style` under the lenient
  react-native typings.
- **`example/app/lib/catalog.ts`** — typed `CatalogItem` discriminated
  union (`ImageItem | VideoItem`), 33 still images + 5 video clips
  interleaved every 7 entries. `ratio` resolved at module load via
  `Image.resolveAssetSource` for images, hard-coded per clip for videos
  (16/9 except `dreamy-hands` which is 9/16).
- **5 video slugs** in `example/assets/footages/` (renamed from the
  Artgrid filenames): `crane-reflection.mp4`, `birds-clouds.mp4`,
  `dreamy-blur.mp4`, `power-lines.mp4`, `dreamy-hands.mp4`. The other
  10 mp4s are left in the folder but NOT `require()`-d — Metro doesn't
  bundle them.
- **Discover screen** (`example/app/index.tsx`) now uses
  `AnimatedFlashList` with the `masonry numColumns={2}
  optimizeItemArrangement` props. Image cells render via `Image`; video
  cells use `useVideoPlayer` + `VideoView` with `surfaceType="textureView"`
  (SurfaceView causes z-order glitches and scroll jank). A red "● LIVE"
  badge marks video cells. `useAnimatedScrollHandler` drives the
  `AnimatedEdgeFadeView` top fade (`scrollY ∈ [0, 120] → topFade ∈ [0,
  120]`).
- **Benchmark screen** ported to `app/benchmark.tsx`, accessed via
  `router.push('/benchmark')`. Back button uses `router.back()`.
- **Item detail screen** still a placeholder (`app/item/[id].tsx`) —
  shows the route id and a "coming up" note.
- **Banner refreshed** earlier on main (`docs/banner.png`, commit
  `cef5288`).

### Outstanding bugs / things to verify on resume

- [ ] **Verify scroll fluidity** on device after the FlashList switch.
      The user reported the masonry was "fangosa" with the manual
      ScrollView. The FlashList migration is the proposed fix — needs a
      real-device check before declaring it done.
- [ ] **First boot needs `npx expo prebuild --platform android --clean
      && npx expo run:android --device`** to bundle the new mp4 assets
      into the APK. JS-only reload won't ship them.
- [ ] If you see a Reanimated `player.pause is not a function` error,
      it's because `runOnJS(fn)(player, ...)` strips prototype methods.
      We've already moved to a closure-captured `togglePlayback` in
      `VideoCard`; double-check no regression slipped in.

### Next commits (suggested order)

1. **chore(example): migrate to expo-router scaffolding** — commit the
   router setup, new deps, deleted legacy files, plugin/app.json wiring.
   Verify with `npx tsc --noEmit && npx jest && npx eslint`.
2. **feat(example): asset catalog with mixed image+video items** — add
   `catalog.ts`, rename the 5 selected mp4 slugs, wire VideoCard
   skeleton (loop + muted + surfaceType=textureView).
3. **perf(example): switch masonry to FlashList virtualization** —
   AnimatedFlashList + masonry props, drop the manual 2-column
   split, drop the now-redundant useAnimatedReaction pause logic.
4. **feat(example): image + video detail screens** (task #12 below).
5. **docs: README + banner refresh** referencing the new example.

### Open tasks on this branch

- [ ] **Detail screens** (`app/item/[id].tsx`):
  - Resolve the item by id via `getCatalogItem(id)` from `catalog.ts`.
  - Image variant — full-bleed Image at native ratio, bottom
    `EdgeFadeView mode="mask"` fading into a metadata card with
    category, accent dot, source filename.
  - Video variant — full-bleed `VideoView` (`contentFit: 'cover'`,
    `surfaceType: 'textureView'`, native controls or a custom minimal
    overlay), top + bottom `EdgeFadeView` so the chrome doesn't sit on
    raw video.
  - Header: back arrow + small category pill.
  - Optional: shared-element transition from the Discover card to
    detail (expo-router v6 supports this — only if it doesn't blow up
    scope).
- [ ] **README + banner refresh**: update the README to point at the
      new app, add a couple of GIFs / screenshots from the masonry +
      detail, mention `expo-video` and FlashList as example-only deps.

## Attempted, deferred

- **`RenderEffect.createRuntimeShaderEffect` fast path on Android API
  31+** (branch `android/render-effect`, abandoned). Composite AGSL
  shader using `uniform shader content` produced a vertically-stretched
  render on Fabric `ViewGroup`s whose children contain a `ScrollView`
  (the masonry demo). Root cause: the FrameLayout's `RenderNode` extent
  does not match the View's logical bounds when children embed a long
  scroll, so `content.eval(fragCoord)` samples in a different coordinate
  space than `fragCoord`. The legacy saveLayer path (with the single-
  edge shrink already shipped) remains the default. To retry: either a
  Skia chain that doesn't sample `content` directly, or a forced layer
  setup (`setLayerType(HARDWARE)` + explicit bounds) — neither
  investigated yet.

## Done previously (in main)

### Branch `api/dx-validations` (merged)
- `__DEV__` warn when `color` is set with explicit `mode="mask"`.
- `radius` as the only corner-radius source; `style.borderRadius`
  ignored with a `__DEV__` warning.
- `AnimatedEdgeFadeView` — ergonomic Reanimated wrapper accepting
  `SharedValue<number>` on top/bottom/left/right/start/end/radius;
  `react-native-reanimated` is an optional peer dependency.
- `NativeEdgeFadeView` moved off the main barrel to the
  `react-native-edge-fade/native` subpath.

### Branch `android/density-rtl-perf` (merged)
- Android `dp → px` density conversion at the ViewManager boundary.
- Logical `start` / `end` props with RTL mapping.
- Single-edge `saveLayer` shrink (two-pass render, ~14–30× less memory).
- `BlendMode.DST_IN` on API 29+, `PorterDuffXfermode` only on 24–28.
- One-shot `Log.w` on AGSL `RuntimeShader` compile / uniform failure.
- Native shader cache release in `onDetachedFromWindow`.

### iOS perf pass (commit `611c436`)
- Partial-redraw mask + leaner overlay path.

## Open / nice-to-have (not blocking the example refresh)

### Android
- [ ] Compose interop (`Modifier.fadingEdge`) — opens the lib to native
      Kotlin consumers.

### iOS
- [ ] Cache `NSNumber*` locations across rebuilds (allocs ~32 per edge).
- [ ] Drop `_overlayContainer` extra `UIView` — `CAGradientLayer`s can
      be children of `self.layer` directly.
- [ ] Use `traitCollection.displayScale` instead of `UIScreen.mainScreen.scale`
      for multi-window iPad correctness.
- [ ] `cornerCurve = .continuous` + `CACornerMask` for squircle radius.

### Tests / QA
- [ ] Benchmark single-edge `saveLayer` shrink on a real mid-range
      device (Pixel 6a-class) — quantify the FPS delta on a long list
      scroll. The example's `/benchmark` screen is the harness.
- [ ] Physical-device test of `radius` + mask mode corner interaction
      on iOS.
- [ ] TypeScript strict-mode audit on the package source.

## When you resume

1. `git status` on `app-example` — review the uncommitted tree against
   the "Uncommitted state" section above.
2. Run `npx tsc --noEmit && npx jest && npx eslint src/ example/app/`
   to make sure nothing has rotted.
3. On Android device: `npx expo prebuild --platform android --clean &&
   npx expo run:android --device` to get the mp4 assets bundled, then
   reload Metro and check that the masonry scrolls smoothly with videos
   looping in the cells that come into view.
4. If everything's green visually, commit in the order listed under
   "Next commits", push, open the PR.
5. Then tackle the detail screen (task above) as a follow-up commit on
   the same branch.
