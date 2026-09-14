# AndroidX binary: example build profile

## Summary

This corrects the official-binary setup for PR #5 after the reported
`:app:checkDebugAarMetadata` failure. The metadata in the user's build log requires
**compileSdk >= 37 and AGP >= 9.1.0**, not compileSdk 37.1. The previous library-only
SDK switch left the app at 36 and the root build using AGP 8.12.0.

The example profile selects **AGP 9.1.1 / compileSdk 37 / Gradle >= 9.3.1**.
Google documents AGP 9.1.1 as supporting API 37.0, Gradle 9.3.1 and JDK 17.
This is a toolchain configuration fix, not a shader/visual-quality change.

## Apply to an existing Android example (PowerShell or a POSIX shell)

Run from the repository root, on `experiment/androidx-progressive-blur`:

```sh
git pull --ff-only origin experiment/androidx-progressive-blur
node scripts/configure-androidx-blur.cjs
node scripts/configure-androidx-blur.cjs --check
yarn example android
```

The helper requires Node and an already generated `example/android`. For a fresh
checkout, first run `yarn install` and `yarn example expo prebuild --platform android`.
Do NOT use `prebuild --clean` to apply this fix. There is no dependency reinstall,
cache removal, node_modules patch or emulator restart in the helper.

To inspect proposed file names without writing, use `--dry-run`. The helper
validates all expected file shapes and the Gradle wrapper before any changes.
It refuses unfamiliar/custom build layouts rather than guessing. If the wrapper
is older than 9.3.1, upgrade it separately first. The reported user's wrapper
already runs Gradle 9.3.1. The helper does not rewrite distribution checksums.

Generated native files are not tracked. **Rerun the helper after any future Expo
prebuild that regenerates them.** This is intentionally an explicit example-only
profile, not a published config plugin or a default for library consumers.

## Files changed

In the checkout: library `android/build.gradle`, setup helper, Node regression
tests, this report and an official-binary CI workflow. At helper execution time:

- `example/android/build.gradle`: pin the root AGP classpath to 9.1.1 and set
  `ext.compileSdkVersion = 37` before the Expo root plugin supplies defaults.
- `example/android/app/build.gradle`: keep/instate the template's inheritance of
  `rootProject.ext.compileSdkVersion` instead of a hard-coded 36.
- `example/android/gradle.properties`: enable the binary and set
  `android.compileSdkVersion=37`, `android.builtInKotlin=false`, `android.newDsl=false`.

The last two flags are documented AGP 9 migration opt-outs. Existing modules use
`kotlin-android` and the legacy DSL; this profile does not attempt to migrate every
Expo/RN dependency to built-in Kotlin. They are NOT metadata-check suppression.
`targetSdk`, `minSdk`, Kotlin/KSP versions, NDK, wrapper and signing are not changed.
The helper preserves CRLF, and saves the first original of each changed file next
to it as `*.before-androidx-blur`. Review those originals before any manual restore.

## Why this change / baseline before

Observed from the submitted log: SDK 37.1 installed successfully; the failure is
AAR metadata, with app SDK 36 and AGP 8.12.0. Installing a platform does not change
an app's compileSdk, and raising only a library's compileSdk cannot fix the app.
The same two requirements are repeated across five Compose artifacts; they are
not ten separate shader failures. We do not disable AAR metadata checks, suppress
SDK warnings, downgrade Compose or change `1.13.0-alpha03` to a different backend.

## Result after / delta

Local validation: Node syntax check and 10 regression tests passed. Tests cover
AGP pinning, root/app SDK alignment, flags, idempotence, Windows CRLF/BOM, preserving
originals, wrapper guards, refusing custom layouts and concurrent-edit detection.
These are source transformation tests, NOT proof of an Android build.

A dedicated CI workflow applies this profile after Expo prebuild and executes
`:app:checkDebugAarMetadata` and `:react-native-edge-fade:compileDebugKotlin` with
the real official dependency. Read that run's outcome; merely adding this workflow
is not a passing build. It does not assemble/install an APK or validate GPU output.

## Risks remaining / tests still missing

Full app assembly, any Kotlin/KSP/dependency interaction revealed by that build,
AndroidX runtime execution, visual comparison and device performance remain gates.
Do not use Kotlin metadata-version suppression flags if a later task reports a
version mismatch. That would require a compatible compiler configuration instead.
At a nonzero blur radius, require `Requested: AndroidX / Active: androidx` in the
demo before treating a screenshot as output from the official library.

## What I deliberately did not change

No renderer, AGSL kernel, public API, iOS, lens, main branch, package/lockfile,
release version or npm publication changes. No unverified first-to-market claim.

## Sources

- User-provided AAR metadata log, 2026-09-14 (SDK >= 37; AGP >= 9.1.0).
- https://developer.android.com/build/releases/agp-9-1-0-release-notes
- https://developer.android.com/build/migrate-to-built-in-kotlin#opt-out
- https://docs.expo.dev/versions/v57.0.0/sdk/build-properties/
- Expo Groovy template and ExpoRootProjectPlugin inspected from expo/expo;
  the helper validates the installed template instead of assuming it is identical.
