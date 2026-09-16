package com.edgefade

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.random.Random

private var checks = 0
private fun verify(value: Boolean, message: String) {
  checks++
  check(value) { message }
}
private fun close(actual: Float, expected: Float, message: String) =
  verify(abs(actual - expected) < 0.0001f, "$message: $actual != $expected")
private fun contains(rect: BlurLabGeometry.Rect, x: Int, y: Int) =
  x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom

fun main() {
  val g = BlurLabGeometry
  close(g.radius(-3f), 0f, "negative radius")
  close(g.radius(Float.NaN), 0f, "NaN radius")
  close(g.radius(Float.POSITIVE_INFINITY), 0f, "infinite radius")
  close(g.radius(200f), 150f, "pixel radius cap")
  close(g.edge(2.01f, 10), 3f, "pixel alignment")
  close(g.edge(30f, 10), 10f, "oversized band")
  close(g.edge(-1f, 10), 0f, "negative band")
  close(g.edge(Float.NaN, 10), 0f, "NaN band")
  verify(g.bands(0, 10, floatArrayOf(4f, 4f, 0f, 0f), 12f).isEmpty(), "zero width")
  verify(g.bands(10, 0, floatArrayOf(4f, 4f, 0f, 0f), 12f).isEmpty(), "zero height")
  verify(g.bands(100, 200, FloatArray(4), 24f).isEmpty(), "disabled edges")
  val top = g.bands(100, 200, floatArrayOf(20f, 0f, 0f, 0f), 12f).single()
  verify(top.visible == BlurLabGeometry.Rect(0, 0, 100, 20), "top visible band")
  verify(top.source == BlurLabGeometry.Rect(0, 0, 100, 33), "padding includes bilinear tap")
  verify(top.edge == 0, "stable edge identity")

  for (p in listOf(0f, 0.05f, 0.5f, 1f, 10f, Float.NaN)) {
    close(g.envelope(100f, 100f, p), 0f, "inner seam zero")
    close(g.envelope(0f, 100f, p), 1f, "outer edge full")
    close(g.envelope(0f, 0f, p), 0f, "disabled edge")
    var previous = 1f
    for (i in 0..100) {
      val actual = g.envelope(i.toFloat(), 100f, p)
      verify(actual in 0f..1f && actual <= previous, "monotone bounded progression")
      previous = actual
    }
  }
  close(g.envelope(75f, 100f, 0.5f), 0.5f, "progression compression")

  // Same clipping partition used by the renderer: sharp outside the union;
  // each band excludes all previous bands. Even overlapping corners have
  // exactly one output owner, with enough source padding for either pass.
  val random = Random(57972)
  repeat(1000) {
    val w = random.nextInt(1, 48)
    val h = random.nextInt(1, 64)
    val edges = FloatArray(4) { random.nextInt(-5, 80).toFloat() + 0.3f }
    val radius = random.nextFloat() * 200f
    val bands = g.bands(w, h, edges, radius)
    val pad = ceil(g.radius(radius)).toInt() + 1
    verify(bands.map { it.edge }.distinct().size == bands.size, "unique edge ids")
    for (band in bands) {
      val s = band.source; val v = band.visible
      verify(s.left >= 0 && s.top >= 0 && s.right <= w && s.bottom <= h, "source bounds")
      verify(s.width > 0 && s.height > 0, "positive recording size")
      verify(s.left <= v.left && s.top <= v.top && s.right >= v.right && s.bottom >= v.bottom, "source contains visible")
      verify(s.left == maxOf(0, v.left - pad) && s.top == maxOf(0, v.top - pad), "leading padding")
      verify(s.right == minOf(w, v.right + pad) && s.bottom == minOf(h, v.bottom + pad), "trailing padding")
    }
    for (y in 0 until h) for (x in 0 until w) {
      val affected = bands.any { contains(it.visible, x, y) }
      var owners = if (affected) 0 else 1
      for (i in bands.indices) {
        if (contains(bands[i].visible, x, y) && (0 until i).none { contains(bands[it].visible, x, y) }) owners++
      }
      verify(owners == 1, "pixel must have exactly one output owner")
    }
  }

  // These check generated SOURCE contracts, not GPU compilation or rendering.
  val horizontal = BlurLabShaders.pass(false)
  val vertical = BlurLabShaders.pass(true)
  verify(horizontal.contains("float2(d, 0.0)"), "horizontal offset")
  verify(vertical.contains("float2(0.0, d)"), "vertical offset")
  verify(horizontal.contains("p.x") && vertical.contains("p.y"), "moved-axis bounds")
  for (source in listOf(horizontal, vertical)) {
    verify(source.contains("const float maxRadius = 150.0"), "bounded kernel")
    verify(source.contains("if (r < 1.0) return content.eval(coord)"), "zero footprint bypass")
    verify(source.contains("float d = i + high / weight"), "paired bilinear taps")
    verify(source.contains("result / weightSum"), "normalization")
  }
  verify(BlurLabShaders.mask.contains("uniform float curve[32]"), "curve uniform size")
  verify(BlurLabShaders.mask.contains("local + origin"), "global mask coordinates")
  verify(BlurLabShaders.mask.contains("max(a, b)"), "corner radius union")
  println("PASS: $checks assertions; 1000 deterministic geometry cases; shader SOURCE contracts")
  println("NOT TESTED: Android compilation, RuntimeShader GPU compilation, visual output, frame time")
}
