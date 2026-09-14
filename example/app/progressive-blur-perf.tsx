import {
  PixelRatio,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { Stack, useLocalSearchParams } from 'expo-router';
import { EdgeFadeView } from 'react-native-edge-fade';

const ALBUMS = [
  '#37b9a7',
  '#5aa7d4',
  '#ef647b',
  '#9183c5',
  '#da974d',
  '#829961',
];
const TITLES = [
  ['Night Drive', 'Paper Satellites'],
  ['Slow Motion', 'Studio North'],
  ['Blue Hours', 'The Evening Club'],
  ['Soft Landing', 'Common Ground'],
  ['Parallel Lines', 'Minor Weather'],
  ['After the Rain', 'Quiet Company'],
];
const TRACKS = Array.from({ length: 64 }, (_, i) => ({
  id: String(i),
  title: TITLES[i % TITLES.length]![0],
  artist: TITLES[i % TITLES.length]![1],
  color: ALBUMS[i % ALBUMS.length],
  time: `${3 + (i % 3)}:${String((i * 13) % 60).padStart(2, '0')}`,
}));

const PERF_DEFAULT_TARGET_RADIUS_PX = 144;
const PERF_MAX_RADIUS_PX = 150;
const PERF_DENSITY = PixelRatio.get();

function resolveRadiusPx(value: string | string[] | undefined) {
  const raw = Array.isArray(value) ? value[0] : value;
  const parsed = Number.parseFloat(raw ?? '');
  if (!Number.isFinite(parsed)) return PERF_DEFAULT_TARGET_RADIUS_PX;
  return Math.min(PERF_MAX_RADIUS_PX, Math.max(1, parsed));
}

export default function ProgressiveBlurPerfRoute() {
  const params = useLocalSearchParams<{
    edges?: string;
    radiusPx?: string;
  }>();
  const fourEdges = params.edges === 'four';
  const targetRadiusPx = resolveRadiusPx(params.radiusPx);
  const radiusDp = targetRadiusPx / PERF_DENSITY;
  const actualRadiusPx = radiusDp * PERF_DENSITY;

  if (Platform.OS !== 'android') {
    return <View />;
  }

  return (
    <View style={s.page} testID="perf-public-progressive">
      <Stack.Screen options={{ headerShown: false }} />
      <View style={s.header} pointerEvents="none">
        <Text style={s.eyebrow}>EDGE FADE / PERF</Text>
        <Text style={s.title}>After hours.</Text>
        <Text style={s.meta}>
          Public Progressive ·
          {` ${radiusDp.toFixed(1)}dp / ${actualRadiusPx.toFixed(0)}px · Smooth · `}
          {fourEdges ? 'Four edges' : 'Top + bottom'}
        </Text>
      </View>

      <View style={s.viewportFrame} collapsable={false}>
        <EdgeFadeView
          testID="perf-edge-fade"
          style={s.viewport}
          mode="blur"
          top={92}
          bottom={112}
          left={fourEdges ? 48 : 0}
          right={fourEdges ? 48 : 0}
          curve="smooth"
          blurRadius={radiusDp}
        >
          <ScrollView
            testID="perf-scroll"
            style={s.list}
            contentContainerStyle={s.tracks}
            showsVerticalScrollIndicator={false}
            removeClippedSubviews={false}
          >
            {TRACKS.map((track, index) => (
              <View key={track.id} style={s.track}>
                <View
                  pointerEvents="none"
                  style={[s.cover, { backgroundColor: track.color }]}
                >
                  <View style={s.coverDisc} />
                  <Text style={s.coverNumber}>
                    {String(index + 1).padStart(2, '0')}
                  </Text>
                </View>
                <View style={s.trackText}>
                  <Text style={s.trackTitle}>{track.title}</Text>
                  <Text style={s.artist}>{track.artist}</Text>
                </View>
                <Text style={s.duration}>{track.time}</Text>
              </View>
            ))}
          </ScrollView>
        </EdgeFadeView>
      </View>
    </View>
  );
}

const s = StyleSheet.create({
  page: {
    flex: 1,
    backgroundColor: '#f7f7f5',
    paddingTop: 52,
    paddingBottom: 28,
  },
  header: { paddingHorizontal: 24, paddingBottom: 16 },
  eyebrow: {
    fontSize: 10,
    letterSpacing: 2,
    fontWeight: '700',
    color: '#656560',
    marginBottom: 10,
  },
  title: {
    fontSize: 34,
    letterSpacing: -1.2,
    fontWeight: '700',
    color: '#181918',
  },
  meta: { fontSize: 11, color: '#74756f', marginTop: 6 },
  viewportFrame: {
    flex: 1,
    marginHorizontal: 16,
    backgroundColor: '#ffffff',
    borderRadius: 24,
    overflow: 'hidden',
  },
  viewport: { flex: 1 },
  list: { flex: 1 },
  tracks: { paddingHorizontal: 18, paddingVertical: 16 },
  track: { flexDirection: 'row', alignItems: 'center', height: 64, gap: 13 },
  cover: {
    width: 44,
    height: 44,
    borderRadius: 10,
    overflow: 'hidden',
    justifyContent: 'flex-end',
    padding: 6,
  },
  coverDisc: {
    position: 'absolute',
    width: 46,
    height: 46,
    borderRadius: 23,
    borderWidth: 9,
    borderColor: '#ffffff50',
    left: 14,
    top: -8,
  },
  coverNumber: { color: '#ffffff', fontSize: 9, fontWeight: '600' },
  trackText: { flex: 1 },
  trackTitle: { color: '#202322', fontSize: 13, fontWeight: '600' },
  artist: { color: '#858a85', fontSize: 11, marginTop: 5 },
  duration: {
    fontSize: 10,
    color: '#999d96',
    fontVariant: ['tabular-nums'],
  },
});
