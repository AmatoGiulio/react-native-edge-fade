import { useState } from 'react';
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
import type { ImageSourcePropType } from 'react-native';
import Animated, {
  Extrapolation,
  interpolate,
  useAnimatedScrollHandler,
  useDerivedValue,
  useSharedValue,
} from 'react-native-reanimated';
import { EdgeFadeView, AnimatedEdgeFadeView } from 'react-native-edge-fade';
import BenchmarkScreen from './BenchmarkScreen';

// AnimatedEdgeFadeView accepts a SharedValue on size-like props (top, bottom,
// left, right, start, end, radius) and routes them through a Reanimated
// useAnimatedProps worklet so updates stay on the UI thread.

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
};

// Accent color per category (used for the small dot on each card).
const CATEGORY_ACCENT: Record<string, string> = {
  Architecture: '#60a5fa',
  Portrait: '#f472b6',
  Animals: '#34d399',
  Abstract: '#a78bfa',
  Nature: '#22c55e',
  Landscape: '#f59e0b',
  Sports: '#94a3b8',
  Underwater: '#06b6d4',
};

const mkItem = (
  id: string,
  source: ImageSourcePropType,
  category: keyof typeof CATEGORY_ACCENT
): Item => ({ id, source, category, accent: CATEGORY_ACCENT[category] });

