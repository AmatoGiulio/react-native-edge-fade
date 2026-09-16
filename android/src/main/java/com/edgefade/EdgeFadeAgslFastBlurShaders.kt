/*
 * Copyright 2026 Giulio Amato
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
 */
package com.edgefade

/**
 * Performance candidate for the API 33+ progressive renderer.
 *
 * The AndroidX reference kernel grows its sample count linearly with physical
 * radius. At the demo's common 40dp radius that is ~140px on a 3.5x device,
 * which makes the fragment workload dominate the frame even when the child
 * scene contains only solid Views.
 *
 * This kernel keeps the exact AndroidX-derived paired-tap path through 16px.
 * Above 16px it evaluates a fixed 16-sample-per-side Gaussian quadrature whose
 * sample positions scale continuously with the local per-pixel radius. The
 * Gaussian profile, spatial radius field, zero-radius identity, H/V separation,
 * edge bounds and normalization semantics remain continuous; only the number of
 * source fetches is bounded for large radii.
 *
 * The 8-sample candidate was rejected on the physical CPH2709 because sparse
 * large-radius sampling produced visibly blocky/stepped output without a useful
 * frame-time win. Sixteen samples per side is the minimum quality candidate we
 * keep for further pipeline profiling: 33 content evaluations per pass instead
 * of the ~141 evaluations required by the exact 140px paired-tap kernel.
 *
 * This lives separately from [BlurLabShaders] so the exact AndroidX reference
 * remains available for visual/performance A/B validation.
 */
internal object EdgeFadeAgslFastBlurShaders {
  private const val SAMPLE_BUDGET = 16
  private const val EXACT_RADIUS_PX = 16f

  fun pass(vertical: Boolean): String {
    val exactOffset = if (vertical) "float2(0.0, d)" else "float2(d, 0.0)"
    val axis = if (vertical) "y" else "x"
    val budgetTaps = buildBudgetTaps(vertical)

    return """
      uniform shader content;
      uniform shader mask;
      uniform float blurRadius;
      uniform float2 extent;

      float gaussian(float x, float sigma) {
        return exp(-(x * x) / (2.0 * sigma * sigma));
      }

      float inside(float2 p) {
        return step(0.0, p.$axis) * (1.0 - step(extent.$axis, p.$axis));
      }

      float4 exactSmallRadius(float2 coord, float radius) {
        float r = floor(radius);
        float4 sampled = float4(content.eval(coord));
        if (r < 1.0) return sampled;

        float sigma = max(radius / 2.0, 1.0);
        float weightSum = 1.0;
        float4 result = sampled;

        for (float i = 1.0; i < ${EXACT_RADIUS_PX + 1f}; i += 2.0) {
          if (i >= r) break;
          float low = gaussian(i, sigma);
          float high = gaussian(i + 1.0, sigma);
          float weight = low + high;
          float d = i + high / weight;
          float2 offset = $exactOffset;
          float2 a = coord - offset;
          float2 b = coord + offset;
          if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
          if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
        }

        if (mod(r, 2.0) > 0.5) {
          float weight = gaussian(r, sigma);
          float d = r;
          float2 offset = $exactOffset;
          float2 a = coord - offset;
          float2 b = coord + offset;
          if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }
          if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }
        }

        return result / weightSum;
      }

      float4 boundedLargeRadius(float2 coord, float radius) {
        float4 result = float4(content.eval(coord));
        float weightSum = 1.0;
$budgetTaps
        return result / weightSum;
      }

      half4 main(float2 coord) {
        float intensity = clamp(mask.eval(coord).a, 0.0, 1.0);
        float radius = blurRadius * intensity;
        if (radius <= ${EXACT_RADIUS_PX}) {
          return half4(exactSmallRadius(coord, radius));
        }
        return half4(boundedLargeRadius(coord, radius));
      }
    """.trimIndent()
  }

  private fun buildBudgetTaps(vertical: Boolean): String {
    val lines = ArrayList<String>(SAMPLE_BUDGET * 12)
    for (index in 1..SAMPLE_BUDGET) {
      val fraction = index.toDouble() / SAMPLE_BUDGET.toDouble()
      // sigma = radius / 2 and d = radius * fraction, therefore the Gaussian
      // weight simplifies to exp(-2 * fraction^2) and is radius-independent.
      val weight = kotlin.math.exp(-2.0 * fraction * fraction)
      val fractionText = "%.9f".format(java.util.Locale.US, fraction)
      val weightText = "%.9f".format(java.util.Locale.US, weight)
      val offset =
        if (vertical) "float2(0.0, radius * $fractionText)"
        else "float2(radius * $fractionText, 0.0)"

      lines += "        {"
      lines += "          float weight = $weightText;"
      lines += "          float2 offset = $offset;"
      lines += "          float2 a = coord - offset;"
      lines += "          float2 b = coord + offset;"
      lines += "          if (inside(a) > 0.0) { result += weight * content.eval(a); weightSum += weight; }"
      lines += "          if (inside(b) > 0.0) { result += weight * content.eval(b); weightSum += weight; }"
      lines += "        }"
    }
    return lines.joinToString("\n")
  }
}
