import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { EdgeFadeView } from 'react-native-edge-fade';

const ITEMS = Array.from({ length: 30 }, (_, i) => `Item ${i + 1}`);

export default function App() {
  return (
    <View style={styles.root}>
      {/* ── Mask mode: vertical list ── */}
      <Text style={styles.label}>Mask — bottom fade</Text>
      <EdgeFadeView
        mode="mask"
        bottom={80}
        style={[styles.box, styles.tallBox]}
      >
        <ScrollView>
          {ITEMS.map((item) => (
            <View key={item} style={styles.row}>
              <Text style={styles.rowText}>{item}</Text>
            </View>
          ))}
        </ScrollView>
      </EdgeFadeView>

      {/* ── Overlay mode: horizontal scroll ── */}
      <Text style={styles.label}>Overlay — left + right</Text>
      <View style={styles.box}>
        <EdgeFadeView
          mode="mask"
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
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#010101',
    paddingTop: 60,
    paddingHorizontal: 16,
    gap: 8,
  },
  text: {
    fontSize: 16,
    color: '#fff',
  },
  label: {
    fontSize: 13,
    fontWeight: '600',
    color: '#666',
    marginTop: 16,
  },
  box: {
    height: 100,
    //backgroundColor: '#333',
    borderRadius: 12,
    overflow: 'hidden',
  },
  tallBox: {
    height: 300,
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
