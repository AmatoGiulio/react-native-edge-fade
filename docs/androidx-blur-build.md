# Official AndroidX progressive blur builds

## SDK 58 integration experiment

Branch: `codex/compose-official-sdk58`, based on `2947f6c`.

The example now targets **Expo 58.0.0-preview.2 / React Native 0.88.0-rc.0**.
These are prerelease versions, not a stable SDK 58 release. The SDK 58 Expo
Gradle plugin removes the library `targetSdk` call that blocked AGP 9 on SDK 57.
The generated Android project uses AGP 9.2.1 / Gradle 9.4.1.

The library's official backend is a build-time opt-in:

```properties
edgeFadeAndroidxBlur=true
```

It requires AGP 9.1+ and compile SDK **37.1** in the consuming app. This is a
compile-time requirement: it does not raise the app's minimum Android version.
The example's `plugins/withOfficialAndroidxBlur.js` enables the property and sets
its compile SDK during Android prebuild. No AAR metadata checks are bypassed.

On eligible Android 13+ configurations, the public `EdgeFadeView mode="blur"`
now invokes the published `androidx.compose.ui:ui-graphics:1.13.0-alpha03` API:

```text
existing edge mask + padded strip geometry
  -> BlurRadiusSpec.shader(maxRadius) { mask }
  -> createRenderEffect(size, Density(1f), TileMode.Clamp)
  -> asAndroidRenderEffect()
  -> strip RenderNode.setRenderEffect(...)
```

This retains edge-local rendering and the sharp center. The port's Gaussian
shaders are not initialized when the official backend is selected. The public
JavaScript props are unchanged; no backend prop is added.

Without the property, consumers keep the dependency-free AGSL port and existing
compile SDK defaults. Android 12/12L still select the GLES backend. Unsupported
configurations still use the experiment's existing mask fallback. **Restoring
all `main` blur fallback behavior is a separate, unfinished compatibility task.**

### Build and distinguish the renderers

From the repository root:

```sh
yarn install
yarn example expo prebuild --platform android --no-install
cd example/android
./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a
```

Use a matching installed JDK and Android SDK; the 37.1 platform must be present.
For an A/B build of the port on the same SDK 58 example:

```sh
./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a -PedgeFadeAndroidxBlur=false
```

Native activation logs explicitly distinguish:

- `Using official AndroidX progressive blur on API 33+`
- `Using pure progressive AGSL blur on API 33+`
- `Using GLES 3.0 continuous progressive blur on API 31-32`

After installing the intended release APK, require the correct renderer:

```sh
node scripts/smoke-progressive-release.mjs --serial DEVICE --expected-backend androidx
node scripts/capture-progressive-perfetto.mjs --serial DEVICE --renderer public --public-backend androidx --radius-px 140
```

The capture script writes the verified backend and capture-checkout information
next to each trace. The checkout revision is **not** proof of the installed APK's
source revision; retain build provenance when comparing results.

Compilation and source tests do not establish visual fidelity or performance.
The SDK/RN upgrade changes the workload environment, so old SDK 57 measurements
cannot establish a before/after win for this integration.

### Validation, 2026-09-17

- Official and dependency-free port release APKs: build PASS, arm64-v8a.
- Expo Doctor: 20/20 checks PASS; Expo dependency alignment PASS.
- Library and example TypeScript checks PASS. The example retains RN's
  `react-native-legacy-deep-imports` compatibility typings during this preview
  migration; the library uses the public `CodegenTypes` namespace.
- JavaScript: 70 tests PASS. Native source contracts: 48 PASS, 3 optional tests
  skipped. Backend-log identification: 3 tests PASS.
- Nord 5 / CPH2709 / API 36: official backend activation PASS in the public
  component, vertical and four-edge smoke PASS (radius zero/recovery, analytical
  and custom curves, scrolling, rotation, background/foreground).
- The port build also passed vertical smoke on the same phone. The final
  official APK was reinstalled and passed four-edge smoke again.
- Gallery: official activation confirmed and screenshot inspected; this is a
  rendering smoke check, not a pixel-equivalence or performance acceptance test.
- Still required: physical API 31–32 validation, controlled visual comparison,
  performance measurements and restoration of the agreed `main` compatibility
  behavior before promoting this experimental branch.

Local APKs, screenshots and smoke logs are saved under
`benchmark-results/sdk58-integration/` (not tracked in Git).

## Independent official reference app

`androidx-reference/` remains a native reference APK using the same published
Compose artifact on ordinary platform Views. It does not use React Native or
Expo. The reference deliberately applies its effect to the viewport, whereas
the library applies it to padded edge strips.

```sh
example/android/gradlew -p androidx-reference assembleRelease
```

The app ID is `com.edgefade.androidxref`, so it installs beside the Expo example.
It requires Android 13+ at runtime. Compare the same physical radius, curves,
content and scroll position. Inspect text, image edges, corners, strip boundaries,
transparency and frame pacing.

## Historical SDK 57 blocker

Compose UI 1.13.0-alpha03 successfully compiled in the isolated reference with
AGP 9.1.1 / SDK 37.1 / Gradle 9.3.1. Embedding it in the Expo SDK 57 example failed
at plugin evaluation (`LibraryDefaultConfig.setTargetSdk(Integer)`). That failure
motivated the original dependency-free port; it is not evidence that SDK 58 also
has that same incompatibility.

### Demo controls

Android demo blur amount is capped at `floor(150 / PixelRatio.get())` dp
(the renderer limit is 150 physical pixels). `corner radius` controls rounded
corners independently and no longer disables progressive blur. Android tint
controls are shown only in overlay mode; iOS-only saturation/lift controls are
hidden on Android. Release smoke alternates square and rounded corners.
