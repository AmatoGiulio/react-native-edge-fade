import { useState } from 'react';
import {
  Image,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  useWindowDimensions,
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
import { AnimatedEdgeFadeView, EdgeFadeView } from 'react-native-edge-fade';
import BenchmarkScreen from './BenchmarkScreen';

const COLORS = {
  bg: '#080808',
  surface: '#111111',
  border: '#1f1f1f',
  text: '#f5f5f5',
  subtle: '#8a8a8a',
  muted: '#343434',
};

const CATEGORY_ACCENT = {
  Architecture: '#60a5fa',
  Portrait: '#f472b6',
  Animals: '#34d399',
  Abstract: '#a78bfa',
  Nature: '#22c55e',
  Landscape: '#f59e0b',
  Sports: '#94a3b8',
  Underwater: '#06b6d4',
} as const;

type CategoryName = keyof typeof CATEGORY_ACCENT;

type GalleryItem = {
  id: string;
  source: ImageSourcePropType;
  category: CategoryName;
  accent: string;
};

type ResolvedAsset = {
  width?: number;
  height?: number;
};

type CategoryFilter = {
  label: 'All' | CategoryName;
  accent: string;
};

const photo = (
  id: string,
  source: ImageSourcePropType,
  category: CategoryName
): GalleryItem => ({
  id,
  source,
  category,
  accent: CATEGORY_ACCENT[category],
});

const ITEMS: GalleryItem[] = [
  photo(
    'i01',
    require('../assets/images/9ba092c8-2d9e-4614-8fb7-4774e45ab457.jpg'),
    'Architecture'
  ),
  photo(
    'i02',
    require('../assets/images/9c4345e2-63f4-4faa-b9ce-b1ee85d0c9d9.jpg'),
    'Portrait'
  ),
  photo(
    'i03',
    require('../assets/images/9d4529fe-f2ae-4ab2-a29f-f14fc999cf0c.jpg'),
    'Animals'
  ),
  photo(
    'i04',
    require('../assets/images/9d8a4f0e-81f3-425f-ab85-8efc7e3cb5b6.jpg'),
    'Abstract'
  ),
  photo(
    'i05',
    require('../assets/images/9d8a4f92-9cd1-47d0-8f7c-9090c35a99e9.jpg'),
    'Nature'
  ),
  photo(
    'i06',
    require('../assets/images/9d8a4fe4-aa56-4750-8bf3-d0dbc5a81a09.jpg'),
    'Portrait'
  ),
  photo(
    'i07',
    require('../assets/images/9d8a5043-09cd-408b-81b9-a62b15a1888c.jpg'),
    'Portrait'
  ),
  photo(
    'i08',
    require('../assets/images/9e242635-a789-410b-8a3c-4ea625b7beeb.jpg'),
    'Portrait'
  ),
  photo(
    'i09',
    require('../assets/images/9e3c7299-6b8f-47e6-a549-83caa9572864.jpg'),
    'Nature'
  ),
  photo(
    'i10',
    require('../assets/images/9e3e5add-485c-467d-827b-55cf402481d2.jpg'),
    'Abstract'
  ),
  photo(
    'i11',
    require('../assets/images/9ef3281a-a251-4733-b2b4-166429b9737b.jpg'),
    'Landscape'
  ),
  photo(
    'i12',
    require('../assets/images/9f256029-83b7-4cca-ab40-34aa82623fee.jpg'),
    'Sports'
  ),
  photo(
    'i13',
    require('../assets/images/9f4b5744-7ad6-42ae-a56a-c0a05830639c.jpg'),
    'Architecture'
  ),
  photo(
    'i14',
    require('../assets/images/9f6820da-c77a-49ce-9dd8-faf244f2b4d4.jpg'),
    'Portrait'
  ),
  photo(
    'i15',
    require('../assets/images/a0395656-c22e-4e94-9a49-480bbf5ca458.jpg'),
    'Landscape'
  ),
  photo(
    'i16',
    require('../assets/images/a03ad659-7fb7-454a-8636-c30e9f666810.jpg'),
    'Portrait'
  ),
  photo(
    'i17',
    require('../assets/images/a07e2d49-8e4b-4818-a83e-400c0218c788.jpg'),
    'Portrait'
  ),
  photo(
    'i18',
    require('../assets/images/a0ca83d6-d331-4b68-9f96-985ac8912b64.jpg'),
    'Nature'
  ),
  photo(
    'i19',
    require('../assets/images/a0d5d70b-21be-4bac-86de-c1fd44a04ae1.jpg'),
    'Abstract'
  ),
  photo(
    'i20',
    require('../assets/images/a0f1dd90-a6f4-4517-8b41-ad82bb2e96a8.jpg'),
    'Landscape'
  ),
  photo(
    'i21',
    require('../assets/images/a0f44c2b-de69-448d-9354-fad6324d4157.jpg'),
    'Architecture'
  ),
  photo(
    'i22',
    require('../assets/images/a110becf-fc2a-455b-b63c-8f97c8605331.jpg'),
    'Portrait'
  ),
  photo(
    'i23',
    require('../assets/images/a1124490-93df-41d7-86a8-b08bab3fbd60.jpg'),
    'Abstract'
  ),
  photo(
    'i24',
    require('../assets/images/a1135b99-8f51-49a0-aa8d-a91f5a2e9d71.jpg'),
    'Nature'
  ),
  photo(
    'i25',
    require('../assets/images/a1153da7-2686-4a2c-b575-2dbaec5c0fba.jpg'),
    'Abstract'
  ),
  photo(
    'i26',
    require('../assets/images/a120e421-b303-4145-be24-f3f8dc77da2b.jpg'),
    'Underwater'
  ),
  photo(
    'i27',
    require('../assets/images/a16ee596-df8e-4469-91d2-f39f162fb184.jpg'),
    'Nature'
  ),
  photo(
    'i28',
    require('../assets/images/p-9877b4d9-adbb-4bff-bc0b-4802f797d4ab.jpg'),
    'Portrait'
  ),
  photo(
    'i29',
    require('../assets/images/p-98946414-c532-4896-9311-a8eba52fa06d.jpg'),
    'Architecture'
  ),
  photo(
    'i30',
    require('../assets/images/p-98dc3a7a-5b07-4084-9156-101443846ce1.jpg'),
    'Portrait'
  ),
  photo(
    'i31',
    require('../assets/images/p-98f88e35-b619-4a0d-9d0c-d80d3a01b9cc.jpg'),
    'Abstract'
  ),
  photo(
    'i32',
    require('../assets/images/p-98fb1bc2-5c32-41c0-a1a0-bdc93f70846e.jpg'),
    'Nature'
  ),
  photo(
    'i33',
    require('../assets/images/p-99bb8611-7a4c-44fb-85bc-06ac11f7ef15.jpg'),
    'Landscape'
  ),
];

const CATEGORIES: CategoryFilter[] = [
  { label: 'All', accent: COLORS.text },
  ...Object.entries(CATEGORY_ACCENT).map(([label, accent]) => ({
    label: label as CategoryName,
    accent,
  })),
];

const imageResolver = (
  Image as typeof Image & {
    resolveAssetSource?: (source: ImageSourcePropType) => ResolvedAsset;
  }
).resolveAssetSource;

const assetRatio = new Map(
  ITEMS.map((item) => {
    const source =
      imageResolver?.(item.source) ??
      (typeof item.source === 'object' ? (item.source as ResolvedAsset) : null);
    const ratio =
      source?.width && source.height ? source.width / source.height : 1;

    return [item.id, ratio];
  })
);

const ratioFor = (item: GalleryItem) => assetRatio.get(item.id) ?? 1;

function splitColumns(items: GalleryItem[]) {
  const left: GalleryItem[] = [];
  const right: GalleryItem[] = [];
  let leftHeight = 0;
  let rightHeight = 0;

  for (const item of items) {
    const height = 1 / ratioFor(item);
    if (leftHeight <= rightHeight) {
      left.push(item);
      leftHeight += height;
    } else {
      right.push(item);
      rightHeight += height;
    }
  }

  return { left, right };
}

function GalleryCard({
  item,
  columnWidth,
}: {
  item: GalleryItem;
  columnWidth: number;
}) {
  return (
    <View style={card.root}>
      <Image
        source={item.source}
        style={[card.image, { height: columnWidth / ratioFor(item) }]}
      />
      <View style={card.meta}>
        <View style={[card.dot, { backgroundColor: item.accent }]} />
        <Text style={card.category}>{item.category.toUpperCase()}</Text>
      </View>
    </View>
  );
}

function CategoryPill({
  category,
  active,
  onPress,
}: {
  category: CategoryFilter;
  active: boolean;
  onPress: () => void;
}) {
  const activeStyle = active
    ? { backgroundColor: category.accent, borderColor: category.accent }
    : pill.inactive;
  const textColor = active
    ? category.label === 'All'
      ? COLORS.bg
      : COLORS.text
    : COLORS.subtle;

  return (
    <Pressable onPress={onPress} style={[pill.root, activeStyle]}>
      <Text style={[pill.text, { color: textColor }]}>{category.label}</Text>
    </Pressable>
  );
}

function DemoScreen({ onBenchmark }: { onBenchmark: () => void }) {
  const { width } = useWindowDimensions();
  const [activeCategory, setActiveCategory] =
    useState<CategoryFilter['label']>('All');
  const scrollY = useSharedValue(0);

  const columnWidth = (width - GRID_PADDING * 2 - COLUMN_GAP) / 2;
  const filteredItems =
    activeCategory === 'All'
      ? ITEMS
      : ITEMS.filter((item) => item.category === activeCategory);
  const columns = splitColumns(filteredItems);

  const onScroll = useAnimatedScrollHandler((event) => {
    scrollY.set(event.contentOffset.y);
  });

  const topFade = useDerivedValue(() =>
    interpolate(scrollY.value, [0, 120], [0, 120], Extrapolation.CLAMP)
  );

  return (
    <View style={styles.screen}>
      <View style={styles.header}>
        <View>
          <Text style={styles.title}>Discover</Text>
          <Text style={styles.subtitle}>{ITEMS.length} pieces</Text>
        </View>
        <Pressable style={styles.benchmarkButton} onPress={onBenchmark}>
          <Text style={styles.benchmarkText}>Benchmark</Text>
        </Pressable>
      </View>

      <EdgeFadeView
        left={120}
        right={120}
        curve="gentle"
        mode="overlay"
        color={COLORS.bg}
        style={styles.filterFrame}
      >
        <ScrollView
          horizontal
          nestedScrollEnabled
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.filterContent}
        >
          {CATEGORIES.map((category) => (
            <CategoryPill
              key={category.label}
              category={category}
              active={activeCategory === category.label}
              onPress={() => setActiveCategory(category.label)}
            />
          ))}
        </ScrollView>
      </EdgeFadeView>

      <AnimatedEdgeFadeView
        top={topFade}
        bottom={{ size: 220, curve: 'smooth' }}
        mode="mask"
        curve="gentle"
        style={styles.gridFrame}
      >
        <Animated.ScrollView
          showsVerticalScrollIndicator={false}
          contentContainerStyle={styles.gridContent}
          scrollEventThrottle={16}
          onScroll={onScroll}
        >
          <View style={styles.columns}>
            <View style={styles.column}>
              {columns.left.map((item) => (
                <GalleryCard
                  key={item.id}
                  item={item}
                  columnWidth={columnWidth}
                />
              ))}
            </View>
            <View style={styles.column}>
              {columns.right.map((item) => (
                <GalleryCard
                  key={item.id}
                  item={item}
                  columnWidth={columnWidth}
                />
              ))}
            </View>
          </View>
        </Animated.ScrollView>
      </AnimatedEdgeFadeView>
    </View>
  );
}

