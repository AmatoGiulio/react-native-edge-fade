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
 * GLSL ES 3.0 equivalent of the API 33+ Public Progressive radius field and
 * separable Gaussian. This backend exists for Android 12 / 12L (API 31-32),
 * where RenderEffect can blur uniformly but RuntimeShader cannot vary the blur
 * radius per fragment.
 *
 * There is deliberately no blur-level stack or opacity cross-fade here. Both
 * passes derive `radius = blurRadius * intensity` for the output fragment and
 * evaluate the same paired Gaussian taps used by the AGSL path.
 */
internal object EdgeFadeGlesShaders {
  // GLSL requires #version to be the first line in the source. Keep the opening
  // raw-string delimiter on the same line so Kotlin does not inject a leading
  // newline before the directive (ANGLE rejects that on API 31-32).
  const val VERTEX = """#version 300 es
    layout(location = 0) in vec2 aPosition;
    out vec2 vUv;

    void main() {
      vUv = aPosition * 0.5 + 0.5;
      gl_Position = vec4(aPosition, 0.0, 1.0);
    }
  """

  private const val MASK_FUNCTIONS = """
    uniform vec2 uViewSize;
    uniform vec4 uEdges;
    uniform float uProgression;
    uniform vec4 uCurveExp;
    uniform vec4 uCurveMode;
    uniform vec4 uUseLut;
    uniform float uCurveTopLut[32];
    uniform float uCurveBottomLut[32];
    uniform float uCurveLeftLut[32];
    uniform float uCurveRightLut[32];

    float presence(float t, float exponent, float mode) {
      float x = clamp(t, 0.0, 1.0);
      if (mode > 1.5) {
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
      }
      if (mode > 0.5) {
        return 1.0 - cos(x * 1.5707963);
      }
      return 1.0 - pow(1.0 - x, exponent);
    }

    float sampleTop(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      for (int i = 0; i < 31; i++) {
        if (x <= float(i + 1)) return mix(uCurveTopLut[i], uCurveTopLut[i + 1], x - float(i));
      }
      return uCurveTopLut[31];
    }

    float sampleBottom(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      for (int i = 0; i < 31; i++) {
        if (x <= float(i + 1)) return mix(uCurveBottomLut[i], uCurveBottomLut[i + 1], x - float(i));
      }
      return uCurveBottomLut[31];
    }

    float sampleLeft(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      for (int i = 0; i < 31; i++) {
        if (x <= float(i + 1)) return mix(uCurveLeftLut[i], uCurveLeftLut[i + 1], x - float(i));
      }
      return uCurveLeftLut[31];
    }

    float sampleRight(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      for (int i = 0; i < 31; i++) {
        if (x <= float(i + 1)) return mix(uCurveRightLut[i], uCurveRightLut[i + 1], x - float(i));
      }
      return uCurveRightLut[31];
    }

    float edgePosition(float distance, float depth) {
      if (depth <= 0.0 || distance >= depth) return -1.0;
      return clamp((1.0 - distance / depth) / uProgression, 0.0, 1.0);
    }

    float radiusIntensity(vec2 coord) {
      float topPos = edgePosition(coord.y, uEdges.x);
      float bottomPos = edgePosition(uViewSize.y - coord.y, uEdges.y);
      float leftPos = edgePosition(coord.x, uEdges.z);
      float rightPos = edgePosition(uViewSize.x - coord.x, uEdges.w);

      float top = topPos < 0.0 ? 0.0 : (uUseLut.x > 0.5 ? sampleTop(topPos) : presence(topPos, uCurveExp.x, uCurveMode.x));
      float bottom = bottomPos < 0.0 ? 0.0 : (uUseLut.y > 0.5 ? sampleBottom(bottomPos) : presence(bottomPos, uCurveExp.y, uCurveMode.y));
      float left = leftPos < 0.0 ? 0.0 : (uUseLut.z > 0.5 ? sampleLeft(leftPos) : presence(leftPos, uCurveExp.z, uCurveMode.z));
      float right = rightPos < 0.0 ? 0.0 : (uUseLut.w > 0.5 ? sampleRight(rightPos) : presence(rightPos, uCurveExp.w, uCurveMode.w));
      return clamp(max(max(top, bottom), max(left, right)), 0.0, 1.0);
    }

    bool inOwnedBand(vec2 coord) {
      float top = ceil(uEdges.x);
      float bottom = ceil(uEdges.y);
      float left = ceil(uEdges.z);
      float right = ceil(uEdges.w);
      float bottomTop = max(uViewSize.y - bottom, top);

      if (coord.y >= 0.0 && coord.y < top) return true;
      if (coord.y >= bottomTop && coord.y < uViewSize.y) return true;

      float centerTop = top;
      float centerBottom = max(uViewSize.y - bottom, centerTop);
      if (coord.y >= centerTop && coord.y < centerBottom) {
        if (coord.x >= 0.0 && coord.x < left) return true;
        float rightLeft = max(uViewSize.x - right, left);
        if (coord.x >= rightLeft && coord.x < uViewSize.x) return true;
      }
      return false;
    }

    float gaussian(float x, float sigma) {
      return exp(-(x * x) / (2.0 * sigma * sigma));
    }
  """

