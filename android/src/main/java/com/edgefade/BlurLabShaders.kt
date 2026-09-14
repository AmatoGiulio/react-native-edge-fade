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
  // Pure source generation: compiled by RuntimeShader only on API 33+.
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
        float radius = blurRadius * clamp(mask.eval(coord).a, 0.0, 1.0);
        float r = floor(radius);
        if (r < 1.0) return content.eval(coord);
        float sigma = max(radius / 2.0, 1.0);
        float weightSum = 1.0;
        float4 result = float4(content.eval(coord));
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
    float presence(float distance, float depth) {
      if (depth <= 0.0 || distance >= depth) return 0.0;
      float t = clamp((1.0 - distance / depth) / progression, 0.0, 1.0);
      float x = t * 31.0;
      int i = int(min(floor(x), 30.0));
      return clamp(mix(curve[i], curve[i + 1], x - float(i)), 0.0, 1.0);
    }
    half4 main(float2 local) {
      float2 p = local + origin;
      float a = max(presence(p.y, edges.x), presence(viewSize.y - p.y, edges.y));
      float b = max(presence(p.x, edges.z), presence(viewSize.x - p.x, edges.w));
      return half4(0.0, 0.0, 0.0, max(a, b));
    }
  """.trimIndent()
}
