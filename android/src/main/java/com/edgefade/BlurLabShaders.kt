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
  /**
   * Benchmark-only continuous-radius fixed-cost Gaussian approximation.
   *
   * The exact progressive kernel is retained for radii below 32px so the
   * sharp -> blur onset remains identical to the reference. Above that point,
   * a 65-point normalized Gaussian is collapsed into 16 bilinear tap pairs per
   * side. Because sigma = radius / 2 and every fixed sample position scales with
   * radius, the normalized weights are constant while the blur radius remains
   * fully continuous per pixel.
   *
   * This bounds each pass to 33 texture evaluations (center + 16 symmetric
   * pairs) instead of a loop whose sample count grows with radius.
   */
  fun pass(vertical: Boolean): String {
    val offset = if (vertical) "float2(0.0, d)" else "float2(d, 0.0)"
    val axis = if (vertical) "y" else "x"

    return """
      uniform shader content;
      uniform shader mask;
      uniform float blurRadius;
      uniform float2 extent;
      const float exactRadiusLimit = 32.0;

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
          float4 result = sampled;
          float weightSum = 1.0;

          if (radius < exactRadiusLimit) {
            // Exact reference behavior in the visually sensitive inner ramp.
            float sigma = max(radius / 2.0, 1.0);
            for (float i = 1.0; i < exactRadiusLimit; i += 2.0) {
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
            float odd = mod(r, 2.0) * (1.0 - step(exactRadiusLimit, r));
            if (odd > 0.0) {
              float weight = gaussian(r, sigma);
              float d = r;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
          } else {
            // 65 nominal samples across [-radius, +radius], collapsed to
            // 16 bilinear pairs on each side. Constants are the normalized
            // Gaussian weights for sigma=radius/2.
            {
              float weight = 1.990266719;
              float d = radius * 0.046829224;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 1.951808703;
              float d = radius * 0.109268190;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 1.884447292;
              float d = radius * 0.171707160;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 1.791230659;
              float d = radius * 0.234146135;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 1.676253924;
              float d = radius * 0.296585116;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 1.544361172;
              float d = radius * 0.359024107;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 1.400808321;
              float d = radius * 0.421463108;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 1.250919385;
              float d = radius * 0.483902122;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 1.099767045;
              float d = radius * 0.546341150;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 0.951903335;
              float d = radius * 0.608780195;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 0.811158571;
              float d = radius * 0.671219257;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 0.680517720;
              float d = radius * 0.733658340;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 0.562074491;
              float d = radius * 0.796097445;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 0.457055641;
              float d = radius * 0.858536573;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 0.365902206;
              float d = radius * 0.920975726;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
            {
              float weight = 0.288391021;
              float d = radius * 0.983414907;
              float2 offset = $offset;
              float2 a = coord - offset;
              float2 b = coord + offset;
              if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
              if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
            }
          }

          sampled = result / weightSum;
        }

        return half4(sampled);
      }
    """.trimIndent()
  }

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
