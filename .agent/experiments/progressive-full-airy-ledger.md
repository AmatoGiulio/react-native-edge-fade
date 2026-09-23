# Progressive FULL airy compositor — experiment ledger

Last updated: 2026-09-22  
Branch: `experiment/pure-cbrt-closed-24px-toe`

## Scope

This branch is an optical experiment for the showcase only.

Hard constraints:

- Do **not** change the public React Native API, public defaults, backend selector, iOS path, or the production `strength=0` Gaussian path.
- Keep experimental runtime work isolated to the internal Android showcase/material path.
- Prefer changes only in:
  - `android/src/main/java/com/edgefade/BlurLabShaders.kt`
  - `android/src/main/java/com/edgefade/EdgeFadeProgressiveStripRenderer.kt`
  - `example/app/showcase.tsx` when scene/benchmark input is required
  - benchmark / experiment documentation under `.agent`, `benchmarks`, or `scripts`
- One optical hypothesis per commit.
- Before implementing a new idea, classify it against the rejected families below. Do not tune a family that has already failed unless the topology is materially different.

## Visual target / failure signature

Target: the reference FULL state has a broad, airy transition from recognizable source content into dense blurred material. It does not read as a horizontal cut, a milky overlay, or a rectangular processed sheet.

Recurring failures observed during this branch:

- CLOSED: a horizontal hard cut remains visible at the progressive boundary.
- FULL/OPEN: residual transparency can create a broad washed / cheap halo.
- Making the mask too weak removes too much material and the result stops reading as blur.
- Increasing only feather length often changes the amount of transition without changing its character.
- A curve can be mathematically smooth and still look like a band because the progressive `cbrt` radius becomes perceptually strong very near zero.

## Baselines to preserve

### Optical body — `93ac1b0`

`fix: rebuild showcase material as continuous optical response`

Established the important optical model:

- one continuous progressive Gaussian;
- internal material response after Gaussian;
- half-resolution material path;
- no fixed screen-space ellipse;
- material density / luminance / chroma response separated from Gaussian radius.

Do not casually replace this body while investigating the entrance.

### Seam hand-off — `590e4f0`

`experiment: overlap sharp and material strip at edge`

Established the first sharp-under-processed overlap:

- sharp source retained under 16 px;
- processed strip faded from transparent to opaque;
- blur/material body after the overlap remained unchanged.

This is the reference point for later entrance experiments.

---

## Attempt ledger

### 1. Local sharp/processed overlap

**Commits:** `590e4f0`

Topology:

`sharp source -> overlap 16 px -> processed strip`

Result:

- removed the literal hard replacement at the clip edge;
- did not create the long airy transition visible in the reference;
- became the base seam guard for later experiments.

**Do not repeat:** simply changing a small fixed overlap width.

---

### 2. Quintic alpha shoulder, 16 px -> 64 px

**Commits:** `531d8b00`, `bcb07cbb`

Changes:

- replaced simple smoothstep with quintic smootherstep;
- widened the local premultiplied-alpha shoulder from 16 px to 64 px;
- sharp source remained underneath the overlap.

Result:

- larger feather, same basic optical family;
- insufficient: the visible transition still read as a band/cut rather than the reference's broad air.

**Rejected family:** fixed-size entrance feather.

---

### 3. Full-band geometric alpha mask

**Commits:** `b07f9d59`, `cef62858`

Changes:

- sharp source kept underneath the **entire** material band;
- processed layer alpha normalized to current visible panel depth;
- full-height smootherstep envelope;
- intended to work at any panel height.

Topology:

`sharp full panel + processed full panel * alpha(distance / panelDepth)`

Result:

- this is the first complete implementation of the “real alpha mask over the whole panel” idea;
- it did not solve the visual target;
- it introduced the same fundamental sharp+processed mixing problem later seen as a washed halo in OPEN.

**Important:** any new proposal that is only “keep sharp underneath + normalize an alpha curve across panel height” is already tested.

---

### 4. Full-area mask driven by optical field instead of geometry

**Commits:** `9d0b7eb1`, `a3d69aac`, `a41acb6`

Changes:

- removed geometric `materialDepth`;
- drove coverage from progressive optical/radius intensity;
- added gamma / accelerated complement;
- later clamped the mask to an exact opaque plateau after a short optical shoulder.

Reason for the plateau:

- the asymptotic alpha curve stayed slightly translucent over too much of FULL;
- sharp + processed mixing produced a broad cheap / washed halo.

Result:

