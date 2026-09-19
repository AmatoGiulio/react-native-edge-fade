import { useMemo, useState } from 'react';
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
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';
import { STILLS_ITEMS } from '@/data/catalog';

const ProgressiveFade = AnimatedEdgeFadeView as any;

const ITEMS = STILLS_ITEMS.slice(0, 16);
const BLUR_RADIUS_PX = 150;
const BLUR_RADIUS_DP = BLUR_RADIUS_PX / PixelRatio.get();
const OPEN_MS = 560;
const CLOSE_MS = 460;
const EASE = Easing.bezier(0.16, 1, 0.3, 1);

const REFERENCE_BLUR_CURVE = {
  type: 'cubicBezier' as const,
  x1: 0.4,
  y1: 0,
  x2: 0.65,
  y2: 1,
};

const STORIES = [
  {
    id: 'mix',
    time: '07:00',
    type: 'MIX DEL GIORNO',
    title: 'Signals From The Floor',
    dek: 'Hypnotic techno, broken rhythm and late-night electronics from Rome.',
    image: ITEMS[1],
  },
  {
    id: 'feature',
    time: '17:15',
    type: 'MAGAZINE',
    title: 'Three rooms shaping the new Roman underground',
    dek: 'Small clubs, independent crews and a different idea of nightlife.',
    image: ITEMS[7],
  },
  {
    id: 'festival',
    time: '16:00',
    type: 'FESTIVAL',
    title: 'Forma announces a two-night warehouse edition',
    dek: 'Live AV, installations and extended sets across the old industrial district.',
    image: ITEMS[10],
  },
  {
    id: 'studio',
    time: '14:20',
    type: 'STUDIO',
    title: 'Inside the visual language of after-hours Rome',
    dek: 'Design, light and sound come together in a new independent series.',
    image: ITEMS[5],
  },
];

const RANKED = [
  'Lorenzo Senni returns with a new live set',
  'Maceo Plex announces Rome warehouse date',
  'A new listening bar opens in Ostiense',
  'The week in electronic music: 10 essential releases',
];