const ITEMS: Item[] = [
  mkItem(
    'i01',
    require('../assets/images/9ba092c8-2d9e-4614-8fb7-4774e45ab457.jpg'),
    'Architecture'
  ),
  mkItem(
    'i02',
    require('../assets/images/9c4345e2-63f4-4faa-b9ce-b1ee85d0c9d9.jpg'),
    'Portrait'
  ),
  mkItem(
    'i03',
    require('../assets/images/9d4529fe-f2ae-4ab2-a29f-f14fc999cf0c.jpg'),
    'Animals'
  ),
  mkItem(
    'i04',
    require('../assets/images/9d8a4f0e-81f3-425f-ab85-8efc7e3cb5b6.jpg'),
    'Abstract'
  ),
  mkItem(
    'i05',
    require('../assets/images/9d8a4f92-9cd1-47d0-8f7c-9090c35a99e9.jpg'),
    'Nature'
  ),
  mkItem(
    'i06',
    require('../assets/images/9d8a4fe4-aa56-4750-8bf3-d0dbc5a81a09.jpg'),
    'Portrait'
  ),
  mkItem(
    'i07',
    require('../assets/images/9d8a5043-09cd-408b-81b9-a62b15a1888c.jpg'),
    'Portrait'
  ),
  mkItem(
    'i08',
    require('../assets/images/9e242635-a789-410b-8a3c-4ea625b7beeb.jpg'),
    'Portrait'
  ),
  mkItem(
    'i09',
    require('../assets/images/9e3c7299-6b8f-47e6-a549-83caa9572864.jpg'),
    'Nature'
  ),
  mkItem(
    'i10',
    require('../assets/images/9e3e5add-485c-467d-827b-55cf402481d2.jpg'),
    'Abstract'
  ),
  mkItem(
    'i11',
    require('../assets/images/9ef3281a-a251-4733-b2b4-166429b9737b.jpg'),
    'Landscape'
  ),
  mkItem(
    'i12',
    require('../assets/images/9f256029-83b7-4cca-ab40-34aa82623fee.jpg'),
    'Sports'
  ),
  mkItem(
    'i13',
    require('../assets/images/9f4b5744-7ad6-42ae-a56a-c0a05830639c.jpg'),
    'Architecture'
  ),
  mkItem(
    'i14',
    require('../assets/images/9f6820da-c77a-49ce-9dd8-faf244f2b4d4.jpg'),
    'Portrait'
  ),
  mkItem(
    'i15',
    require('../assets/images/a0395656-c22e-4e94-9a49-480bbf5ca458.jpg'),
    'Landscape'
  ),
  mkItem(
    'i16',
    require('../assets/images/a03ad659-7fb7-454a-8636-c30e9f666810.jpg'),
    'Portrait'
  ),
  mkItem(
    'i17',
    require('../assets/images/a07e2d49-8e4b-4818-a83e-400c0218c788.jpg'),
    'Portrait'
  ),
  mkItem(
    'i18',
    require('../assets/images/a0ca83d6-d331-4b68-9f96-985ac8912b64.jpg'),
    'Nature'
  ),
  mkItem(
    'i19',
    require('../assets/images/a0d5d70b-21be-4bac-86de-c1fd44a04ae1.jpg'),
    'Abstract'
  ),
  mkItem(
    'i20',
    require('../assets/images/a0f1dd90-a6f4-4517-8b41-ad82bb2e96a8.jpg'),
    'Landscape'
  ),
  mkItem(
    'i21',
    require('../assets/images/a0f44c2b-de69-448d-9354-fad6324d4157.jpg'),
    'Architecture'
  ),
  mkItem(
    'i22',
    require('../assets/images/a110becf-fc2a-455b-b63c-8f97c8605331.jpg'),
    'Portrait'
  ),
  mkItem(
    'i23',
    require('../assets/images/a1124490-93df-41d7-86a8-b08bab3fbd60.jpg'),
    'Abstract'
  ),
  mkItem(
    'i24',
    require('../assets/images/a1135b99-8f51-49a0-aa8d-a91f5a2e9d71.jpg'),
    'Nature'
  ),
  mkItem(
    'i25',
    require('../assets/images/a1153da7-2686-4a2c-b575-2dbaec5c0fba.jpg'),
    'Abstract'
  ),
  mkItem(
    'i26',
    require('../assets/images/a120e421-b303-4145-be24-f3f8dc77da2b.jpg'),
    'Underwater'
  ),
  mkItem(
    'i27',
    require('../assets/images/a16ee596-df8e-4469-91d2-f39f162fb184.jpg'),
    'Nature'
  ),
  mkItem(
    'i28',
    require('../assets/images/p-9877b4d9-adbb-4bff-bc0b-4802f797d4ab.jpg'),
    'Portrait'
  ),
  mkItem(
    'i29',
    require('../assets/images/p-98946414-c532-4896-9311-a8eba52fa06d.jpg'),
    'Architecture'
  ),
  mkItem(
    'i30',
    require('../assets/images/p-98dc3a7a-5b07-4084-9156-101443846ce1.jpg'),
    'Portrait'
  ),
  mkItem(
    'i31',
    require('../assets/images/p-98f88e35-b619-4a0d-9d0c-d80d3a01b9cc.jpg'),
    'Abstract'
  ),
  mkItem(
    'i32',
    require('../assets/images/p-98fb1bc2-5c32-41c0-a1a0-bdc93f70846e.jpg'),
    'Nature'
  ),
  mkItem(
    'i33',
    require('../assets/images/p-99bb8611-7a4c-44fb-85bc-06ac11f7ef15.jpg'),
    'Landscape'
  ),
];

type Category = { label: string; accent: string };

const CATEGORIES: Category[] = [
  { label: 'All', accent: '#f2f2f2' },
  { label: 'Portrait', accent: CATEGORY_ACCENT.Portrait },
  { label: 'Nature', accent: CATEGORY_ACCENT.Nature },
  { label: 'Landscape', accent: CATEGORY_ACCENT.Landscape },
  { label: 'Architecture', accent: CATEGORY_ACCENT.Architecture },
  { label: 'Abstract', accent: CATEGORY_ACCENT.Abstract },
  { label: 'Animals', accent: CATEGORY_ACCENT.Animals },
  { label: 'Underwater', accent: CATEGORY_ACCENT.Underwater },
  { label: 'Sports', accent: CATEGORY_ACCENT.Sports },
];

// ── Gallery helpers ───────────────────────────────────────────────────────────

