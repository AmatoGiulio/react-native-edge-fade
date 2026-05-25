import { useCallback, useMemo, useState } from 'react';
import {
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

import { CATALOG, CATEGORIES, type CatalogItem } from '../constants/catalog';
import { MasonryCard } from '../components/MasonryCard';
import { CategoryPill } from '../components/CategoryPill';
import { useAppTheme } from '../hooks/useAppTheme';

// Reanimated-wrapped FlashList so useAnimatedScrollHandler works
const AnimatedFlashList = Animated.createAnimatedComponent(
  FlashList<CatalogItem>
);

const GRID_H_PADDING = 12;

// Stable render function — defined outside component to prevent recreation
function renderItem({ item }: { item: CatalogItem }) {
  return <MasonryCard item={item} />;
}

function keyExtractor(item: CatalogItem) {
  return item.id;
}

export default function DiscoverScreen() {
  const t = useAppTheme();
  const [activeCategory, setActiveCategory] = useState('All');

  const scrollY = useSharedValue(0);
  const onScroll = useAnimatedScrollHandler((e) => {
    scrollY.set(e.contentOffset.y);
  });
  const topFade = useDerivedValue(() =>
    interpolate(scrollY.value, [0, 60], [0, 60], Extrapolation.CLAMP)
  );

  const data = useMemo(
    () =>
      activeCategory === 'All'
        ? CATALOG
        : CATALOG.filter((i) => i.category === activeCategory),
    [activeCategory]
  );

  const handleBenchmark = useCallback(() => {
    router.push('/benchmark' as never);
  }, []);

  const handleDemo = useCallback(() => {
    router.push('/demo' as never);
  }, []);

  return (
    <View style={[s.screen, { backgroundColor: t.bg }]}>
      {/* Header */}
      <View style={s.header}>
        <View>
          <Text style={[s.title, { color: t.text }]}>Discover</Text>
          <Text style={[s.subtitle, { color: t.sub }]}>
            {CATALOG.length} pieces
          </Text>
        </View>
        <View style={s.headerActions}>
          <Pressable
            style={[s.benchBtn, { borderColor: t.border }]}
            onPress={handleDemo}
          >
            <Text style={[s.benchText, { color: t.sub }]}>Demo</Text>
          </Pressable>
          <Pressable
            style={[s.benchBtn, { borderColor: t.border }]}
            onPress={handleBenchmark}
          >
            <Text style={[s.benchText, { color: t.sub }]}>Bench</Text>
          </Pressable>
        </View>
      </View>

      {/* Category filter strip */}
      <EdgeFadeView
        left={120}
        right={120}
        curve="gentle"
        mode="overlay"
        color={t.bg}
        style={s.filterWrap}
      >
        <ScrollView
          horizontal
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

      {/* Masonry grid with animated top+bottom fade */}
      <AnimatedEdgeFadeView
        top={topFade}
        bottom={{ size: 600, curve: 'smooth' }}
        mode="overlay"
        color={t.bg}
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

const s = StyleSheet.create({
  screen: {
    flex: 1,
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
    letterSpacing: -0.8,
  },
  subtitle: {
    fontSize: 13,
    marginTop: 2,
  },
  headerActions: { flexDirection: 'row', gap: 8 },
  benchBtn: {
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 20,
    borderWidth: 1,
  },
  benchText: { fontSize: 12, fontWeight: '500' },
  filterWrap: { height: 44, marginBottom: 16 },
  filterContent: { paddingHorizontal: 20, alignItems: 'center' },
  gridWrap: { flex: 1 },
  gridContent: {
    paddingHorizontal: GRID_H_PADDING,
  },
});
