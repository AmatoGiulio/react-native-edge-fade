import { useState } from 'react';
import {
  Pressable,
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

const HERO = STILLS_ITEMS[0]!;
const THUMBS = [STILLS_ITEMS[1]!, STILLS_ITEMS[8]!, STILLS_ITEMS[10]!];

const OPEN_MS = 560;
const CLOSE_MS = 460;
const EASE = Easing.bezier(0.16, 1, 0.3, 1);

export default function ProgressiveShowcaseRoute() {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const [open, setOpen] = useState(false);

  const progress = useSharedValue(0);
  const bottomDepth = useSharedValue(118);

  const openDepth = Math.min(height * 0.7, 560);
  const dockClosedWidth = Math.min(width - 28, 420);
  const dockOpenWidth = Math.min(width - 28, 440);

  const dockStyle = useAnimatedStyle(() => ({
    width: interpolate(
      progress.value,
      [0, 1],
      [dockClosedWidth, dockOpenWidth]
    ),
    height: interpolate(progress.value, [0, 1], [68, 294]),
    borderRadius: interpolate(progress.value, [0, 1], [24, 32]),
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [0, -18]) },
    ],
  }));

  const collapsedStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0, 0.2, 0.45], [1, 0.55, 0]),
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [0, -18]) },
      { scale: interpolate(progress.value, [0, 0.5], [1, 0.96]) },
    ],
  }));

  const expandedStyle = useAnimatedStyle(() => ({
    opacity: interpolate(progress.value, [0.2, 0.48, 1], [0, 0.2, 1]),
    transform: [
      { translateY: interpolate(progress.value, [0, 1], [24, 0]) },
      { scale: interpolate(progress.value, [0, 1], [0.985, 1]) },
    ],
  }));

  const heroStyle = useAnimatedStyle(() => ({
    transform: [
      { scale: interpolate(progress.value, [0, 1], [1, 1.018]) },
      { translateY: interpolate(progress.value, [0, 1], [0, -8]) },
    ],
  }));

  const toggle = () => {
    const next = !open;
    setOpen(next);
    const duration = next ? OPEN_MS : CLOSE_MS;

    progress.value = withTiming(next ? 1 : 0, {
      duration,
      easing: EASE,
    });

    bottomDepth.value = withTiming(next ? openDepth : 118, {
      duration,
      easing: EASE,
    });
  };

  return (
    <View style={s.page}>
      <Stack.Screen options={{ headerShown: false }} />

      <AnimatedEdgeFadeView
        mode="blur"
        top={0}
        bottom={bottomDepth}
        left={0}
        right={0}
        curve="smooth"
        blurRadius={150}
        blurProgression={1}
        style={StyleSheet.absoluteFill}
      >
        <Animated.View style={[StyleSheet.absoluteFill, heroStyle]}>
          <Image
            source={HERO.source}
            style={StyleSheet.absoluteFill}
            contentFit="cover"
            contentPosition="center"
          />
        </Animated.View>
      </AnimatedEdgeFadeView>

      <View pointerEvents="none" style={s.scrim} />

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Close showcase"
        hitSlop={18}
        onPress={() => router.back()}
        style={[s.close, { top: insets.top + 8 }]}
      >
        <Text style={s.closeText}>×</Text>
      </Pressable>

      <Animated.View
        style={[
          s.dock,
          { bottom: insets.bottom + 12 },
          dockStyle,
        ]}
      >
        <Animated.View
          pointerEvents={open ? 'none' : 'auto'}
          style={[s.collapsed, collapsedStyle]}
        >
          <View style={s.miniThumbs}>
            {THUMBS.map((item) => (
              <Image
                key={item.id}
                source={item.source}
                style={s.miniThumb}
                contentFit="cover"
              />
            ))}
          </View>

          <View style={s.collapsedCopy}>
            <Text style={s.collapsedEyebrow}>PROGRESSIVE BLUR</Text>
            <Text style={s.collapsedLabel}>Open the edge</Text>
          </View>

          <View style={s.chevron}>
            <Text style={s.chevronText}>↑</Text>
          </View>
        </Animated.View>

        <Animated.View
          pointerEvents={open ? 'auto' : 'none'}
          style={[s.expanded, expandedStyle]}
        >
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

          <View style={s.metaRow}>
            <Text style={s.meta}>150 PX</Text>
            <Text style={s.meta}>NATIVE</Text>
            <Text style={s.meta}>PROGRESSIVE</Text>
          </View>
        </Animated.View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={open ? 'Collapse progressive blur' : 'Expand progressive blur'}
          onPress={toggle}
          style={StyleSheet.absoluteFill}
        />
      </Animated.View>

      <Text
        pointerEvents="none"
        style={[s.hint, { bottom: insets.bottom + 94 }]}
      >
        TAP TO {open ? 'COLLAPSE' : 'EXPAND'}
      </Text>
    </View>
  );
}

