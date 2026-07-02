/**
 * Curve Lab — a DialKit-style debug tool for tuning the cubic-bezier curve
 * that drives EdgeFadeView's fade ramp, live, against real content.
 *
 * Drag P1/P2 on the alpha(t) plot to shape the curve; the same 32 samples
 * feeding the native fade are the ones drawn here (via `serializeCurve`), so
 * what you see on the graph is exactly what the fade uses.
 */

import { useCallback, useMemo, useRef, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { router } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import { EdgeFadeView, serializeCurve } from 'react-native-edge-fade';
import type { EdgeFadeMode } from 'react-native-edge-fade';

import { CATALOG } from '../constants/catalog';
import { PanelSlider } from '../components/PanelSlider';

const MODES: EdgeFadeMode[] = ['mask', 'overlay', 'blur'];

interface Bezier {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

const PRESETS: Array<{ label: string; value: Bezier }> = [
  { label: 'linear', value: { x1: 0, y1: 0, x2: 1, y2: 1 } },
  { label: 'ease', value: { x1: 0.25, y1: 0.1, x2: 0.25, y2: 1 } },
  { label: 'soft', value: { x1: 0.4, y1: 0, x2: 0.6, y2: 1 } },
  { label: 'sharp', value: { x1: 0.7, y1: 0, x2: 0.84, y2: 0 } },
  { label: 'gentle', value: { x1: 0.25, y1: 0.46, x2: 0.45, y2: 0.94 } },
];

const HERO = CATALOG[5]!;
const ROW_IMAGES = CATALOG.slice(16, 22);

const PLOT_H = 180;
const HANDLE_HIT = 14;
const DRAG_THROTTLE_MS = 33;

function clamp01(v: number) {
  return Math.max(0, Math.min(1, v));
}

export default function CurveLabScreen() {
  const insets = useSafeAreaInsets();

  const [mode, setMode] = useState<EdgeFadeMode>('mask');
  const [zone, setZone] = useState(180);
  const [blurRadius, setBlurRadius] = useState(28);
  const [bezier, setBezier] = useState<Bezier>(PRESETS[1]!.value);
  const [panelOpen, setPanelOpen] = useState(true);
  const [plotWidth, setPlotWidth] = useState(0);

  const handleBack = useCallback(() => router.back(), []);

  const samples = useMemo(
    () =>
      serializeCurve({ type: 'cubicBezier', ...bezier })
        .split(',')
        .map(Number),
    [bezier]
  );

  const p1x1Ref = useRef(bezier.x1);
  const p1y1Ref = useRef(bezier.y1);
  const p2x2Ref = useRef(bezier.x2);
  const p2y2Ref = useRef(bezier.y2);
  p1x1Ref.current = bezier.x1;
  p1y1Ref.current = bezier.y1;
  p2x2Ref.current = bezier.x2;
  p2y2Ref.current = bezier.y2;

  const lastUpdateRef = useRef(0);
  const dragStartRef = useRef({ x1: 0, y1: 0, x2: 0, y2: 0 });

  const applyDrag = useCallback(
    (which: 'p1' | 'p2', nx: number, ny: number, throttle: boolean) => {
      const now = Date.now();
      if (throttle && now - lastUpdateRef.current < DRAG_THROTTLE_MS) return;
      lastUpdateRef.current = now;
      setBezier((prev) =>
        which === 'p1'
          ? { ...prev, x1: nx, y1: ny }
          : { ...prev, x2: nx, y2: ny }
      );
    },
    []
  );

  const panP1 = useMemo(
    () =>
      Gesture.Pan()
        // Handlers use React refs/setState; with Reanimated installed they
        // would otherwise run as worklets on the UI thread and crash.
        .runOnJS(true)
        .onStart(() => {
          dragStartRef.current = {
            x1: p1x1Ref.current,
            y1: p1y1Ref.current,
            x2: p2x2Ref.current,
            y2: p2y2Ref.current,
          };
        })
        .onUpdate((e) => {
          if (plotWidth <= 0) return;
          const nx = clamp01(
            dragStartRef.current.x1 + e.translationX / plotWidth
          );
          const ny = clamp01(dragStartRef.current.y1 + e.translationY / PLOT_H);
          applyDrag('p1', nx, ny, true);
        })
        .onEnd((e) => {
          if (plotWidth <= 0) return;
          const nx = clamp01(
            dragStartRef.current.x1 + e.translationX / plotWidth
          );
          const ny = clamp01(dragStartRef.current.y1 + e.translationY / PLOT_H);
          applyDrag('p1', nx, ny, false);
        }),
    [plotWidth, applyDrag]
  );

  const panP2 = useMemo(
    () =>
      Gesture.Pan()
        // Handlers use React refs/setState; with Reanimated installed they
        // would otherwise run as worklets on the UI thread and crash.
        .runOnJS(true)
        .onStart(() => {
          dragStartRef.current = {
            x1: p1x1Ref.current,
            y1: p1y1Ref.current,
            x2: p2x2Ref.current,
            y2: p2y2Ref.current,
          };
        })
        .onUpdate((e) => {
          if (plotWidth <= 0) return;
          const nx = clamp01(
            dragStartRef.current.x2 + e.translationX / plotWidth
          );
          const ny = clamp01(dragStartRef.current.y2 + e.translationY / PLOT_H);
          applyDrag('p2', nx, ny, true);
        })
        .onEnd((e) => {
          if (plotWidth <= 0) return;
          const nx = clamp01(
            dragStartRef.current.x2 + e.translationX / plotWidth
          );
          const ny = clamp01(dragStartRef.current.y2 + e.translationY / PLOT_H);
          applyDrag('p2', nx, ny, false);
        }),
    [plotWidth, applyDrag]
  );

  // Polyline segments between consecutive samples, in plot-local px.
  const segments = useMemo(() => {
    if (plotWidth <= 0) return [];
    const pts = samples.map((alpha, i) => ({
      x: (i / (samples.length - 1)) * plotWidth,
      y: (1 - alpha) * PLOT_H,
    }));
    const segs: Array<{
      x: number;
      y: number;
      length: number;
      angle: number;
    }> = [];
    for (let i = 0; i < pts.length - 1; i++) {
      const a = pts[i]!;
      const b = pts[i + 1]!;
      const dx = b.x - a.x;
      const dy = b.y - a.y;
      segs.push({
        x: (a.x + b.x) / 2,
        y: (a.y + b.y) / 2,
        length: Math.sqrt(dx * dx + dy * dy),
        angle: Math.atan2(dy, dx),
      });
    }
    return segs;
  }, [samples, plotWidth]);

  // The polyline lands at y_px = B(t) * PLOT_H (alpha = 1 - B(t)), i.e. bezier
  // space with y growing downward: curve start top-left, end bottom-right.
  // Handles and control-line anchors must live in the same space.
  const p1Point = { x: bezier.x1 * plotWidth, y: bezier.y1 * PLOT_H };
  const p2Point = { x: bezier.x2 * plotWidth, y: bezier.y2 * PLOT_H };
  const originPoint = { x: 0, y: 0 };
  const endPoint = { x: plotWidth, y: PLOT_H };

  const ctrlLine1 = lineStyle(originPoint, p1Point);
  const ctrlLine2 = lineStyle(endPoint, p2Point);

  const radii =
    mode === 'blur' ? [blurRadius / 3, (blurRadius * 2) / 3, blurRadius] : null;

  return (
    <View style={s.root}>
      {/* ── Preview, full-screen ─────────────────────────────── */}
      <EdgeFadeView
        bottom={zone || false}
        curve={{ type: 'cubicBezier', ...bezier }}
        mode={mode}
        blurRadius={blurRadius}
        color={mode === 'overlay' ? '#FFFFFF' : undefined}
        style={StyleSheet.absoluteFill}
      >
        <ScrollView
          style={s.scroll}
          showsVerticalScrollIndicator={false}
          contentContainerStyle={{
            paddingTop: insets.top + 56,
            paddingBottom: insets.bottom + 420,
          }}
        >
          <Image source={HERO.source} style={s.hero} contentFit="cover" />
          <View style={s.body}>
            <Text style={s.kicker}>CURVE LAB · DEBUG</Text>
            <Text style={s.title}>Bezier Tuning</Text>
            <Text style={s.lead}>
              Drag the handles on the graph below to reshape the fade curve in
              real time, across all three render modes.
            </Text>
            <Text style={s.section}>Gallery</Text>
            {ROW_IMAGES.map((it, i) => (
              <View key={it.id} style={s.galleryRow}>
                <Image
                  source={it.source}
                  style={s.galleryImg}
                  contentFit="cover"
                />
                <View style={s.galleryMeta}>
                  <Text style={s.galleryTitle}>
                    Item {String(i + 1).padStart(2, '0')}
                  </Text>
                  <Text style={s.gallerySub}>{it.category}</Text>
                </View>
              </View>
            ))}
          </View>
        </ScrollView>
      </EdgeFadeView>

      {/* ── Debug overlay: fade band + blur level markers ───── */}
      {zone > 0 && (
        <View
          pointerEvents="none"
          style={[s.debugBand, { height: zone, bottom: 0 }]}
        >
          {mode === 'blur' && (
            <>
              <View style={[s.blurMarkerLine, { bottom: zone / 3 }]}>
                <Text style={s.blurMarkerLabel}>R/3</Text>
              </View>
              <View style={[s.blurMarkerLine, { bottom: (zone * 2) / 3 }]}>
                <Text style={s.blurMarkerLabel}>2R/3</Text>
              </View>
              <View style={[s.blurMarkerLine, { bottom: zone - 1 }]}>
                <Text style={s.blurMarkerLabel}>R</Text>
              </View>
            </>
          )}
        </View>
      )}

      {/* ── Top bar ─────────────────────────────────────────── */}
      <View style={[s.topBar, { paddingTop: insets.top + 6 }]}>
        <Pressable style={s.iconBtn} onPress={handleBack}>
          <Ionicons name="chevron-back" size={20} color="#0a0a0a" />
        </Pressable>
      </View>

      {/* ── Reopen button (panel closed) ────────────────────── */}
      {!panelOpen && (
        <Pressable
          style={[s.fab, { bottom: insets.bottom + 22 }]}
          onPress={() => setPanelOpen(true)}
        >
          <Ionicons name="options-outline" size={22} color="#000" />
        </Pressable>
      )}

      {/* ── Floating glass curve editor ──────────────────────── */}
      {panelOpen && (
        <View style={[s.panelWrap, { paddingBottom: insets.bottom + 18 }]}>
          <View style={s.panel}>
            <View style={s.panelHeader}>
              <Text style={s.panelTitle}>CURVE LAB</Text>
              <Pressable
                hitSlop={10}
                onPress={() => setPanelOpen(false)}
                style={s.panelClose}
              >
                <Ionicons
                  name="close"
                  size={16}
                  color="rgba(255,255,255,0.7)"
                />
              </Pressable>
            </View>

            {/* Mode segmented control */}
            <View style={s.segmented}>
              {MODES.map((m) => (
                <Pressable
                  key={m}
                  style={[s.segment, mode === m && s.segmentActive]}
                  onPress={() => setMode(m)}
                >
                  <Text
                    style={[s.segmentText, mode === m && s.segmentTextActive]}
                  >
                    {m}
                  </Text>
                </Pressable>
              ))}
            </View>

            {/* Plot */}
            <View
              style={s.plot}
              onLayout={(e) => setPlotWidth(e.nativeEvent.layout.width)}
            >
              {mode === 'blur' && plotWidth > 0 && (
                <View style={StyleSheet.absoluteFill} pointerEvents="none">
                  <View
                    style={[
                      s.plotBand,
                      {
                        left: 0,
                        width: plotWidth / 3,
                        backgroundColor: 'rgba(255,255,255,0.03)',
                      },
                    ]}
                  />
                  <View
                    style={[
                      s.plotBand,
                      {
                        left: plotWidth / 3,
                        width: plotWidth / 3,
                        backgroundColor: 'rgba(255,255,255,0.06)',
                      },
                    ]}
                  />
                  <View
                    style={[
                      s.plotBand,
                      {
                        left: (plotWidth * 2) / 3,
                        width: plotWidth / 3,
                        backgroundColor: 'rgba(255,255,255,0.1)',
                      },
                    ]}
                  />
                </View>
              )}

              {plotWidth > 0 && (
                <>
                  <View pointerEvents="none" style={[s.ctrlLine, ctrlLine1]} />
                  <View pointerEvents="none" style={[s.ctrlLine, ctrlLine2]} />

                  {segments.map((seg, i) => (
                    <View
                      key={i}
                      pointerEvents="none"
                      style={[
                        s.curveSeg,
                        {
                          width: seg.length,
                          transform: [
                            { translateX: seg.x - seg.length / 2 },
                            { translateY: seg.y - 1 },
                            { rotate: `${seg.angle}rad` },
                          ],
                        },
                      ]}
                    />
                  ))}

                  <GestureDetector gesture={panP1}>
                    <View
                      hitSlop={HANDLE_HIT}
                      style={[
                        s.handle,
                        { left: p1Point.x - 14, top: p1Point.y - 14 },
                      ]}
                    />
                  </GestureDetector>
                  <GestureDetector gesture={panP2}>
                    <View
                      hitSlop={HANDLE_HIT}
                      style={[
                        s.handle,
                        { left: p2Point.x - 14, top: p2Point.y - 14 },
                      ]}
                    />
                  </GestureDetector>
                </>
              )}
            </View>

            {/* Readout */}
            <Text style={s.readout}>
              {bezier.x1.toFixed(2)} {bezier.y1.toFixed(2)}{' '}
              {bezier.x2.toFixed(2)} {bezier.y2.toFixed(2)} · zone {zone}px
              {radii
                ? ` · R ${radii[0]!.toFixed(0)}/${radii[1]!.toFixed(0)}/${radii[2]!.toFixed(0)}`
                : ''}
            </Text>

            {/* Presets */}
            <View style={s.presetRow}>
              {PRESETS.map((p) => (
                <Pressable
                  key={p.label}
                  style={s.presetPill}
                  onPress={() => setBezier(p.value)}
                >
                  <Text style={s.presetText}>{p.label}</Text>
                </Pressable>
              ))}
            </View>

            <PanelSlider
              label="Zone"
              value={zone}
              min={0}
              max={400}
              step={4}
              format={(v) => `${v}px`}
              onChange={setZone}
            />
            {mode === 'blur' && (
              <PanelSlider
                label="Blur"
                value={blurRadius}
                min={0}
                max={100}
                step={1}
                format={(v) => `${v}px`}
                onChange={setBlurRadius}
              />
            )}
          </View>
        </View>
      )}
    </View>
  );
}

function lineStyle(
  from: { x: number; y: number },
  to: { x: number; y: number }
) {
  const dx = to.x - from.x;
  const dy = to.y - from.y;
  const length = Math.sqrt(dx * dx + dy * dy);
  const angle = Math.atan2(dy, dx);
  return {
    width: length,
    transform: [
      { translateX: from.x },
      { translateY: from.y },
      { rotate: `${angle}rad` },
    ],
  };
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#FFFFFF' },
  scroll: { flex: 1, backgroundColor: '#FFFFFF' },

  hero: { width: '100%', height: 260, backgroundColor: '#ECECEE' },
  body: { paddingHorizontal: 22, paddingTop: 22 },
  kicker: {
    color: 'rgba(0,0,0,0.4)',
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1.5,
  },
  title: {
    color: '#0a0a0a',
    fontSize: 34,
    fontWeight: '800',
    letterSpacing: -0.5,
    marginTop: 6,
  },
  lead: {
    color: 'rgba(0,0,0,0.6)',
    fontSize: 15,
    lineHeight: 22,
    marginTop: 12,
  },
  section: {
    color: '#0a0a0a',
    fontSize: 13,
    fontWeight: '700',
    letterSpacing: 0.5,
    textTransform: 'uppercase',
    marginTop: 30,
    marginBottom: 12,
  },
  galleryRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
    marginBottom: 14,
  },
  galleryImg: {
    width: 96,
    height: 72,
    borderRadius: 12,
    backgroundColor: '#ECECEE',
  },
  galleryMeta: { gap: 3 },
  galleryTitle: { color: '#0a0a0a', fontSize: 15, fontWeight: '600' },
  gallerySub: { color: 'rgba(0,0,0,0.45)', fontSize: 13 },

  topBar: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    paddingHorizontal: 14,
    paddingBottom: 10,
  },
  iconBtn: {
    width: 38,
    height: 38,
    borderRadius: 19,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.06)',
  },

  debugBand: {
    position: 'absolute',
    left: 0,
    right: 0,
    borderWidth: StyleSheet.hairlineWidth,
    borderStyle: 'dashed' as const,
    borderColor: '#ff3030',
  },
  blurMarkerLine: {
    position: 'absolute',
    left: 0,
    right: 0,
    height: 0,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderStyle: 'dashed' as const,
    borderTopColor: '#00e5ff',
    alignItems: 'flex-end',
  },
  blurMarkerLabel: {
    color: '#00e5ff',
    fontSize: 9,
    fontFamily: 'monospace',
    paddingRight: 4,
    transform: [{ translateY: -11 }],
  },

  fab: {
    position: 'absolute',
    right: 18,
    width: 52,
    height: 52,
    borderRadius: 26,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#fff',
    shadowColor: '#000',
    shadowOpacity: 0.4,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 6 },
    elevation: 8,
  },

  panelWrap: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    paddingHorizontal: 16,
  },
  panel: {
    borderRadius: 22,
    padding: 18,
    backgroundColor: 'rgba(20,20,23,0.86)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    shadowColor: '#000',
    shadowOpacity: 0.5,
    shadowRadius: 24,
    shadowOffset: { width: 0, height: 12 },
    elevation: 12,
    gap: 10,
  },
  panelHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 2,
  },
  panelTitle: {
    color: 'rgba(255,255,255,0.5)',
    fontSize: 11,
    fontFamily: 'monospace',
    letterSpacing: 2,
  },
  panelClose: {
    width: 24,
    height: 24,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(255,255,255,0.1)',
  },

  segmented: {
    flexDirection: 'row',
    backgroundColor: 'rgba(255,255,255,0.06)',
    borderRadius: 12,
    padding: 3,
    gap: 3,
  },
  segment: {
    flex: 1,
    paddingVertical: 8,
    borderRadius: 9,
    alignItems: 'center',
  },
  segmentActive: { backgroundColor: 'rgba(255,255,255,0.16)' },
  segmentText: {
    color: 'rgba(255,255,255,0.5)',
    fontSize: 12.5,
    fontWeight: '600',
    textTransform: 'capitalize',
  },
  segmentTextActive: { color: '#fff' },

  plot: {
    height: PLOT_H,
    borderRadius: 12,
    backgroundColor: 'rgba(255,255,255,0.04)',
    overflow: 'hidden',
    marginTop: 4,
  },
  plotBand: { position: 'absolute', top: 0, bottom: 0 },
  ctrlLine: {
    position: 'absolute',
    height: StyleSheet.hairlineWidth,
    backgroundColor: 'rgba(255,255,255,0.25)',
    transformOrigin: '0 0',
  },
  curveSeg: {
    position: 'absolute',
    height: 2,
    backgroundColor: '#ffffff',
    borderRadius: 1,
  },
  handle: {
    position: 'absolute',
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: '#ffffff',
    borderWidth: 2,
    borderColor: 'rgba(0,0,0,0.3)',
  },

  readout: {
    color: 'rgba(255,255,255,0.7)',
    fontSize: 11.5,
    fontFamily: 'monospace',
    marginTop: 2,
  },

  presetRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
    marginTop: 2,
  },
  presetPill: {
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 16,
    backgroundColor: 'rgba(255,255,255,0.08)',
  },
  presetText: {
    color: 'rgba(255,255,255,0.75)',
    fontSize: 12,
    fontWeight: '600',
  },
});
