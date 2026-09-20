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
  // This is intentionally ONE post-Gaussian material pass. There is no second
  // blur, no blur stack and no multi-level approximation. The reference feel is
  // produced by optical-density shaping: luminance is progressively absorbed by
  // the substrate while blurred chroma is allowed to travel through it.
  val materialComposite = """
    uniform shader content;
    uniform shader mask;
    uniform float materialStrength;
    uniform float3 materialColor;
    uniform float materialExposure;
    uniform float materialSurface;
    uniform float materialSurfaceProgression;
    // Full-view geometry is needed only by the demo material pass. It lets the
    // optical shoulder bow upward like the reference instead of reading as a
    // flat horizontal edge.
    uniform float2 origin;
    uniform float2 viewSize;

    half4 main(float2 coord) {
      half4 blurred = content.eval(coord);
      float intensity = clamp(mask.eval(coord).a, 0.0, 1.0);
      if (intensity <= 0.0001 || materialStrength <= 0.0001) return blurred;

      // Reference-oriented optical field. The source movie does not have a
      // straight horizontal material front: over the media it reads as one
      // broad elliptical / domed shoulder. Keep the Gaussian itself continuous
      // and shape only this single optical field in 2D.
      float progression = clamp(materialSurfaceProgression, 0.45, 0.90);
      float edgeField = smoothstep(0.08, progression, intensity);
      edgeField = pow(edgeField, 1.25);

      float2 fullCoord = coord + origin;
      float2 safeView = max(viewSize, float2(1.0));
      float2 uv = fullCoord / safeView;

      // Wide ellipse, deliberately larger than either card. Near the upper
      // shoulder it concentrates material in the centre and leaves the sides
      // more open; deeper in the panel it converges back to a full-width sheet.
      // This reproduces the rounded "oval over the image" visible in the ref.
      float2 ovalCenter = float2(0.54, 0.60);
      float2 ovalRadius = float2(0.62, 0.27);
      float2 ovalCoord = (uv - ovalCenter) / ovalRadius;
      float ellipse = dot(ovalCoord, ovalCoord);
      float oval = 1.0 - smoothstep(0.70, 1.18, ellipse);

      float deepBlend = smoothstep(0.42, 0.90, intensity);
      float shoulder = mix(0.34 + 0.66 * oval, 1.0, deepBlend);
      float ovalLift =
        oval * (1.0 - deepBlend) * smoothstep(0.04, 0.50, intensity) * 0.22;
      float field = clamp(edgeField * shoulder + ovalLift, 0.0, 1.0);
      float material = clamp(materialStrength, 0.0, 1.0) * field;

      float alpha = max(float(blurred.a), 0.0001);
      float3 rgb = clamp(float3(blurred.rgb) / alpha, 0.0, 1.0);

      float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
      float3 chroma = rgb - float3(luma);
      float materialLuma =
        dot(materialColor, float3(0.2126, 0.7152, 0.0722))
        * clamp(materialExposure, 0.75, 1.10);

      // A dense pearlescent substrate mainly converges luminance. Strong source
      // colours must stop reading as rectangular cards, while weak colour
      // differences are still allowed to survive underneath the sheet.
      float darkContent = 1.0 - smoothstep(0.16, 0.66, luma);
      float deep = smoothstep(0.18, 0.78, intensity);
      float surface = clamp(materialSurface, 0.0, 1.0) * material * deep;

      float density = material * mix(0.90, 0.985, darkContent);
      float lumaMix = clamp(density + surface * 0.12, 0.0, 0.985);
      float pearlLuma = mix(luma, materialLuma, lumaMix);

      // Perceptual chroma compression is the key difference from a plain tint:
      // saturated blue/orange blocks are absorbed strongly, but subtle hues
      // remain as soft, low-contrast stains beneath the material.
      float chromaMagnitude =
        max(abs(chroma.r), max(abs(chroma.g), abs(chroma.b)));
      float chromaCompression =
        1.0 / (1.0 + 6.5 * material * chromaMagnitude);
      float chromaGain = mix(1.0, 0.16, material) * chromaCompression;
      chromaGain *= mix(1.0, 0.82, surface);
      rgb = float3(pearlLuma) + chroma * chromaGain;

      // Broad satin reflection. It follows the same continuous optical field,
      // giving the sheet a dense metallic/pearl body without a second blur,
      // highlight layer, or hard band.
      float localHighlight = smoothstep(0.44, 0.88, luma);
      float pearlReflection =
        surface * (0.055 + 0.025 * localHighlight + 0.018 * darkContent);
      rgb = mix(rgb, materialColor, pearlReflection);

      // In the deep body the reference becomes almost silver-grey, with only a
      // trace of source hue left. This final convergence removes the obvious
      // image rectangle while preserving a faint coloured trail.
      float body = smoothstep(0.45, 1.0, intensity) * material;
      float3 bodyColour = float3(materialLuma) + chroma * 0.08;
      rgb = mix(rgb, bodyColour, 0.38 * body);

      rgb = clamp(rgb, 0.0, 1.0);
      return half4(rgb * float(blurred.a), float(blurred.a));
    }
  """.trimIndent()

}
