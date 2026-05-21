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

const img = (id: string) =>
  `https://images.unsplash.com/${id}?auto=format&fit=crop&w=400&q=80`;

type Item = {
  id: string;
  uri: string;
  category: string;
  accent: string;
  h: number;
};

const ITEMS: Item[] = [
  {
    id: '1',
    uri: img('photo-1515886657613-9f3515b0c78f'),
    category: 'Fashion',
    accent: '#f472b6',
    h: 260,
  },
  {
    id: '2',
    uri: img('photo-1487958449943-2429e8be8625'),
    category: 'Architecture',
    accent: '#60a5fa',
    h: 200,
  },
  {
    id: '3',
    uri: img('photo-1579546929518-9e396f3cc809'),
    category: 'Art',
    accent: '#a78bfa',
    h: 300,
  },
  {
    id: '4',
    uri: img('photo-1448375240586-882707db888b'),
    category: 'Nature',
    accent: '#34d399',
    h: 220,
  },
  {
    id: '5',
    uri: img('photo-1558618666-fcd25c85cd64'),
    category: 'Fashion',
    accent: '#f472b6',
    h: 280,
  },
  {
    id: '6',
    uri: img('photo-1486325212027-8081e485255e'),
    category: 'Architecture',
    accent: '#60a5fa',
    h: 240,
  },
  {
    id: '7',
    uri: img('photo-1541701494587-cb58502866ab'),
    category: 'Art',
    accent: '#a78bfa',
    h: 190,
  },
  {
    id: '8',
    uri: img('photo-1506905925346-21bda4d32df4'),
    category: 'Nature',
    accent: '#34d399',
    h: 320,
  },
  {
    id: '9',
    uri: img('photo-1469334031218-e382a71b716b'),
    category: 'Fashion',
    accent: '#f472b6',
    h: 230,
  },
  {
    id: '10',
    uri: img('photo-1477959858617-67f85cf4f1df'),
    category: 'Architecture',
    accent: '#60a5fa',
    h: 270,
  },
  {
    id: '11',
    uri: img('photo-1558591710-4b4a1ae0f04d'),
    category: 'Art',
    accent: '#a78bfa',
    h: 210,
  },
  {
    id: '12',
    uri: img('photo-1465146344425-f00d5f5c8f07'),
    category: 'Nature',
    accent: '#34d399',
    h: 290,
  },
  {
    id: '13',
    uri: img('photo-1509631179647-0177331693ae'),
    category: 'Fashion',
    accent: '#f472b6',
    h: 250,
  },
  {
    id: '14',
    uri: img('photo-1493397212122-2b85dda8106b'),
    category: 'Architecture',
    accent: '#60a5fa',
    h: 310,
  },
  {
    id: '15',
    uri: img('photo-1547891654-e66ed7ebb968'),
    category: 'Art',
    accent: '#a78bfa',
    h: 180,
  },
  {
    id: '16',
    uri: img('photo-1501854140801-50d01698950b'),
    category: 'Nature',
    accent: '#34d399',
    h: 260,
  },
];

type Category = { label: string; accent: string };

const CATEGORIES: Category[] = [
  { label: 'All', accent: '#f2f2f2' },
  { label: 'Fashion', accent: '#f472b6' },
  { label: 'Architecture', accent: '#60a5fa' },
  { label: 'Art', accent: '#a78bfa' },
  { label: 'Nature', accent: '#34d399' },
  { label: 'Dark', accent: '#fb923c' },
];

// ── Masonry helpers ───────────────────────────────────────────────────────────

function splitMasonry(items: Item[]): [Item[], Item[]] {
  const left: Item[] = [];
  const right: Item[] = [];
  let leftH = 0;
  let rightH = 0;
  for (const item of items) {
    if (leftH <= rightH) {
      left.push(item);
      leftH += item.h;
    } else {
      right.push(item);
      rightH += item.h;
    }
  }
  return [left, right];
}

// ── Card ──────────────────────────────────────────────────────────────────────

function MasonryCard({ item }: { item: Item }) {
  return (
    <View style={[mc.root, { height: item.h }]}>
      <Image source={{ uri: item.uri }} style={mc.image} resizeMode="cover" />
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
    ...StyleSheet.absoluteFillObject,
  },
  label: {
    position: 'absolute',
    bottom: 10,
    left: 10,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    backgroundColor: 'rgba(0,0,0,0.55)',
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 20,
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

  const [left, right] = splitMasonry(filtered);

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
          <View style={s.columns}>
            <View style={s.col}>
              {left.map((item) => (
                <MasonryCard key={item.id} item={item} />
              ))}
            </View>
            <View style={s.col}>
              {right.map((item) => (
                <MasonryCard key={item.id} item={item} />
              ))}
            </View>
          </View>
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
