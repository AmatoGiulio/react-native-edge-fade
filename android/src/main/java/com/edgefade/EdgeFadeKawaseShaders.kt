/*
 * Copyright 2021 The Android Open Source Project
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
 * See android/PROGRESSIVE_BLUR_NOTICE.md.
 */
package com.edgefade

/**
 * App-side adaptation of Android RenderEngine's Kawase backdrop blur.
 *
 * The important system characteristics are preserved:
 * - a real 0.25x raster surface (not a transformed RenderNode);
 * - up to four ping-pong Kawase passes;
 * - linearly sampled upscale;
 * - spatial alpha composition performed only after the diffusion texture exists.
 */
internal object EdgeFadeKawaseShaders {
  const val VERTEX = """#version 300 es
    layout(location = 0) in vec2 aPosition;
    out vec2 vUv;

    void main() {
      vUv = aPosition * 0.5 + 0.5;
      gl_Position = vec4(aPosition, 0.0, 1.0);
    }
  """

  const val FIRST_PASS = """#version 300 es
    #extension GL_OES_EGL_image_external_essl3 : require
    precision highp float;
    precision highp samplerExternalOES;

    in vec2 vUv;
    layout(location = 0) out vec4 outColor;

    uniform samplerExternalOES uContent;
    uniform mat4 uTexMatrix;
    uniform vec2 uOffsetUv;

    vec4 sampleSource(vec2 uv) {
      vec2 p = clamp(uv, vec2(0.0), vec2(1.0));
      vec2 transformed = (uTexMatrix * vec4(p, 0.0, 1.0)).xy;
      return texture(uContent, transformed);
    }

    void main() {
      vec4 c = sampleSource(vUv);
      c += sampleSource(vUv + vec2(+uOffsetUv.x, +uOffsetUv.y));
      c += sampleSource(vUv + vec2(+uOffsetUv.x, -uOffsetUv.y));
      c += sampleSource(vUv + vec2(-uOffsetUv.x, -uOffsetUv.y));
      c += sampleSource(vUv + vec2(-uOffsetUv.x, +uOffsetUv.y));
      outColor = vec4(c.rgb * 0.2, 1.0);
    }
  """

  const val KAWASE_PASS = """#version 300 es
    precision highp float;

    in vec2 vUv;
    layout(location = 0) out vec4 outColor;

    uniform sampler2D uContent;
    uniform vec2 uOffsetUv;

    vec4 sampleSource(vec2 uv) {
      return texture(uContent, clamp(uv, vec2(0.0), vec2(1.0)));
    }

    void main() {
      vec4 c = sampleSource(vUv);
      c += sampleSource(vUv + vec2(+uOffsetUv.x, +uOffsetUv.y));
      c += sampleSource(vUv + vec2(+uOffsetUv.x, -uOffsetUv.y));
      c += sampleSource(vUv + vec2(-uOffsetUv.x, -uOffsetUv.y));
      c += sampleSource(vUv + vec2(-uOffsetUv.x, +uOffsetUv.y));
      outColor = vec4(c.rgb * 0.2, 1.0);
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

    float maskIntensity(vec2 coord) {
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
  """

  val FINAL_OVERLAY = """#version 300 es
    precision highp float;

    in vec2 vUv;
    layout(location = 0) out vec4 outColor;

    uniform sampler2D uContent;
    uniform float uContrast;
    uniform float uSaturation;

    $MASK_FUNCTIONS

    vec2 viewCoord() {
      return vec2(vUv.x * uViewSize.x, (1.0 - vUv.y) * uViewSize.y);
    }

    void main() {
      float amount = maskIntensity(viewCoord());
      if (amount <= 0.0001) {
        outColor = vec4(0.0);
        return;
      }

      vec3 rgb = texture(uContent, vUv).rgb;
      float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
      rgb = mix(vec3(luma), rgb, uSaturation);
      rgb = (rgb - 0.5) * uContrast + 0.5;
      rgb = clamp(rgb, 0.0, 1.0);

      // Premultiplied alpha: the hardware Bitmap is drawn SRC_OVER on top of
      // the sharp scene.
      outColor = vec4(rgb * amount, amount);
    }
  """
}
