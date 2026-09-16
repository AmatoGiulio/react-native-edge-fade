# AndroidX progressive blur reference build

## Current conclusion

Compose UI `1.13.0-alpha03` progressive blur is available and its official API compiles with **AGP 9.1.1 / compileSdk 37.1 / Gradle 9.3.1 / JDK 17**.

It cannot currently be embedded in this repository's Expo SDK 57 example without changing the whole Android toolchain. Raising the Expo app to AGP 9.1 reaches an incompatibility in the Expo Gradle plugin (`LibraryDefaultConfig.setTargetSdk(Integer)`). Therefore the official AndroidX binary is deliberately isolated from the RN/Expo consumer build.

The shipped experiment has two separate paths:

- `example/`: React Native / Expo comparison using Legacy and the dependency-free AndroidX-derived AGSL port.
- `androidx-reference/`: native Android reference APK using the **official** `androidx.compose.ui:ui-graphics:1.13.0-alpha03` implementation.

No suppression flag or metadata bypass is used.

## Official reference app

`androidx-reference` is a normal Android application using platform Views, not Compose UI widgets. The playlist `ScrollView` receives the RenderEffect created by:

```text
BlurRadiusSpec.shader(maxRadius) { mask }
  -> createRenderEffect(size, Density(1f), TileMode.Clamp)
  -> asAndroidRenderEffect()
  -> View.setRenderEffect(...)
```

Its controls mirror the RN Blur Lab:

- radius 0..48dp, capped at the AndroidX 150px progressive-blur limit
- top 92dp / bottom 112dp bands
- optional left/right 48dp bands
- `smooth` and `linear` curves
- same generated 48-row playlist

The `smooth` mask matches `EdgeFadeCurves.presenceAt("smooth", t)` exactly: `1 - (1 - t)^3`.

## Build and install on Windows

From the repository root:

```powershell
git pull --ff-only origin experiment/androidx-progressive-blur
.\example\android\gradlew.bat -p androidx-reference installDebug --no-daemon
adb shell am start -n com.edgefade.androidxref/.MainActivity
```

This reuses only the existing Gradle 9.3.1 wrapper executable. `-p androidx-reference` makes Gradle load the isolated AGP 9.1.1 project, so Expo modules are not evaluated.

Requirements:

- Android SDK Platform 37.1 installed
- JDK 17
- Android 13 / API 33 or newer target device

The app id is `com.edgefade.androidxref`, so it installs beside the Expo example.

## Fair comparison gate

On the Expo app, require:

```text
Requested: AGSL port / Active: agsl
```

On the native reference app, require:

```text
AndroidX official / Active: androidx
```

Use the same radius, edge selection and curve on the same device. Compare stationary text, scrolling text, artwork edges, transition seams, corners and frame pacing.

## CI

The `AndroidX blur reference` workflow installs SDK 37.1 and an isolated Gradle 9.3.1, then runs `assembleDebug` for `androidx-reference`. This proves the installable reference APK compiles against the published AndroidX artifact; it is not a runtime/GPU acceptance test.

## Why the old Expo toolchain override was removed

Earlier experiments tried to enable the official binary inside the Expo SDK 57 app. AAR metadata first required AGP 9.1+ and SDK 37.1. After satisfying those requirements, Expo itself failed while evaluating its Gradle modules because the plugin targets the older AGP DSL. The temporary Expo-upgrade helper and its tests were therefore removed rather than hiding the incompatibility.

The dependency-free AGSL backend remains the candidate for an actual Edge Fade release until the RN/Expo Android toolchain can consume the official alpha directly.