- exact opaque plateau reduced persistent transparency;
- still did not produce the wanted airy reference transition.

**Rejected family:** alpha coverage derived only from existing mask / `radiusIntensity`.

---

### 5. Fixed high-radius processed layer + progressive alpha mask

**Commits:** `dc7b63fb`, `8d8b83b3`, `1531dbde`

Changes:

- removed progressive radius from the FULL material layer;
- rendered one stable, fully processed/high-radius layer;
- used the progressive mask only as the final alpha blend to sharp source.

Topology:

`sharp + fixed fully-processed layer * progressive alpha`

Result:

- materially changed the blur identity;
- separated blur progression from the accepted `cbrt` optical field too aggressively;
- discarded.

**Rejected family:** precompute a fully blurred/materialized sheet and reveal it only through alpha.

---

### 6. Material-only air envelope

**Commits:** `e8c4bf6b`, `2ec9ba21`, `7263250f`

Changes:

- kept Gaussian progression intact;
- softened only material density;
- moved from intensity-space ramp to physical-space entrance distance;
- sized the material shoulder from real panel depth.

Goal:

avoid masking/removing blur while letting material arrive later.

Result:

- useful diagnostic: Gaussian and material entrance can be decoupled;
- did not remove the perceived cut by itself.

**Rejected as complete solution:** only delaying material density while leaving Gaussian progression untouched.

---

### 7. Physical entrance toe on progressive Gaussian radius

**Commits:** `d302d3f9`, `a7c7bb79`, `4ddba8d9`, `8af77bd8`

Changes:

- multiplied the `cbrt` radius field by a physical-space toe near the entrance;
- isolated it to FULL/material;
- tried quintic then cubic easing;
- widened the shoulder with panel depth.

Result:

- attacks the real low-intensity `cbrt` amplification directly;
- however it altered the accepted blur feel / removed too much blur in the shoulder;
- user feedback: “abbiamo perso troppo materiale”, then “non sembra più blur”.

**Rejected family:** attenuate Gaussian radius near the entrance with another toe/easing curve.

---

### 8. Keep Gaussian pure; stretch only material air over CLOSED depth

**Commits:** `9fc41280`, `67ea3e15`, then optical restore `8708e229`

Changes:

- removed Gaussian entrance toe again;
- stretched the physical material envelope;
- on short/CLOSED panels the envelope could span the whole panel;
- on tall/OPEN panels it was capped to avoid a huge halo.

Result:

- CLOSED and OPEN wanted conflicting spans;
- did not remove the core cut consistently;
- optical baseline was restored before trying another compositor.

**Rejected family:** solve the seam only by making material-air span proportional to panel depth.

---

### 9. Depth-adaptive local compositor feather

**Commit:** `3f639cca`

Changes:

- local sharp/processed overlap changed continuously from 16 px to 64 px;
- short panel -> wider feather;
- deep panel -> narrow original feather;
- Gaussian/material optics left unchanged.

Result:

- more robust than a fixed pixel feather;
- still the same local-overlap topology and still insufficient.

**Rejected family:** local feather width adaptation alone.

---

### 10. Global subtle panel alpha over sharp source

**Commits:** `1fb6464b`, `f36c57ee`

Changes:

- retained local seam guard;
- kept sharp content underneath the entire material band;
- multiplied the complete processed premultiplied pixel by a global panel mask;
- conservative coverage: about 0.90 -> 1.00 over panel depth.

Result:

- returned to the same full-panel sharp+processed compositing family as attempts 3/4;
- effect was too subtle to remove the visible CLOSED cut;
- OPEN could still retain a detectable halo / washed region.

**Rejected family:** global full-panel alpha modulation over sharp source, even when the material body itself is untouched.

---

### 11. Previous probe — separate FULL alpha regime

**Commit:** `446b198a`

`experiment: add airy full-panel compositor`

Changes:

- CLOSED keeps the 0.90 -> 1.00 compositor;
- FULL starts processed coverage at 0.10;
- FULL reaches 1.00 over ~82% of panel depth;
- CLOSED/FULL mix selected continuously from panel-depth / viewport ratio.

Assessment after historical review:

**This is not a genuinely new pipeline architecture.**

It combines already-tested ingredients from attempts 3, 4 and 10:

- sharp source under the whole panel;
- processed full panel above it;
- distance/depth-normalized alpha envelope;
- smootherstep alpha rise;
- exact opaque body.

The only new parts are parameterization / regime selection:

- `0.10` FULL starting alpha;
- `0.82` air span;
- depth-ratio blend between compact and FULL.

