/**
 * Debug bands — verifica visiva delle 3 fasce del blur progressivo.
 *
 * La stessa riga di immagini è ripetuta per tutta l'altezza dello schermo e
 * `fadeBottom` è esattamente 3 × ROW_H: ogni fascia (L0/L1/L2) copre una riga
 * intera, quindi la stessa immagine si può confrontare mentre attraversa i tre
 * livelli. Le linee/etichette arrivano dall'overlay nativo
 * (DEBUG_BAND_BOUNDARIES in EdgeFadeView.kt, build debug).
 */

import { useMemo } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { EdgeFadeView } from 'react-native-edge-fade';

import { useCatalog, type CatalogItem } from '@/data/catalog';
import { useFadeStore, useFadeRender } from '@/fade/FadeContext';
import { useHeaderHeight } from 'expo-router/react-navigation';

/** Altezza di una riga = altezza di una singola fascia del blur. */
const ROW_H = 110;
/** Numero di fasce native (LEVEL_BOUNDS in EdgeFadeView.kt). */
const N_BANDS = 3;
const COLS = 4;
const N_ROWS = 12;

function ImageRow({ items }: { items: CatalogItem[] }) {
  return (
    <View style={s.row}>
      {items.map((item) => (
        <Image
          key={item.id}
          source={item.source}
          style={s.cell}
          contentFit="cover"
        />
      ))}
    </View>
  );
}

function TextRow() {
  return (
    <View style={[s.row, s.textRow]}>
      <View style={s.textBlock}>
        <Text style={s.title}>Progressive blur test</Text>
        <Text style={s.subtitle}>
          The quick brown fox jumps over the lazy dog · 0123456789
        </Text>
        <Text style={s.caption}>
          Lorem ipsum dolor sit amet, consectetur adipiscing elit — iiillll 1Il|
        </Text>
      </View>
    </View>
  );
}

export function DebugBandsScreen() {
  const { catalog, isLoading } = useCatalog();
  const { mode, tint } = useFadeStore();
  const { curve, blurRadius } = useFadeRender();
  const topHeight = useHeaderHeight();

  const rowItems = useMemo(() => catalog.slice(0, COLS), [catalog]);
  const showTextRow = isLoading || rowItems.length < COLS;

  return (
    <View style={s.root} collapsable={false}>
      <EdgeFadeView
        bottom={ROW_H * N_BANDS}
        curve={curve}
        mode={mode}
        blurRadius={blurRadius}
        color={tint}
        style={StyleSheet.absoluteFill}
      >
        <ScrollView
          showsVerticalScrollIndicator={false}
          style={{ flex: 1, paddingTop: topHeight }}
        >
          {Array.from({ length: N_ROWS }, (_, i) =>
            showTextRow ? (
              <TextRow key={i} />
            ) : (
              <ImageRow key={i} items={rowItems} />
            )
          )}
        </ScrollView>
      </EdgeFadeView>
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1 },
  row: { flexDirection: 'row', height: ROW_H },
  cell: { flex: 1, height: ROW_H },
  textRow: {
    backgroundColor: '#fff',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#ddd',
  },
  textBlock: { flex: 1, justifyContent: 'center', paddingHorizontal: 16 },
  title: { fontSize: 22, fontWeight: '700', color: '#111' },
  subtitle: { fontSize: 15, color: '#333', marginTop: 4 },
  caption: { fontSize: 12, color: '#888', marginTop: 4 },
});
