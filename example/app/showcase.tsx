import { useState } from 'react';
import {
  PixelRatio,
  Pressable,
  ScrollView,
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
  useAnimatedStyle,
  useDerivedValue,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';
import { STILLS_ITEMS } from '@/data/catalog';

const ProgressiveFade = AnimatedEdgeFadeView as any;

const ITEMS = STILLS_ITEMS.slice(0, 18);
const BLUR_RADIUS_PX = 128;
const BLUR_RADIUS_DP = BLUR_RADIUS_PX / PixelRatio.get();
const CLOSED_DEPTH = 112;
const OPEN_MS = 500;
const CLOSE_MS = 420;
const EASE = Easing.bezier(0.16, 1, 0.3, 1);

// The compositor uses a true quarter-resolution Kawase diffusion surface.
// Keep the source colour/luminance neutral and use smootherstep for the spatial
// reveal so the blurred backdrop does not appear as a bright halo before it is
// actually needed.
const REFERENCE_COMPOSITOR_CURVE = 'smoother' as const;

const TOP_STORIES = [
  {
    id: 'story-1',
    type: 'SCENE REPORT',
    date: 'September 19, 2026',
    title: 'Rome After Midnight: A New Electronic Underground',
    image: ITEMS[0],
  },
  {
    id: 'story-2',
    type: 'FEATURES',
    date: 'September 18, 2026',
    title: 'Inside Ostiense’s New Listening Rooms',
    image: ITEMS[4],
  },
];

const LATEST = [
  {
    id: 'latest-1',
    type: 'MIX',
    title: 'Nocturne 04 — Roman Electronics',
    body: 'A slow-burn selection moving from ambient pressure to warehouse rhythm.',
    image: ITEMS[2],
  },
  {
    id: 'latest-2',
    type: 'DESIGN',
    title: 'Light Studies From San Lorenzo',
    body: 'Independent studios exploring projection, typography and low-light spaces.',
    image: ITEMS[6],
  },
  {
    id: 'latest-3',
    type: 'LIVE',
    title: 'A Warehouse Set in Ostiense',
    body: 'Extended sets, live visuals and a room designed around a single system.',
    image: ITEMS[8],
  },
  {
    id: 'latest-4',
    type: 'SCENE',
    title: 'Small Rooms, Long Nights',
    body: 'Four intimate spaces keeping Rome’s after-hours culture deliberately small.',
    image: ITEMS[12],
  },
];

export default function ProgressiveShowcaseRoute() {
  const insets = useSafeAreaInsets();
  const { height, width } = useWindowDimensions();

  const [open, setOpen] = useState(false);
  const progress = useSharedValue(0);
  const bottomDepth = useSharedValue(CLOSED_DEPTH);

  // The reference's blur field begins materially higher than the current demo.
  // Keep the measured airy curve/ramp unchanged and move the whole field upward
  // instead of distorting the radius transfer again.
  const expandedDepth = Math.min(height * 0.78, 720);
  const storyWidth = Math.min(Math.max(width * 0.78, 268), 350);
  const openRampDepth = storyWidth / 1.58;

  // The reference transition occupies roughly one hero-image height. The field
  // itself extends to the bottom of the screen, but the radius reaches maximum
  // over this local ramp instead of evolving across the entire panel.
  const blurProgression = useDerivedValue(() => {
    const rampDepth = interpolate(
      progress.value,
      [0, 1],
      [CLOSED_DEPTH, openRampDepth]
    );
    return Math.min(1, rampDepth / Math.max(bottomDepth.value, 1));
  });

  const panelStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0.12, 0.42, 1], [0, 0.18, 1]),
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [28, 0]) },
    ],
  }));

  const arrowStyle = useAnimatedStyle(() => ({
    transform: [
      { rotate: `${interpolate(progress.value, [0, 1], [0, 180])}deg` },
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

    const nextDepth = next ? expandedDepth : CLOSED_DEPTH;

    bottomDepth.value = withTiming(nextDepth, {
      duration,
      easing: EASE,
    });
  };

  return (
    <View style={s.page}>
      <Stack.Screen options={{ headerShown: false }} />

      <ProgressiveFade
        mode="blur"
        top={0}
        bottom={bottomDepth}
        left={0}
        right={0}
        curve={REFERENCE_COMPOSITOR_CURVE}
        blurRadius={BLUR_RADIUS_DP}
        blurProgression={blurProgression}
        progressiveBackend="compositor"
        style={[StyleSheet.absoluteFill, s.fadeHost]}
      >
        <ScrollView
          style={StyleSheet.absoluteFill}
          contentContainerStyle={[
            s.scrollContent,
            {
              paddingTop: insets.top + 6,
              paddingBottom: insets.bottom + 190,
            },
          ]}
          showsVerticalScrollIndicator={false}
          bounces
          overScrollMode="never"
        >
          <View style={s.header}>
            <View style={s.wordmarkWrap}>
              <View style={s.wordmarkSlash} />
              <Text style={s.wordmark}>ROMA DAILY</Text>
            </View>

            <Pressable
              onPress={() => router.back()}
              hitSlop={14}
              accessibilityRole="button"
              accessibilityLabel="Close"
            >
              <Text style={s.close}>×</Text>
            </Pressable>
          </View>

          <View style={s.sectionHeader}>
            <Text style={s.sectionTitle}>Top Stories</Text>
          </View>

          <ScrollView
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={s.topStoriesRow}
          >
            {TOP_STORIES.map((story) => (
              <View key={story.id} style={[s.topStoryCard, { width: storyWidth }]}>
                <Image
                  source={story.image!.source}
                  style={s.topStoryImage}
                  contentFit="cover"
                />

                <Text style={s.meta}>
                  {story.type} · {story.date}
                </Text>
                <Text style={s.topStoryTitle}>{story.title}</Text>
              </View>
            ))}
          </ScrollView>

          <View style={[s.sectionHeader, s.latestHeader]}>
            <Text style={s.sectionTitle}>Latest</Text>
            <Text style={s.sectionLink}>see all stories</Text>
          </View>

          <View style={s.latestList}>
            {LATEST.map((item) => (
              <View key={item.id} style={s.latestRow}>
                <Image
                  source={item.image!.source}
                  style={s.latestImage}
                  contentFit="cover"
                />

                <View style={s.latestCopy}>
                  <Text style={s.latestMeta}>{item.type}</Text>
                  <Text style={s.latestTitle}>{item.title}</Text>
                  <Text style={s.latestBody}>{item.body}</Text>
                </View>
              </View>
            ))}
          </View>

          <View style={s.editorialBlock}>
            <Text style={s.editorialEyebrow}>TONIGHT IN ROME</Text>
            <Text style={s.editorialTitle}>
              Sound, image and concrete after dark.
            </Text>
            <Text style={s.editorialBody}>
              Three spaces, four live sets and a visual programme moving from
              Ostiense to San Lorenzo until sunrise.
            </Text>
          </View>
        </ScrollView>
      </ProgressiveFade>

      <Animated.View
        pointerEvents={open ? 'auto' : 'none'}
        style={[s.panel, { bottom: insets.bottom + 82 }, panelStyle]}
      >
        <Text style={s.panelKicker}>TONIGHT</Text>
        <Text style={s.panelTitle}>ROMA AFTER DARK</Text>

        <View style={s.panelRule} />

        <View style={s.panelRow}>
          <Text style={s.panelTime}>23:30</Text>
          <View style={s.panelCopy}>
            <Text style={s.panelName}>Forma — Warehouse Edition</Text>
            <Text style={s.panelMeta}>Ostiense · live AV · extended sets</Text>
          </View>
        </View>

        <View style={s.panelRow}>
          <Text style={s.panelTime}>01:00</Text>
          <View style={s.panelCopy}>
            <Text style={s.panelName}>Nocturne — Room II</Text>
            <Text style={s.panelMeta}>San Lorenzo · techno · installation</Text>
          </View>
        </View>

        <View style={s.panelRow}>
          <Text style={s.panelTime}>03:30</Text>
          <View style={s.panelCopy}>
            <Text style={s.panelName}>After — Secret Location</Text>
            <Text style={s.panelMeta}>list only · limited capacity</Text>
          </View>
        </View>
      </Animated.View>

      <View style={[s.bottomBar, { bottom: insets.bottom + 12 }]}>
        <Text style={s.bottomLabel}>ROME / 19.09.26</Text>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={open ? 'Close tonight panel' : 'Open tonight panel'}
          onPress={togglePanel}
          style={s.trigger}
        >
          <Text style={s.triggerText}>TONIGHT</Text>
          <Animated.Text style={[s.triggerArrow, arrowStyle]}>↑</Animated.Text>
        </Pressable>
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  page: {
    flex: 1,
    backgroundColor: '#efeeec',
  },
  fadeHost: {
    // The compositor must own the same opaque surface that is visible behind
    // its children. Previously the beige lived only on the parent, so the blur
    // source was transparent and large dark cards blurred into transparent black.
    backgroundColor: '#efeeec',
  },
  scrollContent: {
    minHeight: '100%',
    paddingBottom: 180,
  },
  header: {
    height: 54,
    paddingHorizontal: 18,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  wordmarkWrap: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
  },
  wordmarkSlash: {
    width: 18,
    height: 8,
    backgroundColor: '#111',
    transform: [{ skewX: '-24deg' }],
  },
  wordmark: {
    color: '#111',
    fontSize: 12,
    fontWeight: '800',
    letterSpacing: 0.2,
  },
  close: {
    color: '#111',
    fontSize: 24,
    lineHeight: 26,
    fontWeight: '300',
  },
  sectionHeader: {
    paddingHorizontal: 18,
    marginTop: 30,
    marginBottom: 16,
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: 14,
  },
  sectionTitle: {
    color: '#171717',
    fontSize: 34,
    lineHeight: 36,
    fontWeight: '300',
    fontStyle: 'italic',
    letterSpacing: -1.3,
  },
  sectionLink: {
    marginBottom: 4,
    color: '#8d8b88',
    fontSize: 10,
    lineHeight: 12,
  },
  topStoriesRow: {
    paddingHorizontal: 18,
    paddingRight: 28,
    gap: 14,
  },
  topStoryCard: {
    flexShrink: 0,
  },
  topStoryImage: {
    width: '100%',
    aspectRatio: 1.58,
    borderRadius: 8,
    backgroundColor: '#dddcd9',
  },
  meta: {
    marginTop: 8,
    color: '#96938f',
    fontSize: 8.5,
    lineHeight: 10.5,
    fontWeight: '700',
    letterSpacing: 0.2,
  },
  topStoryTitle: {
    marginTop: 4,
    color: '#111',
    fontSize: 16,
    lineHeight: 19,
    fontWeight: '500',
    letterSpacing: -0.2,
  },
  latestHeader: {
    marginTop: 44,
  },
  latestList: {
    paddingHorizontal: 18,
  },
  latestRow: {
    minHeight: 116,
    flexDirection: 'row',
    gap: 14,
    paddingVertical: 13,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#d5d3cf',
  },
  latestImage: {
    width: '39%',
    aspectRatio: 1.3,
    borderRadius: 5,
    backgroundColor: '#dddcd9',
  },
  latestCopy: {
    flex: 1,
    paddingTop: 1,
  },
  latestMeta: {
    color: '#96938f',
    fontSize: 8,
    lineHeight: 10,
    fontWeight: '700',
    letterSpacing: 0.4,
  },
  latestTitle: {
    marginTop: 4,
    color: '#111',
    fontSize: 15,
    lineHeight: 18,
    fontWeight: '500',
    letterSpacing: -0.2,
  },
  latestBody: {
    marginTop: 6,
    color: '#5c5955',
    fontSize: 9.5,
    lineHeight: 13.5,
  },
  editorialBlock: {
    marginHorizontal: 18,
    marginTop: 48,
    paddingTop: 18,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#c9c7c3',
  },
  editorialEyebrow: {
    color: '#8f8c87',
    fontSize: 8,
    lineHeight: 10,
    fontWeight: '800',
    letterSpacing: 1,
  },
  editorialTitle: {
    marginTop: 7,
    maxWidth: 310,
    color: '#111',
    fontSize: 27,
    lineHeight: 29,
    fontWeight: '400',
    letterSpacing: -0.8,
  },
  editorialBody: {
    marginTop: 10,
    maxWidth: 330,
    color: '#4b4946',
    fontSize: 11,
    lineHeight: 16,
  },
  panel: {
    position: 'absolute',
    left: 22,
    right: 22,
    zIndex: 20,
  },
  panelKicker: {
    color: 'rgba(255,255,255,0.62)',
    fontSize: 8,
    fontWeight: '800',
    letterSpacing: 1.4,
  },
  panelTitle: {
    marginTop: 5,
    color: '#fff',
    fontSize: 25,
    lineHeight: 27,
    fontWeight: '600',
    letterSpacing: -0.6,
  },
  panelRule: {
    height: StyleSheet.hairlineWidth,
    backgroundColor: 'rgba(255,255,255,0.22)',
    marginVertical: 14,
  },
  panelRow: {
    flexDirection: 'row',
    gap: 14,
    paddingVertical: 9,
  },
  panelTime: {
    width: 44,
    color: 'rgba(255,255,255,0.5)',
    fontSize: 10,
    lineHeight: 12,
    fontWeight: '700',
  },
  panelCopy: {
    flex: 1,
  },
  panelName: {
    color: '#fff',
    fontSize: 13,
    lineHeight: 16,
    fontWeight: '600',
  },
  panelMeta: {
    marginTop: 2,
    color: 'rgba(255,255,255,0.54)',
    fontSize: 9,
    lineHeight: 12,
  },
  bottomBar: {
    position: 'absolute',
    left: 22,
    right: 22,
    height: 50,
    zIndex: 30,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  bottomLabel: {
    color: '#fff',
    fontSize: 8,
    fontWeight: '800',
    letterSpacing: 1,
  },
  trigger: {
    height: 34,
    paddingHorizontal: 12,
    borderRadius: 17,
    backgroundColor: 'rgba(0,0,0,0.34)',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  triggerText: {
    color: '#fff',
    fontSize: 8,
    fontWeight: '800',
    letterSpacing: 1.15,
  },
  triggerArrow: {
    color: '#fff',
    fontSize: 11,
    fontWeight: '700',
  },
});
