package com.edgefade

/**
 * Benchmark-only specialization for the overwhelmingly common vertical-only
 * smooth fade. The radius curve is evaluated directly inside each Gaussian
 * pass, eliminating the separate RuntimeShader mask lookup used by
 * BlurRadiusSpec.shader()/the generic AGSL path.
 *
 * This benchmark variant also compiles a radius-specific loop-bound bucket so
 * the GPU program does not carry the full 150px static loop bound when a much
 * smaller maximum radius is requested.
 *
 * This keeps the same smooth presence function used by EdgeFade:
 *   t = ((1 - distance / depth) / progression).clamp(0..1)
 *   intensity = 1 - (1 - t)^3
 */
internal object EdgeFadeFastVerticalShaders {
  fun pass(verticalBlur: Boolean, bottomEdge: Boolean, maxRadiusPx: Int): String {
    require(maxRadiusPx in 1..150) { "maxRadiusPx must be in 1..150" }

    val offset = if (verticalBlur) "float2(0.0, d)" else "float2(d, 0.0)"
    val axis = if (verticalBlur) "y" else "x"
    val distance = if (bottomEdge) {
      "viewHeight - (coord.y + originY)"
    } else {
      "coord.y + originY"
    }

    return """
      uniform shader content;
      uniform float blurRadius;
      uniform float2 extent;
      uniform float originY;
      uniform float viewHeight;
      uniform float edgeDepth;
      uniform float progression;
      const float maxRadius = ${maxRadiusPx}.0;

      float gaussian(float x, float sigma) {
        return exp(-(x * x) / (2.0 * sigma * sigma));
      }

      float inside(float2 p) {
        return step(0.0, p.$axis) * (1.0 - step(extent.$axis, p.$axis));
      }

      float edgeIntensity(float2 coord) {
        float distance = $distance;
        if (edgeDepth <= 0.0 || distance >= edgeDepth) return 0.0;
        float t = clamp((1.0 - distance / edgeDepth) / progression, 0.0, 1.0);
        float inverse = 1.0 - t;
        return 1.0 - inverse * inverse * inverse;
      }

      half4 main(float2 coord) {
        float radius = blurRadius * edgeIntensity(coord);
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
            float2 sampleOffset = $offset;
            float2 a = coord - sampleOffset;
            float2 b = coord + sampleOffset;
            if (inside(a) > 0.0) {
              result += weight * content.eval(a);
              weightSum += weight;
            }
            if (inside(b) > 0.0) {
              result += weight * content.eval(b);
              weightSum += weight;
            }
          }
          float odd = mod(r, 2.0) * (1.0 - step(maxRadius, r));
          if (odd > 0.0) {
            float weight = gaussian(r, sigma);
            float d = r;
            float2 sampleOffset = $offset;
            float2 a = coord - sampleOffset;
            float2 b = coord + sampleOffset;
            if (inside(a) > 0.0) {
              result += weight * content.eval(a);
              weightSum += weight;
            }
            if (inside(b) > 0.0) {
              result += weight * content.eval(b);
              weightSum += weight;
            }
          }
          sampled = result / weightSum;
        }
        return half4(sampled);
      }
    """.trimIndent()
  }
}
