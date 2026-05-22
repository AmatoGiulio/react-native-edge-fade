import { useEffect, useRef, useState } from 'react';
import {
  FlatList,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import type { ListRenderItem } from 'react-native';
import { EdgeFadeView } from 'react-native-edge-fade';
import type { EdgeFadeMode } from 'react-native-edge-fade';

const FADE_SIZES = [40, 80, 160, 240] as const;
const CURVES = ['smooth', 'sharp', 'gentle', 'soft', 'linear'] as const;
const MODES = ['mask', 'overlay'] as const;
const EDGE_SETS = [
  { label: 'bottom', top: 0, bottom: 1, left: 0, right: 0 },
  { label: 'top+bottom', top: 1, bottom: 1, left: 0, right: 0 },
  { label: 'left+right', top: 0, bottom: 0, left: 1, right: 1 },
  { label: 'all 4', top: 1, bottom: 1, left: 1, right: 1 },
] as const;

const ITEMS = Array.from({ length: 200 }, (_, i) => `Item ${i + 1}`);
const ROW_HEIGHT = 49;

function useFPS() {
  const [fps, setFps] = useState<number | null>(null);
  const frameRef = useRef(0);
  const lastRef = useRef(Date.now());
  const rafRef = useRef<number>(0);

  useEffect(() => {
    const tick = () => {
      frameRef.current += 1;
      const now = Date.now();
      const delta = now - lastRef.current;

      if (delta >= 800) {
        setFps(Math.round((frameRef.current * 1000) / delta));
        frameRef.current = 0;
        lastRef.current = now;
      }

      rafRef.current = requestAnimationFrame(tick);
    };

    rafRef.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafRef.current);
  }, []);

  return fps;
}

function Pills<T extends string | number>({
  label,
  options,
  value,
  onSelect,
  format,
}: {
  label: string;
  options: readonly T[];
  value: T;
  onSelect: (value: T) => void;
  format?: (value: T) => string;
}) {
  return (
    <View style={pill.row}>
      <Text style={pill.label}>{label}</Text>
      <View style={pill.group}>
        {options.map((option) => (
          <Pressable
            key={String(option)}
            style={[pill.item, option === value && pill.active]}
            onPress={() => onSelect(option)}
          >
            <Text style={[pill.text, option === value && pill.activeText]}>
              {format ? format(option) : String(option)}
            </Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

const renderBenchmarkItem: ListRenderItem<string> = ({ item }) => (
  <View style={s.row}>
    <Text style={s.rowText}>{item}</Text>
  </View>
);

const keyExtractor = (item: string) => item;

const getItemLayout = (
  _data: ArrayLike<string> | null | undefined,
  index: number
) => ({
  length: ROW_HEIGHT,
  offset: ROW_HEIGHT * index,
  index,
});

export default function BenchmarkScreen() {
  const fps = useFPS();
  const [fadeSize, setFadeSize] = useState<(typeof FADE_SIZES)[number]>(80);
  const [curve, setCurve] = useState<(typeof CURVES)[number]>('smooth');
  const [mode, setMode] = useState<EdgeFadeMode>('mask');
  const [edgeSet, setEdgeSet] = useState(0);

  const edges = EDGE_SETS[edgeSet]!;
  const color = mode === 'overlay' ? '#010101' : undefined;

  return (
    <View style={s.root}>
      <View style={s.header}>
        <Text style={s.title}>Benchmark</Text>
        <View style={s.fpsBox}>
          <Text style={[s.fps, fps !== null && fps < 50 && s.fpsWarn]}>
            {fps !== null ? `${fps} fps` : '-'}
          </Text>
          <Text style={s.platform}>
            {Platform.OS} {Platform.Version}
          </Text>
        </View>
      </View>

      <View style={s.controls}>
        <Pills
          label="fade"
          options={FADE_SIZES}
          value={fadeSize}
          onSelect={setFadeSize}
          format={(value) => `${value}dp`}
        />
        <Pills
          label="curve"
          options={CURVES}
          value={curve}
          onSelect={setCurve}
        />
        <Pills
          label="mode"
          options={MODES}
          value={mode}
          onSelect={setMode as (value: string) => void}
        />
        <Pills
          label="edges"
          options={EDGE_SETS.map((_, index) => index)}
          value={edgeSet}
          onSelect={setEdgeSet}
          format={(index) => EDGE_SETS[index]!.label}
        />
      </View>

      <View style={s.listWrap}>
        <EdgeFadeView
          mode={mode}
          top={edges.top ? fadeSize : 0}
          bottom={edges.bottom ? fadeSize : 0}
          left={edges.left ? fadeSize : 0}
          right={edges.right ? fadeSize : 0}
          curve={curve}
          color={color}
          style={StyleSheet.absoluteFill}
        >
          <FlatList
            data={ITEMS}
            renderItem={renderBenchmarkItem}
            keyExtractor={keyExtractor}
            getItemLayout={getItemLayout}
            contentContainerStyle={s.listContent}
            showsVerticalScrollIndicator={false}
            removeClippedSubviews
            initialNumToRender={12}
            maxToRenderPerBatch={12}
            windowSize={7}
          />
        </EdgeFadeView>
      </View>

      <Text style={s.hint}>
        Android: Android Studio - CPU Profiler - Capture system trace
      </Text>
      <Text style={s.hint}>
        Look for <Text style={s.code}>EdgeFade.dispatchDraw</Text>
        {' / '}
        <Text style={s.code}>EdgeFade.mask</Text>
      </Text>
    </View>
  );
}

const s = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#010101',
    paddingTop: 56,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-end',
    paddingHorizontal: 16,
    marginBottom: 12,
  },
  title: {
    fontSize: 22,
    fontWeight: '700',
    color: '#fff',
  },
  fpsBox: {
    alignItems: 'flex-end',
  },
  fps: {
    fontSize: 20,
    fontWeight: '600',
    color: '#4ade80',
    fontVariant: ['tabular-nums'],
  },
  fpsWarn: {
    color: '#f87171',
  },
  platform: {
    fontSize: 11,
    color: '#555',
    marginTop: 2,
  },
  controls: {
    paddingHorizontal: 16,
    gap: 8,
    marginBottom: 12,
  },
  listWrap: {
    flex: 1,
  },
  listContent: {
    paddingVertical: 8,
  },
  row: {
    height: ROW_HEIGHT,
    justifyContent: 'center',
    paddingHorizontal: 20,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#1a1a1a',
  },
  rowText: {
    fontSize: 15,
    color: '#e5e5e5',
  },
  hint: {
    fontSize: 11,
    color: '#444',
    textAlign: 'center',
    paddingHorizontal: 16,
    marginBottom: 4,
  },
  code: {
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    color: '#666',
  },
});

const pill = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  label: {
    fontSize: 11,
    fontWeight: '600',
    color: '#555',
    width: 38,
    textTransform: 'uppercase',
  },
  group: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 4,
  },
  item: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#2a2a2a',
  },
  active: {
    backgroundColor: '#fff',
    borderColor: '#fff',
  },
  text: {
    fontSize: 12,
    color: '#666',
  },
  activeText: {
    color: '#000',
    fontWeight: '600',
  },
});
