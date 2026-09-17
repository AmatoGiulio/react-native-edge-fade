package com.edgefade

/**
 * Benchmark-only residual Gaussian used by BlurLabVarianceRenderer.
 *
 * Gaussian variances add under convolution. Each spatial band first receives a
 * fast uniform native Gaussian with reference-space radius `baseRadius`; this
 * shader then applies only the residual radius needed to reach the original
 * continuous target:
 *
 *   residualRadius = sqrt(targetRadius^2 - baseRadius^2)
 *
 * The public radius field therefore remains continuous per pixel. Unlike the
 * rejected pyramid experiment, no two differently blurred images are blended.
 */
internal object BlurLabVarianceShaders {
  fun residualPass(vertical: Boolean): String {
    val offset = if (vertical) "float2(0.0, d)" else "float2(d, 0.0)"
    val axis = if (vertical) "y" else "x"

    return """
      uniform shader content;
      uniform shader mask;
      uniform float blurRadius;
      uniform float baseRadius;
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
        float targetRadius = blurRadius * intensity;
        float residualSquared = max(
          targetRadius * targetRadius - baseRadius * baseRadius,
          0.0
        );
        float radius = sqrt(residualSquared);
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
}
