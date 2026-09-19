import { useState } from 'react';
import {
  PixelRatio,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from 'react-native';
import { Image } from 'expo-image';
import { Stack, router } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Animated, {
  Easing,
  interpolate,
  interpolateColor,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';
import { STILLS_ITEMS } from '@/data/catalog';

const ITEMS = STILLS_ITEMS.slice(0, 14);
const OPEN_MS = 560;
const BLUR_RADIUS_PX = 150;
const BLUR_RADIUS_DP = BLUR_RADIUS_PX / PixelRatio.get();
const CLOSE_MS = 460;
const EASE = Easing.bezier(0.16, 1, 0.3, 1);

const POSTS = [
  {
    id: 'afterglow',
    author: 'afterglow',
    meta: 'Nocturne Festival · Napoli',
    hero: ITEMS[0],
    side: ITEMS[2],
    caption: 'A new stage opens after midnight.',
  },
  {
    id: 'midnight-runway',
    author: 'midnight.runway',
    meta: 'SS27 · Guest installation',
    grid: [ITEMS[3], ITEMS[4], ITEMS[5], ITEMS[6]],
    caption: 'Fashion film, live score, one room.',
  },
  {
    id: 'signal-room',
    author: 'signal.room',
    meta: 'Warehouse 24 · Live session',
    hero: ITEMS[7] ?? ITEMS[1],
    side: ITEMS[8] ?? ITEMS[0],
    caption: 'Doors 23:30 · limited capacity.',
  },
];

export default function ProgressiveShowcaseRoute() {
  const insets = useSafeAreaInsets();
  const { height } = useWindowDimensions();

  const [open, setOpen] = useState(false);
  const [dark, setDark] = useState(false);

  const progress = useSharedValue(0);
  const themeProgress = useSharedValue(0);
  const bottomDepth = useSharedValue(122);

  const expandedDepth = Math.min(height * 0.57, 520);

  const surfaceStyle = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['#f4f4f2', '#101010']
    ),
  }));

  const menuStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0.12, 0.42, 1], [0, 0.2, 1]),
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [22, 0]) },
    ],
  }));

  const collapsedLabelStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0, 0.34, 0.5], [1, 0.28, 0]),
  }));

  const togglePanel = () => {
    const next = !open;
    setOpen(next);
    const duration = next ? OPEN_MS : CLOSE_MS;

    progress.value = withTiming(next ? 1 : 0, {
      duration,
      easing: EASE,
    });

    bottomDepth.value = withTiming(next ? expandedDepth : 122, {
      duration,
      easing: EASE,
    });
  };

  const setTheme = (nextDark: boolean) => {
    setDark(nextDark);
    themeProgress.value = withTiming(nextDark ? 1 : 0, {
      duration: 420,
      easing: EASE,
    });
  };

  const fg = dark ? '#f7f7f5' : '#151515';
  const muted = dark ? 'rgba(255,255,255,0.52)' : 'rgba(0,0,0,0.48)';
  const divider = dark ? '#232323' : '#dfdfdc';

  return (
    <Animated.View style={[s.page, surfaceStyle]}>
      <Stack.Screen options={{ headerShown: false }} />

      <AnimatedEdgeFadeView
        mode="blur"
        top={0}
        bottom={bottomDepth}
        left={0}
        right={0}
        curve="smooth"
        blurRadius={BLUR_RADIUS_DP}
        blurProgression={1}
        style={StyleSheet.absoluteFill}
      >
        <ScrollView
          style={StyleSheet.absoluteFill}
          contentContainerStyle={[
            s.scrollContent,
            {
              paddingTop: insets.top + 14,
              paddingBottom: insets.bottom + 190,
            },
          ]}
          showsVerticalScrollIndicator={false}
          bounces
          overScrollMode="never"
        >
          <View style={s.topbar}>
            <Text style={[s.brand, { color: fg }]}>NOCTURNE</Text>
            <View style={s.topActions}>
              <Text style={[s.topAction, { color: fg }]}>○</Text>
              <Pressable
                hitSlop={12}
                onPress={() => router.back()}
                accessibilityRole="button"
                accessibilityLabel="Close"
              >
                <Text style={[s.close, { color: fg }]}>×</Text>
              </Pressable>
            </View>
          </View>

          <Text style={[s.sectionKicker, { color: muted }]}>FRIDAY · 23:30</Text>
          <Text style={[s.sectionTitle, { color: fg }]}>
            Music, fashion and image after dark.
          </Text>

          {POSTS.map((post, postIndex) => (
            <View key={post.id}>
              <View style={s.profileRow}>
                <Image
                  source={(ITEMS[(postIndex + 9) % ITEMS.length] ?? ITEMS[0])!.source}
                  style={s.avatar}
                  contentFit="cover"
                />
                <View style={s.profileText}>
                  <Text style={[s.handle, { color: fg }]}>{post.author}</Text>
                  <Text style={[s.byline, { color: muted }]}>{post.meta}</Text>
                </View>
                <Text style={[s.more, { color: muted }]}>•••</Text>
              </View>

              {'grid' in post && post.grid ? (
                <View style={s.grid}>
                  {post.grid.map((item, index) => (
                    <Image
                      key={item!.id}
                      source={item!.source}
                      style={index === 0 ? s.gridWide : s.gridTile}
                      contentFit="cover"
                    />
                  ))}
                </View>
              ) : (
                <View style={s.heroRow}>
                  <Image source={post.hero!.source} style={s.heroLarge} contentFit="cover" />
                  <Image source={post.side!.source} style={s.heroSmall} contentFit="cover" />
                </View>
              )}

              <Text style={[s.caption, { color: muted }]}>{post.caption}</Text>
              <View style={[s.divider, { backgroundColor: divider }]} />
            </View>
          ))}
        </ScrollView>
      </AnimatedEdgeFadeView>

      <Animated.View
        pointerEvents="none"
        style={[
          s.milkyVeil,
          { bottom: 0 },
          veilStyle,
        ]}
      />

      <Animated.View
        pointerEvents={open ? 'auto' : 'none'}
        style={[
          s.menu,
          { bottom: insets.bottom + 80 },
          menuStyle,
        ]}
      >
        <Image
          source={(ITEMS[10] ?? ITEMS[3])!.source}
          style={s.menuAvatar}
          contentFit="cover"
        />
        <Text style={s.menuItem}>Lineup</Text>
        <Text style={s.menuItem}>Schedule</Text>
        <Text style={s.menuItem}>Passes</Text>
        <Text style={s.menuItem}>Archive</Text>
      </Animated.View>

      <View style={[s.bottomBar, { bottom: insets.bottom + 12 }]}>
        <View style={s.segmented}>
          <Pressable
            onPress={() => setTheme(true)}
            style={[s.segment, dark && s.segmentActive]}
          >
            <Text style={[s.segmentText, dark && s.segmentTextActive]}>Dark</Text>
          </Pressable>
          <Pressable
            onPress={() => setTheme(false)}
            style={[s.segment, !dark && s.segmentActive]}
          >
            <Text style={[s.segmentText, !dark && s.segmentTextActive]}>Light</Text>
          </Pressable>
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={open ? 'Close menu' : 'Open menu'}
          onPress={togglePanel}
          style={s.menuTrigger}
        >
          <Animated.Text style={[s.menuTriggerLabel, collapsedLabelStyle]}>
            MENU
          </Animated.Text>
          <Text style={s.menuTriggerIcon}>{open ? '↓' : '↑'}</Text>
        </Pressable>
      </View>
    </Animated.View>
  );
}