Therefore **do not continue tuning this family as if it were unexplored**. Keep the commit as an experiment record unless explicitly reverted, but treat it as a duplicate-family probe.

---

### 12. Signed overscan Gaussian support before the panel boundary

**Commits:** `5d48babe`, `3f2ce2a5`

Hypothesis:

The remaining straightness is not primarily the alpha curve. Every previous
pipeline still clipped processed output to `band.visible`, so blur was
mathematically forbidden from existing one pixel before the same straight
horizontal boundary.

New topology:

`sharp source -> faint pure-Gaussian overscan outside panel -> existing progressive material body`

Changes:

- extend the material strip's **output domain** outward beyond the nominal panel;
- extend source capture accordingly;
- create a low-radius Gaussian support field in signed distance space;
- support begins outside the old boundary and grows into it;
- material density still uses the original progressive mask, therefore the
  overscan contains diffusion only — no pearl/smoke grading;
- FULL alpha reveal also begins in signed space outside the old clip;
- the original cbrt Gaussian + material body remains dominant deeper inside;
- CLOSED endpoint remains the compact compositor.

Why this is genuinely different from attempts 1–11:

Those experiments changed overlap, alpha, material density or radius **inside
the same clipped output domain**. This experiment changes where processed
pixels are allowed to exist and gives the Gaussian sampling/support field an
outer shoulder.

Initial constants at `radius=150`:

- outside support: ~108 px;
- inside support: ~143 px;
- maximum bridge Gaussian: ~33 px.

**Status:** implementation complete; pending device CLOSED + FULL capture. The second commit applies the same signed overscan support to the horizontal Gaussian pass as well as the fused vertical/material pass, so the outer bridge is a true 2D Gaussian rather than a vertical-only smear. Do not tune constants until the first visual result is compared with the reference.

---

### 13. Official AndroidX multi-stop radius gradient

**Commit:** `18e312dd`

Context check:

The repository already used the new official API through
`BlurRadiusSpec.shader(maxRadius) { mask }`. What was **not** present was a
direct `BlurRadiusSpec.verticalGradient(List<BlurStop>)` experiment.

New topology:

`sharp -> official direct-radius Gaussian gradient -> existing material grading`

Key differences from the current AGSL path:

- no `mask -> cbrt -> radius` transfer function;
- `BlurStop.radius` values are the Gaussian radii directly;
- 8 radius stops: approximately `0, 1, 4, 12, 30, 65, 110, 150 px`;
- gradient begins outside the nominal panel boundary, where radius is exactly 0;
- output is allowed in that outer region, so the old straight clip is not the
  point at which blur first exists;
- the official effect uses `TileMode.Decal`, matching the sampling semantics
  documented for `BlurredEdgeTreatment.Unbounded`;
- material still uses the existing mask and is absent where its intensity is 0,
  so the outer transition is pure Gaussian diffusion.

Isolation:

- new internal backend: `androidx-gradient`;
- showcase defaults to it only on this experiment branch;
- `?backend=agsl` restores the current custom AGSL baseline for A/B;
- no public API/default, iOS path, or production `strength=0` path is changed.

**Status:** pending device CLOSED + FULL capture. Do not tune the stop profile
before the first A/B against the reference.

Gallery comparison surface:

- `androidx-gradient` is now selectable from the Android Gallery blur renderer
  control next to `auto`, `agsl`, `androidx`, and `scaled`;
- the UI label is shortened to `gradient`, while the internal backend value
  remains explicit;
- the adaptive selector forces it through the exact renderer, so high radii
  cannot silently switch the comparison to the scaled production backend;
- `gallery-renderer-test?backend=androidx-gradient` is also supported.

---

### 14. Astra satin reflection only

**Commit:** `71a6d041`

Goal:

Recover the brighter / more alive material body from the earlier Astra
experiment without restoring the old entrance defect.

What was transplanted:

- the source-luma-driven `localHighlight` response;
- the extra `darkContent` contribution;
- the small satin reflection toward `materialColor`;
- approximately the original Astra reflection magnitude.

What was deliberately **not** restored:

- `edgeField`;
- ellipse / oval geometry;
- `ovalLift`;
- the old shoulder topology;
- silver-body convergence.

Boundary protection:

- reflection is multiplied by the current material `density`;
- a `smoothstep(0.20, 0.58, intensity)` gate suppresses sheen near the
  progressive entrance;
- the AndroidX gradient path keeps its official radius field unchanged;
- the same optical response is applied to both the fused AGSL material path and
  the AndroidX-gradient post-material path so visual comparison stays fair.

