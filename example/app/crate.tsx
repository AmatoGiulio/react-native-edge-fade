import { useCallback, useState } from 'react';
import {
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from 'react-native';
import { StatusBar } from 'expo-status-bar';
import { Image as ExpoImage } from 'expo-image';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Animated, {
  Easing,
  useAnimatedStyle,
  useSharedValue,
  withDelay,
  withTiming,
} from 'react-native-reanimated';
import { EdgeFadeView } from 'react-native-edge-fade';
import { CrateCanvas } from '../modules/crate-canvas';

import { CRATE_COVERS } from '@/data/crateCovers';

const BG = '#222222';
const INK = '#ECF3F3';

// Resting fades; the native canvas raises them itself while a cover is open.
const REST_FADE = 140;
const REST_BLUR = 24;

// Must match the native hero geometry (CrateCanvasView companion).
const HERO_RATIO = 0.72;
const HERO_LIFT = 40;

const TEXT_IN = { duration: 280, easing: Easing.out(Easing.cubic) };
const TEXT_OUT = { duration: 140, easing: Easing.in(Easing.quad) };

const STAR = require('../assets/crate/star.svg');
const THUMBS = CRATE_COVERS.map((c) => c.thumb);
const thumbUri = (thumb: string) => `file:///android_asset/crate/${thumb}`;

const RECORDS = [
  {
    artist: 'Kurilo',
    title: 'Elastic Man',
    cat: 'TP009',
    side: 'B1',
    bpm: 128,
  },
  {
    artist: 'vault.',
    title: 'Spook y Scary',
    cat: 'UX020',
    side: 'A2',
    bpm: 132,
  },
  { artist: 'Rarog', title: 'Dark Sun', cat: 'AM016', side: 'A1', bpm: 134 },
  {
    artist: 'Podolskyi',
    title: 'Flow Check',
    cat: 'TNCD010',
    side: 'A1',
    bpm: 126,
  },
  {
    artist: 'Busso',
    title: 'Anna In The Jelly',
    cat: 'DGS010',
    side: 'B1',
    bpm: 124,
  },
  {
    artist: 'KOKLA',
    title: 'Acid Motion',
    cat: 'S2S001',
    side: 'A1',
    bpm: 138,
  },
  {
    artist: 'Ruton',
    title: 'Rave-O-Lution',
    cat: 'CON007',
    side: 'B2',
    bpm: 140,
  },
  {
    artist: 'a.sl',
    title: 'Romantic Engineerings',
    cat: 'SCORN01',
    side: 'A2',
    bpm: 122,
  },
];

function fit(ratio: number, box: number) {
  return ratio >= 1 ? { w: box, h: box / ratio } : { w: box * ratio, h: box };
}

/**
 * Demo: an endless crate of records on a lens-like surface. The grid, the
 * selection (hero flight, grid receding) and the edge fades rising all run
 * natively on one spring; React only shows the record's text.
 */
export default function CrateScreen() {
  const { width, height } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const [current, setCurrent] = useState(0);
  const [closeSignal, setCloseSignal] = useState(0);
  const text = useSharedValue(0);

  const cover = CRATE_COVERS[current]!;
  const record = RECORDS[current % RECORDS.length]!;
  const heroBox = fit(cover.ratio, width * HERO_RATIO);
  const heroBottom = height / 2 - HERO_LIFT + heroBox.h / 2;

  const onCoverPress = useCallback(
    (e: { nativeEvent: { index: number } }) => {
      setCurrent(e.nativeEvent.index);
      text.set(withDelay(200, withTiming(1, TEXT_IN)));
    },
    [text]
  );
  const onClose = useCallback(() => {
    text.set(withTiming(0, TEXT_OUT));
  }, [text]);

  const textStyle = useAnimatedStyle(() => ({
    opacity: text.get(),
    transform: [{ translateY: (1 - text.get()) * 8 }],
  }));

  return (
    <View style={styles.root}>
      <StatusBar style="light" />
      <EdgeFadeView
        mode="blur"
        top={REST_FADE}
        bottom={REST_FADE}
        blurRadius={REST_BLUR}
        curve="smoother"
        style={styles.fill}
      >
        <CrateCanvas
          covers={THUMBS}
          closeSignal={closeSignal}
          onCoverPress={onCoverPress}
          onClose={onClose}
          style={styles.fill}
        />
      </EdgeFadeView>

      <Animated.View
        pointerEvents="none"
        style={[styles.detail, { top: heroBottom + 22 }, textStyle]}
      >
        <Text style={styles.artist}>{record.artist}</Text>
        <Text style={styles.record}>{record.title}</Text>
        <Text style={styles.meta}>
          {`[${record.cat}] · ${record.side} · ${record.bpm} BPM`}
        </Text>
      </Animated.View>

      <View
        pointerEvents="none"
        style={[styles.player, { bottom: insets.bottom + 16 }]}
      >
        <ExpoImage
          source={{ uri: thumbUri(cover.thumb) }}
          transition={0}
          cachePolicy="memory"
          style={styles.thumb}
        />
        <View style={styles.playerBody}>
          <Text style={styles.playerTitle} numberOfLines={1}>
            {record.artist}
            <Text style={styles.playerSub}>{'  ' + record.title}</Text>
          </Text>
          <View style={styles.track}>
            <View style={styles.trackFill} />
          </View>
        </View>
      </View>

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="crate"
        onPress={() => setCloseSignal((n) => n + 1)}
        hitSlop={16}
        style={[styles.tab, { top: insets.top + 10 }]}
      >
        <ExpoImage source={STAR} style={styles.star} />
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: BG },
  fill: { flex: 1, backgroundColor: BG },
  detail: { position: 'absolute', left: 0, right: 0, alignItems: 'center' },
  artist: { color: INK, fontSize: 20, fontWeight: '600' },
  record: { marginTop: 2, color: INK, opacity: 0.6, fontSize: 15 },
  meta: {
    marginTop: 10,
    color: INK,
    opacity: 0.45,
    fontSize: 12,
    fontFamily: Platform.select({ ios: 'Menlo', default: 'monospace' }),
    letterSpacing: 0.4,
  },
  tab: { position: 'absolute', alignSelf: 'center' },
  star: { width: 30, height: 30 },
  player: {
    position: 'absolute',
    left: 20,
    right: 20,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  thumb: { width: 40, height: 40, borderRadius: 9 },
  playerBody: { flex: 1, gap: 8 },
  playerTitle: { color: INK, fontSize: 14, fontWeight: '600' },
  playerSub: { color: INK, opacity: 0.5, fontWeight: '400' },
  track: {
    height: 2,
    borderRadius: 1,
    backgroundColor: 'rgba(236,243,243,0.18)',
  },
  trackFill: {
    width: '38%',
    height: 2,
    borderRadius: 1,
    backgroundColor: INK,
  },
});
