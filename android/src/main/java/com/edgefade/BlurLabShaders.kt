/*
 * Copyright 2026 The Android Open Source Project
 * Modifications Copyright 2026 Giulio Amato
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The paired-tap blur kernel is adapted from AndroidX BlurShaders.kt,
 * inspected Git blob 9f2bfda9ce672c3d45d4f03cb54fd6641a3cbdcd.
 * Changes: clamp-only bounds, local strip coordinates, per-instance shaders,
 * and a four-edge radius mask. This is a port, NOT the Compose binary.
 * See android/PROGRESSIVE_BLUR_NOTICE.md.
 */
package com.edgefade

internal object BlurLabShaders {
  // Pure progressive Gaussian source generation. There is deliberately no
  // saturation, lift, tint, opacity cross-fade or material grading in either
  // pass: the only spatially varying quantity is the Gaussian radius itself.
  fun pass(vertical: Boolean): String {
    val offset = if (vertical) "float2(0.0, d)" else "float2(d, 0.0)"
    val axis = if (vertical) "y" else "x"

    return """
      uniform shader content;
      uniform shader mask;
      uniform float blurRadius;
      uniform float2 extent;
      // Material mode uses continuous tap support so the progressive radius
      // cannot quantize into visible horizontal bands. Public strength=0 keeps
      // the legacy paired-tap kernel pixel-identical.
      uniform float continuousSupport;
      uniform float2 materialOrigin;
      uniform float2 materialViewSize;
      uniform float4 materialEdges;
      uniform float materialProgression;
      float materialPosition(float distance, float depth) {
        if (depth <= 0.0 || distance >= depth) return 0.0;
        return clamp((1.0 - distance / depth) / materialProgression, 0.0, 1.0);
      }
      const float maxRadius = 150.0;
      float gaussian(float x, float sigma) {
        return exp(-(x * x) / (2.0 * sigma * sigma));
      }
      float inside(float2 p) {
        return step(0.0, p.$axis) * (1.0 - step(extent.$axis, p.$axis));
      }
      half4 main(float2 coord) {
        float intensity = clamp(mask.eval(coord).a, 0.0, 1.0);
        float radiusIntensity = intensity;
        if (continuousSupport > 0.5) {
          float2 p = coord + materialOrigin;
          float t = max(max(
            materialPosition(p.y, materialEdges.x),
            materialPosition(materialViewSize.y - p.y, materialEdges.y)), max(
            materialPosition(p.x, materialEdges.z),
            materialPosition(materialViewSize.x - p.x, materialEdges.w)));
          // Showcase photos end near t=0.45; 0.48 also protects the vertical
          // kernel footprint. Ramp broadly through the body, reaching 0.72.
          // Keep the original arithmetic throughout the untouched shoulder.
          if (t > 0.48) {
            float u = clamp((t - 0.48) / (0.72 - 0.48), 0.0, 1.0);
            float w = u * u * u * (u * (6.0 * u - 15.0) + 10.0);
            radiusIntensity = intensity + w * (pow(intensity, 1.0 / 3.0) - intensity);
          }
        }
        float radius = blurRadius * radiusIntensity;
        float r = floor(radius);
        float4 sampled = float4(content.eval(coord));

        if (continuousSupport > 0.5 && radius > 0.0) {
          float sigma = max(radius / 2.0, 1.0);
          float weightSum = 1.0;
          float4 result = sampled;

          // Fade every new sample into the kernel over a one-pixel radius
          // interval. The support grows continuously instead of snapping at
          // floor(radius), which is what produced the dark-mode scan lines.
          for (float i = 1.0; i < maxRadius; i += 2.0) {
            if (radius <= i - 0.5) break;

            float lowCoverage = smoothstep(i - 0.5, i + 0.5, radius);
            float highCoverage = smoothstep(i + 0.5, i + 1.5, radius);
            float low = gaussian(i, sigma) * lowCoverage;
            float high = gaussian(i + 1.0, sigma) * highCoverage;
            float weight = low + high;
            if (weight <= 0.000001) continue;

            float d = i + high / weight;
            float2 offset = $offset;
            float2 a = coord - offset;
            float2 b = coord + offset;
            if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
            if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
          }
          sampled = result / weightSum;
        } else if (r >= 1.0) {
          float sigma = max(radius / 2.0, 1.0);
          float weightSum = 1.0;
          float4 result = sampled;
          for (float i = 1.0; i < maxRadius; i += 2.0) {
            if (i >= r) break;
            float low = gaussian(i, sigma);
            float high = gaussian(i + 1.0, sigma);
            float weight = low + high;
            float d = i + high / weight;
            float2 offset = $offset;
            float2 a = coord - offset;
            float2 b = coord + offset;
            if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
            if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
          }
          float odd = mod(r, 2.0) * (1.0 - step(maxRadius, r));
          if (odd > 0.0) {
            float weight = gaussian(r, sigma);
            float d = r;
            float2 offset = $offset;
            float2 a = coord - offset;
            float2 b = coord + offset;
            if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
            if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
          }
          sampled = result / weightSum;
        }
        return half4(sampled);
      }
    """.trimIndent()
  }


