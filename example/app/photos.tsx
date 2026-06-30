/**
 * Blur showcase #2 — iOS Photos-style picker.
 *
 * A full-bleed 4-column photo grid that frosts under the status bar (top) and
 * behind the floating "Save as Album" bar (bottom), using `mode="blur"`. The
 * page is dark, so the frost material is dark (`color="#000"`) — content
 * dissolves into the dark bar like iOS Photos, not into white.
 */

import { useCallback } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { router } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { EdgeFadeView } from 'react-native-edge-fade';

import { CATALOG } from '../constants/catalog';

const PHOTOS = CATALOG.slice(0, 44);
const GAP = 2;

export default function PhotosScreen() {
  const insets = useSafeAreaInsets();
  const handleBack = useCallback(() => router.back(), []);

  return (
    <View style={s.root}>
      <EdgeFadeView
        top={insets.top + 120}
        bottom={160}
        // Smootherstep S-curve (Perlin 6t^5−15t^4+10t^3): even gentler easing at
        // both ends than smoothstep — the most gradual ramp, no hard line.
        curve={{ type: 'stops', values: [1, 0.97, 0.83, 0.5, 0.17, 0.03, 0] }}
        mode="blur"
        blurRadius={22}
        color="#000000"
        style={StyleSheet.absoluteFill}
      >
        <ScrollView
          style={s.scroll}
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
        <Pressable style={s.circleBtn} onPress={handleBack}>
          <Ionicons name="chevron-back" size={22} color="#fff" />
        </Pressable>
      </View>

      {/* Floating action bar (bottom) */}
      <View style={[s.bottomBar, { paddingBottom: insets.bottom + 14 }]}>
        <Pressable style={s.pill}>
          <Ionicons name="add" size={20} color="#fff" />
          <Text style={s.pillText}>Save as Album</Text>
        </Pressable>
        <View style={s.checkBtn}>
          <Ionicons name="checkmark" size={22} color="#fff" />
        </View>
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#000000' },
  // Opaque so the blur backdrop has no transparent gaps.
  scroll: { flex: 1, backgroundColor: '#000000' },

  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  cell: {
    width: '25%',
    aspectRatio: 1,
    padding: GAP / 2,
  },
  img: {
    flex: 1,
    backgroundColor: '#141414',
  },

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
    backgroundColor: 'rgba(255,255,255,0.14)',
  },

  bottomBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
    paddingTop: 14,
  },
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 22,
    paddingVertical: 13,
    borderRadius: 26,
    backgroundColor: 'rgba(40,40,42,0.92)',
  },
  pillText: { color: '#fff', fontSize: 15, fontWeight: '600' },
  checkBtn: {
    width: 48,
    height: 48,
    borderRadius: 24,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(40,40,42,0.92)',
  },
});
