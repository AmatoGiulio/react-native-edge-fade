import { useState } from 'react';
import {
  PixelRatio,
  Pressable,
  StatusBar,
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
  interpolateColor,
  type SharedValue,
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
// Reference-oriented opalescent substrate. Blur remains a single Gaussian;
// these values only tune the post-blur material response.
const DEFAULT_MATERIAL_STRENGTH = 0.96;
const DEFAULT_MATERIAL_EXPOSURE = 0.98;
const DEFAULT_MATERIAL_SURFACE = 0.78;
const DEFAULT_MATERIAL_SURFACE_PROGRESSION = 0.62;
const LIGHT_MATERIAL_COLOR = '#bfc0c4';
// Smoke retains source illumination instead of converging to a silver overlay.
const DARK_MATERIAL_COLOR = '#595a60';
const DEFAULT_CLOSED_DEPTH = 210;
// One moving field; the deep region settles before the upper shoulder.
const DEFAULT_OPEN_PROGRESSION = 0.9;
const DEFAULT_EXPANDED_SCALE = 0.7;

// Reference timing: the material reaches the expanded state in roughly
// 300 ms and collapses materially faster. Keep one shared timeline for blur
// depth and chrome so no layer can drift vertically or temporally.
const OPEN_MS = 300;
const CLOSE_MS = 220;
const EASE = Easing.bezier(0.16, 1, 0.3, 1);

const REFERENCE_BLUR_CURVE = {
  type: 'stops' as const,
  // Cubic radius: preserve detail through the spacious upper shoulder,
  // then diffuse broadly in the body. Material density grows independently.
  values: [
    1, 0.9994, 0.9954, 0.9844, 0.963, 0.9277, 0.875, 0.8015, 0.7037, 0.5781,
    0.4213, 0.2297, 0,
  ],
};

function clampNumber(
  value: string | undefined,
  fallback: number,
  min: number,
  max: number
) {
  if (value == null || value.trim() === '') return fallback;
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

function AccountRow({
  avatar,
  name,
  subtitle,
  date,
  themeProgress,
}: {
  avatar: (typeof ITEMS)[number];
  name: string;
  subtitle: string;
  date: string;
  themeProgress: SharedValue<number>;
}) {
  const primaryTextStyle = useAnimatedStyle(() => ({
    color: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['#111111', '#f3f2ef']
    ),
  }));
  const secondaryTextStyle = useAnimatedStyle(() => ({
    color: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['#222222', '#d9d7d2']
    ),
  }));
  const dateStyle = useAnimatedStyle(() => ({
    color: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['#aaa7a2', '#85827d']
    ),
  }));
  const badgeStyle = useAnimatedStyle(() => ({
    borderColor: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['#efeeec', '#121210']
    ),
  }));

  return (
    <View style={s.accountRow}>
      <View style={s.accountIdentity}>
        <View style={s.avatarWrap}>
          <Image source={avatar.source} style={s.avatar} contentFit="cover" />
          <Animated.View style={[s.badge, badgeStyle]}>
            <View style={s.badgeDot} />
          </Animated.View>
        </View>

        <View>
          <Animated.Text style={[s.accountName, primaryTextStyle]}>
            {name}
          </Animated.Text>
          <Animated.Text style={[s.accountSubtitle, secondaryTextStyle]}>
            {subtitle}
          </Animated.Text>
        </View>
      </View>

      <Animated.Text style={[s.accountDate, dateStyle]}>{date}</Animated.Text>
    </View>
  );
}

