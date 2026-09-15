import { useEffect, useMemo, useState } from 'react';
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

const DENSITY = PixelRatio.get();
const DEFAULT_RADIUS_PX = 98;
const MAX_RADIUS_PX = 150;
const STEPS_PX = [DEFAULT_RADIUS_PX, 0, MAX_RADIUS_PX, 1, DEFAULT_RADIUS_PX];

export default function ProgressiveBlurSmokeRoute() {
  const params = useLocalSearchParams<{ edges?: string }>();
  const fourEdges = params.edges === 'four';
  const [step, setStep] = useState(0);
  const rows = useMemo(() => Array.from({ length: 72 }, (_, i) => i + 1), []);

  useEffect(() => {
    const timer = setInterval(() => {
      setStep((value) => (value + 1) % STEPS_PX.length);
    }, 850);
    return () => clearInterval(timer);
  }, []);

  if (Platform.OS !== 'android') return <View />;

  const radiusPx = STEPS_PX[step]!;
  const radiusDp = radiusPx / DENSITY;

  return (
    <View style={s.page} testID="progressive-release-smoke">
      <Stack.Screen options={{ headerShown: false }} />
      <View style={s.header} pointerEvents="none">
        <Text style={s.kicker}>EDGE FADE / RELEASE SMOKE</Text>
        <Text style={s.title}>Lifecycle + radius transitions</Text>
        <Text style={s.meta}>
          {`${radiusPx}px · ${fourEdges ? 'Four edges' : 'Top + bottom'} · step ${step + 1}/${STEPS_PX.length}`}
        </Text>
      </View>

      <View style={s.frame} collapsable={false}>
        <EdgeFadeView
          testID="progressive-release-smoke-edge"
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
            testID="progressive-release-smoke-scroll"
            showsVerticalScrollIndicator={false}
            contentContainerStyle={s.content}
          >
            {rows.map((row) => (
              <View key={row} style={s.row}>
                <View style={s.badge}>
                  <Text style={s.badgeText}>{String(row).padStart(2, '0')}</Text>
                </View>
                <View style={s.copy}>
                  <Text style={s.rowTitle}>Progressive blur smoke</Text>
                  <Text style={s.rowMeta}>scroll · rotate · background · restore</Text>
                </View>
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
    paddingTop: 52,
    paddingBottom: 28,
    backgroundColor: '#f3f3ef',
  },
  header: {
    paddingHorizontal: 22,
    paddingBottom: 14,
  },
  kicker: {
    fontSize: 10,
    letterSpacing: 1.8,
    fontWeight: '700',
    color: '#696963',
  },
  title: {
    marginTop: 8,
    fontSize: 25,
    fontWeight: '700',
    color: '#1f201f',
  },
  meta: {
    marginTop: 6,
    fontSize: 11,
    color: '#777872',
  },
  frame: {
    flex: 1,
    marginHorizontal: 16,
    borderRadius: 24,
    overflow: 'hidden',
    backgroundColor: '#fff',
  },
  viewport: {
    flex: 1,
  },
  content: {
    paddingHorizontal: 16,
    paddingVertical: 18,
  },
  row: {
    height: 62,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  badge: {
    width: 42,
    height: 42,
    borderRadius: 11,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#252625',
  },
  badgeText: {
    color: '#fff',
    fontSize: 10,
    fontWeight: '700',
  },
  copy: {
    flex: 1,
  },
  rowTitle: {
    fontSize: 13,
    fontWeight: '600',
    color: '#252725',
  },
  rowMeta: {
    marginTop: 4,
    fontSize: 11,
    color: '#868982',
  },
});
