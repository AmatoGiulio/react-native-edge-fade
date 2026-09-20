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
import { Stack, useLocalSearchParams } from 'expo-router';
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
const ITEMS = STILLS_ITEMS;

const DEFAULT_BLUR_RADIUS_PX = 150;
const DEFAULT_MATERIAL_STRENGTH = 0.36;
const MATERIAL_COLOR = '#e3e0dc';
const DEFAULT_CLOSED_DEPTH = 112;
// 04-open-s90 is the visual baseline selected against reference.mp4.
const DEFAULT_OPEN_PROGRESSION = 0.9;
const DEFAULT_EXPANDED_SCALE = 0.7;

const OPEN_MS = 500;
const CLOSE_MS = 420;
const EASE = Easing.bezier(0.16, 1, 0.3, 1);

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

function AccountRow({
  avatar,
  name,
  subtitle,
  date,
}: {
  avatar: (typeof ITEMS)[number];
  name: string;
  subtitle: string;
  date: string;
}) {
  return (
    <View style={s.accountRow}>
      <View style={s.accountIdentity}>
        <View style={s.avatarWrap}>
          <Image source={avatar.source} style={s.avatar} contentFit="cover" />
          <View style={s.badge}>
            <View style={s.badgeDot} />
          </View>
        </View>
        <View>
          <Text style={s.accountName}>{name}</Text>
          <Text style={s.accountSubtitle}>{subtitle}</Text>
        </View>
      </View>
      <Text style={s.accountDate}>{date}</Text>
    </View>
  );
}

export default function ProgressiveShowcaseRoute() {
  const insets = useSafeAreaInsets();
  const { height, width } = useWindowDimensions();
  const params = useLocalSearchParams<{ bench?: string | string[] }>();

  const benchParam = Array.isArray(params.bench) ? params.bench[0] : params.bench;
  const [materialRaw, progressionRaw, radiusRaw, depthRaw, scaleRaw] =
    (benchParam ?? '').split(',');

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
  const blurRadiusDp = blurRadiusPx / PixelRatio.get();

  const [open, setOpen] = useState(false);
  const progress = useSharedValue(0);
  const bottomDepth = useSharedValue(closedDepth);

  const expandedDepth = Math.min(height * expandedScale, 720);
  const blurProgression = useDerivedValue(() =>
    interpolate(progress.value, [0, 1], [1, openProgression])
  );

  // Geometry mirrors the reference scene: a cropped previous card, an author
  // row + two-up gallery, then another author row + a large post underneath.
  // Keeping the same composition makes blur comparisons meaningful.
  const contentWidth = width - 44;
  const previousHeight = Math.min(height * 0.25, 460);
  const pairHeight = Math.min(height * 0.265, 500);
  const lowerHeight = Math.min(height * 0.36, 670);

  const closedNavStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0, 0.24, 0.48], [1, 0.72, 0]),
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [0, 8]) },
    ],
  }));

  const openMenuStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0, 0.28, 0.62, 1], [0, 0, 0.78, 1]),
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [18, 0]) },
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

    bottomDepth.value = withTiming(next ? expandedDepth : closedDepth, {
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
        progressiveMaterialColor={MATERIAL_COLOR}
        style={StyleSheet.absoluteFill}
      >
        <ScrollView
          style={StyleSheet.absoluteFill}
          contentContainerStyle={[
            s.feed,
            {
              paddingTop: insets.top + 6,
              paddingBottom: insets.bottom + 42,
            },
          ]}
          showsVerticalScrollIndicator={false}
          bounces={false}
          overScrollMode="never"
        >
          <View style={[s.feedInner, { width: contentWidth }]}>
            <View
              style={[
                s.previousCardWindow,
                { height: previousHeight * 0.46 },
              ]}
            >
              <Image
                source={ITEMS[16]!.source}
                style={[
                  s.previousCard,
                  {
                    width: contentWidth * 0.8,
                    height: previousHeight,
                    top: -previousHeight * 0.54,
                  },
                ]}
                contentFit="cover"
              />
            </View>

            <AccountRow
              avatar={ITEMS[25]!}
              name="roma.daily"
              subtitle="by Studio 19"
              date="Today"
            />

            <View style={[s.photoPair, { height: pairHeight }]}>
              <Image
                source={ITEMS[23]!.source}
                style={s.photoPairPrimary}
                contentFit="cover"
              />
              <Image
                source={ITEMS[25]!.source}
                style={s.photoPairSecondary}
                contentFit="cover"
              />
            </View>

            <AccountRow
              avatar={ITEMS[27]!}
              name="roma.afterdark"
              subtitle="A visual diary from Rome"
              date="May 12"
            />

            <Image
              source={ITEMS[27]!.source}
              style={[s.lowerPost, { height: lowerHeight }]}
              contentFit="cover"
            />
          </View>
        </ScrollView>
      </ProgressiveFade>

      <Animated.View
        pointerEvents={open ? 'none' : 'auto'}
        style={[
          s.closedNav,
          { bottom: insets.bottom + 18 },
          closedNavStyle,
        ]}
      >
        <Text style={s.closedNavLabel}>View</Text>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Open perfection menu"
          onPress={togglePanel}
          hitSlop={18}
          style={s.closedNavCenter}
        >
          <View style={s.perfectionIcon}>
            <View style={s.perfectionInner} />
          </View>
          <Text style={s.closedNavLabel}>Perfection</Text>
        </Pressable>

        <Text style={s.closedNavLabel}>Settings</Text>
      </Animated.View>

      <Animated.View
        pointerEvents={open ? 'auto' : 'none'}
        style={[
          s.openMenu,
          { bottom: insets.bottom + 17 },
          openMenuStyle,
        ]}
      >
        <Image
          source={ITEMS[25]!.source}
          style={s.menuAvatar}
          contentFit="cover"
        />

        <View style={s.menuLinks}>
          <Text style={s.menuLink}>Subscription</Text>
          <Text style={s.menuLink}>Extension</Text>
          <Text style={s.menuLink}>About</Text>
        </View>

        <View style={s.menuBottomRow}>
          <View style={s.segmented}>
            <Text style={s.segmentedMuted}>Dark</Text>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Close expanded glass menu"
              onPress={togglePanel}
              hitSlop={12}
              style={s.segmentedSelected}
            >
              <Text style={s.segmentedSelectedText}>Light</Text>
            </Pressable>
          </View>

          <Text style={s.menuSettings}>Settings</Text>
        </View>
      </Animated.View>
    </View>
  );
}