**Status:** first capture after `71a6d041` showed essentially no visible
brilliance. Diagnosis: the transplanted response was only a small linear mix
toward the mid-grey `materialColor`; on the current compressed body that is
nearly invisible and can darken already-bright pixels.

Second pass changed the reflection to a screen-style light-energy lift. The
next FULL capture was visibly brighter, but still did not read like the Astra
glass body.

Pixel check on equivalent blank material regions confirmed why: the new body was
already *brighter* than Astra but retained more chroma/variance. Example mid-body
means were approximately current RGB `0.841 / 0.805 / 0.835` versus Astra
`0.803 / 0.801 / 0.803`. So the missing quality is not more white energy.

Third pass therefore restores two Astra optical traits without restoring any
geometry:

- mild luminance convergence toward the neutral material anchor;
- stronger chroma compression so source colour survives as a stain rather than
  dominating the substrate;
- satin reflection remains, but at a lower secondary strength;
- all three are gated by `smoothstep(0.30, 0.68, intensity)`, safely away from
  the entrance.

**Status:** pending CLOSED + FULL light/dark capture after neutral glass-body
compression.

---

### 15. Astra body exact transplant, new entrance retained

**Commit:** `b599ab88`

Goal:

Keep the new airy/official progressive entrance, but restore the actual optical
body that made the earlier Astra version read as pearl/glass.

Topology:

`new progressive radius/entrance -> current density -> Astra optical body`

Restored from the Astra body:

- `pearlLuma` convergence toward the material luminance anchor;
- Astra's dark-content-weighted body density;
- aggressive perceptual chroma compression;
- source colour retained as a low-contrast stain;
- satin `pearlReflection`;
- deep-body convergence toward neutral material with a small residual chroma.

Explicitly **not** restored:

- `edgeField`;
- ellipse / oval geometry;
- `ovalLift`;
- old shoulder shaping;
- any old clip or panel-boundary topology.

Boundary protection:

- the Astra body is driven by the current material `density`;
- it is multiplied by `smoothstep(0.30, 0.68, intensity)`;
- therefore the new blur entrance remains untouched and the strong pearl body
  only develops deeper inside the panel;
- the same response is used for AGSL and `androidx-gradient`.

**Status:** first FULL capture exposed a regression: source cards became
darker/sharper and saturated colour returned strongly, especially the blue lower
card. Root cause was structural: the transplant replaced the accepted current
body with Astra equations while `astraMaterial = density * gate` was still
small. In that region the shader therefore fell back toward raw source
luma/chroma instead of the already-compressed current body.

Correction:

- restore the accepted current material result as `baseGraded/baseResult`;
- compute the Astra body separately as `astraTarget`;
- use `astraMaterial = density` inside that target;
- crossfade **base -> Astra target** with `smoothstep(0.30, 0.68, intensity)`;
- entrance can therefore never re-expose raw source colour, while the deep body
  can still converge fully to the Astra optical response.

**Status:** pending CLOSED + FULL light/dark capture after base-preserving Astra
target blend.

---

### 16. Native runtime optical tuner

**Commits:** `3f3c87d1`, follow-up binding fix

Purpose:

Stop using Git history as the tuning UI. The showcase enables an Android-native
PopupWindow tuner outside the captured EdgeFade scene.

Controls:

- renderer: JS/default, AndroidX gradient, AndroidX shader-mask, AGSL, scaled;
- gradient profile: current airy, exact 93ac1b0 Astra radius distribution,
  linear diagnostic;
- native panel bounds on/off;
- radius, progression, outer air/support, gradient span;
- material strength, surface, exposure, surface progression;
- Astra mix, reflection, body convergence and chroma-compression gains;
- in-memory A/B slots plus copy-to-clipboard / Logcat;
- reset returns control to JS props.

Isolation:

- only an internal native-component enable flag is added; no public library prop;
- production defaults stay unchanged;
- all tuner UI uses Android platform widgets;
- native overrides live separately from JS props, so rerenders do not destroy
  the tuning session.

The Astra gradient profile uses the exact 13-stop radius distribution implied by
93ac1b0: `radius = maxRadius * (1 - alpha)`.

---

### 17. 3f639cc exact material over AndroidX gradient

**Commit:** `75f27383`

Goal:

Use the material/compositing behavior from tree state `3f639cc` while changing
only the Gaussian upstream to the new official AndroidX
`BlurRadiusSpec.verticalGradient` pipeline.

