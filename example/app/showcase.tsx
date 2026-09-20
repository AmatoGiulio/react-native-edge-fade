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
import { Stack, router, useLocalSearchParams } from 'expo-router';
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

// Reference-matched scene palette: use warm/pink/blue imagery so the blur is
// evaluated against the same kind of colourful source material as the video.
const ITEMS = STILLS_ITEMS;
const DEFAULT_BLUR_RADIUS_PX = 150;
// Demo-only material extinction measured by eye against reference.mp4.
// Blur remains pure everywhere else because the native default is strength=0.
const DEFAULT_MATERIAL_STRENGTH = 0.16;
const MATERIAL_TONES: Record<string, string> = {
  light: '#e3e0dc',
  warm: '#cec8c3',
  smoke: '#bbb6b2',
};
const DEFAULT_MATERIAL_TONE = 'smoke';
// Stage 6 winner: 0.90 compresses the white shoulder close to the reference
// without crushing the retained coloured low-frequency structure.
const DEFAULT_MATERIAL_EXPOSURE = 0.90;
// Keep the compact closed footer dense enough to extinguish the dark card.
// Stage 9 open-surface sweep: s84 best matches the reference balance — it
// removes the low-frequency dark-card mass without flattening the local colour
// and translucency into the gray slab visible at s90–s95.
const DEFAULT_CLOSED_MATERIAL_SURFACE = 0.95;
const DEFAULT_OPEN_MATERIAL_SURFACE = 0.84;
// Stage 8 full-frame winner:
// - closed: g100 preserves the compact translucent footer without a gray slab
// - open: g44 puts the material takeover at the same vertical band as the ref
const DEFAULT_CLOSED_MATERIAL_SURFACE_PROGRESSION = 1.0;
const DEFAULT_OPEN_MATERIAL_SURFACE_PROGRESSION = 0.44;
const DEFAULT_CLOSED_DEPTH = 112;
const DEFAULT_OPEN_PROGRESSION = 1.0;
const DEFAULT_EXPANDED_SCALE = 0.78;

function clampNumber(
  value: string | undefined,
  fallback: number,
  min: number,
  max: number
) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}
const OPEN_MS = 500;
const CLOSE_MS = 420;
const EASE = Easing.bezier(0.16, 1, 0.3, 1);

// Measured reference profile. Keep the endpoints smooth: the previous
// radius-domain remap introduced two visible knees (hard onset + hard finish).
// The improved lower coverage comes from the deeper expanded field, not from
// those knees, so retain the 66% field while restoring the airy t^1.79 ramp.
const REFERENCE_BLUR_CURVE = {
  type: 'stops' as const,
  values: [
    1.0,
    0.9883,
    0.9595,
    0.9163,
    0.8599,
    0.7912,
    0.7107,
    0.6188,
    0.5159,
    0.4023,
    0.2783,
    0.1442,
    0.0,
  ],
};

const TOP_STORIES = [
  {
    id: 'story-1',
    type: 'SCENE REPORT',
    date: 'September 19, 2026',
    title: 'Rome After Midnight: A New Electronic Underground',
    image: ITEMS[8],
  },
  {
    id: 'story-2',
    type: 'FEATURES',
    date: 'September 18, 2026',
    title: 'Inside Ostiense’s New Listening Rooms',
    image: ITEMS[26],
  },
];

const LATEST = [
  {
    id: 'latest-1',
    type: 'MIX',
    title: 'Nocturne 04 — Roman Electronics',
    body: 'A slow-burn selection moving from ambient pressure to warehouse rhythm.',
    image: ITEMS[27],
  },
  {
    id: 'latest-2',
    type: 'DESIGN',
    title: 'Light Studies From San Lorenzo',
    body: 'Independent studios exploring projection, typography and low-light spaces.',
    image: ITEMS[28],
  },
  {
    id: 'latest-3',
    type: 'LIVE',
    title: 'A Warehouse Set in Ostiense',
    body: 'Extended sets, live visuals and a room designed around a single system.',
    image: ITEMS[16],
  },
  {
    id: 'latest-4',
    type: 'SCENE',
    title: 'Small Rooms, Long Nights',
    body: 'Four intimate spaces keeping Rome’s after-hours culture deliberately small.',
    image: ITEMS[8],
  },
];