export default function ProgressiveShowcaseRoute() {
  const insets = useSafeAreaInsets();
  const { height, width } = useWindowDimensions();
  const params = useLocalSearchParams<{ bench?: string | string[] }>();

  const benchParam = Array.isArray(params.bench)
    ? params.bench[0]
    : params.bench;
  const [materialRaw, progressionRaw, radiusRaw, depthRaw, scaleRaw] = (
    benchParam ?? ''
  ).split(',');

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
  const blurRadiusPx = clampNumber(radiusRaw, DEFAULT_BLUR_RADIUS_PX, 1, 150);
  const closedDepth = clampNumber(depthRaw, DEFAULT_CLOSED_DEPTH, 48, 240);
  const expandedScale = clampNumber(
    scaleRaw,
    DEFAULT_EXPANDED_SCALE,
    0.45,
    0.9
  );
  const blurRadiusDp = blurRadiusPx / PixelRatio.get();

  const [open, setOpen] = useState(false);
  const [darkMode, setDarkMode] = useState(false);
  const progress = useSharedValue(0);
  const themeProgress = useSharedValue(0);

  const expandedDepth = Math.min(height * expandedScale, 720);
  const bottomDepth = useDerivedValue(() =>
    interpolate(progress.value, [0, 1], [closedDepth, expandedDepth])
  );
  // Keep the optical progression invariant between CLOSED and OPEN.
  // The panel transition now changes only the physical bottom depth, so the
  // same normalized material field is used in both states.
  const blurProgression = useDerivedValue(() => openProgression);

  // Reference scene geometry is tied to the viewport width, not to a scrolling
  // document. The native blur is the only thing changing during the transition.
  const sceneLeft = width * 0.075;
  const sceneWidth = width * 0.85;
  const mediaInset = width * 0.085;
  const mediaLeft = sceneLeft + mediaInset;
  const mediaWidth = sceneWidth - mediaInset;

  // The reference movie itself is cropped above the phone. Do not reproduce
  // that crop in the demo: keep the preceding media fully visible and aligned
  // to the same media column as the rest of the feed.
  const previousItem = ITEMS[41]!;
  const lowerItem = ITEMS[27]!;
  const previousTop = insets.top + width * 0.015;
  const previousHeight = mediaWidth / previousItem.ratio;

  const firstAccountTop = previousTop + previousHeight + width * 0.04;
  const accountHeight = Math.max(52, width * 0.132);

  const pairTop = firstAccountTop + accountHeight + width * 0.018;
  const pairHeight = width * 0.44;
  const pairGap = Math.max(9, width * 0.022);
  const primaryWidth = (mediaWidth - pairGap) * 0.61;
  const secondaryWidth = mediaWidth - pairGap - primaryWidth;

  const secondAccountTop = pairTop + pairHeight + width * 0.045;
  const lowerTop = secondAccountTop + accountHeight + width * 0.018;
  // The feed continues underneath the fixed bottom chrome in the reference.
  // Overscan the last media beyond the viewport so the progressive field always
  // has real image content to diffuse all the way to the home-indicator edge.
  const lowerHeight = Math.max(
    height - lowerTop + insets.bottom + width * 0.06,
    mediaWidth * 0.9
  );

  // Reference: the chrome stays spatially pinned. Only the material field
  // changes depth; labels cross-fade in place and never ride the blur.
  const closedNavStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0, 0.24, 0.5], [1, 1, 0]),
  }));

  const openMenuStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0, 0.52, 0.78, 1], [0, 0, 0.86, 1]),
  }));

  const menuAvatarStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0.5, 0.72, 1], [0, 0.74, 1]),
    transform: [
      {
        scale: interpolate(progress.value, [0.5, 1], [0.97, 1]),
      },
    ],
  }));

  const menuLinksStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0.58, 0.82, 1], [0, 0.78, 1]),
  }));

  const menuLinkTextStyle = useAnimatedStyle(() => ({
    // The reference keeps menu copy white in both themes. The pearly material
    // supplies contrast in light mode; a subtle shadow preserves edge clarity.
    color: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['rgba(255,255,255,0.97)', 'rgba(255,255,255,0.98)']
    ),
  }));

  const menuControlStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0.68, 0.9, 1], [0, 0.84, 1]),
  }));

  const backdropStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0, 0.35, 1], [0, 0, 1]),
  }));

  const surfaceStyle = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['#efeeec', '#121210']
    ),
  }));

  // Match the reference pill geometry: compact height, wider track and an
  // exact 2 px optical inset. The previous half-width used the OUTER width,
  // which made the selected pill overrun the track and get clipped on the right.
  const segmentWidth = Math.min(154, width * 0.32);
  const segmentHeight = 32;
  const segmentInset = 2;
  const segmentInnerWidth = segmentWidth - segmentInset * 2;
  const segmentHalf = segmentInnerWidth / 2;

  const segmentTrackStyle = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['rgba(205,199,190,0.56)', 'rgba(67,63,59,0.78)']
    ),
    borderColor: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['rgba(255,255,255,0.46)', 'rgba(255,255,255,0.16)']
    ),
  }));

  const segmentPillStyle = useAnimatedStyle(() => ({
    width: segmentHalf,
    transform: [
      {
        translateX: interpolate(themeProgress.value, [0, 1], [segmentHalf, 0]),
      },
    ],
    backgroundColor: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['rgba(250,248,244,0.96)', 'rgba(5,5,4,0.96)']
    ),
    borderColor: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['rgba(255,255,255,0.82)', 'rgba(255,255,255,0.08)']
    ),
  }));

  const darkSegmentTextStyle = useAnimatedStyle(() => ({
    color: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['rgba(91,86,79,0.88)', '#f6f3ee']
    ),
  }));

  const lightSegmentTextStyle = useAnimatedStyle(() => ({
    color: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['#171513', 'rgba(206,201,194,0.72)']
    ),
  }));

  const setTheme = (nextDark: boolean) => {
    if (nextDark === darkMode) return;
    setDarkMode(nextDark);
    themeProgress.value = withTiming(nextDark ? 1 : 0, {
      duration: OPEN_MS,
      easing: EASE,
    });
  };

  const togglePanel = () => {
    const next = !open;
    setOpen(next);

    const duration = next ? OPEN_MS : CLOSE_MS;
    progress.value = withTiming(next ? 1 : 0, {
      duration,
      easing: EASE,
    });
  };

  return (
    <View style={s.page}>
      <Stack.Screen options={{ headerShown: false }} />
      <StatusBar barStyle={darkMode ? 'light-content' : 'dark-content'} />
      <Animated.View
        pointerEvents="none"
        style={[StyleSheet.absoluteFill, surfaceStyle]}
      />

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
        progressiveMaterialColor={
          darkMode ? DARK_MATERIAL_COLOR : LIGHT_MATERIAL_COLOR
        }
        progressiveMaterialExposure={DEFAULT_MATERIAL_EXPOSURE}
        progressiveMaterialSurface={DEFAULT_MATERIAL_SURFACE}
        progressiveMaterialSurfaceProgression={
          DEFAULT_MATERIAL_SURFACE_PROGRESSION
        }
        style={StyleSheet.absoluteFill}
      >
        <Animated.View
          pointerEvents="none"
          style={[StyleSheet.absoluteFill, surfaceStyle]}
        >
          <Image
            source={previousItem.source}
            style={[
              s.previousCard,
              {
                left: mediaLeft,
                top: previousTop,
                width: mediaWidth,
                height: previousHeight,
              },
            ]}
            contentFit="contain"
          />

          <View
            style={[
              s.accountSlot,
              {
                left: sceneLeft,
                top: firstAccountTop,
                width: sceneWidth,
                height: accountHeight,
              },
            ]}
          >
            <AccountRow
              avatar={ITEMS[25]!}
              name="roma.daily"
              subtitle="by Studio 19"
              date="Today"
              themeProgress={themeProgress}
            />
          </View>

          <View
            style={[
              s.photoPair,
              {
                left: mediaLeft,
                top: pairTop,
                width: mediaWidth,
                height: pairHeight,
              },
            ]}
          >
            <Image
              source={ITEMS[23]!.source}
              style={[
                s.photo,
                {
                  width: primaryWidth,
                  height: pairHeight,
                },
              ]}
              contentFit="cover"
            />
            <Image
              source={ITEMS[25]!.source}
              style={[
                s.photo,
                {
                  width: secondaryWidth,
                  height: pairHeight,
                },
              ]}
              contentFit="cover"
            />
          </View>

          <View
            style={[
              s.accountSlot,
              {
                left: sceneLeft,
                top: secondAccountTop,
                width: sceneWidth,
                height: accountHeight,
              },
            ]}
          >
            <AccountRow
              avatar={lowerItem}
              name="roma.afterdark"
              subtitle="A visual diary from Rome"
              date="May 12"
              themeProgress={themeProgress}
            />
          </View>

          <Image
            source={lowerItem.source}
            style={[
              s.lowerPost,
              {
                left: mediaLeft,
                top: lowerTop,
                width: mediaWidth,
                height: lowerHeight,
              },
            ]}
            contentFit="cover"
          />
        </Animated.View>
      </ProgressiveFade>

      <Animated.View
        pointerEvents={open ? 'auto' : 'none'}
        style={[StyleSheet.absoluteFill, s.openBackdrop, backdropStyle]}
      >
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Close perfection menu"
          onPress={togglePanel}
          style={StyleSheet.absoluteFill}
        />
      </Animated.View>

      <Animated.View
        pointerEvents={open ? 'none' : 'auto'}
        style={[
          s.closedNav,
          {
            left: sceneLeft,
            right: sceneLeft,
            bottom: insets.bottom + 18,
          },
          closedNavStyle,
        ]}
      >
        <Text style={[s.closedNavLabel, s.closedNavView]}>View</Text>

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

        <Text style={[s.closedNavLabel, s.closedNavSettings]}>Settings</Text>
      </Animated.View>

      <Animated.View
        pointerEvents={open ? 'auto' : 'none'}
        style={[
          s.openMenu,
          {
            left: sceneLeft,
            right: sceneLeft,
            bottom: insets.bottom + 18,
          },
          openMenuStyle,
        ]}
      >
        <Animated.View style={menuAvatarStyle}>
          <Image
            source={ITEMS[25]!.source}
            style={s.menuAvatar}
            contentFit="cover"
          />
        </Animated.View>

        <Animated.View style={[s.menuLinks, menuLinksStyle]}>
          <Animated.Text style={[s.menuLink, menuLinkTextStyle]}>
            Subscription
          </Animated.Text>
          <Animated.Text style={[s.menuLink, menuLinkTextStyle]}>
            Extension
          </Animated.Text>
          <Animated.Text style={[s.menuLink, menuLinkTextStyle]}>
            About
          </Animated.Text>
        </Animated.View>

        <Animated.View style={[s.menuBottomRow, menuControlStyle]}>
          <Animated.View
            style={[
              s.themeSegment,
              {
                width: segmentWidth,
                height: segmentHeight,
                borderRadius: segmentHeight / 2,
                padding: segmentInset,
              },
              segmentTrackStyle,
            ]}
          >
            <Animated.View
              pointerEvents="none"
              style={[
                s.themeSegmentPill,
                {
                  left: segmentInset,
                  top: segmentInset,
                  height: segmentHeight - segmentInset * 2,
                  borderRadius: (segmentHeight - segmentInset * 2) / 2,
                },
                segmentPillStyle,
              ]}
            />

            <Pressable
              accessibilityRole="button"
              accessibilityState={{ selected: darkMode }}
              onPress={() => setTheme(true)}
              style={s.themeSegmentHit}
            >
              <Animated.Text
                style={[s.themeSegmentLabel, darkSegmentTextStyle]}
              >
                Dark
              </Animated.Text>
            </Pressable>

            <Pressable
              accessibilityRole="button"
              accessibilityState={{ selected: !darkMode }}
              onPress={() => setTheme(false)}
              style={s.themeSegmentHit}
            >
              <Animated.Text
                style={[s.themeSegmentLabel, lightSegmentTextStyle]}
              >
                Light
              </Animated.Text>
            </Pressable>
          </Animated.View>
        </Animated.View>
      </Animated.View>
    </View>
  );
}

