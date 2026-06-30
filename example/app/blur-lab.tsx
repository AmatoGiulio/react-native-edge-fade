/**
 * Blur playground — a floating glass control panel over real content.
 *
 * Drag the sliders to tune the progressive blur live: blur radius, the top and
 * bottom fade depths, and the curve. The panel floats above a scrollable mock
 * product page wrapped in `EdgeFadeView mode="blur"`, so you see the fade react
 * against realistic content (headings, list rows, photos).
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

const CURVES: CurvePreset[] = ['linear', 'gentle', 'smooth', 'soft', 'sharp'];

const HERO = CATALOG[3]!;
const ROW_IMAGES = CATALOG.slice(10, 16);

const SPECS: Array<[string, string]> = [
  ['Engine', '3.0L Flat-6'],
  ['Power', '473 hp'],
  ['0–100 km/h', '3.4 s'],
  ['Top speed', '308 km/h'],
  ['Drivetrain', 'Rear-wheel'],
  ['Weight', '1,505 kg'],
];

export default function BlurLabScreen() {
  const insets = useSafeAreaInsets();

  const [blur, setBlur] = useState(18);
  const [zoneTop, setZoneTop] = useState(140);
  const [zoneBtm, setZoneBtm] = useState(180);
  const [curveIdx, setCurveIdx] = useState(1); // gentle — softer, more gradual ramp
  const [panelOpen, setPanelOpen] = useState(true);

  const handleBack = useCallback(() => router.back(), []);

  return (
    <View style={s.root}>
      {/* ── Real content, faded ─────────────────────────────── */}
      <EdgeFadeView
        top={zoneTop || false}
        bottom={zoneBtm || false}
        curve={CURVES[curveIdx]}
        mode="blur"
        blurRadius={blur}
        style={StyleSheet.absoluteFill}
      >
        <ScrollView
          style={s.scroll}
          showsVerticalScrollIndicator={false}
          contentContainerStyle={{
            paddingTop: insets.top + 56,
            paddingBottom: insets.bottom + 360,
          }}
        >
          <Image source={HERO.source} style={s.hero} contentFit="cover" />

          <View style={s.body}>
            <Text style={s.kicker}>CONFIGURATOR · 2025</Text>
            <Text style={s.title}>Carrera GTS</Text>
            <Text style={s.lead}>
              A progressive edge fade keeps the chrome readable while the
              content dissolves softly behind it — sharp where you read, blurred
              where it meets the panel.
            </Text>

            <Text style={s.section}>Specification</Text>
            <View style={s.specs}>
              {SPECS.map(([k, v]) => (
                <View key={k} style={s.specRow}>
                  <Text style={s.specKey}>{k}</Text>
                  <Text style={s.specVal}>{v}</Text>
                </View>
              ))}
            </View>

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
                    Detail {String(i + 1).padStart(2, '0')}
                  </Text>
                  <Text style={s.gallerySub}>{it.category}</Text>
                </View>
              </View>
            ))}
          </View>
        </ScrollView>
      </EdgeFadeView>

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

      {/* ── Floating glass control panel ────────────────────── */}
      {panelOpen && (
        <View style={[s.panelWrap, { paddingBottom: insets.bottom + 18 }]}>
          <View style={s.panel}>
            <View style={s.panelHeader}>
              <Text style={s.panelTitle}>EDGE FADE</Text>
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
              max={100}
              step={1}
              format={(v) => `${v}px`}
              onChange={setBlur}
            />
            <PanelSlider
              label="Zone top"
              value={zoneTop}
              min={0}
              max={320}
              step={4}
              format={(v) => `${v}px`}
              onChange={setZoneTop}
            />
            <PanelSlider
              label="Zone btm"
              value={zoneBtm}
              min={0}
              max={320}
              step={4}
              format={(v) => `${v}px`}
              onChange={setZoneBtm}
            />
            <PanelSlider
              label="Curve"
              value={curveIdx}
              min={0}
              max={CURVES.length - 1}
              step={1}
              format={(v) => CURVES[v] ?? ''}
              onChange={setCurveIdx}
            />
          </View>
        </View>
      )}
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#FFFFFF' },
  // Opaque so the blurred RenderNode has no transparent regions — blurring
  // content against transparency produces dark premultiplied-alpha fringes.
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
  specs: { gap: 0 },
  specRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: 13,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: 'rgba(0,0,0,0.1)',
  },
  specKey: { color: 'rgba(0,0,0,0.5)', fontSize: 15 },
  specVal: { color: '#0a0a0a', fontSize: 15, fontWeight: '600' },

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
