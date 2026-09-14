# AndroidX binary: example build profile

## Summary

This addresses the reported `:app:checkDebugAarMetadata` failure in PR #5.
The application was still using SDK 36 / AGP 8.12.0 after enabling the binary.
Installing SDK 37.1 for the library did not configure the consuming application.

**Correction to the first version of this profile:** the diagnostic reports the
major API 37, but Compose 1.13.0-alpha03 release notes explicitly require
**compileSdk 37.1** transitively. The major-only interpretation was insufficient.
The profile now sets **37.1 on the application and every Android library module**.
Expo's integer major-version property stays 37; `compileSdkMinor = 1` is applied
through the public `androidComponents.finalizeDsl` callback after module DSLs.

The profile pins **AGP 9.1.1**, satisfying the AAR's stated minimum 9.1.0, and
requires **Gradle >= 9.3.1** / JDK 17. AGP 9.1.1 release notes list tested API
support through 37.0, so a warning about 37.1 may remain. We do not suppress it
or call this a fully supported/validated combination on that basis alone.
The actual metadata/Kotlin build is a separate CI gate, not assumed to pass.

## Apply to the existing Windows checkout

From the repository root, on `experiment/androidx-progressive-blur`:

```powershell
git pull --ff-only origin experiment/androidx-progressive-blur
node scripts/configure-androidx-blur.cjs
node scripts/configure-androidx-blur.cjs --check
yarn example android
```

Stop if the helper reports an error. `--check` validates the generated source
configuration; its PASS does not mean that Android compilation has succeeded.
To inspect planned file names without writing, use `--dry-run`.

For a fresh checkout only, install project dependencies and generate Android
first: `yarn install`, then `yarn example expo prebuild --platform android`.
Do not use `prebuild --clean` just to apply this fix. The helper does not
reinstall dependencies, remove caches, patch node_modules or restart emulators.

Generated native files are untracked. **Rerun the helper after an Expo prebuild
regenerates them.** This is an explicit example-only profile, not a published
config plugin or a new default for library consumers.

## Files changed

Repository files: library `android/build.gradle`, setup helper, Node tests,
this report, the experiment guide and an official-binary CI workflow.
At helper execution time:

- `example/android/build.gradle`: pin root AGP to 9.1.1; set integer major SDK
  before Expo defaults; register finalizeDsl to set SDK 37/minor 1 for app/libs.
- `example/android/app/build.gradle`: retain/reinstate the template's major SDK
  inheritance. FinalizeDsl adds the minor after this setting.
- `example/android/gradle.properties`: enable `edgeFadeAndroidxBlur=true`, set
  `android.compileSdkVersion=37`, `android.builtInKotlin=false`, `android.newDsl=false`.

The last two flags are Google's temporary AGP 9 legacy Kotlin/DSL opt-outs, not
metadata suppression. They preserve the existing `kotlin-android` setup instead
of migrating every Expo/RN module to built-in Kotlin in this experiment.

The helper validates all expected file shapes and the existing Gradle wrapper
before writing. It refuses unfamiliar layouts, wrappers below 9.3.1 or Gradle 10,
and explicit AGP versions newer than the selected profile. It preserves CRLF
and first-original backups named `*.before-androidx-blur`. No wrapper/checksum,
Kotlin/KSP, NDK, signing, targetSdk or minSdk changes are made.

## Why this change / baseline before

The submitted log repeats two incompatibilities across five artifacts: app SDK
36 and AGP 8.12.0. These are not ten independent shader failures. Library-only
SDK changes cannot fix an app's AAR metadata check. The Compose release notes
add the minor API requirement that was not explicit in that error wording.

The first CI attempt stopped before compilation because `platforms;android-37`
was not a valid SDK Manager package. CI now requests `platforms;android-37.1`,
the same package installed successfully in the submitted Windows log.

## Result after / delta

Local validation: Node syntax check and **12 regression tests passed**. They
cover AGP pinning, major/minor propagation, upgrading an earlier managed block,
flags, idempotence, CRLF/BOM, backups, wrapper guards, refusal of custom layouts
and concurrent edits. These are source transformation tests, NOT a native build.

The dedicated `AndroidX blur build profile` workflow installs the compile SDK,
generates the example, applies this profile, then runs the real tasks:
`:app:checkDebugAarMetadata` and `:react-native-edge-fade:compileDebugKotlin`.
Read its latest outcome in PR #5. It does not assemble/install an APK or test GPU.

## Risks remaining / tests still missing

AAR metadata, Gradle/AGP/Expo/Kotlin compatibility, full app assembly, official
AndroidX runtime output and performance need their actual build/device gates.
A warning about an untested minor SDK is distinct from a hard metadata error,
but neither should be hidden. Do not use metadata-version suppression or disable
AAR checks to make this reference experiment appear to work.

At nonzero radius, require `Requested: AndroidX / Active: androidx` before
interpreting a screenshot as output from the official binary.

## What I deliberately did not change

No rendering logic, AGSL kernel, public API, iOS, lens, main branch, package version,
dependency lockfile, release tag or npm publication. No performance/primacy claim.

## Sources

- Submitted Windows AAR metadata log, 2026-09-14.
- https://developer.android.com/jetpack/androidx/releases/compose-material#1.13.0-alpha03
- https://developer.android.com/jetpack/androidx/releases/compose-animation#1.13.0-alpha03
- https://developer.android.com/build/releases/agp-9-1-0-release-notes
- https://developer.android.com/build/migrate-to-built-in-kotlin#opt-out
- https://developer.android.com/reference/tools/gradle-api/9.1/com/android/build/api/dsl/ApplicationExtension
- https://docs.expo.dev/versions/v57.0.0/sdk/build-properties/
- Inspected Expo Groovy template and ExpoRootProjectPlugin; the helper validates
  the installed generated file shape rather than assuming identical versions.
