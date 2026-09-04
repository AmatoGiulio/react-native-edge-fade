import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
  type NativeScrollEvent,
  type NativeSyntheticEvent,
} from 'react-native';
import { Image } from 'expo-image';
import { router } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import Animated, {
  Extrapolation,
  interpolate,
  useAnimatedScrollHandler,
  useAnimatedStyle,
  useSharedValue,
  type SharedValue,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { EdgeFadeView, type EdgeFadeMode } from 'react-native-edge-fade';

import { useCatalog, type CatalogItem } from '@/data/catalog';

const BACKGROUND = '#050505';
const CARD_RADIUS = 30;
const CARD_GAP = 12;
const AUTOPLAY_MS = 2400;
const MANUAL_PAUSE_MS = 5000;

interface LensCardProps {
  item: CatalogItem;
  index: number;
  mode: EdgeFadeMode;
  scrollY: SharedValue<number>;
  step: number;
  width: number;
  height: number;
}

function LensCard({
  item,
  index,
  mode,
  scrollY,
  step,
  width,
  height,
}: LensCardProps) {
  const animatedStyle = useAnimatedStyle(() => {
    const center = index * step;
    const input = [center - step, center, center + step];

    return {
      opacity: interpolate(
        scrollY.value,
        input,
        [0.62, 1, 0.62],
        Extrapolation.CLAMP
      ),
      transform: [
        {
          scale: interpolate(
            scrollY.value,
            input,
            [0.9, 1, 0.9],
            Extrapolation.CLAMP
          ),
        },
      ],
    };
  }, [index, step]);

  return (
    <View style={[s.slot, { height: step }]}>
      <Animated.View style={[s.card, { width, height }, animatedStyle]}>
        <EdgeFadeView
          mode={mode}
          radius={CARD_RADIUS}
          style={[s.lens, { backgroundColor: item.color + '33' }]}
        >
          <Image source={item.source} style={s.image} contentFit="cover" />
        </EdgeFadeView>

        <View pointerEvents="none" style={s.badge}>
          <Text style={s.badgeGlyph}>✓</Text>
        </View>
      </Animated.View>
    </View>
  );
}

export function LensScreen() {
  const insets = useSafeAreaInsets();
  const { width: screenWidth, height: screenHeight } = useWindowDimensions();
  const { catalog } = useCatalog();
  const [enabled, setEnabled] = useState(true);

  const listRef = useRef<FlatList<CatalogItem>>(null);
  const activeIndex = useRef(0);
  const direction = useRef(1);
  const pauseUntil = useRef(0);
  const scrollY = useSharedValue(0);

  const items = useMemo(() => {
    const portraits = catalog.filter((item) => item.category === 'Portrait');
    const source = portraits.length >= 6 ? portraits : catalog;
    return source.slice(0, 7);
  }, [catalog]);

  const cardWidth = Math.min(screenWidth - 68, 360);
  const cardHeight = Math.min(screenHeight * 0.62, cardWidth * 1.58);
  const step = cardHeight + CARD_GAP;
  const verticalInset = Math.max((screenHeight - cardHeight) / 2, 0);
  const mode: EdgeFadeMode = enabled ? 'lens' : 'mask';

  const scrollHandler = useAnimatedScrollHandler({
    onScroll: (event) => {
      scrollY.value = event.contentOffset.y;
    },
  });

  const renderItem = useCallback(
    ({ item, index }: { item: CatalogItem; index: number }) => (
      <LensCard
        item={item}
        index={index}
        mode={mode}
        scrollY={scrollY}
        step={step}
        width={cardWidth}
        height={cardHeight}
      />
    ),
    [cardHeight, cardWidth, mode, scrollY, step]
  );

  const onMomentumScrollEnd = useCallback(
    (event: NativeSyntheticEvent<NativeScrollEvent>) => {
      activeIndex.current = Math.max(
        0,
        Math.min(
          items.length - 1,
          Math.round(event.nativeEvent.contentOffset.y / step)
        )
      );
    },
    [items.length, step]
  );

  useEffect(() => {
    if (items.length < 2) return;

    const timer = setInterval(() => {
      if (Date.now() < pauseUntil.current) return;

      let next = activeIndex.current + direction.current;
      if (next >= items.length) {
        direction.current = -1;
        next = Math.max(items.length - 2, 0);
      } else if (next < 0) {
        direction.current = 1;
        next = Math.min(1, items.length - 1);
      }

      activeIndex.current = next;
      listRef.current?.scrollToOffset({ offset: next * step, animated: true });
    }, AUTOPLAY_MS);

    return () => clearInterval(timer);
  }, [items.length, step]);

  return (
    <View style={s.screen}>
      <StatusBar style="light" />

      {items.length > 0 ? (
        <Animated.FlatList
          ref={listRef}
          data={items}
          keyExtractor={(item) => item.id}
          renderItem={renderItem}
          onScroll={scrollHandler}
          onScrollBeginDrag={() => {
            pauseUntil.current = Date.now() + MANUAL_PAUSE_MS;
          }}
          onMomentumScrollEnd={onMomentumScrollEnd}
          scrollEventThrottle={16}
          snapToInterval={step}
          snapToAlignment="start"
          decelerationRate="fast"
          disableIntervalMomentum
          showsVerticalScrollIndicator={false}
          overScrollMode="never"
          contentContainerStyle={{ paddingVertical: verticalInset }}
          initialNumToRender={3}
          maxToRenderPerBatch={3}
          windowSize={5}
          getItemLayout={(_, index) => ({
            length: step,
            offset: step * index,
            index,
          })}
        />
      ) : (
        <View style={s.empty}>
          <Text style={s.emptyText}>lens</Text>
        </View>
      )}

      <View
        pointerEvents="box-none"
        style={[s.chrome, { top: Math.max(insets.top, 12) + 8 }]}
      >
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Back"
          hitSlop={10}
          onPress={() => router.back()}
          style={({ pressed }) => [
            s.backButton,
            { opacity: pressed ? 0.58 : 1 },
          ]}
        >
          <Text style={s.backGlyph}>‹</Text>
        </Pressable>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={enabled ? 'Disable lens' : 'Enable lens'}
          onPress={() => setEnabled((value) => !value)}
          style={({ pressed }) => [
            s.modeButton,
            enabled && s.modeButtonEnabled,
            { opacity: pressed ? 0.62 : 1 },
          ]}
        >
          <View style={[s.modeDot, enabled && s.modeDotEnabled]} />
          <Text style={s.modeLabel}>{enabled ? 'lens' : 'plain'}</Text>
        </Pressable>
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: BACKGROUND,
  },
  slot: {
    width: '100%',
    alignItems: 'center',
    justifyContent: 'center',
  },
  card: {
    borderRadius: CARD_RADIUS,
  },
  lens: {
    flex: 1,
    overflow: 'hidden',
  },
  image: {
    flex: 1,
  },
  badge: {
    position: 'absolute',
    right: 11,
    bottom: 11,
    width: 24,
    height: 24,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#FFFFFF',
  },
  badgeGlyph: {
    color: '#111111',
    fontSize: 13,
    lineHeight: 15,
    fontWeight: '800',
  },
  chrome: {
    position: 'absolute',
    left: 16,
    right: 16,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  backButton: {
    width: 32,
    height: 32,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(18,18,18,0.72)',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.16)',
  },
  backGlyph: {
    color: '#FFFFFF',
    fontSize: 27,
    lineHeight: 28,
    marginTop: -2,
  },
  modeButton: {
    height: 32,
    borderRadius: 16,
    paddingHorizontal: 12,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    backgroundColor: 'rgba(18,18,18,0.72)',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.16)',
  },
  modeButtonEnabled: {
    backgroundColor: 'rgba(255,255,255,0.94)',
  },
  modeDot: {
    width: 6,
    height: 6,
    borderRadius: 3,
    backgroundColor: '#777777',
  },
  modeDotEnabled: {
    backgroundColor: '#111111',
  },
  modeLabel: {
    color: '#A6A6A6',
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 0.2,
  },
  empty: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  emptyText: {
    color: '#666666',
    fontSize: 12,
    letterSpacing: 1,
  },
});
