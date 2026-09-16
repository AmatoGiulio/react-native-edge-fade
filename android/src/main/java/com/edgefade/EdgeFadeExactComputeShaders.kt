package com.edgefade

/**
 * Experimental GLES 3.1 compute implementation of the validated progressive
 * Gaussian. This is intentionally not a bounded-tap approximation: the row
 * prepass generates the exact same paired Gaussian offsets/weights used by the
 * AndroidX-derived AGSL shader, then the H/V compute passes evaluate every pair.
 *
 * The optimization is execution-only:
 * - Gaussian weights/offsets are generated once per output row when props change.
 * - Neighboring output pixels reuse source texels through workgroup shared memory.
 * - The signal, radius field, sigma and H -> V ordering are unchanged.
 *
 * The first experimental gate supports top/bottom edges only. Four-edge routing
 * remains on the validated AGSL renderer until this backend passes visual and
 * performance gates on the vertical case.
 */
internal object EdgeFadeExactComputeShaders {
  const val MAX_RADIUS = 150
  const val MAX_PAIRS = 75
  const val HALO = MAX_RADIUS

  // Copy the HardwareRenderer SurfaceTexture into an ordinary RGBA8 texture.
  // Compute shaders cannot portably consume samplerExternalOES on Android, so
  // this cheap one-sample pass is the bridge into image/SSBO based processing.
  const val COPY_VERTEX = """#version 300 es
    layout(location = 0) in vec2 aPosition;
    out vec2 vUv;

    void main() {
      vUv = aPosition * 0.5 + 0.5;
      gl_Position = vec4(aPosition, 0.0, 1.0);
    }
  """

  const val COPY_FRAGMENT = """#version 300 es
    #extension GL_OES_EGL_image_external_essl3 : require
    precision highp float;
    precision highp samplerExternalOES;

    in vec2 vUv;
    layout(location = 0) out vec4 outColor;

    uniform samplerExternalOES uContent;
    uniform mat4 uTexMatrix;
    uniform vec2 uViewSize;
    uniform vec4 uSourceRect; // left, top, width, height in logical top-left px

    void main() {
      vec2 local = vec2(
        vUv.x * uSourceRect.z,
        (1.0 - vUv.y) * uSourceRect.w
      );
      vec2 coord = uSourceRect.xy + local;
      vec2 logical = coord / uViewSize;
      vec2 glUv = vec2(logical.x, 1.0 - logical.y);
      vec2 sourceUv = (uTexMatrix * vec4(glUv, 0.0, 1.0)).xy;
      outColor = texture(uContent, sourceUv);
    }
  """

  /**
   * One invocation per logical view row. Generates the exact paired-tap table
   * used by BlurLabShaders.pass(): same radius, floor(), sigma=radius/2,
   * gaussian(), paired bilinear offset and odd-radius tail.
   */
  const val PRECOMPUTE = """#version 310 es
    precision highp float;
    precision highp int;

    layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

    layout(std430, binding = 2) buffer PairBuffer {
      vec2 pairs[]; // x = paired offset, y = paired weight
    };
    layout(std430, binding = 3) buffer MetaBuffer {
      vec4 meta[];  // x = pair count, y = full symmetric weight sum, z = radius, w = floor(radius)
    };

    uniform int uHeight;
    uniform vec2 uEdges; // top, bottom
    uniform float uProgression;
    uniform float uBlurRadius;
    uniform vec2 uCurveExp;
    uniform vec2 uCurveMode;
    uniform vec2 uUseLut;
    uniform float uTopLut[32];
    uniform float uBottomLut[32];

    const int kMaxRadius = 150;
    const int kMaxPairs = 75;

    float presence(float t, float exponent, float mode) {
      float x = clamp(t, 0.0, 1.0);
      if (mode > 1.5) {
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
      }
      if (mode > 0.5) {
        return 1.0 - cos(x * 1.5707963267948966);
      }
      return 1.0 - pow(1.0 - x, exponent);
    }

    float sampleTop(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      int lo = min(int(floor(x)), 30);
      return mix(uTopLut[lo], uTopLut[lo + 1], x - float(lo));
    }

    float sampleBottom(float t) {
      float x = clamp(t, 0.0, 1.0) * 31.0;
      int lo = min(int(floor(x)), 30);
      return mix(uBottomLut[lo], uBottomLut[lo + 1], x - float(lo));
    }

    float edgePosition(float distance, float depth) {
      if (depth <= 0.0 || distance >= depth) return -1.0;
      return clamp((1.0 - distance / depth) / uProgression, 0.0, 1.0);
    }

    float gaussian(float x, float sigma) {
      return exp(-(x * x) / (2.0 * sigma * sigma));
    }

    void main() {
      int y = int(gl_GlobalInvocationID.x);
      if (y >= uHeight) return;

      float py = float(y) + 0.5;
      float topPos = edgePosition(py, uEdges.x);
      float bottomPos = edgePosition(float(uHeight) - py, uEdges.y);
      float top = topPos < 0.0
        ? 0.0
        : (uUseLut.x > 0.5 ? sampleTop(topPos) : presence(topPos, uCurveExp.x, uCurveMode.x));
      float bottom = bottomPos < 0.0
        ? 0.0
        : (uUseLut.y > 0.5 ? sampleBottom(bottomPos) : presence(bottomPos, uCurveExp.y, uCurveMode.y));
      float radius = uBlurRadius * clamp(max(top, bottom), 0.0, 1.0);
      float r = floor(radius);
      float fullWeightSum = 1.0;
      int count = 0;

      if (r >= 1.0) {
        float sigma = max(radius / 2.0, 1.0);
        for (int sampleIndex = 1; sampleIndex < kMaxRadius; sampleIndex += 2) {
          float i = float(sampleIndex);
          if (i >= r) break;
          float low = gaussian(i, sigma);
          float high = gaussian(i + 1.0, sigma);
          float weight = low + high;
          float d = i + high / weight;
          pairs[y * kMaxPairs + count] = vec2(d, weight);
          fullWeightSum += 2.0 * weight;
          count++;
        }

        if (mod(r, 2.0) > 0.0 && r < float(kMaxRadius)) {
          float weight = gaussian(r, sigma);
          pairs[y * kMaxPairs + count] = vec2(r, weight);
          fullWeightSum += 2.0 * weight;
          count++;
        }
      }

      meta[y] = vec4(float(count), fullWeightSum, radius, r);
    }
  """

