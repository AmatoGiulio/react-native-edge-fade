import { useCallback, useState } from 'react';
import { useLocalSearchParams, router } from 'expo-router';
import {
  Image,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { EdgeFadeView } from 'react-native-edge-fade';

import { getCatalogItem, FOOTAGES } from '../../constants/catalog';
import { FootageCard } from '../../components/FootageCard';
import { useAppTheme } from '../../hooks/useAppTheme';

export default function DetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const item = getCatalogItem(id ?? '');
  const t = useAppTheme();
  const [activeFootageId, setActiveFootageId] = useState(FOOTAGES[0]!.id);

  const handleBack = useCallback(() => router.back(), []);
  const handleShare = useCallback(() => {
    router.push({ pathname: '/share', params: { id: item?.id } } as never);
  }, [item?.id]);
  const handleSelectFootage = useCallback((footageId: string) => {
    setActiveFootageId(footageId);
  }, []);

  if (!item) {
    return (
      <View style={[s.notFound, { backgroundColor: t.bg }]}>
        <Text style={[s.notFoundText, { color: t.sub }]}>Not found</Text>
      </View>
    );
  }

  return (
    <View style={[s.root, { backgroundColor: t.bg }]}>
      {/* Full-screen image — bottom alpha-faded via mask */}
      <EdgeFadeView
        bottom={420}
        curve="soft"
        mode="mask"
        style={StyleSheet.absoluteFill}
      >
        <Image
          source={item.source}
          style={StyleSheet.absoluteFill}
          resizeMode="cover"
        />
      </EdgeFadeView>

      {/* Back */}
      <Pressable style={s.back} onPress={handleBack}>
        <View style={s.backPill}>
          <Ionicons name="chevron-back" size={22} color="#fff" />
        </View>
      </Pressable>

      {/* Bottom panel */}
      <View style={s.bottom}>
        {/* Footage strip — one video active at a time */}
        <EdgeFadeView
          left={80}
          right={80}
          curve="smooth"
          mode="overlay"
          color={t.bg}
          style={s.stripWrap}
        >
          <ScrollView
            horizontal
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={s.stripContent}
          >
            {FOOTAGES.map((footage) => (
              <FootageCard
                key={footage.id}
                footage={footage}
                active={footage.id === activeFootageId}
                onPress={() => handleSelectFootage(footage.id)}
              />
            ))}
          </ScrollView>
        </EdgeFadeView>

        {/* Action bar */}
        <View style={s.actionBar}>
          <View style={s.meta}>
            <View style={[s.dot, { backgroundColor: item.accent }]} />
            <Text style={[s.category, { color: t.sub }]}>
              {item.category.toUpperCase()}
            </Text>
          </View>
          <Pressable style={s.shareBtn} onPress={handleShare}>
            <Text style={s.shareBtnText}>Share</Text>
          </Pressable>
        </View>
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1 },
  back: {
    position: 'absolute',
    top: Platform.OS === 'android' ? 48 : 60,
    left: 20,
    zIndex: 10,
  },
  backPill: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(0,0,0,0.45)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  bottom: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    paddingBottom: Platform.OS === 'ios' ? 36 : 20,
  },
  stripWrap: { height: 160, marginBottom: 16 },
  stripContent: { paddingHorizontal: 24, alignItems: 'center' },
  actionBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 24,
  },
  meta: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  dot: { width: 8, height: 8, borderRadius: 4 },
  category: { fontSize: 11, fontWeight: '700', letterSpacing: 1.2 },
  shareBtn: {
    paddingHorizontal: 28,
    paddingVertical: 14,
    borderRadius: 28,
    backgroundColor: '#fff',
  },
  shareBtnText: { fontSize: 15, fontWeight: '700', color: '#000' },
  notFound: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  notFoundText: { fontSize: 16 },
});
