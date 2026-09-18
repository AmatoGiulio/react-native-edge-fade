import { useEffect, useState } from 'react';
import {
  Pressable,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from 'react-native';
import { Image } from 'expo-image';
import { Stack, router } from 'expo-router';
import Animated, {
  Easing,
  interpolate,
  useAnimatedStyle,
  useSharedValue,
  withDelay,
  withTiming,
} from 'react-native-reanimated';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';
import { STILLS_ITEMS } from '@/data/catalog';
import { useScheme } from '@/theme';

const HERO = STILLS_ITEMS[0]!;
const THUMBS = [STILLS_ITEMS[1]!, STILLS_ITEMS[8]!, STILLS_ITEMS[10]!];

const OPEN_MS = 560;
const EASE = Easing.bezier(0.16, 1, 0.3, 1);

export default function ProgressiveShowcaseRoute() {
  const scheme = useScheme();
  const dark = scheme === 'dark';
  const { width, height } = useWindowDimensions();
  const [open, setOpen] = useState(false);
  const progress = useSharedValue(0);

  const cardWidth = Math.min(width - 36, 430);
  const cardHeight = Math.min(height * 0.69, cardWidth * 1.48);
  const bottomClosed = 12;
  const bottomOpen = Math.min(cardHeight * 0.78, 440);
  const bottom = useSharedValue(bottomClosed);

  useEffect(() => {
    bottom.value = withDelay(
      320,
      withTiming(bottomOpen, { duration: OPEN_MS, easing: EASE })
    );
  }, [bottom, bottomOpen]);

  useEffect(() => {
    progress.value = withDelay(
      320,
      withTiming(1, { duration: OPEN_MS, easing: EASE })
    );
    setOpen(true);
  }, [progress]);

  const cardStyle = useAnimatedStyle(() => ({
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [8, 0]) },
      { scale: interpolate(progress.value, [0, 1], [0.992, 1]) },
    ],
  }));

  const bodyStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0, 0.42, 1], [0, 0.16, 1]),
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [18, 0]) },
    ],
  }));

  const toggle = () => {
    const next = !open;
    setOpen(next);
    const duration = next ? OPEN_MS : 500;
    progress.value = withTiming(next ? 1 : 0, {
      duration,
      easing: EASE,
    });
    bottom.value = withTiming(next ? bottomOpen : bottomClosed, {
      duration,
      easing: EASE,
    });
  };

  return (
    <View style={[s.page, { backgroundColor: dark ? '#000' : '#f5f5f3' }]}>
      <Stack.Screen options={{ headerShown: false }} />

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Close showcase"
        hitSlop={18}
        onPress={() => router.back()}
        style={s.close}
      >
        <Text style={[s.closeText, { color: dark ? '#fff' : '#111' }]}>×</Text>
      </Pressable>

      <Animated.View
        style={[
          s.card,
          {
            width: cardWidth,
            height: cardHeight,
            backgroundColor: dark ? '#111' : '#fff',
          },
          cardStyle,
        ]}
      >
        <AnimatedEdgeFadeView
          mode="blur"
          top={0}
          bottom={bottom}
          curve="smooth"
          blurRadius={150}
          blurProgression={1}
          style={StyleSheet.absoluteFill}
        >
          <Image source={HERO.source} style={StyleSheet.absoluteFill} contentFit="cover" />
        </AnimatedEdgeFadeView>

        <Animated.View pointerEvents="none" style={[s.copy, bodyStyle]}>
          <Text style={s.eyebrow}>PROGRESSIVE BLUR</Text>
          <Text style={s.title}>Beautifully soft.{"\n"}Native at the edge.</Text>
          <Text style={s.body}>
            A spatial Gaussian blur that grows into the content instead of
            hiding it behind a flat overlay.
          </Text>
          <View style={s.thumbRow}>
            {THUMBS.map((item) => (
              <Image
                key={item.id}
                source={item.source}
                style={s.thumb}
                contentFit="cover"
              />
            ))}
          </View>
        </Animated.View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={open ? 'Collapse progressive blur' : 'Expand progressive blur'}
          onPress={toggle}
          style={StyleSheet.absoluteFill}
        />
      </Animated.View>

      <Text style={[s.hint, { color: dark ? '#777' : '#999' }]}>
        TAP TO {open ? 'COLLAPSE' : 'EXPAND'}
      </Text>
    </View>
  );
}

const s = StyleSheet.create({
  page: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 18,
  },
  close: {
    position: 'absolute',
    top: 54,
    right: 24,
    zIndex: 10,
    width: 40,
    height: 40,
    alignItems: 'center',
    justifyContent: 'center',
  },
  closeText: { fontSize: 32, fontWeight: '200', lineHeight: 34 },
  card: {
    borderRadius: 28,
    overflow: 'hidden',
  },
  copy: {
    position: 'absolute',
    left: 26,
    right: 26,
    bottom: 28,
  },
  eyebrow: {
    color: '#fff',
    opacity: 0.7,
    fontSize: 10,
    lineHeight: 13,
    letterSpacing: 2.2,
    fontWeight: '700',
    marginBottom: 10,
  },
  title: {
    color: '#fff',
    fontSize: 34,
    lineHeight: 35,
    letterSpacing: -1.35,
    fontWeight: '700',
    maxWidth: 330,
  },
  body: {
    color: 'rgba(255,255,255,0.72)',
    fontSize: 13,
    lineHeight: 19,
    marginTop: 13,
    maxWidth: 315,
  },
  thumbRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 18,
  },
  thumb: {
    width: 48,
    height: 48,
    borderRadius: 10,
  },
  hint: {
    position: 'absolute',
    bottom: 32,
    fontSize: 9,
    letterSpacing: 1.8,
    fontWeight: '700',
  },
});