const s = StyleSheet.create({
  page: {
    flex: 1,
    backgroundColor: '#efeeec',
  },
  feed: {
    alignItems: 'center',
  },
  feedInner: {
    alignSelf: 'center',
  },
  previousCardWindow: {
    overflow: 'hidden',
    alignItems: 'center',
    marginBottom: 14,
  },
  previousCard: {
    position: 'absolute',
    borderRadius: 9,
    backgroundColor: '#dddcd9',
  },
  accountRow: {
    minHeight: 56,
    paddingHorizontal: 4,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  accountIdentity: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
  },
  avatarWrap: {
    width: 39,
    height: 39,
  },
  avatar: {
    width: 39,
    height: 39,
    borderRadius: 19.5,
    backgroundColor: '#d8d5d0',
  },
  badge: {
    position: 'absolute',
    right: -2,
    bottom: -1,
    width: 13,
    height: 13,
    borderRadius: 4,
    backgroundColor: '#ff2f91',
    borderWidth: 1.5,
    borderColor: '#efeeec',
    alignItems: 'center',
    justifyContent: 'center',
  },
  badgeDot: {
    width: 5,
    height: 5,
    borderRadius: 2.5,
    borderWidth: 1,
    borderColor: '#fff',
  },
  accountName: {
    color: '#111',
    fontSize: 13,
    lineHeight: 16,
    fontWeight: '700',
    letterSpacing: -0.15,
  },
  accountSubtitle: {
    marginTop: 1,
    color: '#222',
    fontSize: 12,
    lineHeight: 15,
    fontWeight: '400',
  },
  accountDate: {
    color: '#aaa7a2',
    fontSize: 10,
    lineHeight: 12,
  },
  photoPair: {
    marginTop: 7,
    marginBottom: 20,
    flexDirection: 'row',
    gap: 11,
  },
  photoPairPrimary: {
    flex: 1.45,
    height: '100%',
    borderRadius: 8,
    backgroundColor: '#d7d3ce',
  },
  photoPairSecondary: {
    flex: 0.78,
    height: '100%',
    borderRadius: 8,
    backgroundColor: '#d7d3ce',
  },
  lowerPost: {
    width: '100%',
    marginTop: 8,
    borderRadius: 8,
    backgroundColor: '#d7d3ce',
  },

  closedNav: {
    position: 'absolute',
    left: 34,
    right: 34,
    zIndex: 30,
    height: 54,
    flexDirection: 'row',
    alignItems: 'flex-end',
    justifyContent: 'space-between',
  },
  closedNavCenter: {
    minWidth: 96,
    alignItems: 'center',
    justifyContent: 'flex-end',
    gap: 4,
  },
  closedNavLabel: {
    color: 'rgba(255,255,255,0.93)',
    fontSize: 11,
    lineHeight: 13,
    fontWeight: '500',
  },
  perfectionIcon: {
    width: 23,
    height: 19,
    borderRadius: 4,
    backgroundColor: 'rgba(255,255,255,0.78)',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.92)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  perfectionInner: {
    width: 15,
    height: 11,
    borderRadius: 2.5,
    backgroundColor: 'rgba(235,232,228,0.58)',
  },

  openMenu: {
    position: 'absolute',
    left: 34,
    right: 34,
    zIndex: 30,
  },
  menuAvatar: {
    width: 31,
    height: 31,
    borderRadius: 15.5,
    backgroundColor: '#d6d2cd',
    marginBottom: 19,
  },
  menuLinks: {
    gap: 15,
  },
  menuLink: {
    color: 'rgba(255,255,255,0.94)',
    fontSize: 12,
    lineHeight: 15,
    fontWeight: '500',
  },
  menuBottomRow: {
    marginTop: 25,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  segmented: {
    width: 136,
    height: 36,
    padding: 3,
    borderRadius: 18,
    backgroundColor: 'rgba(255,255,255,0.43)',
    flexDirection: 'row',
    alignItems: 'center',
  },
  segmentedMuted: {
    width: 62,
    textAlign: 'center',
    color: 'rgba(255,255,255,0.55)',
    fontSize: 11,
    fontWeight: '500',
  },
  segmentedSelected: {
    flex: 1,
    height: 30,
    borderRadius: 15,
    backgroundColor: 'rgba(255,255,255,0.92)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  segmentedSelectedText: {
    color: '#222',
    fontSize: 11,
    fontWeight: '600',
  },
  menuSettings: {
    color: 'rgba(255,255,255,0.9)',
    fontSize: 11,
    lineHeight: 13,
    fontWeight: '500',
  },
});
