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
  cancelAnimation,
  Easing,
  Extrapolation,
  interpolate,
  interpolateColor,
  type SharedValue,
  useAnimatedStyle,
  useDerivedValue,
  useSharedValue,
  withDelay,
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
const LIGHT_MATERIAL_COLOR = '#d4d4d4';
// Near-black smoke anchor: colour shapes come from the source field, not from
// a silver/grey material tint.
const DARK_MATERIAL_COLOR = '#010101';
const DEFAULT_MATERIAL_COLOR_FIELD_MIX = 0.68;
const DEFAULT_MATERIAL_COLOR_FIELD_SCALE = 0.08;
const DEFAULT_MATERIAL_COLOR_FIELD_BLUR_RADIUS_PX = 160;
const DEFAULT_MATERIAL_COLOR_FIELD_CHROMA_GATE = 0.035;
const DEFAULT_MATERIAL_COLOR_FIELD_CHROMA_GAIN = 1.35;
const DEFAULT_MATERIAL_COLOR_FIELD_LUMA_MIX = 0.10;
// CLOSED in the reference is essentially the bottom navigation bar plus a
// small optical shoulder, not a 200+ px material panel.
const CLOSED_BAR_HEIGHT = 54;
const CLOSED_BAR_BOTTOM_OFFSET = 18;
const CLOSED_FIELD_SHOULDER = 14;

// OPEN is also substantially shorter than the previous 70% viewport field.
// The reference keeps the field concentrated around the lower menu/content.
const DEFAULT_OPEN_PROGRESSION = 0.9;
const DEFAULT_EXPANDED_SCALE = 0.38;
const MAX_EXPANDED_DEPTH = 380;

// Motion extracted frame-by-frame from reference_demo_edge_fade.mp4 (60 fps).
// One emphasized curve explains panel open/close and both theme directions:
// near-zero launch velocity, fast middle section, then a long deceleration tail.
const FIELD_OPEN_MS = 600;
const FIELD_CLOSE_MS = 540;
const THEME_SURFACE_MS = 525;
const THEME_CONTROL_MS = 420;
const REFERENCE_MOTION_EASE = Easing.bezier(0.24, 0, 0.15, 1);