export default function App() {
  const [screen, setScreen] = useState<'demo' | 'benchmark'>('demo');

  return (
    <View style={styles.root}>
      {screen === 'demo' ? (
        <DemoScreen onBenchmark={() => setScreen('benchmark')} />
      ) : (
        <View style={styles.benchmarkRoot}>
          <Pressable
            style={styles.backButton}
            onPress={() => setScreen('demo')}
          >
            <Text style={styles.backText}>Back to Discover</Text>
          </Pressable>
          <BenchmarkScreen />
        </View>
      )}
    </View>
  );
}

const GRID_PADDING = 12;
const COLUMN_GAP = 8;

const card = StyleSheet.create({
  root: {
    borderRadius: 16,
    overflow: 'hidden',
    marginBottom: 8,
    backgroundColor: COLORS.surface,
  },
  image: {
    width: '100%',
    backgroundColor: '#0a0a0a',
  },
  meta: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    paddingHorizontal: 10,
    paddingVertical: 8,
    backgroundColor: COLORS.surface,
  },
  dot: {
    width: 6,
    height: 6,
    borderRadius: 3,
  },
  category: {
    fontSize: 9,
    fontWeight: '700',
    color: COLORS.text,
    letterSpacing: 0.8,
  },
});

const pill = StyleSheet.create({
  root: {
    paddingHorizontal: 16,
    paddingVertical: 7,
    borderRadius: 20,
    borderWidth: 1,
    marginRight: 8,
  },
  inactive: {
    backgroundColor: 'transparent',
    borderColor: COLORS.border,
  },
  text: {
    fontSize: 13,
    fontWeight: '600',
  },
});

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: COLORS.bg,
  },
  benchmarkRoot: {
    flex: 1,
  },
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
    color: COLORS.text,
  },
  subtitle: {
    fontSize: 13,
    color: COLORS.subtle,
    marginTop: 2,
  },
  benchmarkButton: {
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: COLORS.border,
  },
  benchmarkText: {
    fontSize: 12,
    color: COLORS.subtle,
    fontWeight: '500',
  },
  filterFrame: {
    height: 44,
    marginBottom: 16,
  },
  filterContent: {
    paddingHorizontal: 20,
    alignItems: 'center',
  },
  gridFrame: {
    flex: 1,
  },
  gridContent: {
    paddingHorizontal: GRID_PADDING,
    paddingBottom: 120,
  },
  columns: {
    flexDirection: 'row',
    gap: COLUMN_GAP,
  },
  column: {
    flex: 1,
  },
  backButton: {
    paddingTop: Platform.OS === 'android' ? 48 : 60,
    paddingHorizontal: 20,
    paddingBottom: 8,
  },
  backText: {
    fontSize: 14,
    color: COLORS.subtle,
    fontWeight: '500',
  },
});
