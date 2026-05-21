import { useState } from 'react';
import {
  Image,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import type { ImageSourcePropType } from 'react-native';
import Animated, {
  Extrapolation,
  interpolate,
  useAnimatedProps,
  useAnimatedScrollHandler,
  useSharedValue,
} from 'react-native-reanimated';
import { EdgeFadeView, NativeEdgeFadeView } from 'react-native-edge-fade';
import BenchmarkScreen from './BenchmarkScreen';

// ── Reanimated native EdgeFadeView ────────────────────────────────────────────
//
// We wrap NativeEdgeFadeView (the raw Fabric component), NOT the JS EdgeFadeView
// wrapper.  Reason: Reanimated's useAnimatedProps fires a UI-thread worklet that
// mutates the shadow node directly.  If we animate `top` on the JS wrapper, the
// mutation lands on Yoga's `top` layout property (shifting the view down).
// Animating `fadeTop` on the native component targets the registered @ReactProp
// instead — no layout side-effects.
//
// Created at module level so Reanimated never re-registers the component.

const ReanimatedNativeEdgeFadeView =
  Animated.createAnimatedComponent(NativeEdgeFadeView);

// ── Palette ───────────────────────────────────────────────────────────────────

const C = {
  bg: '#080808',
  surface: '#111',
  border: '#1e1e1e',
  text: '#f2f2f2',
  sub: '#777',
  muted: '#333',
};

// ── Data ──────────────────────────────────────────────────────────────────────

type Item = {
  id: string;
  source: ImageSourcePropType;
  category: string;
  accent: string;
  ratio: number;
};

type GalleryRow =
  | { type: 'full'; item: Item }
  | { type: 'columns'; left: Item[]; right: Item[] };

const FULL_WIDTH_RATIO = 1.15;

const ITEMS: Item[] = [
  {
    id: 'architecture-01',
    source: require('../assets/images/9ba092c8-2d9e-4614-8fb7-4774e45ab457.jpg'),
    category: 'Architecture',
    accent: '#60a5fa',
    ratio: 0.714,
  },
  {
    id: 'portrait-01',
    source: require('../assets/images/9c4345e2-63f4-4faa-b9ce-b1ee85d0c9d9.jpg'),
    category: 'Portrait',
    accent: '#f472b6',
    ratio: 0.778,
  },
  {
    id: 'wildlife-01',
    source: require('../assets/images/9d4529fe-f2ae-4ab2-a29f-f14fc999cf0c.jpg'),
    category: 'Wildlife',
    accent: '#34d399',
    ratio: 0.667,
  },
  {
    id: 'motion-01',
    source: require('../assets/images/9d8a4f0e-81f3-425f-ab85-8efc7e3cb5b6.jpg'),
    category: 'Motion',
    accent: '#fb923c',
    ratio: 1,
  },
  {
    id: 'still-life-01',
    source: require('../assets/images/9d8a4f92-9cd1-47d0-8f7c-9090c35a99e9.jpg'),
    category: 'Still life',
    accent: '#a78bfa',
    ratio: 1,
  },
  {
    id: 'motion-02',
    source: require('../assets/images/9d8a4fe4-aa56-4750-8bf3-d0dbc5a81a09.jpg'),
    category: 'Motion',
    accent: '#fb923c',
    ratio: 1,
  },
  {
    id: 'portrait-02',
    source: require('../assets/images/9d8a5043-09cd-408b-81b9-a62b15a1888c.jpg'),
    category: 'Portrait',
    accent: '#f472b6',
    ratio: 0.673,
  },
  {
    id: 'still-life-02',
    source: require('../assets/images/9e242635-a789-410b-8a3c-4ea625b7beeb.jpg'),
    category: 'Still life',
    accent: '#a78bfa',
    ratio: 0.667,
  },
  {
    id: 'botanical-01',
    source: require('../assets/images/9e3c7299-6b8f-47e6-a549-83caa9572864.jpg'),
    category: 'Botanical',
    accent: '#22c55e',
    ratio: 1.499,
  },
  {
    id: 'texture-01',
    source: require('../assets/images/9e3e5add-485c-467d-827b-55cf402481d2.jpg'),
    category: 'Texture',
    accent: '#38bdf8',
    ratio: 0.667,
  },
  {
    id: 'landscape-01',
    source: require('../assets/images/9ef3281a-a251-4733-b2b4-166429b9737b.jpg'),
    category: 'Landscape',
    accent: '#f59e0b',
    ratio: 1.499,
  },
  {
    id: 'sport-01',
    source: require('../assets/images/9f256029-83b7-4cca-ab40-34aa82623fee.jpg'),
    category: 'Sport',
    accent: '#94a3b8',
    ratio: 0.666,
  },
  {
    id: 'architecture-02',
    source: require('../assets/images/9f4b5744-7ad6-42ae-a56a-c0a05830639c.jpg'),
    category: 'Architecture',
    accent: '#60a5fa',
    ratio: 0.667,
  },
  {
    id: 'portrait-03',
    source: require('../assets/images/9f6820da-c77a-49ce-9dd8-faf244f2b4d4.jpg'),
    category: 'Portrait',
    accent: '#f472b6',
    ratio: 0.8,
  },
  {
    id: 'landscape-02',
    source: require('../assets/images/a0395656-c22e-4e94-9a49-480bbf5ca458.jpg'),
    category: 'Landscape',
    accent: '#f59e0b',
    ratio: 0.667,
  },
  {
    id: 'portrait-04',
    source: require('../assets/images/a03ad659-7fb7-454a-8636-c30e9f666810.jpg'),
    category: 'Portrait',
    accent: '#f472b6',
    ratio: 0.8,
  },
  {
    id: 'landscape-03',
    source: require('../assets/images/a07e2d49-8e4b-4818-a83e-400c0218c788.jpg'),
    category: 'Landscape',
    accent: '#f59e0b',
    ratio: 0.989,
  },
  {
    id: 'water-01',
    source: require('../assets/images/a0ca83d6-d331-4b68-9f96-985ac8912b64.jpg'),
    category: 'Water',
    accent: '#06b6d4',
    ratio: 0.8,
  },
  {
    id: 'texture-02',
    source: require('../assets/images/a0d5d70b-21be-4bac-86de-c1fd44a04ae1.jpg'),
    category: 'Texture',
    accent: '#38bdf8',
    ratio: 1.499,
  },
  {
    id: 'water-02',
    source: require('../assets/images/a0f1dd90-a6f4-4517-8b41-ad82bb2e96a8.jpg'),
    category: 'Water',
    accent: '#06b6d4',
    ratio: 0.75,
  },
  {
    id: 'architecture-03',
    source: require('../assets/images/a0f44c2b-de69-448d-9354-fad6324d4157.jpg'),
    category: 'Architecture',
    accent: '#60a5fa',
    ratio: 1.5,
  },
  {
    id: 'botanical-02',
    source: require('../assets/images/a110becf-fc2a-455b-b63c-8f97c8605331.jpg'),
    category: 'Botanical',
    accent: '#22c55e',
    ratio: 0.67,
  },
  {
    id: 'architecture-04',
    source: require('../assets/images/a1124490-93df-41d7-86a8-b08bab3fbd60.jpg'),
    category: 'Architecture',
    accent: '#60a5fa',
    ratio: 1.499,
  },
  {
    id: 'portrait-05',
    source: require('../assets/images/a1135b99-8f51-49a0-aa8d-a91f5a2e9d71.jpg'),
    category: 'Portrait',
    accent: '#f472b6',
    ratio: 0.668,
  },
  {
    id: 'texture-03',
    source: require('../assets/images/a1153da7-2686-4a2c-b575-2dbaec5c0fba.jpg'),
    category: 'Texture',
    accent: '#38bdf8',
    ratio: 1.5,
  },
  {
    id: 'water-03',
    source: require('../assets/images/a120e421-b303-4145-be24-f3f8dc77da2b.jpg'),
    category: 'Water',
    accent: '#06b6d4',
    ratio: 0.667,
  },
  {
    id: 'botanical-03',
    source: require('../assets/images/a16ee596-df8e-4469-91d2-f39f162fb184.jpg'),
    category: 'Botanical',
    accent: '#22c55e',
    ratio: 1.499,
  },
  {
    id: 'portrait-06',
    source: require('../assets/images/p-9877b4d9-adbb-4bff-bc0b-4802f797d4ab.jpg'),
    category: 'Portrait',
    accent: '#f472b6',
    ratio: 1.499,
  },
  {
    id: 'architecture-05',
    source: require('../assets/images/p-98946414-c532-4896-9311-a8eba52fa06d.jpg'),
    category: 'Architecture',
    accent: '#60a5fa',
    ratio: 0.8,
  },
  {
    id: 'portrait-07',
    source: require('../assets/images/p-98dc3a7a-5b07-4084-9156-101443846ce1.jpg'),
    category: 'Portrait',
    accent: '#f472b6',
    ratio: 1.25,
  },
  {
    id: 'motion-03',
    source: require('../assets/images/p-98f88e35-b619-4a0d-9d0c-d80d3a01b9cc.jpg'),
    category: 'Motion',
    accent: '#fb923c',
    ratio: 0.683,
  },
  {
    id: 'landscape-04',
    source: require('../assets/images/p-98fb1bc2-5c32-41c0-a1a0-bdc93f70846e.jpg'),
    category: 'Landscape',
    accent: '#f59e0b',
    ratio: 1.503,
  },
  {
    id: 'water-04',
    source: require('../assets/images/p-99bb8611-7a4c-44fb-85bc-06ac11f7ef15.jpg'),
    category: 'Water',
    accent: '#06b6d4',
    ratio: 0.667,
  },
];

type Category = { label: string; accent: string };

const CATEGORIES: Category[] = [
  { label: 'All', accent: '#f2f2f2' },
  { label: 'Architecture', accent: '#60a5fa' },
  { label: 'Portrait', accent: '#f472b6' },
  { label: 'Landscape', accent: '#f59e0b' },
  { label: 'Water', accent: '#06b6d4' },
  { label: 'Botanical', accent: '#22c55e' },
  { label: 'Texture', accent: '#38bdf8' },
  { label: 'Motion', accent: '#fb923c' },
  { label: 'Still life', accent: '#a78bfa' },
  { label: 'Wildlife', accent: '#34d399' },
  { label: 'Sport', accent: '#94a3b8' },
];

// ── Gallery helpers ───────────────────────────────────────────────────────────

function splitColumns(items: Item[]): [Item[], Item[]] {
  const left: Item[] = [];
  const right: Item[] = [];
  let leftH = 0;
  let rightH = 0;
  for (const item of items) {
    const displayH = 1 / item.ratio + 0.16;
    if (leftH <= rightH) {
      left.push(item);
      leftH += displayH;
    } else {
      right.push(item);
      rightH += displayH;
    }
  }
  return [left, right];
}

function buildGalleryRows(items: Item[]): GalleryRow[] {
  const rows: GalleryRow[] = [];
  let pending: Item[] = [];

  const flushColumns = () => {
    if (pending.length === 0) return;
    const [left, right] = splitColumns(pending);
    rows.push({ type: 'columns', left, right });
    pending = [];
  };

  for (const item of items) {
    if (item.ratio >= FULL_WIDTH_RATIO) {
      flushColumns();
      rows.push({ type: 'full', item });
    } else {
      pending.push(item);
    }
  }

  flushColumns();
  return rows;
}

// ── Card ──────────────────────────────────────────────────────────────────────

function MasonryCard({ item }: { item: Item }) {
  return (
    <View style={mc.root}>
      <Image
        source={item.source}
        style={[mc.image, { aspectRatio: item.ratio }]}
        resizeMode="contain"
      />
      <View style={mc.label}>
        <View style={[mc.dot, { backgroundColor: item.accent }]} />
        <Text style={mc.text}>{item.category.toUpperCase()}</Text>
      </View>
    </View>
  );
}

const mc = StyleSheet.create({
  root: {
    borderRadius: 14,
    overflow: 'hidden',
    marginBottom: 8,
    backgroundColor: C.surface,
  },
  image: {
    width: '100%',
    backgroundColor: '#050505',
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
  cat: Category;
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

// ── Demo screen ───────────────────────────────────────────────────────────────

function DemoScreen({ onBenchmark }: { onBenchmark: () => void }) {
  const [activeCategory, setActiveCategory] = useState('All');

  // ── Scroll-driven top fade via Reanimated SharedValue ──────────────────────
  //
  // scrollY lives entirely on the UI thread — no JS-thread bridge round-trip.
  //
  // topFadeProps animates `fadeTop` (native prop) directly on NativeEdgeFadeView
  // via Reanimated's UI-thread shadow-node mutation.  Using `fadeTop` avoids the
  // Yoga layout collision that would occur if we animated the JS wrapper's `top`.
  //
  //   scrollY = 0  → fadeTop = 0   (at the very top, no top fade needed)
  //   scrollY = 80 → fadeTop = 60  (full 60 dp top fade — content above)

  const scrollY = useSharedValue(0);

  const onScroll = useAnimatedScrollHandler((event) => {
    scrollY.set(event.contentOffset.y);
  });

  const topFadeProps = useAnimatedProps(() => ({
    fadeTop: interpolate(
      scrollY.value,
      [0, 160],
      [0, 160],
      Extrapolation.CLAMP
    ),
  }));

  const filtered =
    activeCategory === 'All'
      ? ITEMS
      : ITEMS.filter((i) => i.category === activeCategory);

  const rows = buildGalleryRows(filtered);

  return (
    <View style={s.screen}>
      {/* Header */}
      <View style={s.header}>
        <View>
          <Text style={s.title}>Discover</Text>
          <Text style={s.subtitle}>{ITEMS.length} pieces</Text>
        </View>
        <Pressable style={s.benchBtn} onPress={onBenchmark}>
          <Text style={s.benchText}>⚡ Benchmark</Text>
        </Pressable>
      </View>

      {/*
       * Category filter strip — overlay mode.
       *
       * mode="overlay" + color={C.bg} paints a gradient from the background
       * colour over the scroll edges, creating a "fade into background" look.
       * This is overlay mode's typical use-case; no true alpha masking needed.
       */}
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

      {/*
       * Masonry grid — mask mode, scroll-driven top fade via Reanimated.
       *
       * ReanimatedNativeEdgeFadeView wraps NativeEdgeFadeView directly so Reanimated's
       * UI-thread worklet can mutate `fadeTop` as a @ReactProp without
       * colliding with Yoga layout.
       *
       * Static props (fadeBottom, mode, curve*) are passed as normal React props.
       * The animated prop (fadeTop) is driven by the scroll SharedValue.
       */}
      <ReanimatedNativeEdgeFadeView
        animatedProps={topFadeProps}
        fadeBottom={600}
        mode="mask"
        curveTop="smooth"
        curveBottom="smooth"
        style={s.gridWrap}
      >
        <Animated.ScrollView
          showsVerticalScrollIndicator={false}
          contentContainerStyle={s.gridContent}
          scrollEventThrottle={16}
          onScroll={onScroll}
        >
          {rows.map((row, index) =>
            row.type === 'full' ? (
              <MasonryCard key={row.item.id} item={row.item} />
            ) : (
              <View key={`columns-${index}`} style={s.columns}>
                <View style={s.col}>
                  {row.left.map((item) => (
                    <MasonryCard key={item.id} item={item} />
                  ))}
                </View>
                <View style={s.col}>
                  {row.right.map((item) => (
                    <MasonryCard key={item.id} item={item} />
                  ))}
                </View>
              </View>
            )
          )}
        </Animated.ScrollView>
      </ReanimatedNativeEdgeFadeView>
    </View>
  );
}

// ── Root ──────────────────────────────────────────────────────────────────────

export default function App() {
  const [screen, setScreen] = useState<'demo' | 'benchmark'>('demo');

  return (
    <View style={s.root}>
      {screen === 'demo' ? (
        <DemoScreen onBenchmark={() => setScreen('benchmark')} />
      ) : (
        <View style={s.benchmarkRoot}>
          <Pressable style={s.backBtn} onPress={() => setScreen('demo')}>
            <Text style={s.backText}>← Discover</Text>
          </Pressable>
          <BenchmarkScreen />
        </View>
      )}
    </View>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────

const s = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: C.bg,
  },
  benchmarkRoot: {
    flex: 1,
  },
  screen: {
    flex: 1,
    paddingTop: Platform.OS === 'android' ? 48 : 60,
  },

  // Header
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-end',
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

  // Filter strip
  filterWrap: {
    height: 44,
    marginBottom: 16,
  },
  filterContent: {
    paddingHorizontal: 20,
    alignItems: 'center',
  },

  // Grid
  gridWrap: {
    flex: 1,
  },
  gridContent: {
    paddingHorizontal: 12,
    paddingBottom: 120,
  },
  columns: {
    flexDirection: 'row',
    gap: 8,
  },
  col: {
    flex: 1,
  },

  // Back button (benchmark → demo)
  backBtn: {
    paddingTop: Platform.OS === 'android' ? 48 : 60,
    paddingHorizontal: 20,
    paddingBottom: 8,
  },
  backText: {
    fontSize: 14,
    color: C.sub,
    fontWeight: '500',
  },
});
