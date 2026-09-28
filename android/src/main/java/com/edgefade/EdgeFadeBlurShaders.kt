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
 * Changes: clamp-only bounds, strip-local extents, continuous tap support,
 * and a four-edge radius mask. This is a port, NOT the Compose binary.
 * See android/PROGRESSIVE_BLUR_NOTICE.md.
 */
package com.edgefade

/** AGSL sources for the API 33+ progressive renderer. */
internal object EdgeFadeBlurShaders {
  /** Upper bound of the kernel loop, in working-raster pixels. */
  const val MAX_KERNEL_RADIUS_PX = 150f

  /** Corner rounding of the sharp window, in band depths (0 = square). */
  const val CORNER_ROUNDNESS = 1f

  /**
   * One axis of the separable progressive Gaussian.
   *
   * `radius = blurRadius * mask.a` per fragment, sigma = radius / 2 as in
   * AndroidX. Each paired tap fades in over a one-pixel radius interval, so the
   * support grows continuously instead of snapping at floor(radius); the
   * snapping is what shows up as horizontal scan lines along a vertical field.
   *
   * The last pass (`isFinal`) also composites the strip: it
   * bounds itself to the band it owns (`visible`, antialiased) and fades in
   * over the first pixels of radius, since the sharp scene is drawn underneath.
   * The bound must live here and not in a canvas clip: Skia crops a
   * runtime-shader effect's input to the destination clip, which would starve
   * the kernel of the padded neighbourhood. Pixels it does not own return
   * early, so the padding is only paid for in the horizontal pass.
   */
  fun pass(vertical: Boolean): String {
    val offset = if (vertical) "float2(0.0, d)" else "float2(d, 0.0)"
    val axis = if (vertical) "y" else "x"
    val compositeUniforms = """
      uniform float4 visible;
      uniform float rasterScale;
      uniform float isFinal;
    """
    val coverage = """
        float coverage = 1.0;
        if (isFinal > 0.5) {
          float2 edge = min(coord - visible.xy, visible.zw - coord);
          coverage = clamp(min(edge.x, edge.y) + 0.5, 0.0, 1.0)
            * smoothstep(0.75, 1.5 / rasterScale, radius / rasterScale);
          if (coverage <= 0.0) return half4(0.0);
        }
    """
    return """
      uniform shader content;
      uniform shader mask;
      uniform float blurRadius;
      uniform float2 extent;
      $compositeUniforms
      const float maxRadius = 150.0;

      float gaussian(float x, float sigma) {
        return exp(-(x * x) / (2.0 * sigma * sigma));
      }

      float inside(float2 p) {
        return step(0.0, p.$axis) * (1.0 - step(extent.$axis, p.$axis));
      }

      half4 main(float2 coord) {
        float radius = blurRadius * clamp(mask.eval(coord).a, 0.0, 1.0);
        $coverage
        float4 result = float4(content.eval(coord));
        if (radius <= 0.5) return half4(result * coverage);

        float sigma = max(radius / 2.0, 1.0);
        float weightSum = 1.0;
        for (float i = 1.0; i < maxRadius; i += 2.0) {
          if (radius <= i - 0.5) break;
          float low = gaussian(i, sigma) * smoothstep(i - 0.5, i + 0.5, radius);
          float high = gaussian(i + 1.0, sigma) * smoothstep(i + 0.5, i + 1.5, radius);
          float weight = low + high;
          if (weight <= 0.000001) continue;

          float d = i + high / weight;
          float2 offset = $offset;
          float2 a = coord - offset;
          float2 b = coord + offset;
          if (inside(a) > 0.0) { result += weight * float4(content.eval(a)); weightSum += weight; }
          if (inside(b) > 0.0) { result += weight * float4(content.eval(b)); weightSum += weight; }
        }
        return half4(result / weightSum * coverage);
      }
    """.trimIndent()
  }

  /**
   * Radius field of the whole view, evaluated in strip-local raster space.
   * Returns radius / blurRadius in alpha.
   *
   * Each edge contributes a signed band coordinate (1 at the outer edge, 0 at
   * the inner boundary, negative inside). The vertical and horizontal pairs
   * are joined like a rounded-rectangle SDF, so the sharp window gets rounded
   * corners of CORNER_ROUNDNESS band depths; away from corners this reduces to
   * the single edge's coordinate. Blur is never stacked.
   */
  const val mask = """
    uniform float2 origin;
    uniform float2 viewSize;
    uniform float4 edges;
    uniform float progression;
    uniform float corner;
    uniform float4 curveExp;
    uniform float4 curveMode;
    uniform float4 useLut;
    uniform float curveTopLut[32];
    uniform float curveBottomLut[32];
    uniform float curveLeftLut[32];
    uniform float curveRightLut[32];

    float presence(float t, float exponent, float mode) {
      float x = clamp(t, 0.0, 1.0);
      if (mode > 1.5) return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
      if (mode > 0.5) return 1.0 - cos(x * 1.5707963);
      return 1.0 - pow(1.0 - x, exponent);
    }

    float sampleTop(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      for (int i = 0; i < 31; i++) {
        if (x <= float(i + 1)) return mix(curveTopLut[i], curveTopLut[i + 1], x - float(i));
      }
      return curveTopLut[31];
    }

    float sampleBottom(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      for (int i = 0; i < 31; i++) {
        if (x <= float(i + 1)) return mix(curveBottomLut[i], curveBottomLut[i + 1], x - float(i));
      }
      return curveBottomLut[31];
    }

    float sampleLeft(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      for (int i = 0; i < 31; i++) {
        if (x <= float(i + 1)) return mix(curveLeftLut[i], curveLeftLut[i + 1], x - float(i));
      }
      return curveLeftLut[31];
    }

    float sampleRight(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      for (int i = 0; i < 31; i++) {
        if (x <= float(i + 1)) return mix(curveRightLut[i], curveRightLut[i + 1], x - float(i));
      }
      return curveRightLut[31];
    }

    // Signed band coordinate; disabled edges sit far inside.
    float band(float distance, float depth) {
      return depth > 0.0 ? 1.0 - distance / depth : -1000.0;
    }

    half4 main(float2 local) {
      float2 p = local + origin;
      float top = band(p.y, edges.x);
      float bottom = band(viewSize.y - p.y, edges.y);
      float left = band(p.x, edges.z);
      float right = band(viewSize.x - p.x, edges.w);

      float2 q = max(float2(max(top, bottom), max(left, right)) + corner, 0.0);
      float raw = length(q) - corner;
      if (raw <= 0.0) return half4(0.0);
      float t = clamp(raw / progression, 0.0, 1.0);

      // Evaluate the curve of each edge taking part in this pixel.
      float result = 0.0;
      if (top > -corner && top >= bottom) {
        result = max(result, useLut.x > 0.5 ? sampleTop(t) : presence(t, curveExp.x, curveMode.x));
      }
      if (bottom > -corner && bottom > top) {
        result = max(result, useLut.y > 0.5 ? sampleBottom(t) : presence(t, curveExp.y, curveMode.y));
      }
      if (left > -corner && left >= right) {
        result = max(result, useLut.z > 0.5 ? sampleLeft(t) : presence(t, curveExp.z, curveMode.z));
      }
      if (right > -corner && right > left) {
        result = max(result, useLut.w > 0.5 ? sampleRight(t) : presence(t, curveExp.w, curveMode.w));
      }
      return half4(0.0, 0.0, 0.0, clamp(result, 0.0, 1.0));
    }
  """
}
