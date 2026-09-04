import { useMemo, useState } from 'react';
import {
  Platform,
  Pressable,
  StyleSheet,
  Text,
  useWindowDimensions,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { EdgeFadeView, type EdgeFadeMode } from 'react-native-edge-fade';

import { useTheme } from '@/theme';

const MONO = Platform.select({ ios: 'Menlo', default: 'monospace' });
const LENS_RADIUS = 36;

export function LensScreen() {
  const t = useTheme();
  const insets = useSafeAreaInsets();
  const { width, height } = useWindowDimensions();
  const [enabled, setEnabled] = useState(true);

  const cardSize = useMemo(
    () => ({
      width: Math.min(width - 40, 420),
      height: Math.min(Math.max(height * 0.56, 360), 520),
    }),
    [height, width]
  );

  const mode: EdgeFadeMode = enabled ? 'lens' : 'mask';

  return (
    <View
      style={[
        s.screen,
        {
          backgroundColor: t.bg,
          paddingTop: insets.top + 72,
          paddingBottom: Math.max(insets.bottom, 24),
        },
      ]}
    >
      <View style={s.header}>
        <View>
          <Text style={[s.eyebrow, { color: t.faintText }]}>EXPERIMENT</Text>
          <Text style={[s.title, { color: t.text }]}>Lens</Text>
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={enabled ? 'Disable lens' : 'Enable lens'}
          onPress={() => setEnabled((value) => !value)}
          style={({ pressed }) => [
            s.toggle,
            {
              backgroundColor: enabled ? t.text : t.control,
              opacity: pressed ? 0.72 : 1,
            },
          ]}
        >
          <Text
            style={[
              s.toggleText,
              { color: enabled ? t.bg : t.text },
            ]}
          >
            {enabled ? 'lens' : 'plain'}
          </Text>
        </Pressable>
      </View>

      <View style={s.stage}>
        <EdgeFadeView
          mode={mode}
          radius={LENS_RADIUS}
          style={[
            s.lens,
            cardSize,
            { backgroundColor: t.imgPlaceholder },
          ]}
        >
          <View style={s.poster}>
            <View style={s.posterTop}>
              <Text style={s.posterIndex}>01</Text>
              <Text style={s.posterMeta}>CURRENT KERNEL</Text>
            </View>

            <View style={s.orbit}>
              <View style={s.orbitRing} />
              <View style={s.orbitCore} />
              <View style={[s.orbitLine, s.orbitLineA]} />
              <View style={[s.orbitLine, s.orbitLineB]} />
              <View style={[s.orbitLine, s.orbitLineC]} />
            </View>

            <View style={s.ruleGroup}>
              {Array.from({ length: 8 }, (_, index) => (
                <View key={index} style={s.rule} />
              ))}
            </View>

            <View style={s.posterBottom}>
              <Text style={s.posterWord}>EDGE</Text>
              <Text style={s.posterWord}>FADE</Text>
            </View>
          </View>
        </EdgeFadeView>
      </View>

      <Text style={[s.note, { color: t.subtext }]}> 
        Android 13+ · tap lens/plain to compare the existing refraction path.
      </Text>
    </View>
  );
}

const s = StyleSheet.create({
  screen: {
    flex: 1,
    paddingHorizontal: 20,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    justifyContent: 'space-between',
    width: '100%',
    maxWidth: 520,
    alignSelf: 'center',
  },
  eyebrow: {
    fontFamily: MONO,
    fontSize: 11,
    letterSpacing: 1.4,
    marginBottom: 5,
  },
  title: {
    fontSize: 34,
    fontWeight: '700',
    letterSpacing: -1.2,
  },
  toggle: {
    minWidth: 72,
    height: 36,
    borderRadius: 18,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 14,
  },
  toggleText: {
    fontFamily: MONO,
    fontSize: 12,
    fontWeight: '600',
  },
  stage: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 18,
  },
  lens: {
    overflow: 'hidden',
  },
  poster: {
    flex: 1,
    backgroundColor: '#E8FF45',
    padding: 24,
    overflow: 'hidden',
  },
  posterTop: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
  },
  posterIndex: {
    fontFamily: MONO,
    color: '#111111',
    fontSize: 13,
    fontWeight: '700',
  },
  posterMeta: {
    fontFamily: MONO,
    color: '#111111',
    fontSize: 10,
    letterSpacing: 1.1,
  },
  orbit: {
    position: 'absolute',
    width: 270,
    height: 270,
    borderRadius: 135,
    right: -64,
    top: 72,
    alignItems: 'center',
    justifyContent: 'center',
  },
  orbitRing: {
    position: 'absolute',
    width: 270,
    height: 270,
    borderRadius: 135,
    borderWidth: 2,
    borderColor: '#111111',
  },
  orbitCore: {
    width: 96,
    height: 96,
    borderRadius: 48,
    backgroundColor: '#111111',
  },
  orbitLine: {
    position: 'absolute',
    width: 350,
    height: 2,
    backgroundColor: '#111111',
  },
  orbitLineA: {
    transform: [{ rotate: '18deg' }],
  },
  orbitLineB: {
    transform: [{ rotate: '62deg' }],
  },
  orbitLineC: {
    transform: [{ rotate: '-34deg' }],
  },
  ruleGroup: {
    position: 'absolute',
    left: 24,
    right: 24,
    top: '57%',
    gap: 9,
  },
  rule: {
    height: 1,
    backgroundColor: '#111111',
  },
  posterBottom: {
    marginTop: 'auto',
  },
  posterWord: {
    color: '#111111',
    fontSize: 58,
    lineHeight: 52,
    fontWeight: '900',
    letterSpacing: -4,
  },
  note: {
    alignSelf: 'center',
    maxWidth: 420,
    textAlign: 'center',
    fontFamily: MONO,
    fontSize: 11,
    lineHeight: 16,
  },
});
