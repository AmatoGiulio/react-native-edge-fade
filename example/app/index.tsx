import { useMemo, useState } from 'react';
import {
  Dimensions,
  Image,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { router } from 'expo-router';
import { FlashList } from '@shopify/flash-list';
import Animated, {
  Extrapolation,
  interpolate,
  useAnimatedScrollHandler,
  useDerivedValue,
  useSharedValue,
} from 'react-native-reanimated';
import { EdgeFadeView, AnimatedEdgeFadeView } from 'react-native-edge-fade';

import { CATALOG, CATEGORIES, type CatalogItem } from './lib/catalog';

const AnimatedFlashList = Animated.createAnimatedComponent(FlashList);

// ── Palette ───────────────────────────────────────────────────────────────────

const C = {
  bg: '#080808',
  surface: '#111',
  border: '#1e1e1e',
  text: '#f2f2f2',
  sub: '#777',
};

// ── Layout ────────────────────────────────────────────────────────────────────

const GRID_H_PADDING = 12;
const COLUMN_GAP = 8;
const COLUMN_WIDTH =
  (Dimensions.get('window').width - GRID_H_PADDING * 2 - COLUMN_GAP) / 2;

const heightFor = (it: CatalogItem) => COLUMN_WIDTH / it.ratio;

// ── Card ──────────────────────────────────────────────────────────────────────

function MasonryCard({ item }: { item: CatalogItem }) {
  return (
    <Pressable
      onPress={() => router.push(`/item/${item.id}` as never)}
      style={card.root}
    >
      <Image
        source={item.source}
        style={[card.media, { height: heightFor(item) }]}
      />
      <View style={card.label}>
        <View style={[card.dot, { backgroundColor: item.accent }]} />
        <Text style={card.text}>{item.category.toUpperCase()}</Text>
      </View>
    </Pressable>
  );
}

const card = StyleSheet.create({
  root: {
    borderRadius: 16,
    overflow: 'hidden',
    marginBottom: 8,
    marginHorizontal: COLUMN_GAP / 2,
    backgroundColor: C.surface,
  },
  media: {
    width: '100%',
    backgroundColor: '#0a0a0a',
  },
  label: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    paddingHorizontal: 10,
    paddingVertical: 8,
    backgroundColor: C.surface,
  },
  dot: {
    width: 6,
    height: 6,
    borderRadius: 3,
  },
  text: {
    fontSize: 9,
    fontWeight: '700',
    color: '#fff',
    letterSpacing: 0.8,
  },
});

// ── Category pill ─────────────────────────────────────────────────────────────

function CategoryPill({
  cat,
  active,
  onPress,
}: {
  cat: (typeof CATEGORIES)[number];
  active: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      onPress={onPress}
      style={[
        pill.root,
        active
          ? { backgroundColor: cat.accent, borderColor: cat.accent }
          : pill.inactive,
      ]}
    >
      <Text
        style={[
          pill.text,
          { color: active ? (cat.label === 'All' ? '#000' : '#fff') : C.sub },
        ]}
      >
        {cat.label}
      </Text>
    </Pressable>
  );
}

const pill = StyleSheet.create({
  root: {
    paddingHorizontal: 16,
    paddingVertical: 7,
    borderRadius: 20,
    borderWidth: 1,
    marginRight: 8,
  },
  text: {
    fontSize: 13,
    fontWeight: '600',
    letterSpacing: 0.1,
  },
  inactive: {
    backgroundColor: 'transparent',
    borderColor: C.border,
  },
});

// ── Discover screen ───────────────────────────────────────────────────────────

const keyExtractor = (item: CatalogItem) => item.id;
const renderItem = ({ item }: { item: CatalogItem }) => (
  <MasonryCard item={item} />
);

export default function DiscoverScreen() {
  const [activeCategory, setActiveCategory] = useState('All');

  const scrollY = useSharedValue(0);
  const onScroll = useAnimatedScrollHandler((event) => {
    scrollY.set(event.contentOffset.y);
  });
  const topFade = useDerivedValue(() =>
    interpolate(scrollY.value, [0, 120], [0, 120], Extrapolation.CLAMP)
  );

  const data = useMemo(
    () =>
      activeCategory === 'All'
        ? CATALOG
        : CATALOG.filter((i) => i.category === activeCategory),
    [activeCategory]
  );

  return (
    <View style={s.screen}>
      {/* Header */}
      <View style={s.header}>
        <View>
          <Text style={s.title}>Discover</Text>
          <Text style={s.subtitle}>{CATALOG.length} pieces</Text>
        </View>
        <Pressable
          style={s.benchBtn}
          onPress={() => router.push('/benchmark' as never)}
        >
          <Text style={s.benchText}>Benchmark</Text>
        </Pressable>
      </View>

      {/* Category strip */}
      <EdgeFadeView
        left={120}
        right={120}
        curve="gentle"
        mode="overlay"
        color={C.bg}
        style={s.filterWrap}
      >
        <ScrollView
          horizontal
          nestedScrollEnabled
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={s.filterContent}
        >
          {CATEGORIES.map((cat) => (
            <CategoryPill
              key={cat.label}
              cat={cat}
              active={activeCategory === cat.label}
              onPress={() => setActiveCategory(cat.label)}
            />
          ))}
        </ScrollView>
      </EdgeFadeView>

      {/* Masonry grid */}
      <AnimatedEdgeFadeView
        top={topFade}
        bottom={{ size: 600, curve: 'smooth' }}
        mode="overlay"
        color={C.bg}
        style={s.gridWrap}
      >
        <AnimatedFlashList
          data={data}
          renderItem={renderItem}
          keyExtractor={keyExtractor}
          masonry
          numColumns={2}
          optimizeItemArrangement
          contentContainerStyle={s.gridContent}
          showsVerticalScrollIndicator={false}
          onScroll={onScroll}
          scrollEventThrottle={16}
        />
      </AnimatedEdgeFadeView>
    </View>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────

const s = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: C.bg,
    paddingTop: Platform.OS === 'android' ? 48 : 60,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    marginBottom: 20,
  },
  title: {
    fontSize: 32,
    fontWeight: '700',
    color: C.text,
    letterSpacing: -0.8,
  },
  subtitle: {
    fontSize: 13,
    color: C.sub,
    marginTop: 2,
  },
  benchBtn: {
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: C.border,
  },
  benchText: {
    fontSize: 12,
    color: C.sub,
    fontWeight: '500',
  },
  filterWrap: {
    height: 44,
    marginBottom: 16,
  },
  filterContent: {
    paddingHorizontal: 20,
    alignItems: 'center',
  },
  gridWrap: {
    flex: 1,
  },
  gridContent: {
    paddingHorizontal: GRID_H_PADDING - COLUMN_GAP / 2,
    paddingBottom: 120,
  },
});
