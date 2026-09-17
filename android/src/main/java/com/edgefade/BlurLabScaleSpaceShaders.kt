package com.edgefade

/** Benchmark-only GLES scale-space shaders for the Gallery vertical blur test. */
internal object BlurLabScaleSpaceShaders {
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
   * glGenerateMipmap repeatedly applies the hardware minification filter. A
   * 2x box step contributes variance 0.25 at its source scale, so after a
   * fractional mip level L the accumulated full-resolution variance is well
   * approximated by (4^L - 1) / 12. Invert that relation to choose a continuous
   * LOD from the target Gaussian variance.
   *
   * The reference progressive kernel truncates at radius = 2*sigma. A Gaussian
   * truncated at +/-2 sigma has ~0.773741 variance of the unbounded Gaussian;
   * matching that second moment avoids systematically over-blurring the mip
   * representation.
   */
  const val COMPOSITE_VERTICAL_SMOOTH = """#version 300 es
    precision highp float;

    in vec2 vUv;
    layout(location = 0) out vec4 outColor;

    uniform sampler2D uPyramid;
    uniform vec2 uViewSize;
    uniform vec2 uEdges;
    uniform float uProgression;
    uniform float uBlurRadius;
    uniform float uMaxLod;

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

    float lodForRadius(float radius) {
      if (radius < 1.0) return 0.0;
      float sigma = radius < 2.0 ? 1.0 : radius * 0.5;
      float targetVariance = sigma * sigma * 0.7737413;
      float lod = 0.5 * log2(12.0 * targetVariance + 1.0);
      return clamp(lod, 0.0, uMaxLod);
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
      float lod = lodForRadius(radius);
      outColor = textureLod(uPyramid, vUv, lod);
    }
  """
}