  const val scaledOverlay = """
    uniform shader content;
    uniform shader mask;
    uniform float fullBlurRadius;

    half4 main(float2 coord) {
      half4 blurred = content.eval(coord);
      float intensity = clamp(mask.eval(coord).a, 0.0, 1.0);
      float radius = fullBlurRadius * intensity;

      // Match the successful GLES scaled-continuous composite: keep the
      // identity end exact, then hand over smoothly to the 0.75x Gaussian.
      float blurMix = smoothstep(0.75, 3.0, radius);
      return blurred * half4(blurMix);
    }
  """

  // The mask returns radius / maximumRadius, NOT content opacity. At corners
  // max() combines the edges without stacking blur or multiplying opacity.
  val mask = """
    uniform float2 origin;
    uniform float2 viewSize;
    uniform float4 edges;
    uniform float progression;
    uniform float curve[32];
    float sampleCurve(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      // AGSL only permits constant or unrollable-loop array indices.
      // A per-pixel int(floor(x)) index fails RuntimeShader compilation.
      for (int i = 0; i < 31; i++) {
        if (x <= float(i + 1)) {
          return mix(curve[i], curve[i + 1], x - float(i));
        }
      }
      return curve[31];
    }
    float presence(float distance, float depth) {
      if (depth <= 0.0 || distance >= depth) return 0.0;
      float t = clamp((1.0 - distance / depth) / progression, 0.0, 1.0);
      return clamp(sampleCurve(t), 0.0, 1.0);
    }
    half4 main(float2 local) {
      float2 p = local + origin;
      float a = max(presence(p.y, edges.x), presence(viewSize.y - p.y, edges.y));
      float b = max(presence(p.x, edges.z), presence(viewSize.x - p.x, edges.w));
      return half4(0.0, 0.0, 0.0, max(a, b));
    }
  """.trimIndent()

