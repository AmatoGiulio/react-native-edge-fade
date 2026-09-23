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
      uniform float2 materialOrigin;
      uniform float2 materialViewSize;
      uniform float4 materialEdges;
      uniform float materialProgression;
      float materialPosition(float distance, float depth) {
        if (depth <= 0.0 || distance >= depth) return 0.0;
        return clamp((1.0 - distance / depth) / materialProgression, 0.0, 1.0);
      }
      const float maxRadius = 150.0;
      float gaussian(float x, float sigma) {
        return exp(-(x * x) / (2.0 * sigma * sigma));
      }
      float inside(float2 p) {
        return step(0.0, p.$axis) * (1.0 - step(extent.$axis, p.$axis));
      }
      half4 main(float2 coord) {
        float intensity = clamp(mask.eval(coord).a, 0.0, 1.0);
        float radiusIntensity = intensity;
        if (continuousSupport > 0.5) {
          float2 p = coord + materialOrigin;
          float t = max(max(
            materialPosition(p.y, materialEdges.x),
            materialPosition(materialViewSize.y - p.y, materialEdges.y)), max(
            materialPosition(p.x, materialEdges.z),
            materialPosition(materialViewSize.x - p.x, materialEdges.w)));
          // Showcase photos end near t=0.45; 0.48 also protects the vertical
          // kernel footprint. Ramp broadly through the body, reaching 0.72.
          // Keep the original arithmetic throughout the untouched shoulder.
          if (t > 0.48) {
            float u = clamp((t - 0.48) / (0.72 - 0.48), 0.0, 1.0);
            float w = u * u * u * (u * (6.0 * u - 15.0) + 10.0);
            radiusIntensity = intensity + w * (pow(intensity, 1.0 / 3.0) - intensity);
          }
        }
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
    uniform float materialCurveHeight;
    uniform float materialCurveOffset;
    uniform float materialColorFieldEnabled;
    uniform float materialColorFieldMix;
    uniform float materialColorFieldRadius;
    uniform float2 materialExtent;

    float3 sampleMaterialRgb(float2 coord) {
      float2 maxCoord = max(materialExtent - float2(0.5), float2(0.5));
      float2 p = clamp(coord, float2(0.5), maxCoord);
      half4 sample = content.eval(p);
      float alpha = max(float(sample.a), 0.0001);
      return clamp(float3(sample.rgb) / alpha, 0.0, 1.0);
    }

    half4 main(float2 coord) {
      half4 blurred = content.eval(coord);
      float intensity = clamp(mask.eval(coord).a, 0.0, 1.0);
      if (intensity <= 0.0 || materialStrength <= 0.0) return blurred;

      // Material density must not jump at the optical entrance. The previous
      // response divided by surfaceProgression; at the legal minimum (0.15)
      // even tiny intensity values saturated very quickly and exposed a visible
      // horizontal material boundary.
      //
      // Shape the mask first, then use smootherstep so both ends have zero
      // slope. Lower surfaceProgression now means a later / airier build-up,
      // while intensity=1 still reaches the requested materialStrength exactly.
      float curveHeight = clamp(materialCurveHeight, 0.25, 1.5);
      float materialIntensity =
        clamp((intensity + materialCurveOffset) / curveHeight, 0.0, 1.0);
      float progression = clamp(materialSurfaceProgression, 0.15, 1.0);
      float responseExponent = mix(1.80, 0.75, progression);
      float shaped = pow(materialIntensity, responseExponent);
      float densityCurve =
        shaped * shaped * shaped *
        (shaped * (shaped * 6.0 - 15.0) + 10.0);
      float density = materialStrength * densityCurve;
      float alpha = max(float(blurred.a), 0.0001);
      float3 rgb = clamp(float3(blurred.rgb) / alpha, 0.0, 1.0);

      // Wide RGB-only field. Geometry/alpha stay exactly on the original
      // progressive Gaussian, while neighbouring source colours merge into
      // larger, smoother shapes before the pearl/smoke response.
      if (
        materialColorFieldEnabled > 0.5 &&
        materialColorFieldMix > 0.0001 &&
        materialColorFieldRadius > 0.5
      ) {
        float r = materialColorFieldRadius;
        float h = r * 0.5;
        float d = r * 0.70710678;

        float3 field = rgb * 4.0;

        field += sampleMaterialRgb(coord + float2( h, 0.0)) * 2.0;
        field += sampleMaterialRgb(coord + float2(-h, 0.0)) * 2.0;
        field += sampleMaterialRgb(coord + float2(0.0,  h)) * 2.0;
        field += sampleMaterialRgb(coord + float2(0.0, -h)) * 2.0;

        field += sampleMaterialRgb(coord + float2( r, 0.0));
        field += sampleMaterialRgb(coord + float2(-r, 0.0));
        field += sampleMaterialRgb(coord + float2(0.0,  r));
        field += sampleMaterialRgb(coord + float2(0.0, -r));

        field += sampleMaterialRgb(coord + float2( d,  d));
        field += sampleMaterialRgb(coord + float2(-d,  d));
        field += sampleMaterialRgb(coord + float2( d, -d));
        field += sampleMaterialRgb(coord + float2(-d, -d));

        field /= 20.0;
        rgb = mix(rgb, field, clamp(materialColorFieldMix, 0.0, 1.0));
      }

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
