import { useState } from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';

import BenchmarkScreen from './screens/BenchmarkScreen';
import { DemoScreen } from './screens/DemoScreen';
import { COLORS } from './theme';

export default function App() {
  const [screen, setScreen] = useState<'demo' | 'benchmark'>('demo');

  return (
    <View style={styles.root}>
      {screen === 'demo' ? (
        <DemoScreen onBenchmark={() => setScreen('benchmark')} />
      ) : (
        <View style={styles.benchmarkRoot}>
          <Pressable
            style={styles.backButton}
            onPress={() => setScreen('demo')}
          >
            <Text style={styles.backText}>Back to Discover</Text>
          </Pressable>
          <BenchmarkScreen />
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: COLORS.bg,
  },
  benchmarkRoot: {
    flex: 1,
  },
  backButton: {
    paddingTop: Platform.OS === 'android' ? 48 : 60,
    paddingHorizontal: 20,
    paddingBottom: 8,
  },
  backText: {
    fontSize: 14,
    color: COLORS.subtle,
    fontWeight: '500',
  },
});
