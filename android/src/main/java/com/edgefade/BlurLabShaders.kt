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
      const float maxRadius = 150.0;
      float gaussian(float x, float sigma) {
        return exp(-(x * x) / (2.0 * sigma * sigma));
      }
      float inside(float2 p) {
        return step(0.0, p.$axis) * (1.0 - step(extent.$axis, p.$axis));
      }
      half4 main(float2 coord) {
        float intensity = clamp(mask.eval(coord).a, 0.0, 1.0);
        // Recreate the discarded Astra experiment exactly: keep the public
        // Gaussian path unchanged, but use the cubic-root radius field whenever
        // the internal material path enables continuousSupport.
        float radiusIntensity =
          continuousSupport > 0.5 ? pow(intensity, 1.0 / 3.0) : intensity;
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


  // Material AGSL path fused into the second Gaussian pass. Keeping the
  // material grading inside the vertical pass avoids a third RenderEffect
  // rasterization/resample at the hard strip clip, which was the source of the
  // CLOSED horizontal seam. Public strength=0 continues to use pass() above.
  val materialVerticalPass = """
    uniform shader content;
    uniform shader mask;
    uniform float blurRadius;
    uniform float2 extent;
    uniform float continuousSupport;
    uniform float materialStrength;
    uniform float3 materialColor;
    uniform float materialExposure;
    uniform float materialSurface;
    uniform float materialSurfaceProgression;
    uniform float materialEdge;
    uniform float materialBoundary;
    uniform float materialEntrance;
    uniform float materialPanelDepth;
    uniform float materialPanelAlphaMin;

    const float maxRadius = 150.0;

    float materialDistanceInside(float2 coord) {
      if (materialEdge < 0.5) return materialBoundary - coord.y;
      if (materialEdge < 1.5) return coord.y - materialBoundary;
      if (materialEdge < 2.5) return materialBoundary - coord.x;
      return coord.x - materialBoundary;
    }

    float gaussian(float x, float sigma) {
      return exp(-(x * x) / (2.0 * sigma * sigma));
    }

    float inside(float2 p) {
      return step(0.0, p.y) * (1.0 - step(extent.y, p.y));
    }

    half4 main(float2 coord) {
      float intensity = clamp(mask.eval(coord).a, 0.0, 1.0);
      float radiusIntensity =
        continuousSupport > 0.5 ? pow(intensity, 1.0 / 3.0) : intensity;
      float radius = blurRadius * radiusIntensity;
      float r = floor(radius);
      float4 sampled = float4(content.eval(coord));

      if (continuousSupport > 0.5 && radius > 0.0) {
        float sigma = max(radius / 2.0, 1.0);
        float weightSum = 1.0;
        float4 result = sampled;

        for (float i = 1.0; i < maxRadius; i += 2.0) {
          if (radius <= i - 0.5) break;

          float lowCoverage = smoothstep(i - 0.5, i + 0.5, radius);
          float highCoverage = smoothstep(i + 0.5, i + 1.5, radius);
          float low = gaussian(i, sigma) * lowCoverage;
          float high = gaussian(i + 1.0, sigma) * highCoverage;
          float weight = low + high;
          if (weight <= 0.000001) continue;

          float d = i + high / weight;
          float2 offset = float2(0.0, d);
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
          float2 offset = float2(0.0, d);
          float2 a = coord - offset;
          float2 b = coord + offset;
          if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
          if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
        }

        float odd = mod(r, 2.0) * (1.0 - step(maxRadius, r));
        if (odd > 0.0) {
          float weight = gaussian(r, sigma);
          float d = r;
          float2 offset = float2(0.0, d);
          float2 a = coord - offset;
          float2 b = coord + offset;
          if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
          if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
        }
        sampled = result / weightSum;
      }

      if (intensity > 0.0 && materialStrength > 0.0) {
        float depth = pow(intensity, 0.65) / max(materialSurfaceProgression, 0.15);
        float density = materialStrength * (1.0 - exp(-3.0 * depth));
        float alpha = max(sampled.a, 0.0001);
        float3 rgb = clamp(sampled.rgb / alpha, 0.0, 1.0);
        float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
        float3 chroma = rgb - float3(luma);
        float anchor = dot(materialColor, float3(0.2126, 0.7152, 0.0722));
        float light = smoothstep(0.40, 0.72, anchor);
        float surface = clamp(materialSurface, 0.0, 1.0);

        float slope = mix(0.72, 0.30, light) * mix(1.0, 0.85, surface);
        float exposed = clamp(luma * materialExposure, 0.0, 1.0);
        float bodyLuma = max(0.035, anchor - 0.5 * slope) + slope * exposed;
        float outputLuma = mix(luma, bodyLuma, density);
        float magnitude = max(abs(chroma.r), max(abs(chroma.g), abs(chroma.b)));
        float transmission = exp(-density * mix(0.65, 1.0, light));
        transmission /= 1.0 + density * 2.0 * magnitude;
        float3 graded = float3(outputLuma) + chroma * transmission;
        sampled = float4(clamp(graded, 0.0, 1.0) * sampled.a, sampled.a);
      }

      float insideMaterial = max(materialDistanceInside(coord), 0.0);

      // Local seam guard: preserve the proven soft hand-off at the physical
      // strip boundary.
      float edgeCoverage = materialEntrance <= 0.0
        ? 1.0
        : smoothstep(0.0, materialEntrance, insideMaterial);

      // Global alpha mask: keep the exact same processed pixels, but let their
      // premultiplied coverage breathe in across the entire panel depth.
      // The modulation is intentionally conservative (90% -> 100%) so the
      // accepted material body stays visually almost unchanged.
      float panelT = clamp(
        insideMaterial / max(materialPanelDepth, 1.0),
        0.0,
        1.0
      );
      float panelEase =
        panelT * panelT * panelT *
        (panelT * (panelT * 6.0 - 15.0) + 10.0);
      float panelCoverage = mix(
        clamp(materialPanelAlphaMin, 0.0, 1.0),
        1.0,
        panelEase
      );

      // Sharp content stays underneath the whole material band. This is a real
      // alpha-mask composite: no blur radius, colour response or material
      // grading is changed.
      return half4(sampled * edgeCoverage * panelCoverage);
    }
  """.trimIndent()

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
      float bottomPos = position(viewSize.y - p.y, edges.y);
      float leftPos = position(p.x, edges.z);
      float rightPos = position(viewSize.x - p.x, edges.w);

      float top = topPos < 0.0 ? 0.0 : sampleTop(topPos);
      float bottom = bottomPos < 0.0 ? 0.0 : sampleBottom(bottomPos);
      float left = leftPos < 0.0 ? 0.0 : sampleLeft(leftPos);
      float right = rightPos < 0.0 ? 0.0 : sampleRight(rightPos);

      return half4(0.0, 0.0, 0.0, max(max(top, bottom), max(left, right)));
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
    uniform float2 materialOrigin;
    uniform float2 materialViewSize;
    uniform float materialEdge;
    uniform float materialEdgeDepth;
    uniform float materialEntrance;

    float distanceInsideMaterial(float2 coord) {
      float2 p = coord + materialOrigin;
      if (materialEdge < 0.5) {
        return materialEdgeDepth - p.y;
      }
      if (materialEdge < 1.5) {
        return materialEdgeDepth - (materialViewSize.y - p.y);
      }
      if (materialEdge < 2.5) {
        return materialEdgeDepth - p.x;
      }
      return materialEdgeDepth - (materialViewSize.x - p.x);
    }

    half4 main(float2 coord) {
      half4 blurred = content.eval(coord);
      float intensity = clamp(mask.eval(coord).a, 0.0, 1.0);
      if (intensity <= 0.0 || materialStrength <= 0.0) return blurred;

      // Keep the Gaussian field untouched. The diagnostic probe showed that
      // CAP and GAUSS are continuous at the strip boundary; the visible seam
      // appears only when material density starts. Build scattering over a
      // short fixed screen-space entrance, then become exactly the baseline
      // material response. This keeps the open body/pure-cbrt optics intact.
      float inside = max(distanceInsideMaterial(coord), 0.0);
      float entrance = materialEntrance <= 0.0
        ? 1.0
        : smoothstep(0.0, materialEntrance, inside);

      float depth = pow(intensity, 0.65) / max(materialSurfaceProgression, 0.15);
      float density =
        materialStrength * (1.0 - exp(-3.0 * depth)) * entrance;
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
      float3 result = float3(outputLuma) + chroma * transmission;
      return half4(clamp(result, 0.0, 1.0) * float(blurred.a), blurred.a);
    }
  """.trimIndent()

}
