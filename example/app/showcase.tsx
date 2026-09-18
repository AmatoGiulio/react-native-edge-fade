import { useState } from 'react';
import {
  Pressable,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
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

const FEED = STILLS_ITEMS.slice(0, 8);
const OPEN_MS = 560;
const CLOSE_MS = 460;
const EASE = Easing.bezier(0.16, 1, 0.3, 1);

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
      ['#f4f4f2', '#111111']
    ),
  }));

  const feedStyle = useAnimatedStyle(() => ({
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [0, -8]) },
      { scale: interpolate(progress.value, [0, 1], [1, 0.995]) },
    ],
  }));

  const menuStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0.18, 0.48, 1], [0, 0.18, 1]),
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [26, 0]) },
    ],
  }));

  const collapsedLabelStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0, 0.32, 0.5], [1, 0.3, 0]),
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [0, -10]) },
    ],
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
        blurRadius={150}
        blurProgression={1}
        style={StyleSheet.absoluteFill}
      >
        <Animated.View
          style={[
            s.content,
            { paddingTop: insets.top + 18 },
            feedStyle,
          ]}
        >
          <View style={s.topbar}>
            <Text style={[s.brand, { color: fg }]}>edge fade</Text>
            <Pressable
              hitSlop={12}
              onPress={() => router.back()}
              accessibilityRole="button"
              accessibilityLabel="Close showcase"
            >
              <Text style={[s.close, { color: fg }]}>×</Text>
            </Pressable>
          </View>

          <View style={s.profileRow}>
            <Image source={FEED[6]!.source} style={s.avatar} contentFit="cover" />
            <View style={s.profileText}>
              <Text style={[s.handle, { color: fg }]}>progressive blur</Text>
              <Text style={[s.byline, { color: muted }]}>react-native-edge-fade</Text>
            </View>
            <Text style={[s.more, { color: muted }]}>•••</Text>
          </View>

          <View style={s.heroRow}>
            <Image source={FEED[0]!.source} style={s.heroLarge} contentFit="cover" />
            <Image source={FEED[2]!.source} style={s.heroSmall} contentFit="cover" />
          </View>

          <View style={[s.divider, { backgroundColor: dark ? '#232323' : '#dfdfdc' }]} />

          <View style={s.profileRow}>
            <Image source={FEED[4]!.source} style={s.avatar} contentFit="cover" />
            <View style={s.profileText}>
              <Text style={[s.handle, { color: fg }]}>native rendering</Text>
              <Text style={[s.byline, { color: muted }]}>Gaussian · progressive · fast</Text>
            </View>
            <Text style={[s.more, { color: muted }]}>•••</Text>
          </View>

          <View style={s.grid}>
            {FEED.slice(3, 7).map((item, index) => (
              <Image
                key={item.id}
                source={item.source}
                style={index === 0 ? s.gridWide : s.gridTile}
                contentFit="cover"
              />
            ))}
          </View>

          <Text style={[s.caption, { color: muted }]}>
            Progressive Gaussian blur · Android · iOS
          </Text>
        </Animated.View>
      </AnimatedEdgeFadeView>

      <Animated.View
        pointerEvents={open ? 'auto' : 'none'}
        style={[
          s.menu,
          { bottom: insets.bottom + 76 },
          menuStyle,
        ]}
      >
        <Image source={FEED[7]!.source} style={s.menuAvatar} contentFit="cover" />

        <Text style={s.menuItem}>Progressive blur</Text>
        <Text style={s.menuItem}>Renderer</Text>
        <Text style={s.menuItem}>Gallery</Text>
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
          accessibilityLabel={open ? 'Collapse menu' : 'Expand menu'}
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
  content: {
    flex: 1,
    paddingHorizontal: 22,
  },
  topbar: {
    height: 44,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  brand: {
    fontSize: 13,
    fontWeight: '700',
    letterSpacing: -0.2,
  },
  close: {
    fontSize: 24,
    lineHeight: 26,
    fontWeight: '300',
  },
  profileRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 18,
    marginBottom: 12,
  },
  avatar: {
    width: 28,
    height: 28,
    borderRadius: 14,
  },
  profileText: {
    flex: 1,
    marginLeft: 9,
  },
  handle: {
    fontSize: 12,
    lineHeight: 14,
    fontWeight: '700',
  },
  byline: {
    marginTop: 1,
    fontSize: 9,
    lineHeight: 11,
  },
  more: {
    fontSize: 10,
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
  divider: {
    height: StyleSheet.hairlineWidth,
    marginTop: 18,
    marginBottom: 1,
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
    marginTop: 12,
    fontSize: 9,
    letterSpacing: 0.3,
  },
  menu: {
    position: 'absolute',
    left: 24,
    right: 24,
  },
  menuAvatar: {
    width: 28,
    height: 28,
    borderRadius: 14,
    marginBottom: 18,
  },
  menuItem: {
    color: '#fff',
    fontSize: 13,
    lineHeight: 22,
    fontWeight: '600',
  },
  bottomBar: {
    position: 'absolute',
    left: 24,
    right: 24,
    height: 52,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  segmented: {
    height: 34,
    padding: 3,
    borderRadius: 17,
    backgroundColor: 'rgba(18,18,18,0.48)',
    flexDirection: 'row',
  },
  segment: {
    minWidth: 58,
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
    backgroundColor: 'rgba(18,18,18,0.48)',
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
