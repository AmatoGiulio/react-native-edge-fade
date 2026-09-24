import { useState } from 'react';
import {
  type ImageSourcePropType,
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

const ProgressiveFade = AnimatedEdgeFadeView as any;

const SHOWCASE_IMAGES = {
  beachBand: {
    source: require('../assets/showcase/beach-band.jpg'),
    ratio: 1600 / 950,
  },
  skyPortrait: {
    source: require('../assets/showcase/sky-portrait.jpg'),
    ratio: 1,
  },
  cassettes: {
    source: require('../assets/showcase/cassettes.jpg'),
    ratio: 1,
  },
  halftoneCircles: {
    source: require('../assets/showcase/halftone-circles.jpg'),
    ratio: 1600 / 850,
  },
  amberSkyline: {
    source: require('../assets/showcase/amber-skyline.jpg'),
    ratio: 1,
  },
  windswept: {
    source: require('../assets/showcase/windswept.jpg'),
    ratio: 819 / 1024,
  },
  profile: {
    source: require('../assets/showcase/profile.jpg'),
    ratio: 1,
  },
};

const DEFAULT_BLUR_RADIUS_PX = 150;
// Reference-oriented opalescent substrate. Blur remains a single Gaussian;
// these values only tune the post-blur material response.
const DEFAULT_MATERIAL_STRENGTH = 1;
const DEFAULT_MATERIAL_EXPOSURE = 0.98;
const DEFAULT_MATERIAL_SURFACE = 0.78;
const DEFAULT_MATERIAL_SURFACE_PROGRESSION = 0.95;
const LIGHT_MATERIAL_COLOR = '#c6c2c4';
// Near-black smoke anchor: colour shapes come from the source field, not from
// a silver/grey material tint.
const DARK_MATERIAL_COLOR = '#010101';
const DEFAULT_MATERIAL_COLOR_FIELD_MIX = 1;
const DEFAULT_MATERIAL_COLOR_FIELD_SCALE = 0.08;
const DEFAULT_MATERIAL_COLOR_FIELD_BLUR_RADIUS_PX = 320;
const DEFAULT_MATERIAL_COLOR_FIELD_CHROMA_GATE = 0.035;
const DEFAULT_MATERIAL_COLOR_FIELD_CHROMA_GAIN = 1.2;
const DEFAULT_MATERIAL_COLOR_FIELD_LUMA_MIX = 0.8;
const DEFAULT_MATERIAL_COLOR_FIELD_NEUTRAL_WEIGHT = 1;
const DEFAULT_MATERIAL_CURVE_OFFSET = 0;
// Field-only render: the body reads like a pure smoke/milk wash, no card structure.
const DEFAULT_MATERIAL_CURVE_HEIGHT = 0.4;

// Geometry measured frame-by-frame from reference_demo_edge_fade.mp4,
// normalized to screen width; vertical values are distances from the screen
// bottom (the reference crops the top of the phone, so anchoring to the
// bottom is what stays stable across device sizes).
const REF_LAYOUT = {
  media: { left: 0.182, width: 0.765 },
  contentLeft: 0.049,
  mediaRightMargin: 0.053,
  feed: {
    cardBBottomFromBottom: 1.241,
    // row center -> top of the card immediately below it.
    rowCenterToCardTopAbove: 0.084,
    // bottom of a card -> center of the row immediately below it.
    cardBottomToRowCenterAbove: 0.114,
  },
  accountRow1: { centerFromBottom: 1.127, height: 0.13 },
  photoPair: {
    topFromBottom: 1.046,
    height: 0.46,
    primaryWidth: 0.478,
    gap: 0.027,
    secondaryWidth: 0.478,
  },
  accountRow2: { centerFromBottom: 0.476, height: 0.13 },
  lowerCard: { topFromBottom: 0.392, extraHeight: 0.08 },
  accountRowSizing: {
    avatar: 0.104,
    badge: 0.036,
    textGap: 0.029,
    nameFontSize: 0.041,
    dateFontSize: 0.031,
    lineHeightScale: 1.25,
  },
  closedNav: {
    labelFontSize: 0.036,
    labelCenterFromBottom: 0.104,
    viewLeft: 0.089,
    settingsRight: 0.068,
    fieldDepthScale: 0.29,
    icon: {
      width: 0.092,
      height: 0.059,
      borderRadius: 0.012,
      innerWidthScale: 0.62,
      innerHeightScale: 0.55,
      centerFromBottom: 0.176,
    },
  },
  openMenu: {
    avatarLeft: 0.049,
    avatarDiameter: 0.107,
    avatarTopFromBottom: 0.651,
    linkLeft: 0.049,
    linkFontSize: 0.047,
    linkCentersFromBottom: [0.456, 0.357, 0.257],
    settingsCenterFromBottom: 0.105,
    theme: {
      left: 0.049,
      width: 0.4,
      height: 0.1,
      centerFromBottom: 0.109,
      labelFontSize: 0.036,
    },
  },
} as const;

// OPEN field depth is width-based like REF_LAYOUT: ~1.05W from the bottom reaches mid photo-pair, as in the reference.
const DEFAULT_OPEN_PROGRESSION = 0.9;
// Bottom sheet height relative to the field width (user: "bottom height 1.30x").
const DEFAULT_EXPANDED_SCALE = 1.3;
const MAX_EXPANDED_DEPTH = 620;

// Motion extracted frame-by-frame from reference_demo_edge_fade.mp4 (60 fps).
// One emphasized curve explains panel open/close: near-zero launch velocity,
// fast middle section, then a long deceleration tail.
const FIELD_OPEN_MS = 600;
const FIELD_CLOSE_MS = 540;
const REFERENCE_MOTION_EASE = Easing.bezier(0.24, 0, 0.15, 1);

// Bottom-chrome timing measured separately from the material field.
// OPEN reference: closed nav 0.45→0.55, menu (incl. theme segment) 0.50→0.70.
// CLOSE reference: menu (incl. theme segment) 5.75→5.90, closed nav 5.95→6.10.
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

// Theme-change "single driver": one shared value (themeMotion, 0→1 linear,
// 1100ms) phase-locks everything a Dark/Light tap touches. Each channel reads
// its own eased window of that same 0→1 sweep inside a worklet, so colour,
// pill and panel-lift can never drift out of sync with each other:
//  - colour c(t): reference luma curve, over t in [0.30, 0.62] (swaps while
//    the screen is covered by the lift).
//  - pill p(t): reaches its destination first, ~200ms, over t in [0, 0.22].
//  - lift L(t) = sin(pi * e(t)): one smooth bell over the full sweep, no
//    hold, returns to exactly 0 at t=1 (the "breath" idea, without the old
//    withSequence hold/return discontinuity).
const THEME_MOTION_MS = 1100;
const THEME_COLOUR_WINDOW = { start: 0.3, end: 0.62 };
const THEME_PILL_WINDOW = 0.22;
const THEME_COLOUR_EASE = Easing.bezierFn(0.3, 0.05, 0.15, 1);
const THEME_PILL_EASE = Easing.bezierFn(0.3, 0, 0.1, 1);
const THEME_LIFT_EASE = Easing.bezierFn(0.33, 0, 0.2, 1);
const THEME_LIFT = 1;

function themeColourCurve(t: number) {
  'worklet';
  const norm =
    (t - THEME_COLOUR_WINDOW.start) /
    (THEME_COLOUR_WINDOW.end - THEME_COLOUR_WINDOW.start);
  return THEME_COLOUR_EASE(Math.min(Math.max(norm, 0), 1));
}

function themePillCurve(t: number) {
  'worklet';
  return THEME_PILL_EASE(Math.min(t / THEME_PILL_WINDOW, 1));
}

function smoothstep01(x: number) {
  'worklet';
  const c = Math.min(Math.max(x, 0), 1);
  return c * c * (3 - 2 * c);
}

// Living front: dome + noise + bloom band, only alive during theme motion —
// the wave follows the theme lift bell; colour swaps under cover.
const THEME_WAVE_AMP_SCALE = 0.1; // * W, at lift peak
const THEME_WAVE_DOME_SCALE = 0.22; // * W, at lift peak
const THEME_FRONT_GLOW = 1.0;
const WAVE_SPEED = 1.6;

// Material motion: strength loops 0.13–0.71 and surface progression rises/
// falls while anything moves (open, close or theme); rest values untouched.
const STRENGTH_LOOP_MIN = 0.13;
const STRENGTH_LOOP_MAX = 0.71;
const STRENGTH_LOOP_MID = (STRENGTH_LOOP_MIN + STRENGTH_LOOP_MAX) / 2;
const STRENGTH_LOOP_AMP = (STRENGTH_LOOP_MAX - STRENGTH_LOOP_MIN) / 2;
const STRENGTH_LOOP_PERIOD_S = 1.4;
const SURFACE_PROG_MOTION_FROM = 0.35;
const SURFACE_PROG_MOTION_TO = 1.0;
const MATERIAL_MOTION_WEIGHT_EDGE = 0.2;

const REFERENCE_BLUR_CURVE = {
  type: 'stops' as const,
  // Quadratic radius: the upper shoulder already softens (reference veils the
  // lower half of the photo pair), then diffuses broadly in the body.
  values: [
    1, 0.9931, 0.9722, 0.9375, 0.8889, 0.8264, 0.75, 0.6597, 0.5556, 0.4375,
    0.3056, 0.1597, 0,
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
  width,
}: {
  avatar: { source: ImageSourcePropType };
  name: string;
  subtitle: string;
  date: string;
  themeProgress: SharedValue<number>;
  width: number;
}) {
  const sizing = REF_LAYOUT.accountRowSizing;
  const avatarSize = width * sizing.avatar;
  const badgeSize = width * sizing.badge;
  const nameFontSize = Math.round(width * sizing.nameFontSize);
  const textLineHeight = Math.round(nameFontSize * sizing.lineHeightScale);
  const dateFontSize = Math.round(width * sizing.dateFontSize);
  const textGap = width * sizing.textGap;

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
      <View style={[s.accountIdentity, { gap: textGap }]}>
        <View style={{ width: avatarSize, height: avatarSize }}>
          <Image
            source={avatar.source}
            style={[
              s.avatar,
              {
                width: avatarSize,
                height: avatarSize,
                borderRadius: avatarSize / 2,
              },
            ]}
            contentFit="cover"
          />
          <Animated.View
            style={[
              s.badge,
              {
                width: badgeSize,
                height: badgeSize,
                borderRadius: badgeSize * 0.3,
              },
              badgeStyle,
            ]}
          >
            <View
              style={[
                s.badgeDot,
                {
                  width: badgeSize * 0.38,
                  height: badgeSize * 0.38,
                  borderRadius: badgeSize * 0.19,
                },
              ]}
            />
          </Animated.View>
        </View>

        <View>
          <Animated.Text
            style={[
              s.accountName,
              { fontSize: nameFontSize, lineHeight: textLineHeight },
              primaryTextStyle,
            ]}
          >
            {name}
          </Animated.Text>
          <Animated.Text
            style={[
              s.accountSubtitle,
              { fontSize: nameFontSize, lineHeight: textLineHeight },
              secondaryTextStyle,
            ]}
          >
            {subtitle}
          </Animated.Text>
        </View>
      </View>

      <Animated.Text
        style={[
          s.accountDate,
          {
            fontSize: dateFontSize,
            lineHeight: Math.round(dateFontSize * 1.2),
          },
          dateStyle,
        ]}
      >
        {date}
      </Animated.Text>
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
  const defaultClosedDepth = Math.min(
    180,
    Math.max(72, width * REF_LAYOUT.closedNav.fieldDepthScale)
  );
  const closedDepth = clampNumber(depthRaw, defaultClosedDepth, 72, 180);
  const expandedScale = clampNumber(scaleRaw, DEFAULT_EXPANDED_SCALE, 0.6, 1.6);
  const pixelRatio = PixelRatio.get();
  const blurRadiusDp = blurRadiusPx / pixelRatio;

  const [open, setOpen] = useState(false);
  const [darkMode, setDarkMode] = useState(false);
  const [debugStage, setDebugStage] = useState<
    'material' | 'field' | 'capture' | 'gaussian'
  >('material');
  const progress = useSharedValue(0);
  const closedNavOpacity = useSharedValue(1);
  const openMenuOpacity = useSharedValue(0);
  const openSegmentOpacity = useSharedValue(0);
  // Single theme driver: themeFrom/themeTo bracket the tap, themeMotion
  // sweeps 0→1, everything else is derived from it (see constants above).
  const themeMotion = useSharedValue(0);
  const themeFrom = useSharedValue(0);
  const themeTo = useSharedValue(0);
  // Advances only while theme motion is running, so the field has no idle
  // per-frame native work.
  const waveClock = useSharedValue(0);
  // Advances during open/close AND theme motion — drives the material's
  // strength loop and surface-progression rise/fall while anything moves.
  const materialClock = useSharedValue(0);

  const expandedDepth = Math.max(
    closedDepth + 150,
    Math.min(width * expandedScale, MAX_EXPANDED_DEPTH)
  );
  // Theme lift bell L(t), gated to 0 unless the panel is fully open — a tap
  // while closed still animates colours/pill but must not lift the panel.
  const liftValue = useDerivedValue(() => {
    'worklet';
    const eased = THEME_LIFT_EASE(themeMotion.value);
    return Math.sin(Math.PI * eased);
  });
  const themeLift = useDerivedValue(() =>
    progress.value >= 0.999 ? liftValue.value : 0
  );
  // progress runs [0,1] for closed→open; the theme lift bell adds on top of
  // the open height (single smooth rise/return, no discontinuity).
  const panelProgress = useDerivedValue(
    () => progress.value + THEME_LIFT * themeLift.value
  );
  const bottomDepth = useDerivedValue(() =>
    interpolate(
      panelProgress.value,
      [0, 1, 2],
      [closedDepth, expandedDepth, height],
      Extrapolation.CLAMP
    )
  );
  const blurProgression = useDerivedValue(() =>
    interpolate(
      panelProgress.value,
      [0, 1, 2],
      [1, openProgression, openProgression],
      Extrapolation.CLAMP
    )
  );
  const materialExposureValue = useDerivedValue(
    () => DEFAULT_MATERIAL_EXPOSURE + 0.1 * themeLift.value
  );
  // Living wave: alive only during theme motion, follows the theme lift bell.
  const waveAmplitude = useDerivedValue(
    () => THEME_WAVE_AMP_SCALE * width * themeLift.value * pixelRatio
  );
  const waveDome = useDerivedValue(
    () => THEME_WAVE_DOME_SCALE * width * themeLift.value * pixelRatio
  );
  const frontGlow = useDerivedValue(() => THEME_FRONT_GLOW * themeLift.value);
  // Motion envelope for the material: 0 at rest, 1 mid open/close or mid
  // theme lift. `a` is the soft entry/exit weight applied to the strength
  // loop and the surface-progression rise/fall (see constants above).
  const mOpen = useDerivedValue(() =>
    Math.sin(Math.PI * Math.min(Math.max(progress.value, 0), 1))
  );
  const materialMotion = useDerivedValue(() =>
    Math.max(mOpen.value, themeLift.value)
  );
  const materialMotionWeight = useDerivedValue(() =>
    smoothstep01(materialMotion.value / MATERIAL_MOTION_WEIGHT_EDGE)
  );
  const strengthLoop = useDerivedValue(
    () =>
      STRENGTH_LOOP_MID +
      STRENGTH_LOOP_AMP *
        Math.sin((2 * Math.PI * materialClock.value) / STRENGTH_LOOP_PERIOD_S)
  );
  const strengthValue = useDerivedValue(
    () =>
      materialStrength +
      (strengthLoop.value - materialStrength) * materialMotionWeight.value
  );
  const surfaceProgressionMotion = useDerivedValue(
    () =>
      SURFACE_PROG_MOTION_FROM +
      (SURFACE_PROG_MOTION_TO - SURFACE_PROG_MOTION_FROM) * materialMotion.value
  );
  const surfaceProgressionValue = useDerivedValue(
    () =>
      DEFAULT_MATERIAL_SURFACE_PROGRESSION +
      (surfaceProgressionMotion.value - DEFAULT_MATERIAL_SURFACE_PROGRESSION) *
        materialMotionWeight.value
  );
  // Colour and pill channels of the theme driver — both read themeMotion
  // through their own eased window, so a tap moves shape first, colour last.
  const themeSurfaceProgress = useDerivedValue(
    () =>
      themeFrom.value +
      (themeTo.value - themeFrom.value) * themeColourCurve(themeMotion.value)
  );
  const themeControlProgress = themeSurfaceProgress;
  const themePillProgress = useDerivedValue(
    () =>
      themeFrom.value +
      (themeTo.value - themeFrom.value) * themePillCurve(themeMotion.value)
  );

  // Reference scene geometry is tied to the viewport width, not to a scrolling
  // document. The native blur is the only thing changing during the transition.
  // Vertical positions are anchored to the screen bottom (H - W*k) because the
  // reference crops the top of the phone.
  const W = width;
  const H = height;
  const mediaLeft = W * REF_LAYOUT.media.left;
  const mediaWidth = W * REF_LAYOUT.media.width;
  const contentLeft = W * REF_LAYOUT.contentLeft;
  const contentRight = W * (1 - REF_LAYOUT.mediaRightMargin);

  // The reference is a continuous feed with no giant top card: card A runs
  // past the top of the screen and is clipped there, matching the reference.
  const lowerItem = SHOWCASE_IMAGES.beachBand;

  const accountHeight = W * REF_LAYOUT.accountRow1.height;
  const accountRow1Center = H - W * REF_LAYOUT.accountRow1.centerFromBottom;
  const firstAccountTop = accountRow1Center - accountHeight / 2;

  // Card B sits directly above account row 1; row 0 sits above card B; card A
  // sits above row 0 and is clipped by the page's overflow: hidden.
  const cardBHeight = mediaWidth / SHOWCASE_IMAGES.halftoneCircles.ratio;
  const cardBBottom = H - W * REF_LAYOUT.feed.cardBBottomFromBottom;
  const cardBTop = cardBBottom - cardBHeight;

  const feedRow0Center = cardBTop - W * REF_LAYOUT.feed.rowCenterToCardTopAbove;
  const feedRow0Top = feedRow0Center - accountHeight / 2;

  const cardAHeight = mediaWidth;
  const cardABottom =
    feedRow0Center - W * REF_LAYOUT.feed.cardBottomToRowCenterAbove;
  const cardATop = cardABottom - cardAHeight;

  const pairTop = H - W * REF_LAYOUT.photoPair.topFromBottom;
  const pairHeight = W * REF_LAYOUT.photoPair.height;
  const pairGap = W * REF_LAYOUT.photoPair.gap;
  const primaryWidth = W * REF_LAYOUT.photoPair.primaryWidth;
  // Same width as the primary tile: it runs past the right screen edge and is
  // clipped, matching the reference's horizontally-cut carousel. The page's
  // overflow: hidden clips it — the pair container itself must not.
  const secondaryWidth = W * REF_LAYOUT.photoPair.secondaryWidth;

  const accountRow2Center = H - W * REF_LAYOUT.accountRow2.centerFromBottom;
  const secondAccountTop = accountRow2Center - accountHeight / 2;

  const lowerTop = H - W * REF_LAYOUT.lowerCard.topFromBottom;
  // The reference extends past the screen bottom and is cut there.
  const lowerHeight =
    W * REF_LAYOUT.lowerCard.topFromBottom +
    W * REF_LAYOUT.lowerCard.extraHeight;

  // The reference chrome is not a simple remap of field progress. It has
  // direction-specific delays/durations, especially on CLOSE where the menu
  // disappears in ~90 ms while the material field keeps collapsing for 220 ms.
  const closedNavStyle = useAnimatedStyle(() => ({
    opacity: closedNavOpacity.value,
  }));

  // Avatar + the three links. The theme segment fades on its own schedule
  // (openSegmentOpacity, see segmentOpacityStyle below).
  const openMenuStyle = useAnimatedStyle(() => ({
    opacity: openMenuOpacity.value,
  }));

  const segmentOpacityStyle = useAnimatedStyle(() => ({
    opacity: openSegmentOpacity.value,
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
  const segmentWidth = W * REF_LAYOUT.openMenu.theme.width;
  const segmentHeight = W * REF_LAYOUT.openMenu.theme.height;
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
          themePillProgress.value,
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

    // Capture where the colour channel actually is right now (mid-tap-safe),
    // fold it into themeFrom, then sweep themeMotion 0→1 again. Pill and lift
    // read the same sweep through their own windows, so everything a tap
    // touches stays phase-locked with no separate timers to drift apart.
    cancelAnimation(themeMotion);
    const currentColour = themeColourCurve(themeMotion.value);
    themeFrom.value =
      themeFrom.value + (themeTo.value - themeFrom.value) * currentColour;
    themeTo.value = target;
    themeMotion.value = 0;
    themeMotion.value = withTiming(1, {
      duration: THEME_MOTION_MS,
      easing: Easing.linear,
    });

    cancelAnimation(waveClock);
    waveClock.value = withTiming(
      waveClock.value + (THEME_MOTION_MS / 1000) * WAVE_SPEED,
      { duration: THEME_MOTION_MS, easing: Easing.linear }
    );

    cancelAnimation(materialClock);
    materialClock.value = withTiming(
      materialClock.value + THEME_MOTION_MS / 1000,
      {
        duration: THEME_MOTION_MS,
        easing: Easing.linear,
      }
    );
  };

  const togglePanel = () => {
    const next = !open;
    setOpen(next);

    cancelAnimation(progress);
    cancelAnimation(closedNavOpacity);
    cancelAnimation(openMenuOpacity);
    cancelAnimation(openSegmentOpacity);
    cancelAnimation(materialClock);

    const fieldDurationMs = next ? FIELD_OPEN_MS : FIELD_CLOSE_MS;
    progress.value = withTiming(next ? 1 : 0, {
      duration: fieldDurationMs,
      easing: REFERENCE_MOTION_EASE,
    });
    materialClock.value = withTiming(
      materialClock.value + fieldDurationMs / 1000,
      {
        duration: fieldDurationMs,
        easing: Easing.linear,
      }
    );

    if (next) {
      closedNavOpacity.value = withDelay(
        CLOSED_NAV_HIDE_DELAY_MS,
        withTiming(0, {
          duration: CLOSED_NAV_HIDE_MS,
          easing: CHROME_OUT_EASE,
        })
      );
      openSegmentOpacity.value = withDelay(
        MENU_SHOW_DELAY_MS,
        withTiming(1, {
          duration: MENU_SHOW_MS,
          easing: CHROME_IN_EASE,
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
      openSegmentOpacity.value = withDelay(
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
        ? 'field'
        : current === 'field'
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

  // Closed nav geometry.
  const closedNavLabelFontSize = Math.round(
    W * REF_LAYOUT.closedNav.labelFontSize
  );
  const closedNavLabelLineHeight = Math.round(closedNavLabelFontSize * 1.2);
  const closedNavLabelCenterY =
    H - W * REF_LAYOUT.closedNav.labelCenterFromBottom;
  const closedNavLabelTop =
    closedNavLabelCenterY - closedNavLabelLineHeight / 2;
  const icon = REF_LAYOUT.closedNav.icon;
  const iconWidth = W * icon.width;
  const iconHeight = W * icon.height;
  const iconBorderRadius = W * icon.borderRadius;
  const iconCenterY = H - W * icon.centerFromBottom;
  const iconTop = iconCenterY - iconHeight / 2;
  const closedNavCenterBottom =
    closedNavLabelCenterY + closedNavLabelLineHeight / 2;
  const closedNavCenterHeight = closedNavCenterBottom - iconTop;

  // Open menu geometry.
  const om = REF_LAYOUT.openMenu;
  const menuAvatarSize = W * om.avatarDiameter;
  const menuAvatarTop = H - W * om.avatarTopFromBottom;
  const menuLinkFontSize = Math.round(W * om.linkFontSize);
  const menuLinkLineHeight = Math.round(menuLinkFontSize * 1.2);
  const menuLinkCenters = om.linkCentersFromBottom.map((k) => H - W * k);
  const themeSegmentCenterY = H - W * om.theme.centerFromBottom;
  const themeSegmentTop = themeSegmentCenterY - segmentHeight / 2;
  const themeSegmentLabelFontSize = Math.round(W * om.theme.labelFontSize);
  const persistentSettingsCenterY = H - W * om.settingsCenterFromBottom;
  const persistentSettingsTop =
    persistentSettingsCenterY - closedNavLabelLineHeight / 2;

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
        progressiveMaterialStrength={strengthValue}
        progressiveWaveAmplitude={waveAmplitude}
        progressiveWaveDome={waveDome}
        progressiveWaveTime={waveClock}
        progressiveFrontGlow={frontGlow}
        progressiveMaterialColor={LIGHT_MATERIAL_COLOR}
        progressiveMaterialColorDark={DARK_MATERIAL_COLOR}
        progressiveMaterialThemeProgress={themeSurfaceProgress}
        progressiveMaterialExposure={materialExposureValue}
        progressiveMaterialSurface={DEFAULT_MATERIAL_SURFACE}
        progressiveMaterialSurfaceProgression={surfaceProgressionValue}
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
        // The reference fuses cards and page into one wash, so the low-res
        // field carries neutrals + luminance; curve offset veils the photo
        // pair before detail loss.
        progressiveMaterialColorFieldNeutralWeight={
          DEFAULT_MATERIAL_COLOR_FIELD_NEUTRAL_WEIGHT
        }
        progressiveMaterialCurveOffset={DEFAULT_MATERIAL_CURVE_OFFSET}
        progressiveMaterialCurveHeight={DEFAULT_MATERIAL_CURVE_HEIGHT}
        style={StyleSheet.absoluteFill}
      >
        <Animated.View
          pointerEvents="none"
          style={[StyleSheet.absoluteFill, surfaceStyle]}
        >
          <Image
            source={SHOWCASE_IMAGES.amberSkyline.source}
            style={[
              s.previousCard,
              {
                left: mediaLeft,
                top: cardATop,
                width: mediaWidth,
                height: cardAHeight,
              },
            ]}
            contentFit="cover"
          />

          <View
            style={[
              s.accountSlot,
              {
                left: contentLeft,
                top: feedRow0Top,
                width: contentRight - contentLeft,
                height: accountHeight,
              },
            ]}
          >
            <AccountRow
              avatar={SHOWCASE_IMAGES.skyPortrait}
              name="tape.archive"
              subtitle="Field notes on cassette"
              date="Mon"
              themeProgress={themeSurfaceProgress}
              width={W}
            />
          </View>

          <Image
            source={SHOWCASE_IMAGES.halftoneCircles.source}
            style={[
              s.previousCard,
              {
                left: mediaLeft,
                top: cardBTop,
                width: mediaWidth,
                height: cardBHeight,
              },
            ]}
            contentFit="cover"
          />

          <View
            style={[
              s.accountSlot,
              {
                left: contentLeft,
                top: firstAccountTop,
                width: contentRight - contentLeft,
                height: accountHeight,
              },
            ]}
          >
            <AccountRow
              avatar={SHOWCASE_IMAGES.windswept}
              name="roma.daily"
              subtitle="by Studio 19"
              date="Today"
              themeProgress={themeSurfaceProgress}
              width={W}
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
              source={SHOWCASE_IMAGES.windswept.source}
              style={[
                s.photo,
                {
                  width: primaryWidth,
                  height: pairHeight,
                  marginRight: pairGap,
                },
              ]}
              contentFit="cover"
            />
            <Image
              source={SHOWCASE_IMAGES.cassettes.source}
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
                left: contentLeft,
                top: secondAccountTop,
                width: contentRight - contentLeft,
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
              width={W}
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
            : debugStage === 'field'
              ? 'FIELD'
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
        pointerEvents={open ? 'none' : 'box-none'}
        style={[StyleSheet.absoluteFill, s.chromeLayer, closedNavStyle]}
      >
        <Text
          style={[
            s.closedNavLabel,
            {
              position: 'absolute',
              left: W * REF_LAYOUT.closedNav.viewLeft,
              top: closedNavLabelTop,
              fontSize: closedNavLabelFontSize,
              lineHeight: closedNavLabelLineHeight,
            },
          ]}
        >
          View
        </Text>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Open perfection menu"
          onPress={togglePanel}
          hitSlop={18}
          style={[
            s.closedNavCenter,
            { top: iconTop, height: closedNavCenterHeight },
          ]}
        >
          <View
            style={[
              s.perfectionIcon,
              {
                width: iconWidth,
                height: iconHeight,
                borderRadius: iconBorderRadius,
              },
            ]}
          >
            <View
              style={[
                s.perfectionInner,
                {
                  width: iconWidth * icon.innerWidthScale,
                  height: iconHeight * icon.innerHeightScale,
                  borderRadius: iconBorderRadius * 0.5,
                },
              ]}
            />
          </View>
          <Text
            style={[
              s.closedNavLabel,
              {
                fontSize: closedNavLabelFontSize,
                lineHeight: closedNavLabelLineHeight,
              },
            ]}
          >
            Perfection
          </Text>
        </Pressable>
      </Animated.View>

      <Text
        pointerEvents="none"
        style={[
          s.closedNavLabel,
          s.persistentSettings,
          {
            right: W * REF_LAYOUT.closedNav.settingsRight,
            top: persistentSettingsTop,
            fontSize: closedNavLabelFontSize,
            lineHeight: closedNavLabelLineHeight,
          },
        ]}
      >
        Settings
      </Text>

      <Animated.View
        pointerEvents={open ? 'box-none' : 'none'}
        style={[StyleSheet.absoluteFill, s.chromeLayer]}
      >
        <Animated.View
          pointerEvents="box-none"
          style={[StyleSheet.absoluteFill, openMenuStyle]}
        >
          <Image
            source={SHOWCASE_IMAGES.profile.source}
            style={[
              s.menuAvatar,
              {
                left: W * om.avatarLeft,
                top: menuAvatarTop,
                width: menuAvatarSize,
                height: menuAvatarSize,
                borderRadius: menuAvatarSize / 2,
              },
            ]}
            contentFit="cover"
          />

          <Animated.Text
            style={[
              s.menuLink,
              menuLinkTextStyle,
              {
                left: W * om.linkLeft,
                top: menuLinkCenters[0] - menuLinkLineHeight / 2,
                fontSize: menuLinkFontSize,
                lineHeight: menuLinkLineHeight,
              },
            ]}
          >
            Subscription
          </Animated.Text>
          <Animated.Text
            style={[
              s.menuLink,
              menuLinkTextStyle,
              {
                left: W * om.linkLeft,
                top: menuLinkCenters[1] - menuLinkLineHeight / 2,
                fontSize: menuLinkFontSize,
                lineHeight: menuLinkLineHeight,
              },
            ]}
          >
            Extension
          </Animated.Text>
          <Animated.Text
            style={[
              s.menuLink,
              menuLinkTextStyle,
              {
                left: W * om.linkLeft,
                top: menuLinkCenters[2] - menuLinkLineHeight / 2,
                fontSize: menuLinkFontSize,
                lineHeight: menuLinkLineHeight,
              },
            ]}
          >
            About
          </Animated.Text>
        </Animated.View>

        <Animated.View
          style={[
            s.themeSegment,
            {
              left: W * om.theme.left,
              top: themeSegmentTop,
              width: segmentWidth,
              height: segmentHeight,
              borderRadius: segmentHeight / 2,
              padding: segmentInset,
            },
            segmentTrackStyle,
            segmentOpacityStyle,
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
              style={[
                s.themeSegmentLabel,
                { fontSize: themeSegmentLabelFontSize },
                darkSegmentTextStyle,
              ]}
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
              style={[
                s.themeSegmentLabel,
                { fontSize: themeSegmentLabelFontSize },
                lightSegmentTextStyle,
              ]}
            >
              Light
            </Animated.Text>
          </Pressable>
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

  debugProbe: {
    position: 'absolute',
    right: 8,
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

  persistentSettings: {
    position: 'absolute',
    zIndex: 31,
  },
  closedNavCenter: {
    position: 'absolute',
    left: 0,
    right: 0,
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  closedNavLabel: {
    color: 'rgba(255,255,255,0.93)',
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
  chromeLayer: {
    zIndex: 30,
  },
  menuAvatar: {
    position: 'absolute',
    backgroundColor: '#d6d2cd',
  },
  menuLink: {
    position: 'absolute',
    fontWeight: '600',
    textShadowColor: 'rgba(0,0,0,0.18)',
    textShadowOffset: { width: 0, height: 1 },
    textShadowRadius: 4,
  },
  themeSegment: {
    position: 'absolute',
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
    fontWeight: '600',
    letterSpacing: -0.08,
  },
});
