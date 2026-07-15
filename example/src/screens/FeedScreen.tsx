// Vertical photo pager (not an X/Twitter feed): one near-full-screen rounded
// photo card at a time, snapped, with the next/previous card peeking at the
// edges. `mode="lens"` is HARDWIRED here (not read from the fade store) so
// this screen always demonstrates the liquid-glass lens effect regardless of
// whatever mode is selected in the debug panel.
import { memo } from 'react';
import { StyleSheet, useWindowDimensions, View } from 'react-native';
import { FlashList } from '@shopify/flash-list';
import { Image } from 'expo-image';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';

import { STILLS_ITEMS, type CatalogItem } from '@/data/catalog';
import { useFadeStore, useFadeRender } from '@/fade/FadeContext';
import { useTheme } from '@/theme';

const H_MARGIN = 14;
const CARD_HEIGHT_RATIO = 0.84;
const CARD_RADIUS = 28;
const GAP = 12;
// Matches the fixed header offset used across the example screens (see
// GalleryScreen's `listContent.paddingTop`) so the first/last card centers
// under the transparent header instead of the raw screen edge.
const HEADER_OFFSET = 116;

function keyExtractor(item: CatalogItem) {
  return item.id;
}

const PhotoCard = memo(function PhotoCard({
  item,
  cardWidth,
  cardHeight,
}: {
  item: CatalogItem;
  cardWidth: number;
  cardHeight: number;
}) {
  return (
    <View style={[s.cardWrap, { height: cardHeight + GAP }]}>
      <Image
        source={item.source}
        style={[
          s.card,
          {
            width: cardWidth,
            height: cardHeight,
            backgroundColor: item.color + '33',
          },
        ]}
        contentFit="cover"
        placeholder={
          item.blur_hash && item.blur_hash.length >= 6
            ? { blurhash: item.blur_hash }
            : undefined
        }
        transition={300}
      />
    </View>
  );
});

export function FeedScreen() {
  const t = useTheme();
  const { width, height } = useWindowDimensions();
  const { top, bottom, left, right, radius } = useFadeStore();
  const {
    curve,
    lensRefraction,
    lensDispersion,
    lensSaturation,
    lensContrast,
    lensSpecular,
    lensAngle,
  } = useFadeRender();

  const cardWidth = width - H_MARGIN * 2;
  const visibleHeight = height - HEADER_OFFSET;
  const cardHeight = visibleHeight * CARD_HEIGHT_RATIO;
  const itemHeight = cardHeight + GAP;
  const sidePadding = (visibleHeight - itemHeight) / 2;

  function renderItem({ item }: { item: CatalogItem }) {
    return (
      <PhotoCard item={item} cardWidth={cardWidth} cardHeight={cardHeight} />
    );
  }

  return (
    <View style={[s.root, { backgroundColor: t.bg }]}>
      <AnimatedEdgeFadeView
        mode="lens"
        top={top}
        bottom={bottom}
        left={left}
        right={right}
        radius={radius}
        curve={curve}
        lens={{
          refraction: lensRefraction,
          dispersion: lensDispersion,
          saturation: lensSaturation,
          contrast: lensContrast,
          specular: lensSpecular,
          angle: lensAngle,
        }}
        style={[StyleSheet.absoluteFill, { backgroundColor: t.bg }]}
      >
        <FlashList
          data={STILLS_ITEMS}
          keyExtractor={keyExtractor}
          renderItem={renderItem}
          showsVerticalScrollIndicator={false}
          snapToInterval={itemHeight}
          decelerationRate="fast"
          disableIntervalMomentum
          contentContainerStyle={{
            paddingTop: HEADER_OFFSET + sidePadding,
            paddingBottom: sidePadding,
          }}
        />
      </AnimatedEdgeFadeView>
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1 },

  cardWrap: {
    paddingHorizontal: H_MARGIN,
    paddingBottom: GAP,
    alignItems: 'center',
  },

  card: { borderRadius: CARD_RADIUS },
});