  // Shared golden radius-mask used by both BlurLab AGSL and the public exact
  // renderer. All curves are sampled through the same 32-entry LUT path so
  // forcing AGSL in EdgeFadeView is pixel-equivalent to the Lab reference.
  val maskPerEdge = """
    uniform float2 origin;
    uniform float2 viewSize;
    uniform float4 edges;
    uniform float progression;
    uniform float curveTop[32];
    uniform float curveBottom[32];
    uniform float curveLeft[32];
    uniform float curveRight[32];
    // Living bottom-front warp. All default to 0, which makes depthB
    // collapse back to edges.y exactly (byte-identical to the pre-wave mask).
    uniform float waveAmp;
    uniform float waveDome;
    uniform float waveTime;
    // Marea V0 surface deformation (raster px): x = amplitude, y = centre x,
    // z = core sigma, w = outer sigma; tideBeta scales the outer gaussian so
    // the signed area stays balanced. amplitude 0 is a no-op.
    uniform float4 tide;
    uniform float tideBeta;
    uniform float tideWall;
    // Core profile exponent: 2 = gaussian dome, lower = pointed flame tip.
    uniform float tideSharp;
    // Flame flicker: x = amplitude (same units as tide.x), y = time (s).
    uniform float2 tideFlicker;

    // Travelling ripples along the tongue, in units of the core sigma, so the
    // flame edge licks up and down; strongest on the body (sqrt g1).
    float tideFlickerAt(float d, float g1) {
      if (tideFlicker.x == 0.0) return 0.0;
      float u = d / tide.z;
      float t = tideFlicker.y;
      float n = 0.55 * sin(u * 1.9 + t * 5.1) +
        0.30 * sin(u * 3.7 - t * 7.3 + 1.7) +
        0.15 * sin(u * 6.1 + t * 11.0 + 0.4);
      return tideFlicker.x * sqrt(g1) * n;
    }

    // tideWall (same units, > 0) is the top edge of the view: the crest
    // cannot pass it, so it flattens and spreads against it (smooth min).
    float tideAt(float x) {
      if (tide.x == 0.0) return 0.0;
      float d = x - tide.y;
      float g1 = exp(-0.5 * pow(abs(d) / tide.z, tideSharp));
      float g2 = exp(-0.5 * d * d / (tide.w * tide.w));
      float h = tide.x * (g1 - tideBeta * g2) + tideFlickerAt(d, g1);
      if (tideWall <= 0.0) return h;
      float k = 0.04 * tideWall;
      float over = (tideWall - h) / k;
      if (over > 20.0) return h;
      return tideWall - k * log(1.0 + exp(over));
    }

    float sampleTop(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      for (int i = 0; i < 31; i++) {
        if (x <= float(i + 1)) return mix(curveTop[i], curveTop[i + 1], x - float(i));
      }
      return curveTop[31];
    }

    float sampleBottom(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      for (int i = 0; i < 31; i++) {
        if (x <= float(i + 1)) return mix(curveBottom[i], curveBottom[i + 1], x - float(i));
      }
      return curveBottom[31];
    }

    float sampleLeft(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      for (int i = 0; i < 31; i++) {
        if (x <= float(i + 1)) return mix(curveLeft[i], curveLeft[i + 1], x - float(i));
      }
      return curveLeft[31];
    }

    float sampleRight(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      for (int i = 0; i < 31; i++) {
        if (x <= float(i + 1)) return mix(curveRight[i], curveRight[i + 1], x - float(i));
      }
      return curveRight[31];
    }

    float position(float distance, float depth) {
      if (depth <= 0.0 || distance >= depth) return -1.0;
      return clamp((1.0 - distance / depth) / progression, 0.0, 1.0);
    }

    half4 main(float2 local) {
      float2 p = local + origin;
      float topPos = position(p.y, edges.x);

      // Organic bottom front: a parabolic dome (centre higher than sides)
      // plus a three-octave noise displacement, both in depth px. depthB
      // collapses to edges.y when waveDome/waveAmp are 0, so the identity
      // mask is pixel-unchanged.
      float xn = p.x / max(viewSize.x, 1.0);
      float dome = waveDome * (1.0 - (2.0 * xn - 1.0) * (2.0 * xn - 1.0));
      float n = 0.55 * sin(xn * 6.2831 * 1.15 + waveTime * 1.3) +
        0.30 * sin(xn * 6.2831 * 2.35 - waveTime * 0.9 + 1.7) +
        0.15 * sin(xn * 6.2831 * 4.1 + waveTime * 2.1 + 0.4);
      float depthB = edges.y <= 0.0 ? edges.y : max(edges.y + dome + waveAmp * n + tideAt(p.x), 0.0);
      float bottomPos = position(viewSize.y - p.y, depthB);

      float leftPos = position(p.x, edges.z);
      float rightPos = position(viewSize.x - p.x, edges.w);

      float top = topPos < 0.0 ? 0.0 : sampleTop(topPos);
      float bottom = bottomPos < 0.0 ? 0.0 : sampleBottom(bottomPos);
      float left = leftPos < 0.0 ? 0.0 : sampleLeft(leftPos);
      float right = rightPos < 0.0 ? 0.0 : sampleRight(rightPos);

      return half4(0.0, 0.0, 0.0, max(max(top, bottom), max(left, right)));
    }
  """.trimIndent()


  // Demo-only chroma extractor for the low-resolution colour field.
  // It suppresses neutral/luminance structure before the wide Gaussian so the
  // field behaves like weighted colour diffusion instead of a blurred copy of
  // rectangular thumbnails. neutralWeight raises the floor so low-chroma
  // page/card backgrounds diffuse too, letting the reference fuse blurred
  // cards and the page into one wash; default 0 keeps chroma-only behaviour.
  val colorFieldSource = """
    uniform shader content;
    uniform float chromaGate;
    uniform float chromaGain;
    uniform float lumaMix;
    uniform float neutralWeight;
    // Neutral pivot the carried luminance mixes toward. 0.5 in light theme
    // (byte-identical to the previous fixed-pivot behaviour); pivots toward
    // the dark material anchor's own luminance as the theme goes dark, which
    // removes the residual grey/white haze the fixed 0.5 pivot produced over
    // dark content.
    uniform float lumaPivot;

    half4 main(float2 coord) {
      half4 source = content.eval(coord);
      float sourceAlpha = max(float(source.a), 0.0001);
      float3 rgb = clamp(float3(source.rgb) / sourceAlpha, 0.0, 1.0);

      float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
      float maxChannel = max(rgb.r, max(rgb.g, rgb.b));
      float minChannel = min(rgb.r, min(rgb.g, rgb.b));
      float chromaAmount = maxChannel - minChannel;

      float gate = clamp(chromaGate, 0.0, 0.25);
      float weight = smoothstep(gate, gate + 0.18, chromaAmount);
      weight = pow(weight, 0.72);
      weight = max(weight, clamp(neutralWeight, 0.0, 1.0));

      // Remove most local luminance geometry while preserving hue direction.
      // Neutral page/card backgrounds therefore contribute almost no weight,
      // while saturated image colours mix together through the later Gaussian.
      float carriedLuma = mix(lumaPivot, luma, clamp(lumaMix, 0.0, 1.0));
      float3 chroma = rgb - float3(luma);
      float3 fieldRgb =
        clamp(float3(carriedLuma) + chroma * clamp(chromaGain, 0.5, 2.5), 0.0, 1.0);

      float coverage = weight * float(source.a);
      return half4(fieldRgb * coverage, coverage);
    }
  """.trimIndent()

