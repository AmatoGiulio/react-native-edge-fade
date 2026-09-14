import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { router } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { GalleryScreen } from '@/screens/GalleryScreen';

export default function GalleryEntry() {
  const insets = useSafeAreaInsets();
  return (
    <View style={styles.root}>
      <GalleryScreen />
      {Platform.OS === 'android' && (
        <Pressable
          accessibilityRole="button"
          onPress={() => router.push('/progressive-blur')}
          style={[styles.lab, { bottom: insets.bottom + 20 }]}
        >
          <Text style={styles.label}>Progressive Blur Lab</Text>
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  lab: { position: 'absolute', alignSelf: 'center', borderRadius: 24, backgroundColor: '#202520', paddingHorizontal: 24, paddingVertical: 14 },
  label: { color: '#ffffff', fontWeight: '600', fontSize: 13 },
});