const s = StyleSheet.create({
  page: {
    flex: 1,
  },
  scrollContent: {
    paddingHorizontal: 18,
  },
  topbar: {
    height: 42,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  brand: {
    fontSize: 12,
    fontWeight: '800',
    letterSpacing: 1.4,
  },
  topActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
  },
  topAction: {
    fontSize: 19,
    lineHeight: 20,
  },
  close: {
    fontSize: 24,
    lineHeight: 26,
    fontWeight: '300',
  },
  sectionKicker: {
    marginTop: 18,
    fontSize: 9,
    lineHeight: 12,
    letterSpacing: 1.5,
    fontWeight: '700',
  },
  sectionTitle: {
    marginTop: 6,
    marginBottom: 18,
    maxWidth: 280,
    fontSize: 25,
    lineHeight: 28,
    letterSpacing: -0.8,
    fontWeight: '700',
  },
  profileRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 8,
    marginBottom: 11,
  },
  avatar: {
    width: 27,
    height: 27,
    borderRadius: 13.5,
  },
  profileText: {
    flex: 1,
    marginLeft: 9,
  },
  handle: {
    fontSize: 11,
    lineHeight: 13,
    fontWeight: '700',
  },
  byline: {
    marginTop: 1,
    fontSize: 8.5,
    lineHeight: 10.5,
  },
  more: {
    fontSize: 9,
    letterSpacing: 1,
  },
  heroRow: {
    height: 205,
    flexDirection: 'row',
    gap: 8,
  },
  heroLarge: {
    flex: 1.55,
    borderRadius: 12,
  },
  heroSmall: {
    flex: 0.82,
    borderRadius: 12,
  },
  grid: {
    height: 234,
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  gridWide: {
    width: '59%',
    height: 113,
    borderRadius: 12,
  },
  gridTile: {
    flexGrow: 1,
    width: '36%',
    height: 113,
    borderRadius: 12,
  },
  caption: {
    marginTop: 10,
    fontSize: 8.5,
    lineHeight: 12,
    letterSpacing: 0.2,
  },
  divider: {
    height: StyleSheet.hairlineWidth,
    marginTop: 16,
    marginBottom: 7,
  },
  menu: {
    position: 'absolute',
    left: 24,
    right: 24,
    zIndex: 20,
  },
  menuAvatar: {
    width: 28,
    height: 28,
    borderRadius: 14,
    marginBottom: 15,
  },
  menuItem: {
    color: '#fff',
    fontSize: 13,
    lineHeight: 23,
    fontWeight: '600',
  },
  bottomBar: {
    position: 'absolute',
    left: 22,
    right: 22,
    height: 52,
    zIndex: 30,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  segmented: {
    height: 34,
    padding: 3,
    borderRadius: 17,
    backgroundColor: 'rgba(18,18,18,0.46)',
    flexDirection: 'row',
  },
  segment: {
    minWidth: 57,
    paddingHorizontal: 13,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 14,
  },
  segmentActive: {
    backgroundColor: 'rgba(255,255,255,0.94)',
  },
  segmentText: {
    color: 'rgba(255,255,255,0.66)',
    fontSize: 10,
    fontWeight: '600',
  },
  segmentTextActive: {
    color: '#151515',
  },
  menuTrigger: {
    minWidth: 72,
    height: 34,
    paddingHorizontal: 12,
    borderRadius: 17,
    backgroundColor: 'rgba(18,18,18,0.46)',
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: 6,
  },
  menuTriggerLabel: {
    color: '#fff',
    fontSize: 8,
    fontWeight: '700',
    letterSpacing: 1.2,
  },
  menuTriggerIcon: {
    color: '#fff',
    fontSize: 12,
    fontWeight: '700',
  },
});
