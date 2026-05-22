import { useLocalSearchParams, router } from 'expo-router';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';

// Placeholder — the next commit replaces this with full image / video detail
// screens backed by the asset catalog.
export default function ItemDetail() {
  const { id } = useLocalSearchParams<{ id: string }>();

  return (
    <View style={s.root}>
      <Pressable style={s.back} onPress={() => router.back()}>
        <Text style={s.backText}>← Back</Text>
      </Pressable>
      <Text style={s.title}>Item</Text>
      <Text style={s.id}>{id}</Text>
      <Text style={s.note}>Detail screen — coming up.</Text>
    </View>
  );
}

const s = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#080808',
    paddingTop: Platform.OS === 'android' ? 48 : 60,
    paddingHorizontal: 20,
  },
  back: {
    paddingVertical: 6,
  },
  backText: {
    fontSize: 14,
    color: '#777',
    fontWeight: '500',
  },
  title: {
    fontSize: 32,
    fontWeight: '700',
    color: '#f2f2f2',
    marginTop: 12,
  },
  id: {
    fontSize: 14,
    color: '#555',
    marginTop: 4,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
  note: {
    fontSize: 13,
    color: '#555',
    marginTop: 40,
  },
});