  /**
   * Horizontal exact pass. Workgroups process 16x2 output pixels. Each of the
   * two rows loads a 150px halo plus the 16px body into shared memory once, so
   * neighboring outputs reuse the same texels instead of refetching them for
   * every Gaussian pair.
   */
  const val HORIZONTAL = """#version 310 es
    precision highp float;
    precision highp int;

    layout(local_size_x = 16, local_size_y = 2, local_size_z = 1) in;
    layout(binding = 0) uniform sampler2D uContent;
    layout(rgba16f, binding = 0) writeonly uniform highp image2D uOutput;

    layout(std430, binding = 2) readonly buffer PairBuffer {
      vec2 pairs[];
    };
    layout(std430, binding = 3) readonly buffer MetaBuffer {
      vec4 meta[];
    };

    uniform ivec2 uSize;
    uniform int uSourceBottom; // logical bottom of this source rect, exclusive

    const int kHalo = 150;
    const int kBodyX = 16;
    const int kBodyY = 2;
    const int kTileWidth = kBodyX + kHalo * 2;
    const int kTileSize = kTileWidth * kBodyY;
    const int kMaxPairs = 75;

    shared vec4 tile[kTileSize];

    vec4 sampleShared(float position, int row, int baseX) {
      float p = clamp(position, 0.0, float(uSize.x - 1));
      int lo = int(floor(p));
      int hi = min(lo + 1, uSize.x - 1);
      float t = p - float(lo);
      int first = baseX - kHalo;
      int localLo = lo - first;
      int localHi = hi - first;
      vec4 a = tile[row * kTileWidth + localLo];
      vec4 b = tile[row * kTileWidth + localHi];
      return mix(a, b, t);
    }

    void main() {
      ivec2 groupBase = ivec2(gl_WorkGroupID.xy) * ivec2(kBodyX, kBodyY);
      int localIndex = int(gl_LocalInvocationIndex);

      for (int index = localIndex; index < kTileSize; index += kBodyX * kBodyY) {
        int row = index / kTileWidth;
        int col = index - row * kTileWidth;
        int x = groupBase.x + col - kHalo;
        int y = groupBase.y + row;
        tile[index] = (x >= 0 && x < uSize.x && y >= 0 && y < uSize.y)
          ? texelFetch(uContent, ivec2(x, y), 0)
          : vec4(0.0);
      }
      barrier();

      ivec2 outCoord = groupBase + ivec2(gl_LocalInvocationID.xy);
      if (outCoord.x >= uSize.x || outCoord.y >= uSize.y) return;

      int globalY = uSourceBottom - 1 - outCoord.y;
      int count = int(meta[globalY].x + 0.5);
      int row = int(gl_LocalInvocationID.y);
      int centerIndex = row * kTileWidth + int(gl_LocalInvocationID.x) + kHalo;
      vec4 result = tile[centerIndex];
      float weightSum = 1.0;
      float centerX = float(outCoord.x);

      for (int pairIndex = 0; pairIndex < kMaxPairs; pairIndex++) {
        if (pairIndex >= count) break;
        vec2 pair = pairs[globalY * kMaxPairs + pairIndex];
        float d = pair.x;
        float weight = pair.y;

        float aCoord = centerX + 0.5 - d;
        if (aCoord >= 0.0 && aCoord < float(uSize.x)) {
          result += weight * sampleShared(centerX - d, row, groupBase.x);
          weightSum += weight;
        }

        float bCoord = centerX + 0.5 + d;
        if (bCoord >= 0.0 && bCoord < float(uSize.x)) {
          result += weight * sampleShared(centerX + d, row, groupBase.x);
          weightSum += weight;
        }
      }

      imageStore(uOutput, outCoord, result / weightSum);
    }
  """

