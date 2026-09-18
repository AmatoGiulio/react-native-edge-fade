package com.edgefade

/** Benchmark-only scaled continuous Gaussian shaders. */
internal object BlurLabScaledContinuousShaders {
  const val VERTEX = """#version 300 es
    layout(location = 0) in vec2 aPosition;
    out vec2 vUv;

    void main() {
      vUv = aPosition * 0.5 + 0.5;
      gl_Position = vec4(aPosition, 0.0, 1.0);
    }
  """

  private const val COMMON = """
    uniform vec2 uFullSize;
    uniform vec2 uWorkSize;
    uniform vec2 uEdges;
    uniform float uProgression;
    uniform float uBlurRadius;
    uniform float uScale;

    float smoothPresence(float t) {
      float x = clamp(t, 0.0, 1.0);
      return 1.0 - pow(1.0 - x, 3.0);
    }

    float edgePresence(float distance, float depth) {
      if (depth <= 0.0 || distance >= depth) return 0.0;
      float t = clamp((1.0 - distance / depth) / uProgression, 0.0, 1.0);
      return smoothPresence(t);
    }

    float radiusAt(vec2 fullCoord) {
      float top = edgePresence(fullCoord.y, uEdges.x);
      float bottom = edgePresence(uFullSize.y - fullCoord.y, uEdges.y);
      return uBlurRadius * max(top, bottom);
    }

    float gaussian(float x, float sigma) {
      return exp(-(x * x) / (2.0 * sigma * sigma));
    }
  """

  const val HORIZONTAL = """#version 300 es
    #extension GL_OES_EGL_image_external_essl3 : require
    precision highp float;
    precision highp samplerExternalOES;

    in vec2 vUv;
    layout(location = 0) out vec4 outColor;

    uniform samplerExternalOES uContent;
    uniform mat4 uTexMatrix;

    $COMMON

    vec4 sampleSource(vec2 uv) {
      vec2 clamped = clamp(uv, vec2(0.0), vec2(1.0));
      vec2 sourceUv = (uTexMatrix * vec4(clamped, 0.0, 1.0)).xy;
      return texture(uContent, sourceUv);
    }

    void main() {
      const int maxRadius = 128;
      vec2 fullCoord = vec2(vUv.x * uFullSize.x, (1.0 - vUv.y) * uFullSize.y);
      float radius = radiusAt(fullCoord) * uScale;
      float r = floor(radius);
      vec4 sampled = sampleSource(vUv);

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
          vec2 delta = vec2(d / uWorkSize.x, 0.0);
          result += weight * sampleSource(vUv - delta);
          result += weight * sampleSource(vUv + delta);
          weightSum += 2.0 * weight;
        }

        if (mod(r, 2.0) > 0.0 && r < float(maxRadius)) {
          float weight = gaussian(r, sigma);
          vec2 delta = vec2(r / uWorkSize.x, 0.0);
          result += weight * sampleSource(vUv - delta);
          result += weight * sampleSource(vUv + delta);
          weightSum += 2.0 * weight;
        }
        sampled = result / weightSum;
      }

      outColor = sampled;
    }
  """

  const val VERTICAL = """#version 300 es
    precision highp float;

    in vec2 vUv;
    layout(location = 0) out vec4 outColor;

    uniform sampler2D uContent;

    $COMMON

    vec4 sampleWork(vec2 uv) {
      return texture(uContent, clamp(uv, vec2(0.0), vec2(1.0)));
    }

    void main() {
      const int maxRadius = 128;
      vec2 fullCoord = vec2(vUv.x * uFullSize.x, (1.0 - vUv.y) * uFullSize.y);
      float radius = radiusAt(fullCoord) * uScale;
      float r = floor(radius);
      vec4 sampled = sampleWork(vUv);

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
          vec2 delta = vec2(0.0, d / uWorkSize.y);
          result += weight * sampleWork(vUv - delta);
          result += weight * sampleWork(vUv + delta);
          weightSum += 2.0 * weight;
        }

        if (mod(r, 2.0) > 0.0 && r < float(maxRadius)) {
          float weight = gaussian(r, sigma);
          vec2 delta = vec2(0.0, r / uWorkSize.y);
          result += weight * sampleWork(vUv - delta);
          result += weight * sampleWork(vUv + delta);
          weightSum += 2.0 * weight;
        }
        sampled = result / weightSum;
      }

      outColor = sampled;
    }
  """

  const val COMPOSITE = """#version 300 es
    #extension GL_OES_EGL_image_external_essl3 : require
    precision highp float;
    precision highp samplerExternalOES;

    in vec2 vUv;
    layout(location = 0) out vec4 outColor;

    uniform sampler2D uBlurred;
    uniform samplerExternalOES uSource;
    uniform mat4 uTexMatrix;

    $COMMON

    vec4 sampleSource(vec2 uv) {
      vec2 sourceUv = (uTexMatrix * vec4(clamp(uv, vec2(0.0), vec2(1.0)), 0.0, 1.0)).xy;
      return texture(uSource, sourceUv);
    }

    bool inOwnedBand(vec2 coord) {
      float top = ceil(uEdges.x);
      float bottom = ceil(uEdges.y);
      float bottomTop = max(uFullSize.y - bottom, top);
      if (coord.y >= 0.0 && coord.y < top) return true;
      if (coord.y >= bottomTop && coord.y < uFullSize.y) return true;
      return false;
    }

    void main() {
      vec2 fullCoord = vec2(vUv.x * uFullSize.x, (1.0 - vUv.y) * uFullSize.y);
      if (!inOwnedBand(fullCoord)) {
        outColor = vec4(0.0);
        return;
      }

      float radius = radiusAt(fullCoord);
      vec4 sharp = sampleSource(vUv);
      vec4 blurred = texture(uBlurred, vUv);

      // Preserve the identity end of the progressive field exactly. The work
      // texture is lower resolution, so use the original source until the
      // reference kernel itself has entered a meaningful blur radius.
      float blurMix = smoothstep(0.75, 3.0, radius);
      outColor = mix(sharp, blurred, blurMix);
    }
  """
}
