/**
 * Blur showcase — iOS Photos-style picker, live-tunable.
 *
 * A full-bleed 4-column grid that frosts under the status bar (top) and behind
 * the floating action bar (bottom) via `mode="blur"`. The frost config comes
 * from the shared `useFade` store; tap the sliders button to open the native
 * editor sheet and tune it live.
 */

import { useCallback } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { FlashList } from '@shopify/flash-list';
import { Image } from 'expo-image';
import { router } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { StatusBar } from 'expo-status-bar';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { EdgeFadeView } from 'react-native-edge-fade';

import { CATALOG } from '../../constants/catalog';
import { FROST_COLOR, useFade } from './fade-context';

const GAP = 2;

export default function PhotosScreen() {
  const insets = useSafeAreaInsets();
  const { blur, top, bottom, left, right, radius, curve, frost } = useFade();

  const handleBack = useCallback(() => router.back(), []);
  const handleTune = useCallback(() => router.push('/photos/tune'), []);

  const onDark = frost === 'dark';
  const pageBg = onDark ? '#000000' : '#FFFFFF';

  const renderItem = useCallback(
    ({ item }: { item: (typeof CATALOG)[number] }) => (
      <Pressable
        style={s.cell}
        onPress={() => router.push('/photos/' + item.id)}
      >
        <Image source={item.source} style={s.img} contentFit="cover" />
      </Pressable>
    ),
    []
  );

  return (
    <View style={[s.root, { backgroundColor: pageBg }]}>
      <StatusBar style={onDark ? 'light' : 'dark'} />
      <EdgeFadeView
        top={top || false}
        bottom={bottom || false}
        left={left || false}
        right={right || false}
        radius={radius}
        curve={curve}
        mode="blur"
        blurRadius={blur}
        color={FROST_COLOR[frost]}
        style={StyleSheet.absoluteFill}
      >
        <FlashList
          data={CATALOG}
          numColumns={4}
          keyExtractor={(item) => item.id}
          renderItem={renderItem}
          showsVerticalScrollIndicator={false}
          // Full-bleed: let cells scroll edge-to-edge, under the status bar and
          // the floating action bar. Insetting the content with paddingTop/Bottom
          // left an empty band beneath the bars that the blur frosted over
          // nothing — the `mode="blur"` UIVisualEffectView frosts its whole
          // backdrop, so the fade region must always sit over real image content.
          contentContainerStyle={{ paddingTop: 100, paddingBottom: 0 }}
        />
      </EdgeFadeView>

      {/* Floating controls (top) */}
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
        <Pressable
          style={[s.circleBtn, onDark ? s.glassDark : s.glassLight]}
          onPress={handleTune}
        >
          <Ionicons
            name="options-outline"
            size={20}
            color={onDark ? '#fff' : '#0a0a0a'}
          />
        </Pressable>
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1 },

  // ponytail: flex:1 + aspectRatio:1 gives square cells inside FlashList numColumns
  cell: { flex: 1, aspectRatio: 1, padding: GAP / 2 },
  img: { flex: 1, backgroundColor: '#141414' },

  topBar: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    justifyContent: 'space-between',
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
});
