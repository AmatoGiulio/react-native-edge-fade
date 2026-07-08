/**
 * Photo detail — opens from the grid via `/photo/${id}`. Shows the selected
 * photo (70% width, 3:4 contained, rounded) wrapped in an AnimatedEdgeFadeView
 * driven by the SHARED fade store, so tuning reflects back on the grid. The
 * tuning panel lives in the `/panel` form sheet, opened from the header or the
 * "Tune edge fade" button.
 */

import { useCallback } from 'react';
import { useLocalSearchParams, router } from 'expo-router';
import {
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import Animated, { useAnimatedStyle } from 'react-native-reanimated';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';

import { getCatalogItem } from '@/data/catalog';
import { useFadeStore, useFadeRender } from '@/fade/FadeContext';
import { useTheme } from '@/theme';

export function PhotoScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const item = getCatalogItem(id ?? '');
  const insets = useSafeAreaInsets();
  const t = useTheme();
  const { top, bottom, left, right, radius, mode, tint, showBands } =
    useFadeStore();
  const { curve, blurRadius } = useFadeRender();

  const openPanel = useCallback(() => router.push('/panel'), []);

  const topBandStyle = useAnimatedStyle(() => ({
    height: top.get(),
    opacity: showBands && top.get() > 0 ? 1 : 0,
  }));
  const bottomBandStyle = useAnimatedStyle(() => ({
    height: bottom.get(),
    opacity: showBands && bottom.get() > 0 ? 1 : 0,
  }));

  if (!item) {
    return (
      <View style={[s.notFound, { backgroundColor: t.bg }]}>
        <Text style={[s.notFoundText, { color: t.subtext }]}>Not found</Text>
      </View>
    );
  }

  return (
    <View style={[s.root, { backgroundColor: t.bg }]}>
      <ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={[
          s.scroll,
          { paddingTop: insets.top + 64, paddingBottom: insets.bottom + 32 },
        ]}
      >
        {/* Photo — 70% width, 3:4 contained, rounded, live EdgeFadeView */}
        <View style={s.photoWrap}>
          <AnimatedEdgeFadeView
            top={top}
            bottom={bottom}
            left={left}
            right={right}
            curve={curve}
            mode={mode}
            blurRadius={blurRadius}
            color={tint}
            radius={radius}
            style={s.edgeFade}
          >
            <Image source={item.source} style={s.photo} resizeMode="cover" />
          </AnimatedEdgeFadeView>
          <Animated.View
            pointerEvents="none"
            style={[s.debugBand, s.debugTop, topBandStyle]}
          />
          <Animated.View
            pointerEvents="none"
            style={[s.debugBand, s.debugBottom, bottomBandStyle]}
          />
        </View>

        {/* Title / subtitle */}
        <View style={s.meta}>
          <Text style={[s.title, { color: t.text }]}>{item.category}</Text>
          <Text style={[s.subtitle, { color: t.subtext }]}>
            Edge Fade · {id}
          </Text>
        </View>

        <Pressable
          style={[s.tuneBtn, { backgroundColor: t.control }]}
          onPress={openPanel}
        >
          <Ionicons name="options-outline" size={18} color={t.text} />
          <Text style={[s.tuneText, { color: t.text }]}>Tune edge fade</Text>
        </Pressable>
      </ScrollView>
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1 },
  notFound: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  notFoundText: { fontSize: 16 },

  scroll: { alignItems: 'center', paddingHorizontal: 20 },

  photoWrap: { width: '70%', aspectRatio: 3 / 4 },
  edgeFade: { flex: 1 },
  photo: { width: '100%', height: '100%' },
  debugBand: {
    position: 'absolute',
    left: 0,
    right: 0,
    borderWidth: StyleSheet.hairlineWidth,
    borderStyle: 'dashed' as const,
  },
  debugTop: { top: 0, borderColor: '#00e5ff' },
  debugBottom: { bottom: 0, borderColor: '#ff3030' },

  meta: { marginTop: 20, alignItems: 'center', gap: 6 },
  title: { fontSize: 22, fontWeight: '700' },
  subtitle: { fontSize: 14 },

  tuneBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginTop: 28,
    paddingHorizontal: 20,
    paddingVertical: 12,
    borderRadius: 22,
  },
  tuneText: { fontSize: 15, fontWeight: '600' },
});
