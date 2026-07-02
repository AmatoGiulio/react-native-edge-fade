/**
 * Curve Lab — a DialKit-style debug tool for tuning the cubic-bezier curve
 * that drives EdgeFadeView's fade ramp, live, against real content.
 *
 * All parameters live in SharedValues: dial-row drags and direct curve
 * editing on the plot (grab-nearest-point pan) run as worklets on the UI
 * thread, and only throttled mirrors reach React state for the non-animatable
 * `curve` / `blurRadius` props. The preview fades on the TOP edge (the panel
 * covers the bottom), and the panel follows the light DialKit reference:
 * white card, plot on top, parameter rows below, readout at the bottom.
 */

import { useCallback, useMemo, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Image } from 'expo-image';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
} from 'react-native-reanimated';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';
import type { EdgeFadeMode } from 'react-native-edge-fade';

import { CATALOG } from '../constants/catalog';
import {
  BezierPlot,
  DialReadout,
  DialRow,
  useDialCurve,
  useThrottledMirror,
} from '../components/dial';

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

// Underwater shot — rich color and detail for the top fade to chew on.
const HERO = CATALOG[21]!;
const ROW_IMAGES = CATALOG.slice(28, 34);

function fmt2(v: number): string {
  'worklet';
  return v.toFixed(2);
}

function fmtPx(v: number): string {
  'worklet';
  return `${Math.round(v)}px`;
}

