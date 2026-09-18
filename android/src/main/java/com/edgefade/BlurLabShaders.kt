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
      const float maxRadius = 150.0;
      float gaussian(float x, float sigma) {
        return exp(-(x * x) / (2.0 * sigma * sigma));
      }
      float inside(float2 p) {
        return step(0.0, p.$axis) * (1.0 - step(extent.$axis, p.$axis));
      }
      half4 main(float2 coord) {
        float intensity = clamp(mask.eval(coord).a, 0.0, 1.0);
        float radius = blurRadius * intensity;
        float r = floor(radius);
        float4 sampled = float4(content.eval(coord));
        if (r >= 1.0) {
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


  /**
   * Benchmark-only single-node adaptive Gaussian.
   *
   * radius <= 44px: byte-for-byte equivalent paired-tap kernel to [pass].
   * radius >= 56px: groups four adjacent Gaussian weights into one weighted
   * centroid sample per side, with a continuous support window so groups do
   * not appear at discrete radius thresholds. The 44..56px interval blends
   * the two estimates inside the same shader coordinate space, so there is no
   * geometric seam or contour-band boundary.
   */
  fun passAdaptive(vertical: Boolean): String {
    val offset = if (vertical) "float2(0.0, d)" else "float2(d, 0.0)"
    val axis = if (vertical) "y" else "x"

    return """
      uniform shader content;
      uniform shader mask;
      uniform float blurRadius;
      uniform float2 extent;
      const float maxRadius = 150.0;
      const float adaptiveStart = 44.0;
      const float adaptiveEnd = 56.0;

      float gaussian(float x, float sigma) {
        return exp(-(x * x) / (2.0 * sigma * sigma));
      }

      float inside(float2 p) {
        return step(0.0, p.$axis) * (1.0 - step(extent.$axis, p.$axis));
      }

      half4 exactGaussian(float2 coord, float radius) {
        float r = floor(radius);
        float4 sampled = float4(content.eval(coord));
        if (r < 1.0) return half4(sampled);

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
        return half4(result / weightSum);
      }

      float support(float x, float radius) {
        // Keep the truncation edge continuous as radius changes from pixel to
        // pixel. The exact kernel changes support at integer radii; doing that
        // with four-weight groups made the group enter all at once and exposed
        // horizontal contour bands in a spatially varying blur.
        return 1.0 - smoothstep(radius - 0.5, radius + 1.5, x);
      }

      half4 groupedGaussian(float2 coord, float radius) {
        float4 sampled = float4(content.eval(coord));
        if (radius < 1.0) return half4(sampled);

        float sigma = max(radius / 2.0, 1.0);
        float weightSum = 1.0;
        float4 result = sampled;

        for (float i = 1.0; i < maxRadius; i += 4.0) {
          // Enter the next group while its support is still exactly zero.
          // That keeps loop-count changes from changing the image.
          if (i > radius + 2.0) break;

          float w0 = gaussian(i, sigma) * support(i, radius);
          float w1 = gaussian(i + 1.0, sigma) * support(i + 1.0, radius);
          float w2 = gaussian(i + 2.0, sigma) * support(i + 2.0, radius);
          float w3 = gaussian(i + 3.0, sigma) * support(i + 3.0, radius);
          float weight = w0 + w1 + w2 + w3;
          if (weight <= 0.00001) continue;

          float d =
            (i * w0 +
             (i + 1.0) * w1 +
             (i + 2.0) * w2 +
             (i + 3.0) * w3) / weight;

          float2 offset = $offset;
          float2 a = coord - offset;
          float2 b = coord + offset;
          if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
          if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
        }
        return half4(result / weightSum);
      }

      half4 main(float2 coord) {
        float intensity = clamp(mask.eval(coord).a, 0.0, 1.0);
        float radius = blurRadius * intensity;

        if (radius <= adaptiveStart) {
          return exactGaussian(coord, radius);
        }
        if (radius >= adaptiveEnd) {
          return groupedGaussian(coord, radius);
        }

        half4 exact = exactGaussian(coord, radius);
        half4 grouped = groupedGaussian(coord, radius);
        float mixAmount = smoothstep(adaptiveStart, adaptiveEnd, radius);
        return mix(exact, grouped, half4(mixAmount));
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
}
