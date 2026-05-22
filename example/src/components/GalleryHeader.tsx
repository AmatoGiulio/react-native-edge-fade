import { Pressable, StyleSheet, Text, View } from 'react-native';

import { ITEMS } from '../galleryData';
import { COLORS } from '../theme';

export function GalleryHeader({ onBenchmark }: { onBenchmark: () => void }) {
  return (
    <View style={styles.root}>
      <View>
        <Text style={styles.title}>Discover</Text>
        <Text style={styles.subtitle}>{ITEMS.length} pieces</Text>
      </View>
      <Pressable style={styles.benchmarkButton} onPress={onBenchmark}>
        <Text style={styles.benchmarkText}>Benchmark</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    marginBottom: 20,
  },
  title: {
    fontSize: 32,
    fontWeight: '700',
    color: COLORS.text,
  },
  subtitle: {
    fontSize: 13,
    color: COLORS.subtle,
    marginTop: 2,
  },
  benchmarkButton: {
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: COLORS.border,
  },
  benchmarkText: {
    fontSize: 12,
    color: COLORS.subtle,
    fontWeight: '500',
  },
});