Preserved from `3f639cc`:

- material density equation;
- luminance compression / exposure response;
- chroma transmission response;
- premultiplied material output;
- final entrance coverage applied to the full processed pixel;
- depth-adaptive 16..64px sharp-source overlap.

Deliberate difference:

- the old AGSL cbrt Gaussian is removed from this profile;
- the shader receives AndroidX gradient-blurred content instead;
- outside support / gradient stops remain tunable as part of the new AndroidX
  pipeline.

The `3f639cc exact` material profile does not execute any later Astra
reflection/body additions.

---

### 18. Deep-body fusion over 3f639cc exact

**Commits:** `c75e6bf5`, uniform-binding fix `bbc72645`

Observed issue:

The AndroidX-gradient + 3f639cc configuration has the desired overall material,
but saturated cards remain visible as large soft orange/blue islands in the
middle/deep body. These blobs track source geometry, so increasing Gaussian
radius would only make them larger.

Hypothesis:

Keep the exact 3f639cc shoulder/material response, then progressively reduce
local luminance contrast and chroma only deeper inside the panel.

Implementation:

- the original 3f639cc material equations run first and remain unchanged;
- an optional post-material body-fusion stage is gated by normalized physical
  depth inside the panel;
- the shoulder is untouched before `bodyFusionStart`;
- deep luminance converges toward the existing material luminance anchor;
- deep chroma is attenuated without replacing it with a flat tint;
- no additional Gaussian, alpha-mask topology or source resampling is added.

Initial runtime defaults:

- body fusion: on;
- uniformity: 0.72;
- deep chroma gain: 0.38;
- deep luma compression: 0.50;
- body start: 0.16;
- body end: 0.68.

The native tuner exposes all five values plus an on/off switch. Turning body
fusion off restores the unmodified 3f639cc material/compositor response.

**Observed result:** tonal/chroma compression alone did not remove the large
soft card silhouettes. It reduced their contrast but preserved their spatial
footprint, because every pixel was still transformed independently.

---

### 19. Spatial deep-body diffusion

**Commit:** `0d3bdb90`

Root cause:

The remaining orange/blue blobs are low-frequency spatial structure already
present in the AndroidX-blurred scene. A per-pixel luma/chroma transform cannot
erase their geometry.

Change:

- keep AndroidX `verticalGradient` as the primary Gaussian;
- keep the 3f639cc material equations after it;
- before material grading, only in the deep-body gate, sample the already
  blurred child through a 13-tap two-ring spatial kernel;
- blend that low-frequency field into the scene progressively with panel depth;
- preserve the shoulder exactly before `bodyFusionStart`;
- clamp samples to the captured raster so the wider kernel cannot introduce
  transparent/black edge halos.

Runtime knobs:

- `spatial diffusion`: default 0.88;
- `diffusion radius`: default 64 screen px;
- existing body start/end still control where the diffusion develops.

This is deliberately not another global Gaussian-radius increase: the official
AndroidX blur field is untouched, and the extra spatial averaging exists only
inside the deep body where the card-shaped blobs were visible.

**Observed result:** rejected. A sparse local kernel made the blobs softer but
did not remove their silhouette. The low-frequency card geometry was still
present because every output pixel continued to sample around its own local x/y
position.

---

### 20. Cross-panel low-frequency body field

**Commit:** `7521eed5`

Root cause refinement:

The unwanted forms are not high-frequency detail anymore; they are broad
low-frequency colour masses. More local blur cannot remove their topology.

New model:

- keep AndroidX `verticalGradient` unchanged;
- keep the exact 3f639cc material equations;
- in the deep-body gate, replace the local blurred scene with a scene-derived
  field that is **independent of output x**;
- each field row is the average of five samples distributed across the full
  captured raster;
- vertically smooth that row using y-radius samples above and below;
- because every pixel on a row receives the same field colour, individual card
  silhouettes cannot survive in the deep body;
- the field is still derived from the real blurred scene, so broad warm/cool
  palette changes remain instead of collapsing to a flat material tint.

Runtime:

- `field mix`: default 1.00;
- `vertical field radius`: default 72 px;
- body start/end still preserve the original shoulder before the field takes
  over.

**Observed result:** rejected. Even a field independent of output x did not fix
the visible shapes. This rules out the post-material/body response as the
primary cause.

---

### 21. Restore 3f639cc effective radius field in AndroidX

**Commit:** `abd8a4ec`

Code audit result:

The desired 3f639cc state does **not** feed the material with the current
AndroidX `current airy` radius profile.

