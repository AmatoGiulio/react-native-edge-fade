import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { EdgeFadeView } from 'react-native-edge-fade';
import BenchmarkScreen from './BenchmarkScreen';

const ITEMS = Array.from({ length: 30 }, (_, i) => `Item ${i + 1}`);

function DemoScreen() {
  return (
    <ScrollView style={styles.root} contentContainerStyle={styles.content}>
      <Text style={styles.label}>Mask — bottom fade</Text>
      <EdgeFadeView
        mode="mask"
        bottom={80}
        style={[styles.box, { height: 300 }]}
      >
        <ScrollView>
          {ITEMS.map((item) => (
            <View key={item} style={styles.row}>
              <Text style={styles.rowText}>{item}</Text>
            </View>
          ))}
        </ScrollView>
      </EdgeFadeView>

      <Text style={styles.label}>Overlay — left + right</Text>
      <View style={styles.box}>
        <EdgeFadeView
          mode="overlay"
          left={120}
          right={120}
          curve="gentle"
          color="#010101"
          style={StyleSheet.absoluteFill}
        >
          <ScrollView horizontal showsHorizontalScrollIndicator={false}>
            {ITEMS.map((item) => (
              <View key={item} style={styles.chip}>
                <Text style={styles.text}>{item}</Text>
              </View>
            ))}
          </ScrollView>
        </EdgeFadeView>
      </View>

      <Text style={styles.label}>Mask — left + right</Text>
      <View style={styles.box}>
        <EdgeFadeView
          mode="mask"
          left={120}
          right={120}
          curve="gentle"
          style={StyleSheet.absoluteFill}
        >
          <ScrollView horizontal showsHorizontalScrollIndicator={false}>
            {ITEMS.map((item) => (
              <View key={item} style={styles.chip}>
                <Text style={styles.text}>{item}</Text>
              </View>
            ))}
          </ScrollView>
        </EdgeFadeView>
      </View>

      {/* ── Custom curve demos ── */}
      <Text style={styles.sectionHeader}>
        Custom curves (AGSL LUT on API 33+)
      </Text>

      <Text style={styles.label}>cubicBezier(0.25, 0.1, 0.25, 1) — ease</Text>
      <EdgeFadeView
        mode="mask"
        bottom={80}
        curve={{ type: 'cubicBezier', x1: 0.25, y1: 0.1, x2: 0.25, y2: 1 }}
        style={styles.tallBox}
      >
        <ScrollView>
          {ITEMS.map((item) => (
            <View key={item} style={styles.row}>
              <Text style={styles.rowText}>{item}</Text>
            </View>
          ))}
        </ScrollView>
      </EdgeFadeView>

      <Text style={styles.label}>
        cubicBezier(0.42, 0, 0.58, 1) — ease-in-out (symmetric)
      </Text>
      <EdgeFadeView
        mode="mask"
        bottom={80}
        curve={{ type: 'cubicBezier', x1: 0.42, y1: 0, x2: 0.58, y2: 1 }}
        style={styles.tallBox}
      >
        <ScrollView>
          {ITEMS.map((item) => (
            <View key={item} style={styles.row}>
              <Text style={styles.rowText}>{item}</Text>
            </View>
          ))}
        </ScrollView>
      </EdgeFadeView>

      <Text style={styles.label}>
        stops [1, 0.9, 0.6, 0.2, 0] — concave step
      </Text>
      <EdgeFadeView
        mode="mask"
        bottom={80}
        curve={{ type: 'stops', values: [1, 0.9, 0.6, 0.2, 0] }}
        style={styles.tallBox}
      >
        <ScrollView>
          {ITEMS.map((item) => (
            <View key={item} style={styles.row}>
              <Text style={styles.rowText}>{item}</Text>
            </View>
          ))}
        </ScrollView>
      </EdgeFadeView>

      <Text style={styles.label}>
        stops [1, 0.5, 0.5, 0.5, 0] — shelf / plateau
      </Text>
      <EdgeFadeView
        mode="mask"
        bottom={80}
        curve={{ type: 'stops', values: [1, 0.5, 0.5, 0.5, 0] }}
        style={styles.tallBox}
      >
        <ScrollView>
          {ITEMS.map((item) => (
            <View key={item} style={styles.row}>
              <Text style={styles.rowText}>{item}</Text>
            </View>
          ))}
        </ScrollView>
      </EdgeFadeView>
    </ScrollView>
  );
}

const TABS = ['Demo', 'Benchmark'] as const;
type Tab = (typeof TABS)[number];

export default function App() {
  const [tab, setTab] = useState<Tab>('Demo');

  return (
    <View style={{ flex: 1, backgroundColor: '#010101' }}>
      {/* Tab bar */}
      <View style={tabs.bar}>
        {TABS.map((t) => (
          <Pressable
            key={t}
            style={[tabs.tab, t === tab && tabs.active]}
            onPress={() => setTab(t)}
          >
            <Text style={[tabs.text, t === tab && tabs.activeText]}>{t}</Text>
          </Pressable>
        ))}
      </View>

      {tab === 'Demo' && <DemoScreen />}
      {tab === 'Benchmark' && <BenchmarkScreen />}
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#010101',
  },
  content: {
    paddingTop: 16,
    paddingHorizontal: 16,
    paddingBottom: 40,
    gap: 8,
  },
  text: {
    fontSize: 16,
    color: '#fff',
  },
  sectionHeader: {
    fontSize: 11,
    fontWeight: '700',
    color: '#444',
    marginTop: 24,
    marginBottom: 4,
    textTransform: 'uppercase',
    letterSpacing: 1,
  },
  label: {
    fontSize: 13,
    fontWeight: '600',
    color: '#666',
    marginTop: 16,
  },
  tallBox: {
    height: 200,
  },
  box: {
    height: 100,
    borderRadius: 12,
    overflow: 'hidden',
  },
  row: {
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#030303',
  },
  rowText: {
    fontSize: 15,
    color: '#fff',
  },
  chip: {
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: '#030303',
    backgroundColor: '#333',
    borderRadius: 12,
    paddingHorizontal: 16,
    width: 80,
    alignItems: 'center',
    paddingVertical: 10,
    marginLeft: 8,
    justifyContent: 'center',
  },
});

const tabs = StyleSheet.create({
  bar: {
    flexDirection: 'row',
    paddingTop: 56,
    paddingHorizontal: 16,
    paddingBottom: 8,
    gap: 8,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#1a1a1a',
    backgroundColor: '#010101',
  },
  tab: {
    paddingHorizontal: 16,
    paddingVertical: 6,
    borderRadius: 20,
  },
  active: {
    backgroundColor: '#1a1a1a',
  },
  text: {
    fontSize: 14,
    color: '#555',
    fontWeight: '500',
  },
  activeText: {
    color: '#fff',
  },
});
