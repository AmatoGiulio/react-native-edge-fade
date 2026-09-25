import { useState } from 'react';
import {
  type ImageSourcePropType,
  type StyleProp,
  type TextStyle,
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
  useFrameCallback,
  useSharedValue,
  withDelay,
  withTiming,
} from 'react-native-reanimated';
import { scheduleOnRN } from 'react-native-worklets';
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
// Dark strength; light uses LIGHT_MATERIAL_STRENGTH (follows g(t)).
const DEFAULT_MATERIAL_STRENGTH = 1;
const LIGHT_MATERIAL_STRENGTH = 0.45;
const DEFAULT_MATERIAL_EXPOSURE = 0.98;
const DEFAULT_MATERIAL_SURFACE = 0.78;
const DEFAULT_MATERIAL_SURFACE_PROGRESSION = 0.95;
const LIGHT_MATERIAL_COLOR = '#c6c2c4';
// Near-black smoke anchor: colour shapes come from the source field, not from
// a silver/grey material tint.
const DARK_MATERIAL_COLOR = '#010101';
const DEFAULT_MATERIAL_COLOR_FIELD_MIX = 1;
// 0.20: finer low-res grid, so the bilinear upscale leaves no row creases.
const DEFAULT_MATERIAL_COLOR_FIELD_SCALE = 0.2;
const DEFAULT_MATERIAL_COLOR_FIELD_BLUR_RADIUS_PX = 320;
const DEFAULT_MATERIAL_COLOR_FIELD_CHROMA_GATE = 0.04;
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
// blurProgression is animated on `progress` (open/close only):
// interpolate([0,1], [1, openProgression]), so the blur gradient densifies as
// the panel opens, matching 098aa76.
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

// Theme change = two overlapping processes.
// 1. Global transition g(t), measured on the reference video: every surface
//    (page, text, pill, material tone) changes uniformly in direct sRGB;
//    Light→Dark 10/50/90% at ~50/130/290 ms, settled ~400 ms; Dark→Light
//    ~15% faster. One bezier fitted to those points drives it.
// 2. Light wave V0, the sheet's own material phenomenon: one very wide
//    exposure wave (gaussian in depth, gain in stops) rises through the FMASK
//    material. Only the material response's exposure changes; diffusion,
//    detail and colour hue are untouched. Values are calibration parameters.
const THEME_EASE = Easing.bezier(0.35, 0, 0.2, 1);
const THEME_LD = { ms: 420, stops: 0.5 };
const THEME_DL = { ms: 430, stops: 0.4 };
// Wave centre travels from below the sheet to past its top fade with a
// finite launch speed: c(τ) = from + (to - from)·(1 - (1 - τ)^P).
const LIGHT_WAVE_FROM = -0.6;
const LIGHT_WAVE_TO = 1.5;
const LIGHT_WAVE_POWER = 1.6;
const LIGHT_WAVE_WIDTH = 0.35;
// Gain ramps in over the first ~60 ms and out over the last 20%.
const LIGHT_WAVE_RAMP_IN_MS = 60;

// Marea: the theme toggle releases a flame of material from the centre of
// the sheet. It only rises: a soft push, a free climb, the crest breaks
// against the top edge of the screen, then it settles back to rest from above
// (never below it) while the theme changes. The surface amplitude a(t) is
// normalised to the space above the open panel (1 = the top edge, a wall):
//   push    : v eases 0 -> v0 over TIDE_PUSH_S (the tap's impulse)
//   flight  : a'' = -g                   (one parabola, aimed past 1)
//   impact  : a'' = -g - Ωw²(a-1) - 2ζwΩw a'   (a > 1, the wall absorbs it)
//   fall    : a'' = -Ωf²a - 2Ωf a'       (critically damped, lands from above)
// The theme follows the fall: 0 at the start of the descent, 1 at rest.
const TIDE_V0 = true;
const TIDE_REACH = 1; // wall = top edge of the screen
const TIDE_CENTER = 0.5; // the flame always rises from the screen centre
const TIDE_WIDTH = 1.1; // base FWHM, fraction of screen width
const TIDE_SHARPNESS = 1.7; // core exponent: 2 = round dome, lower = flame tip
const TIDE_TAPER = 0.12; // how much narrower the tongue gets at the top
const TIDE_VOLUME = 0; // no returned-volume trough: the flame only rises
const TIDE_MENISCUS_PX = 0; // replaced by the surface lens (native)
const TIDE_FLICKER = 0.07; // edge ripple, fraction of the space above panel
const TIDE_SWAY = 0.05; // lateral sway of the flame, fraction of width
const TIDE_SWAY_HZ = 1.1;
const TIDE_BREATH = 0.12; // width breathing while it burns
const TIDE_BREATH_HZ = 1.7;
// Free-flight apex, in wall units: the extra energy is what the wall absorbs.
const TIDE_ENERGY = 1.45;
const TIDE_TIME_S = 0.6; // gravity time scale (unit parabola rise time)
const TIDE_PUSH_S = 0.16;
const TIDE_WALL_OMEGA = 4.5; // wall cushion stiffness (rad/s)
const TIDE_WALL_DAMPING = 0.7;
const TIDE_FALL_OMEGA = 6;
// How much wider the crest spreads per unit of penetration into the wall.
const TIDE_SPLASH = 10;
const TIDE_GRAVITY = 2 / (TIDE_TIME_S * TIDE_TIME_S);
const TIDE_LAUNCH_SPEED = Math.sqrt(2 * TIDE_GRAVITY * TIDE_ENERGY);
const TIDE_STEP_S = 0.001;
const TIDE_REST = 0;
const TIDE_PUSH = 1;
const TIDE_FLIGHT = 2;
const TIDE_FALL = 3;