At 3f639cc:

- showcase alpha stops are approximately `alpha = 1 - t^3`;
- `presence = 1 - alpha = t^3`;
- fused Gaussian uses `radiusIntensity = cbrt(presence)`;
- therefore the effective Gaussian radius is approximately **linear in t**;
- `progression = 0.90` means max radius is reached at 90% of panel depth and
  held for the final 10%;
- material raster runs at 0.5x.

Current AndroidX `current airy` instead uses:
`0,1,4,12,30,65,110,150px` over the full gradient span. It therefore leaves
far more source geometry intact through the middle of the panel. The broad
orange/blue forms seen by the user are already present in the Gaussian input
before material grading.

Change:

- add explicit AndroidX gradient profile `3f639cc` = direct linear radius;
- for that profile, derive gradient span from the existing `progression`
  value, preserving old `/ progression` semantics;
- add native `3f639 RADIUS` button that sets only upstream radius-field state:
  AndroidX gradient, radius 150, progression 0.90, outer-air 0, body-fusion off;
- do **not** change the user's material tuning;
- failed body-fusion experiments remain available diagnostically but are off by
  default.

This is the first test that preserves the new AndroidX implementation while
feeding the material the same effective radius topology as 3f639cc.

---

### 22. Kernel-topology isolation: exact 3f639cc vs AndroidX hybrid

**Commit:** pending in this commit

Target clarified by the saved clean run:

The desired body is a broad, smooth blue/purple/pink field with essentially no
local card silhouettes. Attempts to remove those shapes downstream failed:
tonal compression, local diffusion and cross-panel field replacement did not
address the real difference.

Code-level difference:

`3f639cc` does not perform one local variable-radius blur. It is a
non-stationary **separable** pipeline:

1. horizontal AGSL Gaussian, radius driven by the cbrt mask per output row;
2. vertical AGSL Gaussian + material, whose samples come from rows that have
   already been horizontally blurred with their own radius.

The official AndroidX variable-radius effect is a different kernel topology.
Matching only the radius-vs-y curve therefore cannot guarantee the same broad
field.

This attempt adds two internal comparators:

- `3f639 exact AGSL`: exact fused shader copied directly from tree state
  `3f639cc`, old output topology, no later air/body experiments;
- `AndroidX + 3f639 H`: exact old horizontal cbrt prefilter first, then the
  official `BlurRadiusSpec.verticalGradient`, then the exact 3f639cc material
  post-pass.

The hybrid keeps AndroidX as the final progressive blur while restoring the
specific horizontal preconditioning that produced the clean old body.

Native tuner adds one-tap `3f639 EXACT` and `3f639 HYBRID` presets.
No production/public path changes.

---

## Rejected / exhausted families

Do not start another experiment whose only substantive change is one of these:

1. fixed 16/32/64 px overlap;
2. wider/narrower smootherstep feather;
3. alpha mask normalized to visible panel height;
4. alpha mask driven by `intensity` or `radiusIntensity`;
5. sharp source under full panel + processed layer alpha;
6. fixed fully blurred/materialized layer revealed by alpha;
7. material-density ramp only;
8. extra Gaussian-radius toe near the boundary;
9. panel-depth-adaptive feather length;
10. another 0.x -> 1.0 global panel coverage curve.

Changing constants, gamma, smootherstep order, plateau threshold, or span within those topologies is **parameter tuning, not a new pipeline**.

## What would count as a genuinely new experiment

A new test should change the rendering/compositing topology, not another curve over the same two layers.

Examples of materially different questions:

- Is the reference entrance produced by **sampling/diffusion outside the visible panel boundary**, rather than fading a processed strip over sharp content?
- Does the progressive field need a different **source support / capture domain** at the entrance while keeping the accepted body kernel?
- Can the transition be created by changing **where processed pixels are sampled from** rather than how their final alpha is mixed?
- Is there a single continuous field that preserves source detail and spreads colour before the dense body without introducing a second sharp/processed cross-fade?

These are hypotheses, not implementation decisions. They must be validated against the reference before code changes.

## Required workflow for the next experiment

1. Read this ledger.
2. State the new topology in one sentence.
3. Name the prior family it differs from.
4. Change only the experimental Android showcase/material path.
5. Keep `strength=0`, public props/defaults, selector, iOS and other library paths untouched.
6. Capture CLOSED + FULL before changing another parameter.
7. Record commit SHA, hypothesis, exact change and visual result in this file.
8. If the result is visually equivalent to an exhausted family, stop instead of sweeping constants.