export default function ProgressiveShowcaseRoute() {
  const insets = useSafeAreaInsets();
  const { height, width } = useWindowDimensions();
  const params = useLocalSearchParams<{ bench?: string | string[] }>();

  const benchParam = Array.isArray(params.bench) ? params.bench[0] : params.bench;
  const [
    materialRaw,
    progressionRaw,
    radiusRaw,
    depthRaw,
    scaleRaw,
    toneRaw,
    exposureRaw,
    surfaceRaw,
    surfaceProgressionRaw,
  ] = (benchParam ?? '').split(',');

  const materialStrength = clampNumber(
    materialRaw,
    DEFAULT_MATERIAL_STRENGTH,
    0,
    1
  );
  const openProgression = clampNumber(
    progressionRaw,
    DEFAULT_OPEN_PROGRESSION,
    0.2,
    1
  );
  const blurRadiusPx = clampNumber(
    radiusRaw,
    DEFAULT_BLUR_RADIUS_PX,
    1,
    150
  );
  const closedDepth = clampNumber(
    depthRaw,
    DEFAULT_CLOSED_DEPTH,
    48,
    240
  );
  const expandedScale = clampNumber(
    scaleRaw,
    DEFAULT_EXPANDED_SCALE,
    0.45,
    0.9
  );
  const materialTone =
    MATERIAL_TONES[toneRaw ?? ''] ?? MATERIAL_TONES[DEFAULT_MATERIAL_TONE];
  const materialExposure = clampNumber(
    exposureRaw,
    DEFAULT_MATERIAL_EXPOSURE,
    0.5,
    1.2
  );
  const openMaterialSurface = clampNumber(
    surfaceRaw,
    DEFAULT_OPEN_MATERIAL_SURFACE,
    0,
    1
  );
  const openMaterialSurfaceProgression = clampNumber(
    surfaceProgressionRaw,
    DEFAULT_OPEN_MATERIAL_SURFACE_PROGRESSION,
    0.15,
    1
  );
  const blurRadiusDp = blurRadiusPx / PixelRatio.get();

  const [open, setOpen] = useState(false);
  const progress = useSharedValue(0);
  const bottomDepth = useSharedValue(closedDepth);

  // The reference's blur field begins materially higher than the current demo.
  // Keep the measured airy curve/ramp unchanged and move the whole field upward
  // instead of distorting the radius transfer again.
  const expandedDepth = Math.min(height * expandedScale, 720);
  // Closed uses the full compact depth as the ramp. Open keeps only a small
  // fully-material region at the bottom and lets blur/material evolve across
  // almost the entire panel, matching the long continuous falloff in the ref.
  const blurProgression = useDerivedValue(() =>
    interpolate(progress.value, [0, 1], [1, openProgression])
  );
  const materialSurface = useDerivedValue(() =>
    interpolate(
      progress.value,
      [0, 1],
      [DEFAULT_CLOSED_MATERIAL_SURFACE, openMaterialSurface]
    )
  );
  const materialSurfaceProgression = useDerivedValue(() =>
    interpolate(
      progress.value,
      [0, 1],
      [
        DEFAULT_CLOSED_MATERIAL_SURFACE_PROGRESSION,
        openMaterialSurfaceProgression,
      ]
    )
  );
  const storyWidth = Math.min(Math.max(width * 0.78, 268), 350);

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

    const nextDepth = next ? expandedDepth : closedDepth;

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
        curve={REFERENCE_BLUR_CURVE}
        blurRadius={blurRadiusDp}
        blurProgression={blurProgression}
        progressiveBackend="agsl"
        progressiveMaterialStrength={materialStrength}
        progressiveMaterialColor={materialTone}
        progressiveMaterialExposure={materialExposure}
        progressiveMaterialSurface={materialSurface}
        progressiveMaterialSurfaceProgression={materialSurfaceProgression}
        style={StyleSheet.absoluteFill}
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
