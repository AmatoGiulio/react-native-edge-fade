package com.edgefade

/** Benchmark-only explicit Gaussian scale-space shaders. */
internal object BlurLabGaussianScaleShaders {
  const val LEVEL_COUNT = 8

  const val VERTEX = """#version 300 es
    layout(location = 0) in vec2 aPosition;
    out vec2 vUv;

    void main() {
      vUv = aPosition * 0.5 + 0.5;
      gl_Position = vec4(aPosition, 0.0, 1.0);
    }
  """

  const val COPY_EXTERNAL = """#version 300 es
    #extension GL_OES_EGL_image_external_essl3 : require
    precision highp float;
    precision highp samplerExternalOES;

    in vec2 vUv;
    layout(location = 0) out vec4 outColor;

    uniform samplerExternalOES uContent;
    uniform mat4 uTexMatrix;

    void main() {
      vec2 sourceUv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
      outColor = texture(uContent, sourceUv);
    }
  """

  /**
   * One exact separable-binomial Gaussian prefilter while reducing by 2x.
   * [1 4 6 4 1] / 16 has variance 1 source pixel per axis. Repeating it
   * across 2x levels yields full-resolution variances:
   * 0, 1, 5, 21, 85, 341, 1365, 5461.
   */
  const val GAUSSIAN_DOWNSAMPLE = """#version 300 es
    precision highp float;

    in vec2 vUv;
    layout(location = 0) out vec4 outColor;

    uniform sampler2D uSource;
    uniform vec2 uTexel;

    float w(int i) {
      int a = i < 0 ? -i : i;
      if (a == 0) return 6.0;
      if (a == 1) return 4.0;
      return 1.0;
    }

    void main() {
      vec4 sum = vec4(0.0);
      for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
          vec2 offset = vec2(float(x), float(y)) * uTexel;
          sum += texture(uSource, clamp(vUv + offset, vec2(0.0), vec2(1.0))) * w(x) * w(y);
        }
      }
      outColor = sum / 256.0;
    }
  """

  /**
   * Continuous radius field, sampled from a true Gaussian scale-space.
   * Adjacent Gaussian levels are mixed in variance space. There are no
   * screen-space bands and no box-filter mip chain.
   */
  const val COMPOSITE_VERTICAL_SMOOTH = """#version 300 es
    precision highp float;

    in vec2 vUv;
    layout(location = 0) out vec4 outColor;

    uniform sampler2D uLevel0;
    uniform sampler2D uLevel1;
    uniform sampler2D uLevel2;
    uniform sampler2D uLevel3;
    uniform sampler2D uLevel4;
    uniform sampler2D uLevel5;
    uniform sampler2D uLevel6;
    uniform sampler2D uLevel7;
    uniform vec2 uViewSize;
    uniform vec2 uEdges;
    uniform float uProgression;
    uniform float uBlurRadius;

    float smoothPresence(float t) {
      float x = clamp(t, 0.0, 1.0);
      return 1.0 - pow(1.0 - x, 3.0);
    }

    float edgePresence(float distance, float depth) {
      if (depth <= 0.0 || distance >= depth) return 0.0;
      float t = clamp((1.0 - distance / depth) / uProgression, 0.0, 1.0);
      return smoothPresence(t);
    }

    bool inOwnedBand(vec2 coord) {
      float top = ceil(uEdges.x);
      float bottom = ceil(uEdges.y);
      float bottomTop = max(uViewSize.y - bottom, top);
      if (coord.y >= 0.0 && coord.y < top) return true;
      if (coord.y >= bottomTop && coord.y < uViewSize.y) return true;
      return false;
    }

    vec4 sampleLevel(int level, vec2 uv) {
      if (level <= 0) return texture(uLevel0, uv);
      if (level == 1) return texture(uLevel1, uv);
      if (level == 2) return texture(uLevel2, uv);
      if (level == 3) return texture(uLevel3, uv);
      if (level == 4) return texture(uLevel4, uv);
      if (level == 5) return texture(uLevel5, uv);
      if (level == 6) return texture(uLevel6, uv);
      return texture(uLevel7, uv);
    }

    float varianceAt(int level) {
      if (level <= 0) return 0.0;
      if (level == 1) return 1.0;
      if (level == 2) return 5.0;
      if (level == 3) return 21.0;
      if (level == 4) return 85.0;
      if (level == 5) return 341.0;
      if (level == 6) return 1365.0;
      return 5461.0;
    }

    vec4 gaussianScaleSample(vec2 uv, float radius) {
      if (radius < 1.0) return sampleLevel(0, uv);

      // The reference kernel uses sigma=max(radius/2,1) and truncates at
      // +/-2 sigma. Match its second moment before entering scale-space.
      float sigma = max(radius * 0.5, 1.0);
      float targetVariance = sigma * sigma * 0.7737413;

      int low = 0;
      int high = 1;
      for (int i = 0; i < 7; i++) {
        if (targetVariance <= varianceAt(i + 1)) {
          low = i;
          high = i + 1;
          break;
        }
        low = 6;
        high = 7;
      }

      float v0 = varianceAt(low);
      float v1 = varianceAt(high);
      float t = clamp((targetVariance - v0) / max(v1 - v0, 0.0001), 0.0, 1.0);
      return mix(sampleLevel(low, uv), sampleLevel(high, uv), t);
    }

    void main() {
      vec2 coord = vec2(vUv.x * uViewSize.x, (1.0 - vUv.y) * uViewSize.y);
      if (!inOwnedBand(coord)) {
        outColor = vec4(0.0);
        return;
      }

      float top = edgePresence(coord.y, uEdges.x);
      float bottom = edgePresence(uViewSize.y - coord.y, uEdges.y);
      float radius = uBlurRadius * max(top, bottom);
      outColor = gaussianScaleSample(vUv, radius);
    }
  """
}
