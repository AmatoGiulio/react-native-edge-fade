import { useCallback } from 'react';
import { PixelRatio, Pressable, StyleSheet, Text, View } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useVideoPlayer, VideoView } from 'expo-video';
import Animated, {
  interpolate,
  useAnimatedStyle,
  useDerivedValue,
  useSharedValue,
  withSpring,
} from 'react-native-reanimated';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';

const SOURCE = require('../assets/video/meeting.mp4');

// Geometry, in dp.
const CARD_BOTTOM = 64; // above the search row
const CARD_REST = 52; // collapsed chip
const CARD_OPEN = 300;
// The blur front sits this far above the card top, so the sharp-to-blurred
// ramp is visible above the card instead of hidden behind it. Open, the ramp
// reaches into the live video.
const REST_LEAD = 56;
const OPEN_LEAD = 360;
const REST_FADE = CARD_BOTTOM + CARD_REST + REST_LEAD;
const OPEN_FADE = CARD_BOTTOM + CARD_OPEN + OPEN_LEAD;
// Radius at the bottom edge: a quiet fade at rest, 150 physical px open.
const REST_BLUR = 18;
const OPEN_BLUR = 150 / PixelRatio.get();

const CONTENT_OPEN_SCALE = 0.95;

const SPRING = { duration: 650, dampingRatio: 0.92 };

const TRANSCRIPT = [
  {
    who: 'Maya',
    time: '1:04',
    color: '#E9A23B',
    text: 'Thanks for joining. The goal today is to agree on the new chat bubble before we hand it to engineering.',
  },
  {
    who: 'Leo',
    time: '1:40',
    color: '#5B8DEF',
    text: 'Sharing my screen now. Incoming and outgoing bubbles use the same grey, so long threads are hard to scan.',
  },
  {
    who: 'Maya',
    time: '2:12',
    color: '#E9A23B',
    text: 'Agreed. What if outgoing messages take the accent colour and we tighten the corner radius a bit?',
  },
  {
    who: 'Ines',
    time: '2:51',
    color: '#3FB28F',
    text: 'I can prototype both versions this afternoon and run a quick test with five people tomorrow.',
  },
  {
    who: 'Leo',
    time: '3:27',
    color: '#5B8DEF',
    text: 'Perfect. Let’s also check contrast in dark mode, the grey is almost invisible there.',
  },
];

/**
 * Release demo: a meeting screen with a live video. Opening the action card
 * raises the progressive blur front with it — sharp above, increasingly
 * blurred below, per pixel, while the video keeps playing.

 */
