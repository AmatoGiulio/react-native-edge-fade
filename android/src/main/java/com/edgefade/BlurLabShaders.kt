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
        float4 sampled = float4(content.eval(coord));
        if (radius >= 1.0) {
          float sigma = max(radius / 2.0, 1.0);
          float weightSum = 1.0;
          float4 result = sampled;
          for (float i = 1.0; i < maxRadius; i += 2.0) {
            if (i >= radius) break;

            // Fade each discrete Gaussian tap in continuously as the spatial
            // radius crosses it. The previous floor(radius) cutoff changed the
            // kernel in visible steps; over a tall progressive field those steps
            // read as horizontal bands.
            float lowGate = clamp(radius - i, 0.0, 1.0);
            float highGate = clamp(radius - (i + 1.0), 0.0, 1.0);
            float low = gaussian(i, sigma) * lowGate;
            float high = gaussian(i + 1.0, sigma) * highGate;
            float weight = low + high;

            if (weight > 0.000001) {
              float d = i + high / weight;
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


  // Optional demo-only material pass. The public renderer leaves this disabled
  // (strength = 0), preserving its pure progressive-Gaussian contract.
  //
  // Blur removes high frequencies, but very dark cards can still survive as
  // large rectangular low-frequency masses. The reference material gradually
  // compresses those masses into the surrounding surface. Drive that extinction
  // from the SAME radius field, but with a delayed onset so the inner edge stays
  // optically sharp and the material only takes over deeper in the blur field.
  val materialComposite = """
    uniform shader content;
    uniform shader mask;
    uniform float materialStrength;
    uniform float3 materialColor;

    half4 main(float2 coord) {
      half4 blurred = content.eval(coord);
      float intensity = clamp(mask.eval(coord).a, 0.0, 1.0);

      // Blur starts immediately; grading deliberately starts later. This avoids
      // the cheap "white gradient over content" look at the transition edge.
      float material = clamp(materialStrength, 0.0, 1.0)
        * smoothstep(0.22, 0.92, intensity);
      if (material <= 0.0001) return blurred;

      float alpha = max(float(blurred.a), 0.0001);
      float3 rgb = clamp(float3(blurred.rgb) / alpha, 0.0, 1.0);

      // Reference-like extinction: first reduce chroma, then compress contrast,
      // then let the blurred content dissolve into the surrounding material.
      float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
      float saturation = mix(1.0, 0.92, material);
      rgb = mix(float3(luma), rgb, saturation);

      float contrast = mix(1.0, 0.74, material);
      rgb = (rgb - 0.5) * contrast + 0.5;

      // Keep the source chroma visible. The reference behaves more like a
      // colored low-frequency wash than an opaque neutral frost layer.
      float tintAmount = 0.20 * material;
      rgb = mix(rgb, materialColor, tintAmount);
      rgb = clamp(rgb + 0.006 * material, 0.0, 1.0);

      return half4(rgb * float(blurred.a), float(blurred.a));
    }
  """.trimIndent()

}