  val HORIZONTAL = """#version 300 es
    #extension GL_OES_EGL_image_external_essl3 : require
    precision highp float;
    precision highp samplerExternalOES;

    in vec2 vUv;
    layout(location = 0) out vec4 outColor;

    uniform samplerExternalOES uContent;
    uniform mat4 uTexMatrix;
    uniform float uBlurRadius;

    $MASK_FUNCTIONS

    vec2 viewCoord() {
      return vec2(vUv.x * uViewSize.x, (1.0 - vUv.y) * uViewSize.y);
    }

    vec4 sampleContent(vec2 coord) {
      vec2 logical = coord / uViewSize;
      vec2 glUv = vec2(logical.x, 1.0 - logical.y);
      vec2 sourceUv = (uTexMatrix * vec4(glUv, 0.0, 1.0)).xy;
      return texture(uContent, sourceUv);
    }

    bool insideX(vec2 coord) {
      return coord.x >= 0.0 && coord.x < uViewSize.x;
    }

    void main() {
      const int maxRadius = 150;
      vec2 coord = viewCoord();
      float intensity = radiusIntensity(coord);
      float radius = uBlurRadius * intensity;
      float r = floor(radius);
      vec4 sampled = sampleContent(coord);

      if (r >= 1.0) {
        float sigma = max(radius / 2.0, 1.0);
        float weightSum = 1.0;
        vec4 result = sampled;

        for (int sampleIndex = 1; sampleIndex < maxRadius; sampleIndex += 2) {
          float i = float(sampleIndex);
          if (i >= r) break;
          float low = gaussian(i, sigma);
          float high = gaussian(i + 1.0, sigma);
          float weight = low + high;
          float d = i + high / weight;
          vec2 a = coord - vec2(d, 0.0);
          vec2 b = coord + vec2(d, 0.0);
          if (insideX(a)) { result += weight * sampleContent(a); weightSum += weight; }
          if (insideX(b)) { result += weight * sampleContent(b); weightSum += weight; }
        }

        if (mod(r, 2.0) > 0.0 && r < float(maxRadius)) {
          float weight = gaussian(r, sigma);
          vec2 a = coord - vec2(r, 0.0);
          vec2 b = coord + vec2(r, 0.0);
          if (insideX(a)) { result += weight * sampleContent(a); weightSum += weight; }
          if (insideX(b)) { result += weight * sampleContent(b); weightSum += weight; }
        }
        sampled = result / weightSum;
      }

      outColor = sampled;
    }
  """

  val VERTICAL = """#version 300 es
    precision highp float;

    in vec2 vUv;
    layout(location = 0) out vec4 outColor;

    uniform sampler2D uContent;
    uniform float uBlurRadius;

    $MASK_FUNCTIONS

    vec2 viewCoord() {
      return vec2(vUv.x * uViewSize.x, (1.0 - vUv.y) * uViewSize.y);
    }

    vec4 sampleContent(vec2 coord) {
      vec2 logical = coord / uViewSize;
      return texture(uContent, vec2(logical.x, 1.0 - logical.y));
    }

    bool insideY(vec2 coord) {
      return coord.y >= 0.0 && coord.y < uViewSize.y;
    }

    void main() {
      const int maxRadius = 150;
      vec2 coord = viewCoord();
      if (!inOwnedBand(coord)) {
        outColor = vec4(0.0);
        return;
      }

      float intensity = radiusIntensity(coord);
      float radius = uBlurRadius * intensity;
      float r = floor(radius);
      vec4 sampled = sampleContent(coord);

      if (r >= 1.0) {
        float sigma = max(radius / 2.0, 1.0);
        float weightSum = 1.0;
        vec4 result = sampled;

        for (int sampleIndex = 1; sampleIndex < maxRadius; sampleIndex += 2) {
          float i = float(sampleIndex);
          if (i >= r) break;
          float low = gaussian(i, sigma);
          float high = gaussian(i + 1.0, sigma);
          float weight = low + high;
          float d = i + high / weight;
          vec2 a = coord - vec2(0.0, d);
          vec2 b = coord + vec2(0.0, d);
          if (insideY(a)) { result += weight * sampleContent(a); weightSum += weight; }
          if (insideY(b)) { result += weight * sampleContent(b); weightSum += weight; }
        }

        if (mod(r, 2.0) > 0.0 && r < float(maxRadius)) {
          float weight = gaussian(r, sigma);
          vec2 a = coord - vec2(0.0, r);
          vec2 b = coord + vec2(0.0, r);
          if (insideY(a)) { result += weight * sampleContent(a); weightSum += weight; }
          if (insideY(b)) { result += weight * sampleContent(b); weightSum += weight; }
        }
        sampled = result / weightSum;
      }

      outColor = sampled;
    }
  """
}
