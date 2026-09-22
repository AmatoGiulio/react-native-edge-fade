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

### 11. Current HEAD experiment — separate FULL alpha regime

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