// Dark smoke reads too grey/white at the default exposure; darken it while
// the theme is dark.
const DARK_MATERIAL_EXPOSURE = 0.7;

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

// Theme colour change as an opacity cross-fade of two static copies: animating
// Text `color` re-lays out the text on every frame (Fabric updateState +
// StaticLayout), which saturated the UI thread during the theme swap.
function CrossfadeText({
  progress,
  colors,
  style,
  children,
}: {
  progress: SharedValue<number>;
  colors: [string, string];
  style: StyleProp<TextStyle>;
  children: string;
}) {
  const fromStyle = useAnimatedStyle(() => ({ opacity: 1 - progress.value }));
  const toStyle = useAnimatedStyle(() => ({ opacity: progress.value }));
  return (
    <View>
      <Animated.Text style={[style, { color: colors[0] }, fromStyle]}>
        {children}
      </Animated.Text>
      <Animated.Text
        style={[style, { color: colors[1] }, s.crossfadeTop, toStyle]}
      >
        {children}
      </Animated.Text>
    </View>
  );
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
          <CrossfadeText
            progress={themeProgress}
            colors={['#111111', '#f3f2ef']}
            style={[
              s.accountName,
              { fontSize: nameFontSize, lineHeight: textLineHeight },
            ]}
          >
            {name}
          </CrossfadeText>
          <CrossfadeText
            progress={themeProgress}
            colors={['#222222', '#d9d7d2']}
            style={[
              s.accountSubtitle,
              { fontSize: nameFontSize, lineHeight: textLineHeight },
            ]}
          >
            {subtitle}
          </CrossfadeText>
        </View>
      </View>

      <CrossfadeText
        progress={themeProgress}
        colors={['#aaa7a2', '#85827d']}
        style={[
          s.accountDate,
          {
            fontSize: dateFontSize,
            lineHeight: Math.round(dateFontSize * 1.2),
          },
        ]}
      >
        {date}
      </CrossfadeText>
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
    'material' | 'field' | 'fieldmask' | 'capture' | 'gaussian'
  >('fieldmask');
  const progress = useSharedValue(0);
  const closedNavOpacity = useSharedValue(1);
  const openMenuOpacity = useSharedValue(0);
  const openSegmentOpacity = useSharedValue(0);
  // Theme colour progress: 0 = light, 1 = dark. Surface, text, pill and
  // material-theme progress all read this single value.
  const themeColour = useSharedValue(0);
  // Light wave clock (linear 0 -> 1 over the transition) and direction
  // (1 = towards dark).
  const phaseT = useSharedValue(0);
  const phaseToDark = useSharedValue(1);
  // Marea surface state (see TIDE_* above).
  const tideAmount = useSharedValue(0);
  const tideVelocity = useSharedValue(0);
  const tideShape = useSharedValue(1);
  const tideCenter = useSharedValue(TIDE_CENTER);
  const tidePhase = useSharedValue(TIDE_REST);
  const tideTime = useSharedValue(0);
  // Theme carried by the fall: target, value at the start of the descent and
  // the height the descent starts from (-1 = not descending yet).
  const tideThemeTo = useSharedValue(0);
  const tideThemeFrom = useSharedValue(0);
  const tideFallFrom = useSharedValue(-1);

  const commitDarkMode = (nextDark: boolean) => setDarkMode(nextDark);

  useFrameCallback((frame) => {
    'worklet';
    if (tidePhase.value === TIDE_REST) return;
    const dt = Math.min((frame.timeSincePreviousFrame ?? 16) / 1000, 0.05);
    let a = tideAmount.value;
    let v = tideVelocity.value;
    let phase = tidePhase.value;
    let time = tideTime.value;
    for (let t = 0; t < dt; t += TIDE_STEP_S) {
      const h = Math.min(TIDE_STEP_S, dt - t);
      time += h;
      if (phase === TIDE_PUSH) {
        // Smoothstep velocity ramp: the flame leaves with zero acceleration
        // jerk and reaches launch speed at the end of the push.
        const u = Math.min(time / TIDE_PUSH_S, 1);
        v = TIDE_LAUNCH_SPEED * u * u * (3 - 2 * u);
        a += v * h;
        if (u >= 1) phase = TIDE_FLIGHT;
        continue;
      }
      let acc: number;
      if (phase === TIDE_FLIGHT) {
        acc = -TIDE_GRAVITY;
        if (a > 1) {
          acc -=
            TIDE_WALL_OMEGA * TIDE_WALL_OMEGA * (a - 1) +
            2 * TIDE_WALL_DAMPING * TIDE_WALL_OMEGA * v;
        }
      } else {
        acc = -TIDE_FALL_OMEGA * TIDE_FALL_OMEGA * a - 2 * TIDE_FALL_OMEGA * v;
      }
      v += acc * h;
      a += v * h;
      if (phase === TIDE_FLIGHT && v < 0) {
        phase = TIDE_FALL;
        tideFallFrom.value = Math.max(a, 1e-3);
        tideThemeFrom.value = themeColour.value;
      } else if (phase === TIDE_FALL && a < 2e-3 && Math.abs(v) < 2e-2) {
        phase = TIDE_REST;
        a = 0;
        v = 0;
        break;
      }
    }
    a = Math.max(a, 0);
    // Base width breathes and the tongue tapers as it climbs; the crest
    // spreads along the top edge while pressed into it.
    const lit = Math.min(a, 1);
    tideShape.value = Math.min(
      1 -
        TIDE_TAPER * lit +
        TIDE_BREATH * lit * Math.sin(2 * Math.PI * TIDE_BREATH_HZ * time) +
        TIDE_SPLASH * Math.max(a - 1, 0),
      3
    );
    tideCenter.value =
      TIDE_CENTER +
      TIDE_SWAY * lit * Math.sin(2 * Math.PI * TIDE_SWAY_HZ * time + 0.6);
    // The theme is deposited by the fall and completes at rest.
    if (tideFallFrom.value > 0) {
      const fall =
        phase === TIDE_REST
          ? 1
          : Math.min(Math.max(1 - a / tideFallFrom.value, 0), 1);
      const from = tideThemeFrom.value;
      themeColour.value = from + (tideThemeTo.value - from) * fall;
      if (phase === TIDE_REST) {
        tideFallFrom.value = -1;
        scheduleOnRN(commitDarkMode, tideThemeTo.value > 0.5);
      }
    }
    tideAmount.value = a;
    tideVelocity.value = v;
    tidePhase.value = phase;
    tideTime.value = time;
  });

  const pressTide = (nextDark: boolean) => {
    cancelAnimation(themeColour);
    tideThemeTo.value = nextDark ? 1 : 0;
    // A new press while the flame is still up keeps its height and speed.
    if (tidePhase.value === TIDE_REST) {
      tideTime.value = 0;
      tidePhase.value = TIDE_PUSH;
    } else if (tidePhase.value === TIDE_FALL) {
      tideFallFrom.value = -1;
      tideVelocity.value = Math.max(tideVelocity.value, 0) + TIDE_LAUNCH_SPEED;
      tidePhase.value = TIDE_FLIGHT;
    }
  };

  const expandedDepth = Math.max(
    closedDepth + 150,
    Math.min(width * expandedScale, MAX_EXPANDED_DEPTH)
  );
  const bottomDepth = useDerivedValue(() =>
    interpolate(
      progress.value,
      [0, 1],
      [closedDepth, expandedDepth],
      Extrapolation.CLAMP
    )
  );
  // Blur gradient densifies as the panel opens (098aa76), driven by `progress`.
  const blurProgressionValue = useDerivedValue(() =>
    interpolate(
      progress.value,
      [0, 1],
      [1, openProgression],
      Extrapolation.CLAMP
    )
  );
  // Light wave V0 channels, gated to the fully open panel.
  const lightWaveCenter = useDerivedValue(() => {
    const t = Math.min(Math.max(phaseT.value, 0), 1);
    return (
      LIGHT_WAVE_FROM +
      (LIGHT_WAVE_TO - LIGHT_WAVE_FROM) *
        (1 - Math.pow(1 - t, LIGHT_WAVE_POWER))
    );
  });
  const lightWaveStops = useDerivedValue(() => {
    const t = phaseT.value;
    if (progress.value < 0.999 || t <= 0 || t >= 1) return 0;
    const c = phaseToDark.value > 0.5 ? THEME_LD : THEME_DL;
    const ease = (x: number) => x * x * (3 - 2 * x);
    const rampIn = Math.min(t / (LIGHT_WAVE_RAMP_IN_MS / c.ms), 1);
    const out = Math.min(Math.max((1 - t) / 0.2, 0), 1);
    return c.stops * ease(rampIn) * ease(out);
  });
  // Material strength is part of the global transition: it follows g(t).
  const strengthValue = useDerivedValue(
    () =>
      LIGHT_MATERIAL_STRENGTH +
      (materialStrength - LIGHT_MATERIAL_STRENGTH) * themeColour.value
  );
  // Colour and pill channels all read the single global value g(t).
  const themeSurfaceProgress = themeColour;
  const themeControlProgress = themeSurfaceProgress;
  const themePillProgress = themeSurfaceProgress;
  // Darkens with the global theme transition g(t).
  const materialExposureValue = useDerivedValue(
    () =>
      DEFAULT_MATERIAL_EXPOSURE +
      (DARK_MATERIAL_EXPOSURE - DEFAULT_MATERIAL_EXPOSURE) *
        themeSurfaceProgress.value
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

  const setTheme = (nextDark: boolean) => {
    if (nextDark === darkMode) return;
    setDarkMode(nextDark);
    const target = nextDark ? 1 : 0;

    const c = nextDark ? THEME_LD : THEME_DL;
    cancelAnimation(themeColour);
    themeColour.value = withTiming(target, {
      duration: c.ms,
      easing: THEME_EASE,
    });

    cancelAnimation(phaseT);
    phaseToDark.value = target;
    phaseT.value = 0;
    phaseT.value = withTiming(1, { duration: c.ms, easing: Easing.linear });
  };

  const togglePanel = () => {
    const next = !open;
    setOpen(next);

    cancelAnimation(progress);
    cancelAnimation(closedNavOpacity);
    cancelAnimation(openMenuOpacity);
    cancelAnimation(openSegmentOpacity);

    const fieldDurationMs = next ? FIELD_OPEN_MS : FIELD_CLOSE_MS;
    progress.value = withTiming(next ? 1 : 0, {
      duration: fieldDurationMs,
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
          ? 'fieldmask'
          : current === 'fieldmask'
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
        blurProgression={blurProgressionValue}
        progressiveBackend={debugBackend}
        progressiveNativeTuner={true}
        // Static per theme: no strength motion on open/close or theme swap.
        progressiveMaterialStrength={strengthValue}
        progressiveLightWaveCenter={lightWaveCenter}
        progressiveLightWaveStops={lightWaveStops}
        progressiveLightWaveWidth={LIGHT_WAVE_WIDTH}
        progressiveTideAmount={tideAmount}
        progressiveTideShape={tideShape}
        progressiveTideCenter={tideCenter}
        progressiveTideHeight={TIDE_V0 ? TIDE_REACH : 0}
        progressiveTideWidth={TIDE_WIDTH}
        progressiveTideVolume={TIDE_VOLUME}
        progressiveTideMeniscus={TIDE_MENISCUS_PX}
        progressiveTideSharpness={TIDE_SHARPNESS}
        progressiveTideFlicker={TIDE_FLICKER}
        progressiveTideTime={tideTime}
        progressiveMaterialColor={LIGHT_MATERIAL_COLOR}
        progressiveMaterialColorDark={DARK_MATERIAL_COLOR}
        progressiveMaterialThemeProgress={themeSurfaceProgress}
        progressiveMaterialExposure={materialExposureValue}
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
              : debugStage === 'fieldmask'
                ? 'FMASK'
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
            onPressIn={() => TIDE_V0 && pressTide(true)}
            onPress={() => !TIDE_V0 && setTheme(true)}
            style={s.themeSegmentHit}
          >
            <CrossfadeText
              progress={themeControlProgress}
              colors={['rgba(91,86,79,0.88)', '#f6f3ee']}
              style={[
                s.themeSegmentLabel,
                { fontSize: themeSegmentLabelFontSize },
              ]}
            >
              Dark
            </CrossfadeText>
          </Pressable>

          <Pressable
            accessibilityRole="button"
            accessibilityState={{ selected: !darkMode }}
            onPressIn={() => TIDE_V0 && pressTide(false)}
            onPress={() => !TIDE_V0 && setTheme(false)}
            style={s.themeSegmentHit}
          >
            <CrossfadeText
              progress={themeControlProgress}
              colors={['#171513', 'rgba(206,201,194,0.72)']}
              style={[
                s.themeSegmentLabel,
                { fontSize: themeSegmentLabelFontSize },
              ]}
            >
              Light
            </CrossfadeText>
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
  crossfadeTop: {
    position: 'absolute',
    left: 0,
    top: 0,
  },
  menuLink: {
    position: 'absolute',
    color: 'rgba(255,255,255,0.97)',
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