const s = StyleSheet.create({
  page: {
    flex: 1,
    backgroundColor: '#000',
  },
  scrim: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: 'rgba(0,0,0,0.08)',
  },
  close: {
    position: 'absolute',
    right: 18,
    zIndex: 20,
    width: 42,
    height: 42,
    alignItems: 'center',
    justifyContent: 'center',
  },
  closeText: {
    color: '#fff',
    fontSize: 30,
    lineHeight: 32,
    fontWeight: '200',
  },
  dock: {
    position: 'absolute',
    alignSelf: 'center',
    overflow: 'hidden',
  },
  collapsed: {
    ...StyleSheet.absoluteFillObject,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
  },
  miniThumbs: {
    flexDirection: 'row',
  },
  miniThumb: {
    width: 42,
    height: 42,
    borderRadius: 12,
    marginRight: -8,
    borderWidth: 1,
    borderColor: 'rgba(255,255,255,0.12)',
  },
  collapsedCopy: {
    flex: 1,
    marginLeft: 18,
  },
  collapsedEyebrow: {
    color: 'rgba(255,255,255,0.52)',
    fontSize: 8,
    lineHeight: 10,
    letterSpacing: 1.6,
    fontWeight: '700',
  },
  collapsedLabel: {
    color: '#fff',
    fontSize: 14,
    lineHeight: 18,
    fontWeight: '650',
    marginTop: 2,
  },
  chevron: {
    width: 34,
    height: 34,
    borderRadius: 17,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(255,255,255,0.09)',
  },
  chevronText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  expanded: {
    ...StyleSheet.absoluteFillObject,
    justifyContent: 'flex-end',
    paddingHorizontal: 24,
    paddingBottom: 22,
  },
  eyebrow: {
    color: 'rgba(255,255,255,0.62)',
    fontSize: 9,
    lineHeight: 12,
    letterSpacing: 2.1,
    fontWeight: '700',
    marginBottom: 9,
  },
  title: {
    color: '#fff',
    fontSize: 32,
    lineHeight: 33,
    letterSpacing: -1.3,
    fontWeight: '700',
    maxWidth: 330,
  },
  body: {
    color: 'rgba(255,255,255,0.68)',
    fontSize: 12,
    lineHeight: 18,
    marginTop: 12,
    maxWidth: 310,
  },
  thumbRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 17,
  },
  thumb: {
    width: 46,
    height: 46,
    borderRadius: 11,
  },
  metaRow: {
    flexDirection: 'row',
    gap: 14,
    marginTop: 16,
  },
  meta: {
    color: 'rgba(255,255,255,0.42)',
    fontSize: 8,
    letterSpacing: 1.2,
    fontWeight: '700',
  },
  hint: {
    position: 'absolute',
    alignSelf: 'center',
    color: 'rgba(255,255,255,0.4)',
    fontSize: 8,
    letterSpacing: 1.7,
    fontWeight: '700',
  },
});
