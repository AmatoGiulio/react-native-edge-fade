import { memo, useCallback, useMemo } from 'react';
import {
  Dimensions,
  Image,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { router } from 'expo-router';
import { useAppTheme } from '../hooks/useAppTheme';
import type { CatalogItem } from '../constants/catalog';

const GRID_H_PADDING = 12;
const COLUMN_GAP = 8;
const COLUMN_WIDTH =
  (Dimensions.get('window').width - GRID_H_PADDING * 2 - COLUMN_GAP) / 2;

function createStyles(surface: string) {
  return StyleSheet.create({
    root: {
      borderRadius: 16,
      overflow: 'hidden',
      marginBottom: 8,
      marginHorizontal: COLUMN_GAP / 2,
      backgroundColor: surface,
    },
    label: {
      flexDirection: 'row',
      alignItems: 'center',
      gap: 5,
      paddingHorizontal: 10,
      paddingVertical: 8,
      backgroundColor: surface,
    },
  });
}

const s = StyleSheet.create({
  dot: { width: 6, height: 6, borderRadius: 3 },
  text: {
    fontSize: 9,
    fontWeight: '700',
    color: '#fff',
    letterSpacing: 0.8,
  },
  media: { width: '100%', backgroundColor: '#0a0a0a' },
});

interface Props {
  item: CatalogItem;
}

export const MasonryCard = memo(function MasonryCard({ item }: Props) {
  const t = useAppTheme();
  const themed = useMemo(() => createStyles(t.surface), [t.surface]);

  const height = COLUMN_WIDTH / item.ratio;

  const handlePress = useCallback(() => {
    router.push(`/item/${item.id}` as never);
  }, [item.id]);

  return (
    <Pressable onPress={handlePress} style={themed.root}>
      <Image source={item.source} style={[s.media, { height }]} />
      <View style={themed.label}>
        <View style={[s.dot, { backgroundColor: item.accent }]} />
        <Text style={s.text}>{item.category.toUpperCase()}</Text>
      </View>
    </Pressable>
  );
});