// Intrinsic aspect ratio of each bundled asset, resolved once at module load.
const RATIOS = new Map<string, number>(
  ITEMS.map((it) => {
    const src = Image.resolveAssetSource(it.source);
    return [it.id, src?.width && src?.height ? src.width / src.height : 1];
  })
);

const ratioFor = (it: Item) => RATIOS.get(it.id) ?? 1;

// Masonry column width derived from the grid layout:
//   gridContent.paddingHorizontal = 12  → 24 total side padding
//   columns.gap                   = 8   → 8 between the two columns
const GRID_H_PADDING = 12;
const COLUMN_GAP = 8;
const COLUMN_WIDTH =
  (Dimensions.get('window').width - GRID_H_PADDING * 2 - COLUMN_GAP) / 2;

// Per-item image height: column width divided by the asset's true ratio.
// Result: each card matches the image's natural aspect ratio → no crop,
// columns end up with different totals → masonry layout.
const heightFor = (it: Item) => COLUMN_WIDTH / ratioFor(it);

// 2-column masonry: drop each item into the currently shorter column,
// measuring column height as the running sum of 1/ratio (= height per unit width).
function splitColumns(items: Item[]): { left: Item[]; right: Item[] } {
  const left: Item[] = [];
  const right: Item[] = [];
  let leftH = 0;
  let rightH = 0;
  for (const it of items) {
    const h = 1 / ratioFor(it);
    if (leftH <= rightH) {
      left.push(it);
      leftH += h;
    } else {
      right.push(it);
      rightH += h;
    }
  }
  return { left, right };
}

// ── Card ──────────────────────────────────────────────────────────────────────

function MasonryCard({ item }: { item: Item }) {
  return (
    <View style={mc.root}>
      <Image
        source={item.source}
        style={[mc.image, { height: heightFor(item) }]}
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
    borderRadius: 16,
    overflow: 'hidden',
    marginBottom: 8,
    backgroundColor: C.surface,
  },
  image: {
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
  // topFade is a derived SharedValue that grows the top fade as the user
  // scrolls down, and is fed directly to AnimatedEdgeFadeView's `top` prop.
  //
  //   scrollY = 0   → topFade = 0    (at the very top, no top fade needed)
  //   scrollY = 120 → topFade = 120  (full 120 dp top fade — content above)

  const scrollY = useSharedValue(0);

  const onScroll = useAnimatedScrollHandler((event) => {
    scrollY.set(event.contentOffset.y);
  });

  const topFade = useDerivedValue(() =>
    interpolate(scrollY.value, [0, 120], [0, 120], Extrapolation.CLAMP)
  );

  const filtered =
    activeCategory === 'All'
      ? ITEMS
      : ITEMS.filter((i) => i.category === activeCategory);

  const { left: leftCol, right: rightCol } = splitColumns(filtered);

  return (
    <View style={s.screen}>
      {/* Header */}
      <View style={s.header}>
        <View>
          <Text style={s.title}>Discover</Text>
          <Text style={s.subtitle}>{ITEMS.length} pieces</Text>
        </View>
        <Pressable style={s.benchBtn} onPress={onBenchmark}>
          <Text style={s.benchText}>Benchmark</Text>
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
       * `top` is a SharedValue<number> — AnimatedEdgeFadeView routes it
       * through a UI-thread worklet so the fade depth updates without a JS
       * round-trip. `bottom` stays a static EdgeConfig with its own curve.
       */}
      <AnimatedEdgeFadeView
        top={topFade}
        bottom={{ size: 600, curve: 'smooth' }}
        mode="mask"
        curve="gentle"
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
              {leftCol.map((it) => (
                <MasonryCard key={it.id} item={it} />
              ))}
            </View>
            <View style={s.col}>
              {rightCol.map((it) => (
                <MasonryCard key={it.id} item={it} />
              ))}
            </View>
          </View>
        </Animated.ScrollView>
      </AnimatedEdgeFadeView>
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