  // Triangular dither on premultiplied low-res colour-field texels.
  val lowResDither = """
    uniform shader content;

    // Hoskins "hash without sine": white noise, fp32-stable at screen coords.
    // (Interleaved gradient noise has a line structure that showed up as
    // streaks under partial field coverage.)
    float ign(float2 p) {
      float3 p3 = fract(float3(p.xyx) * 0.1031);
      p3 += dot(p3, p3.yzx + 33.33);
      return fract((p3.x + p3.y) * p3.z);
    }

    half4 main(float2 coord) {
      half4 c = content.eval(coord);
      // +-3 LSB: the ~5x bilinear upscale averages this noise down to ~1 LSB,
      // which is what it takes to break the low-res 8-bit contours.
      float n = (ign(coord) + ign(coord + float2(47.0, 17.0)) - 1.0) * (3.0 / 255.0);
      return half4(clamp(float3(c.rgb) + n * float(c.a), 0.0, float(c.a)), c.a);
    }
  """.trimIndent()

  // Demo-only optical response after ONE continuously varying Gaussian.
  // No screen-space shape: the apparent contour must come from source colour.
  val materialComposite = """
    uniform shader content;
    uniform shader mask;
    uniform float materialStrength;
    uniform float3 materialColor;
    uniform float materialExposure;
    uniform float materialSurface;
    uniform float materialSurfaceProgression;
    uniform float materialCurveHeight;
    uniform float materialCurveOffset;
    uniform float materialOverlayMode;
    uniform float materialOverlayMix;
    // Debug-only "fieldmask" stage: the field is processed at full density
    // (materialIntensity forced to 1 below) regardless of mask intensity,
    // then a final alpha mask built from the ORIGINAL mask intensity is
    // applied to the already-processed result. 0 (every other stage) leaves
    // both the density path and the overlay return byte-identical.
    uniform float fieldMaskMode;
    // fieldmask only: the low-res processed colour field, sampled directly so
    // the scene/field mix happens in this pass (content = sharp scene).
    uniform shader field;
    // fieldmask geometry: x = raster origin y, y = raster scale,
    // z = view height px, w = exact bottom panel depth px.
    uniform float4 maskGeom;
    // Scales the fieldmask dissolve span (1 at rest, < 1 during the theme lift).
    uniform float maskSpanScale;
    // Light wave V0 (fieldmask): a wide exposure wave through the material.
    // Centre in panel depth (0 bottom, 1 top), gain in stops (0 = off) and
    // gaussian sigma in depth units. Diffusion/detail are never touched.
    uniform float lightWaveCenter;
    uniform float lightWaveStops;
    uniform float lightWaveWidth;
    // Marea V0 (fieldmask): x = amplitude in screen px, y = centre x, z = core
    // sigma, w = outer sigma (raster px); tideBeta balances the signed area.
    // meniscus: x = drag px per px of surface height, y = band sigma px.
    // amplitude 0 is a no-op.
    uniform float4 tide;
    uniform float tideBeta;
    uniform float tideWall;
    // Core profile exponent: 2 = gaussian dome, lower = pointed flame tip.
    uniform float tideSharp;
    // Flame flicker: x = amplitude (same units as tide.x), y = time (s).
    uniform float2 tideFlicker;

    // Travelling ripples along the tongue, in units of the core sigma, so the
    // flame edge licks up and down; strongest on the body (sqrt g1).
    float tideFlickerAt(float d, float g1) {
      if (tideFlicker.x == 0.0) return 0.0;
      float u = d / tide.z;
      float t = tideFlicker.y;
      float n = 0.55 * sin(u * 1.9 + t * 5.1) +
        0.30 * sin(u * 3.7 - t * 7.3 + 1.7) +
        0.15 * sin(u * 6.1 + t * 11.0 + 0.4);
      return tideFlicker.x * sqrt(g1) * n;
    }
    uniform float tidePile;
    uniform float2 meniscus;
    // Impact shell (fieldmask), screen px: rippleSrc x = impact x on the top
    // edge, y = age s, z = strength (0 = off), w = view width; rippleShape x =
    // expansion time s, y = band sigma px, z = displacement px, w = max radius.
    uniform float4 rippleSrc;
    uniform float4 rippleShape;
    // Lens riding the flame surface (fieldmask), screen px: x = displacement,
    // y = band sigma, z = chromatic split, w = iridescent glow.
    uniform float4 lensFront;
    // 0..1 presence of the flame lens: scales its bend, light and sheen
    // together so it fades out as one piece while the flame settles.
    uniform float lensLevel;
    // How much of the impact shell is seen (light, rim, sheen, frosted
    // wake), independent of how much it bends: 0 = pure refraction.
    uniform float shellLook;
    // Strip source left edge in screen px (raster -> screen x).
    uniform float srcLeft;

    half3 opticSpectrum(float t) {
      return half3(0.5 + 0.5 * cos(6.2831853 * (t + float3(0.0, 0.33, 0.67))));
    }

    // Glass-dome lighting shared by the flame lens and the impact shell
    // (chessboard wave-shader): a normal built from the dome slope gives a lit
    // near flank, a shaded far flank, a sharp glint and a fresnel lip, plus an
    // analogous green-blue-purple sheen on the crest.
    float3 opticShade(float3 col, float2 slope, float shell, float rim, float hue, float glow) {
      if (shell + rim <= 0.0005) return col;
      float3 n = normalize(float3(slope, 1.0));
      float3 l = normalize(float3(-0.5, -0.78, 0.6));
      float3 hv = normalize(l + float3(0.0, 0.0, 1.0));
      float diff = dot(n, l);
      float spec = pow(max(dot(n, hv), 0.0), 60.0);
      // Added light only fills the headroom above the pixel, so a light
      // theme reads the glass through its shaded flank and tint instead of
      // clipping to white; a dark theme gets the full glint and sheen.
      float room = 1.0 - dot(col, float3(0.2126, 0.7152, 0.0722));
      float3 irid = float3(opticSpectrum(hue));
      col *= 1.0 - clamp(-diff, 0.0, 1.0) * shell * 0.45;
      col += float3(0.82, 0.88, 1.0) * (clamp(diff, 0.0, 1.0) * shell * 0.32 * room);
      col += float3(spec * shell * 1.4 * (0.25 + 0.75 * room));
      col += float3(0.85, 0.9, 1.0) * (rim * 0.18 * room);
      col = mix(col, col * (0.6 + 0.8 * irid), clamp(shell * glow, 0.0, 1.0));
      col += irid * (shell * glow * 0.6 * room);
      return clamp(col, 0.0, 1.0);
    }
    // "Focus" theme transition: replaces backdrop colour with veilColor.
    // All three default to 0, which leaves `result` byte-identical.
    uniform float materialVeil;        // 0..1 amount of backdrop replaced by the veil colour
    uniform float materialNeutrality;  // 0..1 chroma removal
    uniform float materialLumaFlatten; // 0..1 luminance range compression toward the veil luminance
    uniform float3 veilColor;
    // Light "bloom" band at the front of the mask. 0 is a no-op.
    uniform float frontGlow;
    // Screen-space px of the full (unscaled) blur radius for a half-res
    // androidx-gradient strip whose sharp content is drawn underneath rather
    // than clipped out (see EdgeFadeProgressiveStripRenderer.crossfadeEntrance).
    // 0 (every other backend/path) disables the ramp entirely, leaving the
    // non-overlay return byte-identical to before this uniform existed.
    uniform float entranceRadiusPx;

    // Hoskins "hash without sine": white noise, fp32-stable at screen coords.
    // (Interleaved gradient noise has a line structure that showed up as
    // streaks under partial field coverage.)
    float ign(float2 p) {
      float3 p3 = fract(float3(p.xyx) * 0.1031);
      p3 += dot(p3, p3.yzx + 33.33);
      return fract((p3.x + p3.y) * p3.z);
    }

    float ditherNoise(float2 coord) {
      // Interleaved gradient noise (Jimenez): unlike a sin-hash, this stays
      // precision-safe at the large screen-space coords a mobile GPU's mediump
      // float sees, where sin-hash collapses into visible integer plateaus.
      // Two staggered samples give a triangular PDF, +-1.5 LSB.
      return (ign(coord) + ign(coord + float2(47.0, 17.0)) - 1.0) * (1.5 / 255.0);
    }

    // fieldmask: normalised panel depth measured upward (0 at the bottom
    // edge, 1 at the panel top).
    // tideWall (same units, > 0) is the top edge of the view: the crest
    // cannot pass it, so it flattens and spreads against it (smooth min).
    float tideRaw(float x) {
      if (tide.x == 0.0) return 0.0;
      float d = x - tide.y;
      float g1 = exp(-0.5 * pow(abs(d) / tide.z, tideSharp));
      float g2 = exp(-0.5 * d * d / (tide.w * tide.w));
      return tide.x * (g1 - tideBeta * g2) + tideFlickerAt(d, g1);
    }

    float tideClamp(float h) {
      if (tideWall <= 0.0) return h;
      float k = 0.04 * tideWall;
      float over = (tideWall - h) / k;
      if (over > 20.0) return h;
      return tideWall - k * log(1.0 + exp(over));
    }

    float tideAt(float x) {
      return tideClamp(tideRaw(x));
    }

    // The tide lifts the whole material body (not a stretch of its ramp), so
    // the dense part travels with the surface like a rising mass. Where the
    // crest is pressed into the top edge the surface stops but the body keeps
    // coming (tidePile x the penetration): the material piles up against it.
    float fmDepthUp(float2 coord) {
      float screenY = (coord.y + maskGeom.x) / max(maskGeom.y, 0.0001);
      float raw = tideRaw(coord.x);
      float h = tideClamp(raw);
      float lift = h + tidePile * max(raw - h, 0.0);
      return (maskGeom.z - screenY - lift) / max(maskGeom.w, 1.0);
    }

    // Meniscus: the sharp scene next to the surface is dragged along with its
    // local height (raster px, positive = sample below = content moves up).
    float meniscusShift(float2 coord) {
      float h = tideAt(coord.x);
      float screenY = (coord.y + maskGeom.x) / max(maskGeom.y, 0.0001);
      float surfaceY = maskGeom.z - (maskGeom.w + h);
      float d = (surfaceY - screenY) / max(meniscus.y, 1.0);
      return meniscus.x * h * exp(-0.5 * d * d) * maskGeom.y;
    }

    // fieldmask coverage: 0 -> 1 from the panel top (+ offset) over
    // curveHeight * 1.25 (defaults 0 -> 0.50). smoothstep keeps zero slope at
    // the boundary (no line); the 1.6 power brings the body forward.
    float fmCoverage(float2 coord) {
      float t = 1.0 - fmDepthUp(coord);
      float curveHeight = clamp(materialCurveHeight, 0.25, 1.5);
      float m = clamp(
        (t - materialCurveOffset) / max(curveHeight * 1.25 * maskSpanScale, 0.01),
        0.0, 1.0);
      float eased = m * m * (3.0 - 2.0 * m);
      return clamp(materialOverlayMix, 0.0, 1.0) * (1.0 - pow(1.0 - eased, 1.6));
    }

    half4 main(float2 coord) {
      half4 blurred = content.eval(coord);
      half4 sharp = blurred;
      float2 opticSlope = float2(0.0);
      float opticShell = 0.0;
      float opticRim = 0.0;
      float opticHue = 0.42;
      float opticGlow = 0.0;
      float opticFrost = 0.0;
      if (fieldMaskMode > 0.5) {
        float scale = max(maskGeom.y, 0.0001);
        float2 s = float2(coord.x / scale + srcLeft, (coord.y + maskGeom.x) / scale);
        float2 disp = float2(0.0);
        float2 ca = float2(0.0);
        // Lens riding the flame surface: a gaussian dome across the signed
        // distance to the surface, measured along its true normal, magnifies
        // what the rising front passes over.
        if (lensFront.x != 0.0 && tide.x != 0.0) {
          float e = 2.0 * scale;
          float hs = tideAt(coord.x);
          float dh = (tideAt(coord.x + e) - tideAt(coord.x - e)) / (2.0 * e);
          float norm = sqrt(1.0 + dh * dh);
          float2 dir = float2(-dh, -1.0) / norm;
          float surfaceY = maskGeom.z - (maskGeom.w + hs);
          float w = max(lensFront.y, 1.0);
          float x = (surfaceY - s.y) / norm;
          float lens = exp(-(x * x) / (2.0 * w * w));
          float grad = -(x / (w * w)) * lens;
          float bend = grad * w * lensFront.x;
          disp += dir * bend;
          ca += dir * abs(bend) * lensFront.z;
          opticSlope += -dir * (grad * w * 0.9) * lensLevel;
          opticShell = max(opticShell, lens * lensLevel);
          opticRim += exp(-(x * x) / (2.0 * (w * 0.45) * (w * 0.45))) * lensLevel;
          opticGlow = lensFront.w;
          opticHue = 0.42 + 0.24 * sin(s.x * 0.006 + x * 0.01 + tideFlicker.y * 2.5);
        }
        // Impact shell: one thick glass dome expanding from the point where
        // the crest hit the top edge, bursting out and decelerating, with a
        // frosted wake that lags behind it and clears as it passes.
        if (rippleSrc.z > 0.0 && rippleSrc.y >= 0.0) {
          float p = rippleSrc.y / max(rippleShape.x, 0.001);
          float2 v = s - float2(rippleSrc.x, 0.0);
          float dist = max(length(v), 0.001);
          float2 dir = v / dist;
          float ang = atan(v.y, v.x);
          float wob = 1.0 + 0.04 * (sin(ang * 3.0) * 0.6 + sin(ang * 2.0 + 1.7) * 0.4);
          float released = smoothstep(0.0, 0.08, p);
          float fe = 1.0 - pow(1.0 - clamp(p, 0.0, 1.0), 2.2);
          float front = rippleShape.w * fe * wob;
          float w = max(rippleShape.y, 1.0);
          float x = dist - front;
          float lens = exp(-(x * x) / (2.0 * w * w));
          float fade = 1.0 - smoothstep(0.75, 1.2, p);
          float k = released * fade * rippleSrc.z;
          float grad = -(x / (w * w)) * lens;
          float bend = grad * w * rippleShape.z * k;
          disp += dir * bend;
          ca += dir * abs(bend) * lensFront.z;
          opticSlope += -dir * (grad * w * 0.9) * k * shellLook;
          opticShell = max(opticShell, lens * k * shellLook);
          opticRim += exp(-(x * x) / (2.0 * (w * 0.45) * (w * 0.45))) * k * shellLook;
          opticGlow = max(opticGlow, 0.55 * rippleSrc.z * shellLook);
          opticHue = 0.42 + 0.24 * sin(ang * 2.0 + dist * 0.008 + p * 2.5);
          float passed = smoothstep(front - w * 2.4, front - w * 2.4 - rippleSrc.w * 0.9, dist);
          opticFrost = passed * fade * rippleSrc.z * shellLook;
        }
        if (meniscus.x != 0.0 && tide.x != 0.0) disp.y += meniscusShift(coord) / scale;
        float2 rc = coord + disp * scale;
        if (disp.x != 0.0 || disp.y != 0.0) {
          sharp = content.eval(rc);
          if (ca.x != 0.0 || ca.y != 0.0) {
            sharp.r = content.eval(rc + ca * scale).r;
            sharp.b = content.eval(rc - ca * scale).b;
          }
        }
        blurred = field.eval(rc);
        // Frosted wake: the scene behind the shell dissolves into the
        // diffused colour field, then clears as the shell fades.
        sharp.rgb = mix(sharp.rgb, blurred.rgb, half(clamp(opticFrost * 0.7, 0.0, 1.0)));
      }
      float intensity = clamp(mask.eval(coord).a, 0.0, 1.0);
      if (intensity <= 0.0 || materialStrength <= 0.0) {
        if (fieldMaskMode > 0.5) {
          return half4(opticShade(float3(sharp.rgb), opticSlope, opticShell, opticRim, opticHue, opticGlow), 1.0);
        }
        return materialOverlayMode > 0.5 ? half4(0.0) : blurred;
      }

      // Material density must not jump at the optical entrance. The previous
      // response divided by surfaceProgression; at the legal minimum (0.15)
      // even tiny intensity values saturated very quickly and exposed a visible
      // horizontal material boundary.
      //
      // Shape the mask first, then use smootherstep so both ends have zero
      // slope. Lower surfaceProgression now means a later / airier build-up,
      // while intensity=1 still reaches the requested materialStrength exactly.
      float curveHeight = clamp(materialCurveHeight, 0.25, 1.5);
      // fieldMaskMode processes the field at full density independent of the
      // panel mask; the mask is applied afterward as a pure alpha (see the
      // overlay return below).
      float materialIntensity =
        fieldMaskMode > 0.5
          ? 1.0
          : clamp((intensity + materialCurveOffset) / curveHeight, 0.0, 1.0);
      float progression = clamp(materialSurfaceProgression, 0.15, 1.0);
      float responseExponent = mix(1.80, 0.75, progression);
      float shaped = pow(materialIntensity, responseExponent);
      float densityCurve =
        shaped * shaped * shaped *
        (shaped * (shaped * 6.0 - 15.0) + 10.0);
      float density = materialStrength * densityCurve;
      float alpha = max(float(blurred.a), 0.0001);
      float3 rgb = clamp(float3(blurred.rgb) / alpha, 0.0, 1.0);

      float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
      float3 chroma = rgb - float3(luma);
      float anchor = dot(materialColor, float3(0.2126, 0.7152, 0.0722));
      float light = smoothstep(0.40, 0.72, anchor);
      float surface = clamp(materialSurface, 0.0, 1.0);

      // Compress luminance independently from colour. Pearl has a narrow tonal
      // range; smoke transmits substantially more of the source illumination.
      // The toe lifts dark stains without turning the whole dark sheet grey.
      float slope = mix(0.72, 0.30, light) * mix(1.0, 0.85, surface);
      float exposed = clamp(luma * materialExposure, 0.0, 1.0);
      float bodyLuma = max(0.035, anchor - 0.5 * slope) + slope * exposed;
      float outputLuma = mix(luma, bodyLuma, density);
      float magnitude = max(abs(chroma.r), max(abs(chroma.g), abs(chroma.b)));
      float transmission = exp(-density * mix(0.65, 1.0, light));
      transmission /= 1.0 + density * 2.0 * magnitude;
      float3 result = clamp(float3(outputLuma) + chroma * transmission, 0.0, 1.0);

      // Front bloom: a band centred just past the optical entrance, lifting
      // light and boosting colour. frontGlow=0 leaves result untouched.
      float band = smoothstep(0.02, 0.22, intensity) * (1.0 - smoothstep(0.30, 0.75, intensity));
      float g = clamp(frontGlow, 0.0, 1.5) * band;
      result = clamp(result + g * (0.22 * (1.0 - result) * light + 0.35 * chroma), 0.0, 1.0);

      // "Focus" theme veil: progressively replaces backdrop chroma/luma/colour
      // with veilColor, gated by the same intensity ramp as material density.
      // materialVeil/Neutrality/LumaFlatten default to 0, so this block is a
      // no-op until the theme transition animates them.
      float veilCoverage = clamp(intensity * 4.0, 0.0, 1.0);
      float vLuma = dot(veilColor, float3(0.2126, 0.7152, 0.0722));
      float rLuma = dot(result, float3(0.2126, 0.7152, 0.0722));
      float3 rChroma = result - float3(rLuma);
      rChroma *= 1.0 - clamp(materialNeutrality, 0.0, 1.0) * veilCoverage;
      rLuma = mix(rLuma, vLuma, clamp(materialLumaFlatten, 0.0, 1.0) * 0.85 * veilCoverage);
      result = clamp(float3(rLuma) + rChroma, 0.0, 1.0);
      result = mix(result, veilColor, clamp(materialVeil, 0.0, 1.0) * veilCoverage);

      // 8-bit dither: breaks up the banding/contour artifacts the low-res
      // colour field upscale exposes, strongest in dark mode. Only reached
      // past the intensity/materialStrength early return above, so identity
      // regions (intensity<=0) stay pixel-exact.
      result = clamp(result + ditherNoise(coord), 0.0, 1.0);

      if (materialOverlayMode > 0.5) {
        if (fieldMaskMode > 0.5) {
          float coverage = fmCoverage(coord);
          // Light wave V0: exposure gain on the material response only (the
          // scene mix below keeps the same coverage, the field its diffusion).
          if (lightWaveStops != 0.0) {
            float d = (fmDepthUp(coord) - lightWaveCenter) / max(lightWaveWidth, 0.01);
            result = clamp(result * exp2(lightWaveStops * exp(-0.5 * d * d)), 0.0, 1.0);
          }
          // Opaque single-pass mix with the sharp scene: no translucent 8-bit
          // layer, so the coverage ramp is never quantised by blending.
          float3 mixed = mix(float3(sharp.rgb), result, coverage);
          mixed = opticShade(mixed, opticSlope, opticShell, opticRim, opticHue, opticGlow);
          mixed += ditherNoise(coord + float2(13.0, 29.0));
          return half4(clamp(mixed, 0.0, 1.0), 1.0);
        }
        float coverage =
          clamp(materialOverlayMix, 0.0, 1.0) *
          clamp(densityCurve, 0.0, 1.0) *
          float(blurred.a);
        coverage = clamp(coverage + g * 0.35 * float(blurred.a), 0.0, 1.0);
        // The veil hides the colour field entirely as it takes over.
        coverage *= 1.0 - clamp(materialVeil, 0.0, 1.0) * veilCoverage;
        return half4(result * coverage, coverage);
      }

      half4 premul = half4(result * float(blurred.a), blurred.a);
      if (entranceRadiusPx > 0.0) {
        float ramp = smoothstep(0.75, 3.0, entranceRadiusPx * intensity);
        premul *= half4(ramp);
      }
      return premul;
    }
  """.trimIndent()

}
