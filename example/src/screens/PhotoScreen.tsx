import { useCallback } from 'react';
import { useLocalSearchParams, router } from 'expo-router';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Animated, { useAnimatedStyle } from 'react-native-reanimated';

import { useCatalogItem } from '@/data/catalog';
import { useFadeStore, useFadeRender } from '@/fade/FadeContext';
import { BeforeAfterPhoto } from '@/components/BeforeAfterPhoto';
import { SymbolIcon } from '@/components/SymbolIcon';
import { useTheme } from '@/theme';

export function PhotoScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { item, isLoading } = useCatalogItem(id ?? '');
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
  const handleDebug = useCallback(() => {
    router.push('/debug');
  }, []);

  if (isLoading) {
    return (
      <View style={[s.notFound, { backgroundColor: t.bg }]}>
        <Text style={[s.notFoundText, { color: t.subtext }]}>Loading…</Text>
      </View>
    );
  }
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
        <Pressable onPress={handleDebug}>
          <Text>Debug</Text>
        </Pressable>

        <View style={s.photoWrap}>
          <BeforeAfterPhoto
            source={item.source}
            top={top}
            bottom={bottom}
            left={left}
            right={right}
            radius={radius}
            curve={curve}
            mode={mode}
            blurRadius={blurRadius}
            color={tint}
            background={t.bg}
          />
          <Animated.View
            pointerEvents="none"
            style={[s.debugBand, s.debugTop, topBandStyle]}
          />
          <Animated.View
            pointerEvents="none"
            style={[s.debugBand, s.debugBottom, bottomBandStyle]}
          />
        </View>

        <View style={s.meta}>
          <Text style={[s.title, { color: t.text }]}>{item.title}</Text>
          <View style={s.authorRow}>
            <View
              style={[s.accentDot, { backgroundColor: item.accent }]}
            />
            <Text style={[s.subtitle, { color: t.subtext }]}>
              {item.author} · {item.width}×{item.height}
            </Text>
          </View>
        </View>

        <Pressable
          style={[s.tuneBtn, { backgroundColor: t.control }]}
          onPress={openPanel}
        >
          <SymbolIcon name="slider.horizontal.3" color={t.text} size={18} />
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
  title: { fontSize: 22, fontWeight: '700', textAlign: 'center' },
  authorRow: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  accentDot: { width: 8, height: 8, borderRadius: 4 },
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