export default function CurveLabScreen() {
  const insets = useSafeAreaInsets();

  const [mode, setMode] = useState<EdgeFadeMode>('mask');
  const [expanded, setExpanded] = useState(true);

  const x1 = useSharedValue(PRESETS[1]!.value.x1);
  const y1 = useSharedValue(PRESETS[1]!.value.y1);
  const x2 = useSharedValue(PRESETS[1]!.value.x2);
  const y2 = useSharedValue(PRESETS[1]!.value.y2);
  const zone = useSharedValue(180);
  const blur = useSharedValue(28);

  const curve = useDialCurve(x1, y1, x2, y2);
  const readBlurWorklet = useCallback((): number => {
    'worklet';
    return blur.get();
  }, [blur]);
  const blurRadius = useThrottledMirror(readBlurWorklet, 28);

  const toggleExpanded = useCallback(() => setExpanded((v) => !v), []);

  const applyPreset = useCallback(
    (b: Bezier) => {
      x1.set(b.x1);
      y1.set(b.y1);
      x2.set(b.x2);
      y2.set(b.y2);
    },
    [x1, y1, x2, y2]
  );

  const readoutValues = useMemo(() => [x1, y1, x2, y2], [x1, y1, x2, y2]);

  const debugBandStyle = useAnimatedStyle(() => ({
    height: zone.get(),
    opacity: zone.get() > 0 ? 1 : 0,
  }));
  const marker1Style = useAnimatedStyle(() => ({ top: zone.get() / 3 }));
  const marker2Style = useAnimatedStyle(() => ({
    top: (zone.get() * 2) / 3,
  }));
  const marker3Style = useAnimatedStyle(() => ({ top: zone.get() - 1 }));

  return (
    <View style={s.root}>
      {/* ── Preview, full-screen, fading on the TOP edge ─────── */}
      <AnimatedEdgeFadeView
        top={zone}
        curve={curve}
        mode={mode}
        blurRadius={blurRadius}
        color={mode === 'overlay' ? '#FFFFFF' : undefined}
        style={StyleSheet.absoluteFill}
      >
        <ScrollView
          style={s.scroll}
          showsVerticalScrollIndicator={false}
          // No top padding: the hero starts at y=0, flush under the status
          // bar, so the top fade works on the image itself.
          contentContainerStyle={{ paddingBottom: insets.bottom + 480 }}
        >
          <Image source={HERO.source} style={s.hero} contentFit="cover" />
          <View style={s.body}>
            <Text style={s.kicker}>CURVE LAB · DEBUG</Text>
            <Text style={s.title}>Bezier Tuning</Text>
            <Text style={s.lead}>
              Adjust the parameter rows below to reshape the fade curve in real
              time, across all three render modes.
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
      </AnimatedEdgeFadeView>

      {/* ── Debug overlay: fade band + blur level markers (top) ── */}
      <Animated.View pointerEvents="none" style={[s.debugBand, debugBandStyle]}>
        {mode === 'blur' && (
          <>
            <Animated.View style={[s.blurMarkerLine, marker1Style]}>
              <Text style={s.blurMarkerLabel}>R/3</Text>
            </Animated.View>
            <Animated.View style={[s.blurMarkerLine, marker2Style]}>
              <Text style={s.blurMarkerLabel}>2R/3</Text>
            </Animated.View>
            <Animated.View style={[s.blurMarkerLine, marker3Style]}>
              <Text style={s.blurMarkerLabel}>R</Text>
            </Animated.View>
          </>
        )}
      </Animated.View>

      {/* ── DialKit-style floating panel (light) ─────────────── */}
      <View style={[s.panelWrap, { paddingBottom: insets.bottom + 18 }]}>
        <View style={s.panel}>
          <Pressable style={s.panelHeader} onPress={toggleExpanded}>
            <Text style={s.panelTitle}>Curve Lab</Text>
            <Ionicons
              name={expanded ? 'chevron-down' : 'chevron-up'}
              size={20}
              color="#8a8a8e"
            />
          </Pressable>

          {expanded && (
            <View style={s.panelBody}>
              <BezierPlot
                x1={x1}
                y1={y1}
                x2={x2}
                y2={y2}
                tint="light"
                showPresenceBands={mode === 'blur'}
              />

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

              {/* Presets — bezier starting points for the rows below */}
              <View style={s.presetRow}>
                {PRESETS.map((p) => (
                  <Pressable
                    key={p.label}
                    style={s.presetPill}
                    onPress={() => applyPreset(p.value)}
                  >
                    <Text style={s.presetText}>{p.label}</Text>
                  </Pressable>
                ))}
              </View>

              <DialRow
                label="x1"
                value={x1}
                min={0}
                max={1}
                step={0.01}
                format={fmt2}
                tint="light"
              />
              <DialRow
                label="y1"
                value={y1}
                min={0}
                max={1}
                step={0.01}
                format={fmt2}
                tint="light"
              />
              <DialRow
                label="x2"
                value={x2}
                min={0}
                max={1}
                step={0.01}
                format={fmt2}
                tint="light"
              />
              <DialRow
                label="y2"
                value={y2}
                min={0}
                max={1}
                step={0.01}
                format={fmt2}
                tint="light"
              />
              <DialRow
                label="Zone"
                value={zone}
                min={0}
                max={400}
                step={4}
                format={fmtPx}
                tint="light"
              />
              {mode === 'blur' && (
                <DialRow
                  label="Blur"
                  value={blur}
                  min={0}
                  max={100}
                  step={1}
                  format={fmtPx}
                  tint="light"
                />
              )}

              <DialReadout label="Ease" values={readoutValues} tint="light" />
            </View>
          )}
        </View>
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#FFFFFF' },
  scroll: { flex: 1, backgroundColor: '#FFFFFF' },

  hero: { width: '100%', height: 420, backgroundColor: '#ECECEE' },
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

  debugBand: {
    position: 'absolute',
    left: 0,
    right: 0,
    top: 0,
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
    transform: [{ translateY: 2 }],
  },

  panelWrap: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
    paddingHorizontal: 16,
  },
  panel: {
    borderRadius: 20,
    padding: 16,
    backgroundColor: '#FFFFFF',
    shadowColor: '#000',
    shadowOpacity: 0.12,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 8 },
    elevation: 8,
  },
  panelHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  panelTitle: {
    color: '#1a1a1a',
    fontSize: 17,
    fontWeight: '700',
  },
  panelBody: {
    marginTop: 12,
    gap: 8,
  },

  segmented: {
    flexDirection: 'row',
    backgroundColor: '#F2F2F4',
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
  segmentActive: { backgroundColor: '#E3E3E6' },
  segmentText: {
    color: '#8a8a8e',
    fontSize: 13,
    fontWeight: '500',
    textTransform: 'capitalize',
  },
  segmentTextActive: { color: '#1a1a1a', fontWeight: '700' },

  presetRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
  },
  presetPill: {
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 16,
    backgroundColor: '#F2F2F4',
  },
  presetText: {
    color: '#1a1a1a',
    fontSize: 12,
    fontWeight: '600',
  },
});
