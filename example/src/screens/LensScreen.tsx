import { useMemo, useState } from 'react';
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

const BACKGROUND = '#050505';
const CARD_RADIUS = 28;
const CARD_GAP = 14;
const EDGE_DEPTH = 112;

function keyExtractor(item: CatalogItem) {
  return item.id;
}

export function LensScreen() {
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const { catalog, isLoading, isError } = useCatalog();
  const [enabled, setEnabled] = useState(true);

  const items = useMemo(() => {
    const vertical = catalog.filter((item) => item.ratio < 0.9);
    return (vertical.length >= 8 ? vertical : catalog).slice(0, 18);
  }, [catalog]);

  const cardWidth = Math.min(width - 34, 420);
  const cardHeight = Math.min(height * 0.62, cardWidth * 1.42);

  const renderItem = ({ item }: { item: CatalogItem }) => (
    <View style={s.slot}>
      <Image
        source={item.source}
        style={[
          s.image,
          {
            width: cardWidth,
            height: cardHeight,
            backgroundColor: item.color + '33',
          },
        ]}
        contentFit="cover"
      />
    </View>
  );

  const mode = enabled ? 'lens' : 'mask';

  return (
    <View style={s.screen}>
      <EdgeFadeView
        mode={mode}
        top={enabled ? EDGE_DEPTH : false}
        bottom={enabled ? EDGE_DEPTH : false}
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
              paddingTop: insets.top + 72,
              paddingBottom: insets.bottom + 72,
            }}
          />
        )}
      </EdgeFadeView>

      <View
        pointerEvents="box-none"
        style={[s.chrome, { top: insets.top + 14 }]}
      >
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Back"
          hitSlop={10}
          onPress={() => router.back()}
          style={({ pressed }) => [
            s.chromeButton,
            pressed && s.chromeButtonPressed,
          ]}
        >
          <Text style={s.backGlyph}>‹</Text>
        </Pressable>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={enabled ? 'Disable lens' : 'Enable lens'}
          hitSlop={8}
          onPress={() => setEnabled((value) => !value)}
          style={({ pressed }) => [
            s.modeButton,
            enabled && s.modeButtonEnabled,
            pressed && s.chromeButtonPressed,
          ]}
        >
          <View style={[s.modeDot, enabled && s.modeDotEnabled]} />
          <Text style={[s.modeLabel, enabled && s.modeLabelEnabled]}>
            {enabled ? 'lens' : 'plain'}
          </Text>
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
  },
  image: {
    borderRadius: CARD_RADIUS,
  },
  separator: {
    height: CARD_GAP,
  },
  chrome: {
    position: 'absolute',
    left: 16,
    right: 16,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  chromeButton: {
    width: 34,
    height: 34,
    borderRadius: 17,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(16,16,16,0.72)',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.16)',
  },
  chromeButtonPressed: {
    opacity: 0.62,
  },
  backGlyph: {
    color: '#FFFFFF',
    fontSize: 29,
    lineHeight: 30,
    marginTop: -3,
  },
  modeButton: {
    height: 34,
    borderRadius: 17,
    paddingHorizontal: 12,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    backgroundColor: 'rgba(16,16,16,0.72)',
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
  modeLabelEnabled: {
    color: '#111111',
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