  /**
   * Vertical exact pass. Workgroups process 2x16 visible pixels and stage a
   * 150px vertical halo for both columns. Each output row uses its own exact
   * precomputed Gaussian table, so spatially-varying radius semantics remain
   * identical to the validated shader.
   */
  const val VERTICAL = """#version 310 es
    precision highp float;
    precision highp int;

    layout(local_size_x = 2, local_size_y = 16, local_size_z = 1) in;
    layout(binding = 0) uniform sampler2D uContent;
    layout(rgba8, binding = 0) writeonly uniform highp image2D uOutput;

    layout(std430, binding = 2) readonly buffer PairBuffer {
      vec2 pairs[];
    };
    layout(std430, binding = 3) readonly buffer MetaBuffer {
      vec4 meta[];
    };

    uniform ivec2 uSourceSize;
    uniform ivec2 uOutputSize;
    uniform ivec2 uSourceOffset; // output (0,0) in source texture coordinates
    uniform int uVisibleBottom;  // logical bottom of the visible rect, exclusive

    const int kHalo = 150;
    const int kBodyX = 2;
    const int kBodyY = 16;
    const int kTileHeight = kBodyY + kHalo * 2;
    const int kTileSize = kTileHeight * kBodyX;
    const int kMaxPairs = 75;

    shared vec4 tile[kTileSize];

    vec4 sampleShared(float position, int column, int baseSourceY) {
      float p = clamp(position, 0.0, float(uSourceSize.y - 1));
      int lo = int(floor(p));
      int hi = min(lo + 1, uSourceSize.y - 1);
      float t = p - float(lo);
      int first = baseSourceY - kHalo;
      int localLo = lo - first;
      int localHi = hi - first;
      vec4 a = tile[column * kTileHeight + localLo];
      vec4 b = tile[column * kTileHeight + localHi];
      return mix(a, b, t);
    }

    void main() {
      ivec2 outputBase = ivec2(gl_WorkGroupID.xy) * ivec2(kBodyX, kBodyY);
      int baseSourceY = uSourceOffset.y + outputBase.y;
      int localIndex = int(gl_LocalInvocationIndex);

      for (int index = localIndex; index < kTileSize; index += kBodyX * kBodyY) {
        int column = index / kTileHeight;
        int row = index - column * kTileHeight;
        int x = uSourceOffset.x + outputBase.x + column;
        int y = baseSourceY + row - kHalo;
        tile[index] = (x >= 0 && x < uSourceSize.x && y >= 0 && y < uSourceSize.y)
          ? texelFetch(uContent, ivec2(x, y), 0)
          : vec4(0.0);
      }
      barrier();

      ivec2 outCoord = outputBase + ivec2(gl_LocalInvocationID.xy);
      if (outCoord.x >= uOutputSize.x || outCoord.y >= uOutputSize.y) return;

      int sourceY = uSourceOffset.y + outCoord.y;
      int globalY = uVisibleBottom - 1 - outCoord.y;
      int count = int(meta[globalY].x + 0.5);
      int column = int(gl_LocalInvocationID.x);
      int centerIndex = column * kTileHeight + int(gl_LocalInvocationID.y) + kHalo;
      vec4 result = tile[centerIndex];
      float weightSum = 1.0;
      float centerY = float(sourceY);

      for (int pairIndex = 0; pairIndex < kMaxPairs; pairIndex++) {
        if (pairIndex >= count) break;
        vec2 pair = pairs[globalY * kMaxPairs + pairIndex];
        float d = pair.x;
        float weight = pair.y;

        float aCoord = centerY + 0.5 - d;
        if (aCoord >= 0.0 && aCoord < float(uSourceSize.y)) {
          result += weight * sampleShared(centerY - d, column, baseSourceY);
          weightSum += weight;
        }

        float bCoord = centerY + 0.5 + d;
        if (bCoord >= 0.0 && bCoord < float(uSourceSize.y)) {
          result += weight * sampleShared(centerY + d, column, baseSourceY);
          weightSum += weight;
        }
      }

      imageStore(uOutput, outCoord, result / weightSum);
    }
  """
}