const s = StyleSheet.create({
  page: {
    flex: 1,
    backgroundColor: '#efeeec',
    overflow: 'hidden',
  },

  previousCard: {
    position: 'absolute',
    borderRadius: 8,
    backgroundColor: '#d8d5d0',
  },
  accountSlot: {
    position: 'absolute',
  },
  accountRow: {
    flex: 1,
    paddingHorizontal: 1,
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
    position: 'absolute',
    flexDirection: 'row',
    gap: 10,
  },
  photo: {
    borderRadius: 8,
    backgroundColor: '#d7d3ce',
  },
  lowerPost: {
    position: 'absolute',
    borderRadius: 8,
    backgroundColor: '#d7d3ce',
  },

  closedNav: {
    position: 'absolute',
    zIndex: 30,
    height: 54,
    flexDirection: 'row',
    alignItems: 'flex-end',
    justifyContent: 'center',
  },
  closedNavView: {
    position: 'absolute',
    left: 0,
    bottom: 0,
  },
  closedNavSettings: {
    position: 'absolute',
    right: 0,
    bottom: 0,
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
    width: 24,
    height: 20,
    borderRadius: 5,
    backgroundColor: 'rgba(255,255,255,0.97)',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,1)',
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#000',
    shadowOpacity: 0.12,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 2 },
    elevation: 2,
  },
  perfectionInner: {
    width: 15,
    height: 11,
    borderRadius: 2.5,
    backgroundColor: 'rgba(224,220,214,0.96)',
  },

  openBackdrop: {
    zIndex: 20,
    backgroundColor: 'transparent',
  },
  openMenu: {
    position: 'absolute',
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
    fontSize: 12,
    lineHeight: 15,
    fontWeight: '600',
    textShadowColor: 'rgba(0,0,0,0.18)',
    textShadowOffset: { width: 0, height: 1 },
    textShadowRadius: 4,
  },
  menuBottomRow: {
    marginTop: 25,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'flex-start',
  },
  themeSegment: {
    position: 'relative',
    flexDirection: 'row',
    overflow: 'hidden',
    borderWidth: StyleSheet.hairlineWidth,
  },
  themeSegmentPill: {
    position: 'absolute',
    borderWidth: StyleSheet.hairlineWidth,
    shadowColor: '#000',
    shadowOpacity: 0.1,
    shadowRadius: 5,
    shadowOffset: { width: 0, height: 1 },
    elevation: 1,
  },
  themeSegmentHit: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 2,
  },
  themeSegmentLabel: {
    fontSize: 11,
    lineHeight: 13,
    fontWeight: '600',
    letterSpacing: -0.08,
  },
});
