import { useLocalSearchParams, router } from 'expo-router';
import {
  Image,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { EdgeFadeView } from 'react-native-edge-fade';
import { getCatalogItem } from '../lib/catalog';

const C = {
  bg: '#080808',
  sub: '#666',
};

export default function ItemDetail() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const item = getCatalogItem(id ?? '');

  if (!item) {
    return (
      <View style={s.notFound}>
        <Text style={s.notFoundText}>Not found</Text>
      </View>
    );
  }

  return (
    <View style={s.root}>
      {/* Full-screen image, bottom alpha-masked via EdgeFadeView */}
      <EdgeFadeView
        bottom={600}
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

      {/* Back button */}
      <Pressable style={s.back} onPress={() => router.back()}>
        <View style={s.backPill}>
          <Text style={s.backArrow}>←</Text>
        </View>
      </Pressable>

      {/* Action bar — sopra la zona sfumata */}
      <View style={s.actionBar}>
        <View style={s.meta}>
          <View style={[s.dot, { backgroundColor: item.accent }]} />
          <Text style={s.category}>{item.category.toUpperCase()}</Text>
        </View>

        <Pressable
          style={s.shareBtn}
          onPress={() =>
            router.push({
              pathname: '/share',
              params: { id: item.id },
            } as never)
          }
        >
          <Text style={s.shareBtnText}>Share</Text>
        </Pressable>
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: C.bg,
  },
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
  backArrow: {
    fontSize: 18,
    color: '#fff',
  },
  actionBar: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 24,
    paddingBottom: Platform.OS === 'ios' ? 44 : 28,
    paddingTop: 16,
  },
  meta: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  category: {
    fontSize: 11,
    fontWeight: '700',
    color: C.sub,
    letterSpacing: 1.2,
  },
  shareBtn: {
    paddingHorizontal: 28,
    paddingVertical: 14,
    borderRadius: 28,
    backgroundColor: '#fff',
  },
  shareBtnText: {
    fontSize: 15,
    fontWeight: '700',
    color: '#000',
  },
  notFound: {
    flex: 1,
    backgroundColor: C.bg,
    alignItems: 'center',
    justifyContent: 'center',
  },
  notFoundText: {
    color: C.sub,
    fontSize: 16,
  },
});