// Bottom-chrome timing measured separately from the material field.
// OPEN reference: closed nav 0.45→0.55, menu 0.50→0.70.
// CLOSE reference: menu 5.75→5.90, closed nav 5.95→6.10.
const CLOSED_NAV_HIDE_DELAY_MS = 40;
const CLOSED_NAV_HIDE_MS = 110;
const MENU_SHOW_DELAY_MS = 90;
const MENU_SHOW_MS = 220;
const MENU_HIDE_DELAY_MS = 45;
const MENU_HIDE_MS = 145;
const CLOSED_NAV_SHOW_DELAY_MS = 235;
const CLOSED_NAV_SHOW_MS = 160;
const CHROME_IN_EASE = Easing.bezier(0.16, 1, 0.3, 1);
const CHROME_OUT_EASE = Easing.bezier(0.4, 0, 0.6, 1);

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
      ['#111111', '#f3f2ef'],
      'RGB',
      { gamma: 1 }
    ),
  }));
  const secondaryTextStyle = useAnimatedStyle(() => ({
    color: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['#222222', '#d9d7d2'],
      'RGB',
      { gamma: 1 }
    ),
  }));
  const dateStyle = useAnimatedStyle(() => ({
    color: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['#aaa7a2', '#85827d'],
      'RGB',
      { gamma: 1 }
    ),
  }));
  const badgeStyle = useAnimatedStyle(() => ({
    borderColor: interpolateColor(
      themeProgress.value,
      [0, 1],
      ['#efeeec', '#121210'],
      'RGB',
      { gamma: 1 }
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
  const defaultClosedDepth =
    insets.bottom +
    CLOSED_BAR_BOTTOM_OFFSET +
    CLOSED_BAR_HEIGHT +
    CLOSED_FIELD_SHOULDER;
  const closedDepth = clampNumber(depthRaw, defaultClosedDepth, 72, 180);
  const expandedScale = clampNumber(
    scaleRaw,
    DEFAULT_EXPANDED_SCALE,
    0.28,
    0.65
  );
  const blurRadiusDp = blurRadiusPx / PixelRatio.get();

  const [open, setOpen] = useState(false);
  const [darkMode, setDarkMode] = useState(false);
  const [debugStage, setDebugStage] = useState<
    'material' | 'capture' | 'gaussian'
  >('material');
  const progress = useSharedValue(0);
  const closedNavOpacity = useSharedValue(1);
  const openMenuOpacity = useSharedValue(0);
  const themeSurfaceProgress = useSharedValue(0);
  const themeControlProgress = useSharedValue(0);

  const expandedDepth = Math.max(
    closedDepth + 150,
    Math.min(height * expandedScale, MAX_EXPANDED_DEPTH)
  );
  const bottomDepth = useDerivedValue(() =>
    interpolate(progress.value, [0, 1], [closedDepth, expandedDepth])
  );
  const blurProgression = useDerivedValue(() =>
    interpolate(progress.value, [0, 1], [1, openProgression])
  );

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

  // The reference chrome is not a simple remap of field progress. It has
  // direction-specific delays/durations, especially on CLOSE where the menu
  // disappears in ~145 ms while the material field keeps collapsing for 540 ms.
  const closedNavStyle = useAnimatedStyle(() => ({
    opacity: closedNavOpacity.value,
  }));

  const openMenuStyle = useAnimatedStyle(() => ({
    opacity: openMenuOpacity.value,
  }));

  const menuLinkTextStyle = useAnimatedStyle(() => ({
    // The reference keeps menu copy white in both themes. The pearly material
    // supplies contrast in light mode; a subtle shadow preserves edge clarity.
    color: interpolateColor(
      themeSurfaceProgress.value,
      [0, 1],
      ['rgba(255,255,255,0.97)', 'rgba(255,255,255,0.98)'],
      'RGB',
      { gamma: 1 }
    ),
  }));

  const backdropStyle = useAnimatedStyle(() => ({
    opacity: interpolate(
      progress.value,
      [0.08, 0.78],
      [0, 1],
      Extrapolation.CLAMP
    ),
  }));

  const surfaceStyle = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(
      themeSurfaceProgress.value,
      [0, 1],
      ['#efeeec', '#121210'],
      'RGB',
      { gamma: 1 }
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
      themeControlProgress.value,
      [0, 1],
      ['rgba(205,199,190,0.56)', 'rgba(67,63,59,0.78)'],
      'RGB',
      { gamma: 1 }
    ),
    borderColor: interpolateColor(
      themeControlProgress.value,
      [0, 1],
      ['rgba(255,255,255,0.46)', 'rgba(255,255,255,0.16)'],
      'RGB',
      { gamma: 1 }
    ),
  }));

  const segmentPillStyle = useAnimatedStyle(() => ({
    width: segmentHalf,
    transform: [
      {
        translateX: interpolate(
          themeControlProgress.value,
          [0, 1],
          [segmentHalf, 0]
        ),
      },
    ],
    backgroundColor: interpolateColor(
      themeControlProgress.value,
      [0, 1],
      ['rgba(250,248,244,0.96)', 'rgba(5,5,4,0.96)'],
      'RGB',
      { gamma: 1 }
    ),
    borderColor: interpolateColor(
      themeControlProgress.value,
      [0, 1],
      ['rgba(255,255,255,0.82)', 'rgba(255,255,255,0.08)'],
      'RGB',
      { gamma: 1 }
    ),
  }));

  const darkSegmentTextStyle = useAnimatedStyle(() => ({
    color: interpolateColor(
      themeControlProgress.value,
      [0, 1],
      ['rgba(91,86,79,0.88)', '#f6f3ee'],
      'RGB',
      { gamma: 1 }
    ),
  }));

  const lightSegmentTextStyle = useAnimatedStyle(() => ({
    color: interpolateColor(
      themeControlProgress.value,
      [0, 1],
      ['#171513', 'rgba(206,201,194,0.72)'],
      'RGB',
      { gamma: 1 }
    ),
  }));

  const setTheme = (nextDark: boolean) => {
    if (nextDark === darkMode) return;
    setDarkMode(nextDark);
    const target = nextDark ? 1 : 0;

    themeControlProgress.value = withTiming(target, {
      duration: THEME_CONTROL_MS,
      easing: REFERENCE_MOTION_EASE,
    });
    themeSurfaceProgress.value = withTiming(target, {
      duration: THEME_SURFACE_MS,
      easing: REFERENCE_MOTION_EASE,
    });
  };

  const togglePanel = () => {
    const next = !open;
    setOpen(next);

    cancelAnimation(progress);
    cancelAnimation(closedNavOpacity);
    cancelAnimation(openMenuOpacity);

    progress.value = withTiming(next ? 1 : 0, {
      duration: next ? FIELD_OPEN_MS : FIELD_CLOSE_MS,
      easing: REFERENCE_MOTION_EASE,
    });

    if (next) {
      closedNavOpacity.value = withDelay(
        CLOSED_NAV_HIDE_DELAY_MS,
        withTiming(0, {
          duration: CLOSED_NAV_HIDE_MS,
          easing: CHROME_OUT_EASE,
        })
      );
      openMenuOpacity.value = withDelay(
        MENU_SHOW_DELAY_MS,
        withTiming(1, {
          duration: MENU_SHOW_MS,
          easing: CHROME_IN_EASE,
        })
      );
    } else {
      openMenuOpacity.value = withDelay(
        MENU_HIDE_DELAY_MS,
        withTiming(0, {
          duration: MENU_HIDE_MS,
          easing: CHROME_OUT_EASE,
        })
      );
      closedNavOpacity.value = withDelay(
        CLOSED_NAV_SHOW_DELAY_MS,
        withTiming(1, {
          duration: CLOSED_NAV_SHOW_MS,
          easing: CHROME_IN_EASE,
        })
      );
    }
  };

  const cycleDebugStage = () => {
    setDebugStage((current) =>
      current === 'material'
        ? 'capture'
        : current === 'capture'
          ? 'gaussian'
          : 'material'
    );
  };

  const debugBackend =
    debugStage === 'material'
      ? 'androidx-gradient'
      : `agsl-debug-${debugStage}`;

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
        progressiveBackend={debugBackend}
        progressiveNativeTuner={true}
        progressiveMaterialStrength={materialStrength}
        progressiveMaterialColor={LIGHT_MATERIAL_COLOR}
        progressiveMaterialColorDark={DARK_MATERIAL_COLOR}
        progressiveMaterialThemeProgress={themeSurfaceProgress}
        progressiveMaterialExposure={DEFAULT_MATERIAL_EXPOSURE}
        progressiveMaterialSurface={DEFAULT_MATERIAL_SURFACE}
        progressiveMaterialSurfaceProgression={
          DEFAULT_MATERIAL_SURFACE_PROGRESSION
        }
        progressiveMaterialColorFieldEnabled={true}
        progressiveMaterialColorFieldMix={DEFAULT_MATERIAL_COLOR_FIELD_MIX}
        progressiveMaterialColorFieldScale={DEFAULT_MATERIAL_COLOR_FIELD_SCALE}
        progressiveMaterialColorFieldBlurRadiusPx={
          DEFAULT_MATERIAL_COLOR_FIELD_BLUR_RADIUS_PX
        }
        progressiveMaterialColorFieldChromaGate={
          DEFAULT_MATERIAL_COLOR_FIELD_CHROMA_GATE
        }
        progressiveMaterialColorFieldChromaGain={
          DEFAULT_MATERIAL_COLOR_FIELD_CHROMA_GAIN
        }
        progressiveMaterialColorFieldLumaMix={
          DEFAULT_MATERIAL_COLOR_FIELD_LUMA_MIX
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
              themeProgress={themeSurfaceProgress}
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
              themeProgress={themeSurfaceProgress}
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

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Cycle progressive debug stage"
        onPress={cycleDebugStage}
        style={[s.debugProbe, { top: insets.top + 6 }]}
      >
        <Text style={s.debugProbeText}>
          {debugStage === 'material'
            ? 'FULL'
            : debugStage === 'capture'
              ? 'CAP'
              : 'GAUSS'}
        </Text>
      </Pressable>

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

      </Animated.View>

      <Text
        pointerEvents="none"
        style={[
          s.closedNavLabel,
          s.persistentSettings,
          {
            right: sceneLeft,
            bottom: insets.bottom + 18,
          },
        ]}
      >
        Settings
      </Text>

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
        <Image
          source={ITEMS[25]!.source}
          style={s.menuAvatar}
          contentFit="cover"
        />

        <View style={s.menuLinks}>
          <Animated.Text style={[s.menuLink, menuLinkTextStyle]}>
            Subscription
          </Animated.Text>
          <Animated.Text style={[s.menuLink, menuLinkTextStyle]}>
            Extension
          </Animated.Text>
          <Animated.Text style={[s.menuLink, menuLinkTextStyle]}>
            About
          </Animated.Text>
        </View>

        <View style={s.menuBottomRow}>
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
        </View>
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

  debugProbe: {
    position: 'absolute',
    left: 8,
    zIndex: 100,
    minWidth: 48,
    height: 26,
    paddingHorizontal: 8,
    borderRadius: 13,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(0,0,0,0.64)',
  },
  debugProbeText: {
    color: '#fff',
    fontSize: 10,
    fontWeight: '700',
    letterSpacing: 0.4,
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
  persistentSettings: {
    position: 'absolute',
    zIndex: 31,
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