export default function MeetingScreen() {
  const insets = useSafeAreaInsets();
  const { blur, radiusPx } = useLocalSearchParams<{
    blur?: string;
    radiusPx?: string;
  }>();
  // `?blur=off` keeps the same scene and motion without the effect (benchmark
  // baseline); `?radiusPx=` overrides the open radius in physical pixels.
  const blurOff = blur === 'off';
  const parsedRadius = Number.parseFloat(radiusPx ?? '');
  const openBlur = Number.isFinite(parsedRadius)
    ? parsedRadius / PixelRatio.get()
    : OPEN_BLUR;

  const player = useVideoPlayer(SOURCE, (p) => {
    p.loop = true;
    p.muted = true;
    p.play();
  });

  const progress = useSharedValue(0);
  const toggle = useCallback(() => {
    progress.set(withSpring(progress.get() > 0.5 ? 0 : 1, SPRING));
  }, [progress]);

  const bottom = useDerivedValue(
    () =>
      interpolate(progress.get(), [0, 1], [REST_FADE, OPEN_FADE]) +
      insets.bottom
  );
  const blurRadius = useDerivedValue(() =>
    blurOff ? 0 : interpolate(progress.get(), [0, 1], [REST_BLUR, openBlur])
  );

  const cardStyle = useAnimatedStyle(() => ({
    height: interpolate(progress.get(), [0, 1], [CARD_REST, CARD_OPEN]),
  }));
  // The page behind recedes slightly as the card comes forward.
  const contentStyle = useAnimatedStyle(() => ({
    transform: [
      { scale: interpolate(progress.get(), [0, 1], [1, CONTENT_OPEN_SCALE]) },
    ],
  }));
  const detailStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.get(), [0.35, 1], [0, 1], 'clamp'),
    transform: [
      { translateY: interpolate(progress.get(), [0, 1], [12, 0], 'clamp') },
    ],
  }));

  return (
    <View style={styles.root}>
      <AnimatedEdgeFadeView
        mode="blur"
        bottom={bottom}
        blurRadius={blurRadius}
        curve="smoother"
        style={styles.fill}
      >
        <Animated.View
          style={[
            styles.content,
            { paddingTop: insets.top + 24 },
            contentStyle,
          ]}
        >
          <View style={styles.videoCard}>
            <VideoView
              player={player}
              surfaceType="textureView"
              contentFit="cover"
              nativeControls={false}
              style={styles.fill}
            />
            <View style={styles.liveBadge}>
              <View style={styles.liveDot} />
              <Text style={styles.liveText}>LIVE</Text>
            </View>
          </View>

          {TRANSCRIPT.map((line, i) => (
            <View key={i} style={styles.line}>
              <View style={[styles.avatar, { backgroundColor: line.color }]}>
                <Text style={styles.avatarText}>{line.who[0]}</Text>
              </View>
              <View style={styles.lineBody}>
                <Text style={styles.who}>
                  {line.who}
                  <Text style={styles.time}>{'  ' + line.time}</Text>
                </Text>
                <Text style={[styles.text, i > 1 && styles.textMuted]}>
                  {line.text}
                </Text>
              </View>
            </View>
          ))}
        </Animated.View>
      </AnimatedEdgeFadeView>

      <Animated.View
        style={[
          styles.card,
          { bottom: CARD_BOTTOM + insets.bottom },
          cardStyle,
        ]}
      >
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="toggle action card"
          onPress={toggle}
          style={styles.chip}
        >
          <View style={[styles.icon, { backgroundColor: '#2F7CF6' }]}>
            <Text style={styles.iconText}>✦</Text>
          </View>
          <Text style={styles.chipTitle}>Improve chat bubble</Text>
          <View style={styles.pill}>
            <Text style={styles.pillText}>Add to board</Text>
          </View>
        </Pressable>
        <Animated.View style={[styles.detail, detailStyle]}>
          <Text style={styles.detailText}>
            Outgoing and incoming bubbles share one grey, so long threads are
            hard to scan. Give outgoing messages the accent colour and tighten
            the corner radius.
          </Text>
          <View style={styles.subtaskHead}>
            <View style={[styles.icon, { backgroundColor: '#E6397A' }]}>
              <Text style={styles.iconText}>≡</Text>
            </View>
            <Text style={styles.chipTitle}>Subtasks</Text>
          </View>
          <Text style={styles.subtask}>• Prototype both bubble variants</Text>
          <Text style={styles.subtask}>• Check contrast in dark mode</Text>
          <Text style={styles.subtask}>• Five-person test tomorrow</Text>
        </Animated.View>
      </Animated.View>

      <View style={[styles.search, { bottom: insets.bottom + 18 }]}>
        <Text style={styles.searchText}>⌕ Search in the meeting</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#FFFFFF' },
  fill: { flex: 1, backgroundColor: '#FFFFFF' },
  content: { flex: 1, paddingHorizontal: 20 },
  videoCard: {
    height: 210,
    borderRadius: 22,
    overflow: 'hidden',
    backgroundColor: '#000000',
    marginBottom: 22,
  },
  liveBadge: {
    position: 'absolute',
    top: 12,
    left: 12,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    borderRadius: 10,
    backgroundColor: 'rgba(0,0,0,0.45)',
    paddingHorizontal: 8,
    paddingVertical: 4,
  },
  liveDot: { width: 7, height: 7, borderRadius: 4, backgroundColor: '#FF3B30' },
  liveText: { color: '#FFFFFF', fontSize: 11, fontWeight: '700' },
  line: { flexDirection: 'row', gap: 12, marginBottom: 18 },
  avatar: {
    width: 28,
    height: 28,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarText: { color: '#FFFFFF', fontWeight: '700', fontSize: 13 },
  lineBody: { flex: 1 },
  who: { fontSize: 15, fontWeight: '700', color: '#0A0A0A', marginBottom: 4 },
  time: { fontSize: 13, fontWeight: '400', color: '#8E8E93' },
  text: { fontSize: 16, lineHeight: 23, color: '#0A0A0A' },
  textMuted: { color: '#6C6C70' },
  card: {
    position: 'absolute',
    left: 20,
    right: 20,
    borderRadius: 24,
    overflow: 'hidden',
    backgroundColor: 'rgba(255,255,255,0.62)',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(0,0,0,0.08)',
  },
  chip: {
    height: CARD_REST,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    gap: 10,
  },
  icon: {
    width: 26,
    height: 26,
    borderRadius: 7,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconText: { color: '#FFFFFF', fontSize: 14, fontWeight: '700' },
  chipTitle: { flex: 1, fontSize: 15, fontWeight: '600', color: '#0A0A0A' },
  pill: {
    borderRadius: 14,
    backgroundColor: '#FFFFFF',
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  pillText: { color: '#2F7CF6', fontSize: 14, fontWeight: '600' },
  detail: { paddingHorizontal: 16, paddingTop: 4 },
  detailText: {
    fontSize: 15,
    lineHeight: 22,
    color: '#3A3A3C',
    marginBottom: 18,
  },
  subtaskHead: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    marginBottom: 8,
  },
  subtask: { fontSize: 15, lineHeight: 24, color: '#3A3A3C', paddingLeft: 36 },
  search: { position: 'absolute', left: 0, right: 0, alignItems: 'center' },
  searchText: { fontSize: 15, color: '#8E8E93' },
});
