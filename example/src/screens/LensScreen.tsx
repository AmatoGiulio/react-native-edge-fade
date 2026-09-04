import { useMemo } from 'react';
import {
  Pressable,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from 'react-native';
import { FlashList } from '@shopify/flash-list';
import { Image } from 'expo-image';
import { router } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { EdgeFadeView } from 'react-native-edge-fade';

import { useCatalog, type CatalogItem } from '@/data/catalog';

const BACKGROUND = '#000000';
const CARD_RADIUS = 24;
const CARD_GAP = 20;
const EDGE_DEPTH = 112;

function keyExtractor(item: CatalogItem) {
  return item.id;
}

export function LensScreen() {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const { catalog, isLoading, isError } = useCatalog();

  const items = useMemo(() => {
    const vertical = catalog.filter((item) => item.ratio < 0.78);
    return (vertical.length >= 8 ? vertical : catalog).slice(0, 18);
  }, [catalog]);

  const cardWidth = Math.min(width - 58, 360);
  const cardHeight = Math.min(height * 0.72, cardWidth * 1.86);

  const renderItem = ({ item }: { item: CatalogItem }) => (
    <View style={s.slot}>
      <View
        style={[
          s.card,
          {
            width: cardWidth,
            height: cardHeight,
            backgroundColor: item.color + '33',
          },
        ]}
      >
        <Image source={item.source} style={s.image} contentFit="cover" />
        <View pointerEvents="none" style={s.badge}>
          <Text style={s.badgeGlyph}>✓</Text>
        </View>
      </View>
    </View>
  );

  return (
    <View style={s.screen}>
      <EdgeFadeView
        mode="lens"
        top={EDGE_DEPTH}
        bottom={EDGE_DEPTH}
        left={false}
        right={false}
        radius={0}
        style={StyleSheet.absoluteFill}
      >
        {isLoading || isError || items.length === 0 ? (
          <View style={s.empty}>
            <Text style={s.emptyText}>LOADING PHOTOS</Text>
          </View>
        ) : (
          <FlashList
            data={items}
            keyExtractor={keyExtractor}
            renderItem={renderItem}
            ItemSeparatorComponent={() => <View style={s.separator} />}
            showsVerticalScrollIndicator={false}
            contentContainerStyle={{
              paddingTop: insets.top + 8,
              paddingBottom: insets.bottom + 18,
            }}
          />
        )}
      </EdgeFadeView>

      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Back"
        hitSlop={12}
        onPress={() => router.back()}
        style={({ pressed }) => [
          s.backButton,
          { top: insets.top + 8 },
          pressed && s.backButtonPressed,
        ]}
      >
        <Text style={s.backGlyph}>‹</Text>
      </Pressable>
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
  },
  card: {
    borderRadius: CARD_RADIUS,
    overflow: 'hidden',
  },
  image: {
    flex: 1,
  },
  separator: {
    height: CARD_GAP,
  },
  badge: {
    position: 'absolute',
    right: 10,
    bottom: 10,
    width: 24,
    height: 24,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#FFFFFF',
  },
  badgeGlyph: {
    color: '#171717',
    fontSize: 13,
    lineHeight: 15,
    fontWeight: '800',
  },
  backButton: {
    position: 'absolute',
    left: 14,
    width: 32,
    height: 32,
    borderRadius: 16,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(20,20,20,0.72)',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.14)',
  },
  backButtonPressed: {
    opacity: 0.62,
  },
  backGlyph: {
    color: '#FFFFFF',
    fontSize: 27,
    lineHeight: 28,
    marginTop: -3,
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
