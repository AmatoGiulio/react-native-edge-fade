/**
 * Blur showcase #2 — iOS Photos-style picker with a live-editing panel.
 *
 * A full-bleed 4-column photo grid that frosts under the status bar (top) and
 * behind the floating action bar (bottom), using `mode="blur"`. A floating glass
 * control panel tunes the effect live — blur radius, the top/bottom fade depths,
 * the curve, and the frost material (dark like iOS Photos, or light).
 */

import { useCallback, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { router } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { EdgeFadeView } from 'react-native-edge-fade';
import type { CurvePreset } from 'react-native-edge-fade';

import { CATALOG } from '../constants/catalog';
import { PanelSlider } from '../components/PanelSlider';

const PHOTOS = CATALOG.slice(0, 44);
const GAP = 2;

// Ordered softest → hardest break. `smoother` (smootherstep) eases in at the
// inner edge — the least direct cutoff.
const CURVES: CurvePreset[] = ['smoother', 'smooth', 'gentle', 'soft', 'sharp'];

type Frost = { label: string; color: string };
const FROSTS: Frost[] = [
  { label: 'Dark', color: '#000000' },
  { label: 'Light', color: '#FFFFFF' },
];

export default function PhotosScreen() {
  const insets = useSafeAreaInsets();
  const handleBack = useCallback(() => router.back(), []);

  const [blur, setBlur] = useState(22);
  const [zoneTop, setZoneTop] = useState(Math.round(insets.top) + 120);
  const [zoneBtm, setZoneBtm] = useState(160);
  const [curveIdx, setCurveIdx] = useState(0); // smoother
  const [frostIdx, setFrostIdx] = useState(0); // dark
  const [panelOpen, setPanelOpen] = useState(true);

  const frost = FROSTS[frostIdx]!;
  const onDark = frostIdx === 0;
  const pageBg = onDark ? '#000000' : '#FFFFFF';

  return (
    <View style={[s.root, { backgroundColor: pageBg }]}>
      <EdgeFadeView
        top={zoneTop || false}
        bottom={zoneBtm || false}
        curve={CURVES[curveIdx]}
        mode="blur"
        blurRadius={blur}
        color={frost.color}
        style={StyleSheet.absoluteFill}
      >
        <ScrollView
          style={[s.scroll, { backgroundColor: pageBg }]}
          showsVerticalScrollIndicator={false}
          contentContainerStyle={{
            paddingTop: insets.top + 56,
            paddingBottom: insets.bottom + 96,
          }}
        >
          <View style={s.grid}>
            {PHOTOS.map((item) => (
              <View key={item.id} style={s.cell}>
                <Image source={item.source} style={s.img} contentFit="cover" />
              </View>
            ))}
          </View>
        </ScrollView>
      </EdgeFadeView>

      {/* Floating back button (top-left) */}
      <View style={[s.topBar, { paddingTop: insets.top + 6 }]}>
        <Pressable
          style={[s.circleBtn, onDark ? s.glassDark : s.glassLight]}
          onPress={handleBack}
        >
          <Ionicons
            name="chevron-back"
            size={22}
            color={onDark ? '#fff' : '#0a0a0a'}
          />
        </Pressable>
      </View>

      {/* Reopen button (panel closed) */}
      {!panelOpen && (
        <Pressable
          style={[s.fab, { bottom: insets.bottom + 22 }]}
          onPress={() => setPanelOpen(true)}
        >
          <Ionicons name="options-outline" size={22} color="#000" />
        </Pressable>
      )}

      {/* Floating glass control panel */}
      {panelOpen && (
        <View style={[s.panelWrap, { paddingBottom: insets.bottom + 18 }]}>
          <View style={s.panel}>
            <View style={s.panelHeader}>
              <Text style={s.panelTitle}>SCROLL-EDGE BLUR</Text>
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

            <PanelSlider
              label="Blur"
              value={blur}
              min={0}
              max={60}
              step={1}
              format={(v) => `${v}px`}
              onChange={setBlur}
            />
            <PanelSlider
              label="Top"
              value={zoneTop}
              min={0}
              max={320}
              step={4}
              format={(v) => `${v}px`}
              onChange={setZoneTop}
            />
            <PanelSlider
              label="Bottom"
              value={zoneBtm}
              min={0}
              max={320}
              step={4}
              format={(v) => `${v}px`}
              onChange={setZoneBtm}
            />

            <Segmented
              label="Curve"
              options={CURVES}
              index={curveIdx}
              onChange={setCurveIdx}
            />
            <Segmented
              label="Frost"
              options={FROSTS.map((f) => f.label)}
              index={frostIdx}
              onChange={setFrostIdx}
            />
          </View>
        </View>
      )}
    </View>
  );
}

/** Compact segmented control matching the panel's monospace aesthetic. */
function Segmented({
  label,
  options,
  index,
  onChange,
}: {
  label: string;
  options: string[];
  index: number;
  onChange: (i: number) => void;
}) {
  return (
    <View style={s.segRow}>
      <Text style={s.segLabel}>{label}</Text>
      <View style={s.segTrack}>
        {options.map((opt, i) => {
          const active = i === index;
          return (
            <Pressable
              key={opt}
              style={[s.segItem, active && s.segItemActive]}
              onPress={() => onChange(i)}
            >
              <Text style={[s.segText, active && s.segTextActive]}>{opt}</Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1 },
  scroll: { flex: 1 },

  grid: { flexDirection: 'row', flexWrap: 'wrap' },
  cell: { width: '25%', aspectRatio: 1, padding: GAP / 2 },
  img: { flex: 1, backgroundColor: '#141414' },

  topBar: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    paddingHorizontal: 14,
    paddingBottom: 10,
  },
  circleBtn: {
    width: 38,
    height: 38,
    borderRadius: 19,
    alignItems: 'center',
    justifyContent: 'center',
  },
  glassDark: { backgroundColor: 'rgba(255,255,255,0.14)' },
  glassLight: { backgroundColor: 'rgba(0,0,0,0.06)' },

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
    backgroundColor: 'rgba(20,20,23,0.9)',
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.08)',
    shadowColor: '#000',
    shadowOpacity: 0.5,
    shadowRadius: 24,
    shadowOffset: { width: 0, height: 12 },
    elevation: 12,
    gap: 2,
  },
  panelHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 10,
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

  segRow: {
    flexDirection: 'row',
    alignItems: 'center',
    minHeight: 34,
    marginTop: 6,
  },
  segLabel: {
    width: 70,
    color: 'rgba(255,255,255,0.55)',
    fontSize: 12,
    fontFamily: 'monospace',
  },
  segTrack: {
    flex: 1,
    flexDirection: 'row',
    backgroundColor: 'rgba(255,255,255,0.06)',
    borderRadius: 9,
    padding: 2,
    gap: 2,
  },
  segItem: {
    flex: 1,
    paddingVertical: 6,
    borderRadius: 7,
    alignItems: 'center',
    justifyContent: 'center',
  },
  segItemActive: { backgroundColor: 'rgba(255,255,255,0.16)' },
  segText: {
    color: 'rgba(255,255,255,0.5)',
    fontSize: 10.5,
    fontFamily: 'monospace',
  },
  segTextActive: { color: '#fff' },

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
});
