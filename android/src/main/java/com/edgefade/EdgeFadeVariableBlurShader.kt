package com.edgefade

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Experimental API 33+ spatially-variable blur shader.
 *
 * Unlike the production multi-Gaussian stack, the sample radius is computed for
 * every fragment from the same fade curve used by EdgeFadeView:
 *
 *   radius(t) = maxRadius * presence(curve, min(t / frostProgression, 1))
 *
 * This is intentionally a sparse 9-tap Gaussian-like low-pass kernel, not a
 * mathematically exact Gaussian. The experiment is about whether continuous
 * spatial radius + one composite can beat three native Gaussian passes in the
 * quality/performance tradeoff.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeVariableBlurShader {
  private val shader = RuntimeShader(AGSL_SRC)
  private val effect = RenderEffect.createRuntimeShaderEffect(shader, "content")

  private var curveKey: String? = null

  fun renderEffect(): RenderEffect = effect

  fun update(
    curve: String,
    nodeWidth: Float,
    nodeHeight: Float,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    maxRadius: Float,
    frostProgression: Float,
    frostSaturation: Float,
    frostLift: Float,
  ): Boolean {
    if (curveKey != curve) {
      if (!uploadCurve(curve)) return false
      curveKey = curve
    }

    return runCatching {
      shader.setFloatUniform("nodeSize", nodeWidth, nodeHeight)
      shader.setFloatUniform("start", x0, y0)
      shader.setFloatUniform("end", x1, y1)
      shader.setFloatUniform("maxRadius", maxRadius.coerceAtLeast(0f))
      shader.setFloatUniform("frostProgression", frostProgression.coerceIn(0.05f, 1f))
      shader.setFloatUniform("saturation", frostSaturation.coerceAtLeast(0f))
      shader.setFloatUniform("lift", frostLift.coerceAtLeast(0f))
      true
    }.getOrDefault(false)
  }

  private fun uploadCurve(curve: String): Boolean {
    val preset = EdgeFadeCurves.agslPresetParams(curve)
    val lut = if (preset == null) EdgeFadeCurves.parseCustomLUT(curve) else null
    if (preset == null && lut == null) return false

    return runCatching {
      if (lut != null) {
        shader.setFloatUniform("useLUT", 1f)
        shader.setFloatUniform("alphaLUT", lut)
        shader.setFloatUniform("curveExp", 1f)
        shader.setFloatUniform("isSoft", 0f)
      } else {
        val (exp, soft) = preset!!
        shader.setFloatUniform("useLUT", 0f)
        shader.setFloatUniform("curveExp", exp)
        shader.setFloatUniform("isSoft", soft)
      }
      true
    }.getOrDefault(false)
  }

  internal companion object {
    const val AGSL_SRC = """
      uniform shader content;

      uniform float2 nodeSize;
      uniform float2 start;
      uniform float2 end;

      uniform float maxRadius;
      uniform float frostProgression;
      uniform float saturation;
      uniform float lift;

      uniform float curveExp;
      uniform float isSoft;
      uniform float useLUT;
      uniform float alphaLUT[32];

      float lutSample(float t) {
        float pos = clamp(t, 0.0, 1.0) * 31.0;
        int lo = clamp(int(pos), 0, 30);
        float f = pos - float(lo);
        return mix(alphaLUT[lo], alphaLUT[lo + 1], f);
      }

      float alphaAt(float t) {
        t = clamp(t, 0.0, 1.0);
        if (useLUT > 0.5) {
          return lutSample(t);
        }
        if (isSoft > 1.5) {
          return 1.0 - (t * t * t * (t * (t * 6.0 - 15.0) + 10.0));
        }
        if (isSoft > 0.5) {
          return cos(t * 1.5707963);
        }
        return pow(1.0 - t, curveExp);
      }

      float2 safeCoord(float2 p) {
        // EdgeFadeView supplies a radius-sized recording pad around the visible
        // band. Clamp is therefore a last-resort guard, not the normal sampler
        // behavior at the sharp/frost seam.
        return clamp(p, float2(0.5), nodeSize - float2(0.5));
      }

      half4 tap(float2 p) {
        return content.eval(safeCoord(p));
      }

      half3 grade(half3 rgb) {
        half luma = dot(rgb, half3(0.2126, 0.7152, 0.0722));
        half3 graded = mix(half3(luma), rgb, saturation);
        return clamp(graded * lift, 0.0, 1.0);
      }

      half4 main(float2 xy) {
        float2 d = end - start;
        float len2 = dot(d, d);
        float t = len2 > 0.0
          ? clamp(dot(xy - start, d) / len2, 0.0, 1.0)
          : 0.0;

        float u = min(t / max(frostProgression, 0.0001), 1.0);
        float presence = clamp(1.0 - alphaAt(u), 0.0, 1.0);
        float r = maxRadius * presence;

        // The center sample has a deliberately high weight. At tiny radii this
        // converges smoothly to the source instead of introducing a constant
        // softening floor.
        half4 c = tap(xy) * 0.20;

        float a = r * 0.45;
        c += tap(xy + float2( a, 0.0)) * 0.12;
        c += tap(xy + float2(-a, 0.0)) * 0.12;
        c += tap(xy + float2(0.0,  a)) * 0.12;
        c += tap(xy + float2(0.0, -a)) * 0.12;

        float q = r * 0.70710678;
        c += tap(xy + float2( q,  q)) * 0.08;
        c += tap(xy + float2(-q,  q)) * 0.08;
        c += tap(xy + float2( q, -q)) * 0.08;
        c += tap(xy + float2(-q, -q)) * 0.08;

        c.rgb = grade(c.rgb);
        return c;
      }
    """
  }
}
