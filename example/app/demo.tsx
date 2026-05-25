/**
 * Demo screen — dreamy-blur.mp4 with colour-grade filter selector.
 *
 * Architecture:
 *  • ONE VideoPlayer + ONE VideoView (background, live)
 *  • Static FRAME image (first frame of video) used in FilterCards — cross-platform reliable
 *  • FilterCard shows FRAME + filter colour overlay — zero extra decoders
 *  • Animated.View overlay for the active filter on the background (CSS transition)
 */

import { memo, useCallback, useState } from 'react';
import {
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import Animated from 'react-native-reanimated';
import { Image } from 'expo-image';
import { router } from 'expo-router';
import { useVideoPlayer, VideoView } from 'expo-video';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { EdgeFadeView } from 'react-native-edge-fade';

// ── Assets ────────────────────────────────────────────────────────────────────

const SOURCE = require('../assets/footages/dreamy-blur.mp4') as number;
// Static first-frame used in filter cards — reliable on both iOS and Android
const FRAME = require('../assets/images/demo-frame.png') as number;

// ── Filters ───────────────────────────────────────────────────────────────────

const FILTERS = [
  { id: 'original', label: 'Original', overlay: null },
  { id: 'warm', label: 'Warm', overlay: 'rgba(255,140,50,0.32)' },
  { id: 'cool', label: 'Cool', overlay: 'rgba(50,120,255,0.26)' },
  { id: 'golden', label: 'Golden', overlay: 'rgba(255,210,30,0.36)' },
  { id: 'dusk', label: 'Dusk', overlay: 'rgba(90,50,200,0.30)' },
  { id: 'fade', label: 'Fade', overlay: 'rgba(255,255,255,0.24)' },
  { id: 'noir', label: 'Noir', overlay: 'rgba(0,0,30,0.58)' },
  { id: 'teal', label: 'Teal', overlay: 'rgba(0,200,180,0.26)' },
] as const;

type Filter = (typeof FILTERS)[number];
type FilterId = Filter['id'];

// ── FilterCard ────────────────────────────────────────────────────────────────

interface CardProps {
  filter: Filter;
  active: boolean;
  onPress: () => void;
}

const FilterCard = memo(function FilterCard({
  filter,
  active,
  onPress,
}: CardProps) {
  return (
    <Pressable
      onPress={onPress}
      style={[card.root, active ? card.rootActive : card.rootIdle]}
    >
      {/* Static first frame — reliable cross-platform */}
      <Image
        source={FRAME}
        style={StyleSheet.absoluteFill}
        contentFit="cover"
      />

      {/* Filter colour overlay on top of the frame */}
      {filter.overlay && (
        <View
          style={[StyleSheet.absoluteFill, { backgroundColor: filter.overlay }]}
          pointerEvents="none"
        />
      )}

      {/* Bottom label */}
      <View style={card.footer}>
        <Text
          style={[card.label, active && card.labelActive]}
          numberOfLines={1}
        >
          {filter.label}
        </Text>
      </View>

      {/* Active indicator dot */}
      {active && <View style={card.dot} />}
    </Pressable>
  );
});

const card = StyleSheet.create({
  root: {
    width: 76,
    height: 116,
    borderRadius: 14,
    overflow: 'hidden',
    marginRight: 10,
    borderWidth: 2,
  },
  rootActive: { borderColor: 'rgba(255,255,255,0.80)' },
  rootIdle: { borderColor: 'rgba(255,255,255,0.12)' },
  placeholder: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: '#111',
  },
  footer: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    paddingHorizontal: 6,
    paddingBottom: 7,
    paddingTop: 20,
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  label: {
    fontSize: 9,
    fontWeight: '600',
    color: 'rgba(255,255,255,0.45)',
    letterSpacing: 0.5,
    textTransform: 'uppercase',
  },
  labelActive: { color: '#fff' },
  dot: {
    position: 'absolute',
    top: 8,
    right: 8,
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: '#fff',
  },
});

// ── Demo screen ───────────────────────────────────────────────────────────────

export default function DemoScreen() {
  const [filterId, setFilterId] = useState<FilterId>('original');
  const insets = useSafeAreaInsets();

  const player = useVideoPlayer(SOURCE, (p) => {
    p.loop = true;
    p.muted = true;
    p.play();
  });

  const activeFilter = FILTERS.find((f) => f.id === filterId) ?? FILTERS[0]!;

  const handleSelect = useCallback((id: FilterId) => setFilterId(id), []);
  const handleShare = useCallback(() => router.push('/share' as never), []);
  const handleBack = useCallback(() => router.back(), []);

  return (
    <View style={s.root}>
      {/* 1 ── Live background video */}
      <VideoView
        player={player}
        style={StyleSheet.absoluteFill}
        contentFit="cover"
        nativeControls={false}
      />

      {/* 2 ── Active filter colour overlay (CSS transition — UI thread) */}
      <Animated.View
        pointerEvents="none"
        style={[
          s.filterOverlay,
          activeFilter.overlay
            ? { backgroundColor: activeFilter.overlay }
            : s.filterOverlayHidden,
        ]}
      />

      {/* 3 ── Bottom vignette */}
      <EdgeFadeView
        bottom={600}
        curve="gentle"
        style={StyleSheet.absoluteFill}
        pointerEvents="none"
      />

      {/* 4 ── Back */}
      <Pressable style={s.back} onPress={handleBack}>
        <View style={s.backPill}>
          <Ionicons name="chevron-back" size={22} color="#fff" />
        </View>
      </Pressable>

      {/* 5 ── Bottom panel */}
      <View style={[s.panel, { paddingBottom: insets.bottom || 20 }]}>
        <EdgeFadeView
          left={80}
          right={80}
          curve="smooth"
          mode="mask"
          style={s.strip}
        >
          <ScrollView
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={s.stripContent}
          >
            {FILTERS.map((f) => (
              <FilterCard
                key={f.id}
                filter={f}
                active={f.id === filterId}
                onPress={() => handleSelect(f.id)}
              />
            ))}
          </ScrollView>
        </EdgeFadeView>

        <View style={s.bar}>
          <View style={s.meta}>
            <View style={s.metaDot} />
            <Text style={s.metaLabel}>{activeFilter.label.toUpperCase()}</Text>
          </View>
          <Pressable style={s.shareBtn} onPress={handleShare}>
            <Text style={s.shareBtnText}>Share</Text>
          </Pressable>
        </View>
      </View>
    </View>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#000' },

  filterOverlay: {
    ...StyleSheet.absoluteFillObject,
  },
  filterOverlayHidden: {
    // original filter: fully transparent
    backgroundColor: 'rgba(0,0,0,0)',
  },

  back: {
    position: 'absolute',
    top: Platform.OS === 'android' ? 48 : 60,
    left: 20,
    zIndex: 10,
  },
  backPill: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(0,0,0,0.45)',
    alignItems: 'center',
    justifyContent: 'center',
  },

  panel: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
  },

  strip: { height: 140, marginBottom: 20 },
  stripContent: { paddingHorizontal: 24, alignItems: 'center' },

  bar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 24,
  },
  meta: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  metaDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: 'rgba(255,255,255,0.35)',
  },
  metaLabel: {
    fontSize: 11,
    fontWeight: '700',
    color: 'rgba(255,255,255,0.40)',
    letterSpacing: 1.2,
  },
  shareBtn: {
    paddingHorizontal: 28,
    paddingVertical: 14,
    borderRadius: 28,
    backgroundColor: '#fff',
  },
  shareBtnText: { fontSize: 15, fontWeight: '700', color: '#000' },
});
