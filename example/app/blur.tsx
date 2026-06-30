/**
 * Blur-mode showcase — a real-world editorial photo feed.
 *
 * A vertically scrolling gallery wrapped in an `EdgeFadeView` with `mode="blur"`.
 * Content stays crisp in the middle and progressively dissolves into a frosted
 * blur as it approaches the fixed header (top) and the floating action bar
 * (bottom) — the same pattern used by music mini-players and map bottom sheets,
 * where scrolling content melts into the chrome instead of being hard-clipped.
 */

import { useCallback } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { router } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { EdgeFadeView } from 'react-native-edge-fade';

import { CATALOG } from '../constants/catalog';

// A curated slice of the catalog — enough rows to scroll through the fade.
const FEED = CATALOG.slice(0, 14);

const TOP_FADE = 96;
const BOTTOM_FADE = 200;

export default function BlurFeedScreen() {
  const insets = useSafeAreaInsets();
  const handleBack = useCallback(() => router.back(), []);

  return (
    <View style={s.root}>
      {/* Scrolling feed that fades into blur at both edges */}
      <EdgeFadeView
        top={TOP_FADE}
        bottom={BOTTOM_FADE}
        curve="gentle"
        mode="blur"
        blurRadius={28}
        style={StyleSheet.absoluteFill}
      >
        <ScrollView
          showsVerticalScrollIndicator={false}
          contentContainerStyle={[
            s.feed,
            {
              paddingTop: insets.top + 64,
              paddingBottom: insets.bottom + 132,
            },
          ]}
        >
          {FEED.map((item, i) => (
            <View key={item.id} style={s.card}>
              <Image
                source={item.source}
                style={[s.photo, { aspectRatio: Math.max(0.75, item.ratio) }]}
                contentFit="cover"
                transition={200}
              />
              <View style={s.caption}>
                <View style={[s.dot, { backgroundColor: item.accent }]} />
                <Text style={s.captionText}>{item.category.toUpperCase()}</Text>
                <Text style={s.captionMeta}>
                  · Shot {String(i + 1).padStart(2, '0')}
                </Text>
              </View>
            </View>
          ))}
        </ScrollView>
      </EdgeFadeView>

      {/* Fixed translucent header — content blurs as it scrolls underneath */}
      <View style={[s.header, { paddingTop: insets.top + 8 }]}>
        <Pressable style={s.iconBtn} onPress={handleBack}>
          <Ionicons name="chevron-back" size={22} color="#fff" />
        </Pressable>
        <Text style={s.headerTitle}>Featured</Text>
        <View style={s.iconBtn}>
          <Ionicons name="search" size={20} color="#fff" />
        </View>
      </View>

      {/* Floating action bar — sits over the frosted bottom edge */}
      <View style={[s.bar, { paddingBottom: insets.bottom || 16 }]}>
        <View style={s.barInfo}>
          <Text style={s.barTitle}>14 photographs</Text>
          <Text style={s.barSub}>Editorial selection</Text>
        </View>
        <Pressable style={s.cta}>
          <Ionicons name="bookmark" size={16} color="#000" />
          <Text style={s.ctaText}>Save</Text>
        </Pressable>
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#0c0c0e' },

  feed: { paddingHorizontal: 18, gap: 22 },
  card: { gap: 10 },
  photo: {
    width: '100%',
    borderRadius: 18,
    backgroundColor: '#161618',
  },
  caption: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingLeft: 2,
  },
  dot: { width: 7, height: 7, borderRadius: 3.5 },
  captionText: {
    color: '#fff',
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.8,
  },
  captionMeta: {
    color: 'rgba(255,255,255,0.4)',
    fontSize: 11,
    fontWeight: '500',
  },

  header: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingBottom: 10,
  },
  headerTitle: { color: '#fff', fontSize: 17, fontWeight: '700' },
  iconBtn: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(255,255,255,0.12)',
    alignItems: 'center',
    justifyContent: 'center',
  },

  bar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 22,
    paddingTop: 14,
  },
  barInfo: { gap: 2 },
  barTitle: { color: '#fff', fontSize: 15, fontWeight: '700' },
  barSub: { color: 'rgba(255,255,255,0.45)', fontSize: 12 },
  cta: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 20,
    paddingVertical: 12,
    borderRadius: 24,
    backgroundColor: '#fff',
  },
  ctaText: { color: '#000', fontSize: 14, fontWeight: '700' },
});