export default function ProgressiveShowcaseRoute() {
  const insets = useSafeAreaInsets();
  const { height, width } = useWindowDimensions();

  const [open, setOpen] = useState(false);
  const progress = useSharedValue(0);
  const bottomDepth = useSharedValue(116);

  const expandedDepth = Math.min(height * 0.60, 560);
  const isWide = width >= 760;

  const menuStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0.12, 0.44, 1], [0, 0.15, 1]),
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [26, 0]) },
    ],
  }));

  const triggerStyle = useAnimatedStyle(() => ({
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

    bottomDepth.value = withTiming(next ? expandedDepth : 116, {
      duration,
      easing: EASE,
    });
  };

  const lead = useMemo(() => STORIES[0]!, []);

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
        blurRadius={BLUR_RADIUS_DP}
        blurProgression={0.9}
        progressiveBackend="agsl"
        style={StyleSheet.absoluteFill}
      >
        <ScrollView
          style={StyleSheet.absoluteFill}
          contentContainerStyle={[
            s.scrollContent,
            {
              paddingTop: insets.top,
              paddingBottom: insets.bottom + 180,
            },
          ]}
          showsVerticalScrollIndicator={false}
          bounces
          overScrollMode="never"
        >
          <View style={s.nav}>
            <Text style={s.mark}>N</Text>

            <View style={s.navLinks}>
              <Text style={s.navLink}>EVENTI</Text>
              <Text style={s.navLink}>MUSICA</Text>
              <Text style={s.navLink}>MAGAZINE</Text>
              <Text style={s.navLink}>ROMA</Text>
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

          <View style={s.headerRule} />

          <View style={[s.contentRow, !isWide && s.contentRowNarrow]}>
            <View style={[s.mainColumn, !isWide && s.mainColumnNarrow]}>
              <Text style={s.kicker}>
                {lead.time} · {lead.type}
              </Text>
              <Text style={s.leadTitle}>{lead.title}</Text>
              <Text style={s.leadDek}>{lead.dek}</Text>

              <View style={s.leadMediaRow}>
                <Image
                  source={lead.image!.source}
                  style={s.leadImage}
                  contentFit="cover"
                />
                <View style={s.leadTextBlock}>
                  <Text style={s.meta}>VEN, 21:30 · LIVE</Text>
                  <Text style={s.sideHeadline}>
                    Late-night frequencies from the capital
                  </Text>
                  <Text style={s.sideDek}>
                    A visual and sonic diary of the city after midnight.
                  </Text>
                </View>
              </View>

              {STORIES.slice(1).map((story) => (
                <View key={story.id} style={s.storyRow}>
                  <Image
                    source={story.image!.source}
                    style={s.storyImage}
                    contentFit="cover"
                  />

                  <View style={s.storyCopy}>
                    <Text style={s.meta}>
                      {story.time} · {story.type}
                    </Text>
                    <Text style={s.storyTitle}>{story.title}</Text>
                    <Text style={s.storyDek}>{story.dek}</Text>
                  </View>
                </View>
              ))}
            </View>

            <View style={[s.sidebar, !isWide && s.sidebarNarrow]}>
              <Text style={s.sidebarLabel}>ORA</Text>

              {RANKED.map((item, index) => (
                <View key={item} style={s.rankRow}>
                  <Text style={s.rankNumber}>{index + 1}</Text>
                  <Text style={s.rankTitle}>{item}</Text>
                </View>
              ))}

              <View style={s.poster}>
                <Image
                  source={(ITEMS[12] ?? ITEMS[3])!.source}
                  style={StyleSheet.absoluteFill}
                  contentFit="cover"
                />
                <View style={s.posterScrim} />
                <Text style={s.posterEyebrow}>NEXT FRIDAY</Text>
                <Text style={s.posterTitle}>VOID / ROMA</Text>
                <Text style={s.posterMeta}>00:00 — 08:00</Text>
              </View>
            </View>
          </View>
        </ScrollView>
      </ProgressiveFade>

      <Animated.View
        pointerEvents={open ? 'auto' : 'none'}
        style={[s.panel, { bottom: insets.bottom + 80 }, menuStyle]}
      >
        <Text style={s.panelEyebrow}>TONIGHT</Text>
        <Text style={s.panelTitle}>ROMA AFTER DARK</Text>

        <View style={s.panelRule} />

        <View style={s.panelRow}>
          <Text style={s.panelTime}>23:30</Text>
          <View style={s.panelCopy}>
            <Text style={s.panelName}>Forma / Warehouse Edition</Text>
            <Text style={s.panelMeta}>Ostiense · live AV · extended sets</Text>
          </View>
        </View>

        <View style={s.panelRow}>
          <Text style={s.panelTime}>01:00</Text>
          <View style={s.panelCopy}>
            <Text style={s.panelName}>Nocturne — Room II</Text>
            <Text style={s.panelMeta}>San Lorenzo · techno · visual installation</Text>
          </View>
        </View>

        <View style={s.panelRow}>
          <Text style={s.panelTime}>03:30</Text>
          <View style={s.panelCopy}>
            <Text style={s.panelName}>After / Secret Location</Text>
            <Text style={s.panelMeta}>limited capacity · entry via list</Text>
          </View>
        </View>
      </Animated.View>

      <View style={[s.bottomBar, { bottom: insets.bottom + 12 }]}>
        <Text style={s.bottomWordmark}>N / ROMA</Text>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={open ? 'Close tonight panel' : 'Open tonight panel'}
          onPress={togglePanel}
          style={s.menuTrigger}
        >
          <Text style={s.menuTriggerText}>TONIGHT</Text>
          <Animated.Text style={[s.menuTriggerIcon, triggerStyle]}>↑</Animated.Text>
        </Pressable>
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  page: {
    flex: 1,
    backgroundColor: '#fff',
  },
  scrollContent: {
    minHeight: '100%',
  },
  nav: {
    height: 58,
    paddingHorizontal: 18,
    backgroundColor: '#050505',
    flexDirection: 'row',
    alignItems: 'center',
  },
  mark: {
    width: 36,
    color: '#fff',
    fontSize: 22,
    lineHeight: 24,
    fontWeight: '900',
    fontStyle: 'italic',
  },
  navLinks: {
    flex: 1,
    flexDirection: 'row',
    gap: 18,
  },
  navLink: {
    color: '#fff',
    fontSize: 10,
    lineHeight: 12,
    fontWeight: '700',
    letterSpacing: 0.2,
  },
  close: {
    color: '#fff',
    fontSize: 24,
    lineHeight: 26,
    fontWeight: '300',
  },
  headerRule: {
    height: 1,
    backgroundColor: '#ececea',
  },
  contentRow: {
    flexDirection: 'row',
    gap: 26,
    paddingHorizontal: 18,
    paddingTop: 18,
  },
  contentRowNarrow: {
    gap: 14,
  },
  mainColumn: {
    flex: 1,
    minWidth: 0,
  },
  mainColumnNarrow: {
    flex: 1.65,
  },
  sidebar: {
    width: 220,
    paddingTop: 8,
  },
  sidebarNarrow: {
    width: 118,
  },
  kicker: {
    color: '#b2b2ae',
    fontSize: 10,
    lineHeight: 12,
    fontWeight: '800',
    letterSpacing: 0.9,
  },
  leadTitle: {
    marginTop: 5,
    color: '#0d0d0d',
    fontSize: 31,
    lineHeight: 32,
    fontWeight: '900',
    letterSpacing: -1.3,
  },
  leadDek: {
    marginTop: 9,
    color: '#202020',
    fontSize: 12,
    lineHeight: 17,
    maxWidth: 520,
  },
  leadMediaRow: {
    flexDirection: 'row',
    gap: 12,
    marginTop: 20,
    marginBottom: 6,
  },
  leadImage: {
    width: '44%',
    aspectRatio: 1.55,
    backgroundColor: '#e8e8e5',
  },
  leadTextBlock: {
    flex: 1,
    paddingTop: 2,
  },
  meta: {
    color: '#aaa9a5',
    fontSize: 9,
    lineHeight: 11,
    fontWeight: '800',
    letterSpacing: 0.6,
  },
  sideHeadline: {
    marginTop: 5,
    color: '#0d0d0d',
    fontSize: 19,
    lineHeight: 20,
    fontWeight: '900',
    letterSpacing: -0.5,
  },
  sideDek: {
    marginTop: 6,
    color: '#272727',
    fontSize: 10,
    lineHeight: 14,
  },
  storyRow: {
    flexDirection: 'row',
    gap: 12,
    paddingVertical: 12,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#e2e2df',
  },
  storyImage: {
    width: '44%',
    aspectRatio: 1.55,
    backgroundColor: '#e8e8e5',
  },
  storyCopy: {
    flex: 1,
    paddingTop: 1,
  },
  storyTitle: {
    marginTop: 4,
    color: '#101010',
    fontSize: 18,
    lineHeight: 20,
    fontWeight: '900',
    letterSpacing: -0.45,
  },
  storyDek: {
    marginTop: 5,
    color: '#2a2a2a',
    fontSize: 9.5,
    lineHeight: 13.5,
  },
  sidebarLabel: {
    color: '#aaa9a5',
    fontSize: 9,
    fontWeight: '800',
    letterSpacing: 0.8,
    marginBottom: 4,
  },
  rankRow: {
    flexDirection: 'row',
    gap: 10,
    paddingVertical: 14,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#dededb',
  },
  rankNumber: {
    width: 18,
    color: '#b2b2ae',
    fontSize: 20,
    lineHeight: 22,
    fontWeight: '900',
  },
  rankTitle: {
    flex: 1,
    color: '#111',
    fontSize: 11,
    lineHeight: 14,
    fontWeight: '700',
  },
  poster: {
    height: 190,
    marginTop: 28,
    overflow: 'hidden',
    backgroundColor: '#111',
    justifyContent: 'flex-end',
    padding: 12,
  },
  posterScrim: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0,0,0,0.48)',
  },
  posterEyebrow: {
    color: 'rgba(255,255,255,0.68)',
    fontSize: 8,
    fontWeight: '800',
    letterSpacing: 1.2,
  },
  posterTitle: {
    marginTop: 3,
    color: '#fff',
    fontSize: 20,
    lineHeight: 21,
    fontWeight: '900',
  },
  posterMeta: {
    marginTop: 3,
    color: 'rgba(255,255,255,0.74)',
    fontSize: 9,
  },
  panel: {
    position: 'absolute',
    left: 24,
    right: 24,
    zIndex: 20,
  },
  panelEyebrow: {
    color: 'rgba(255,255,255,0.58)',
    fontSize: 8,
    fontWeight: '800',
    letterSpacing: 1.4,
  },
  panelTitle: {
    marginTop: 6,
    color: '#fff',
    fontSize: 26,
    lineHeight: 28,
    fontWeight: '900',
    letterSpacing: -0.7,
  },
  panelRule: {
    height: StyleSheet.hairlineWidth,
    backgroundColor: 'rgba(255,255,255,0.22)',
    marginVertical: 14,
  },
  panelRow: {
    flexDirection: 'row',
    gap: 14,
    paddingVertical: 10,
  },
  panelTime: {
    width: 46,
    color: 'rgba(255,255,255,0.48)',
    fontSize: 10,
    fontWeight: '800',
  },
  panelCopy: {
    flex: 1,
  },
  panelName: {
    color: '#fff',
    fontSize: 13,
    lineHeight: 16,
    fontWeight: '700',
  },
  panelMeta: {
    marginTop: 2,
    color: 'rgba(255,255,255,0.52)',
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
  bottomWordmark: {
    color: '#fff',
    fontSize: 9,
    fontWeight: '800',
    letterSpacing: 1.1,
  },
  menuTrigger: {
    height: 34,
    paddingHorizontal: 12,
    borderRadius: 17,
    backgroundColor: 'rgba(0,0,0,0.46)',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  menuTriggerText: {
    color: '#fff',
    fontSize: 8,
    fontWeight: '800',
    letterSpacing: 1.2,
  },
  menuTriggerIcon: {
    color: '#fff',
    fontSize: 11,
    fontWeight: '700',
  },
});
