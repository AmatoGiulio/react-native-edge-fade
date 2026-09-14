# AndroidX progressive blur reference app

This is an isolated native Android app used only to compare Edge Fade's dependency-free AGSL port with the official `androidx.compose.ui:ui-graphics:1.13.0-alpha03` progressive blur implementation.

It deliberately does **not** use Expo, React Native or Compose UI widgets. The playlist is built with ordinary Android Views and the official AndroidX `BlurRadiusSpec` is realized into a platform `RenderEffect` and assigned with `View.setRenderEffect()`.

## Requirements

- Android 13 / API 33 or newer device or emulator
- Android SDK Platform 37.1 installed
- JDK 17
- Gradle 9.3.1. The generated Expo example currently has a compatible wrapper, so it can be reused locally without changing the Expo build.

## Build and install on Windows

From the repository root:

```powershell
git pull --ff-only origin experiment/androidx-progressive-blur
.\example\android\gradlew.bat -p androidx-reference installDebug --no-daemon
adb shell am start -n com.edgefade.androidxref/.MainActivity
```

The app is installed alongside the Expo example under a separate application id: `com.edgefade.androidxref`.

## What to compare

Open the Expo **Progressive Blur Lab** with **AGSL port** active and this native reference app side by side on the same device. Both use:

- 48 generated playlist rows
- radius range 0..48dp, capped to 150px
- top band 92dp
- bottom band 112dp
- optional left/right bands 48dp
- smooth or linear edge progression

The reference status line must read `AndroidX official / Active: androidx` at non-zero radius. The Expo lab must read `Requested: AGSL port / Active: agsl`.

The two apps intentionally keep separate build toolchains. Compose UI 1.13.0-alpha03 requires AGP 9.1+ / compileSdk 37.1, while Expo SDK 57 currently evaluates against the AGP 8.x DSL.
