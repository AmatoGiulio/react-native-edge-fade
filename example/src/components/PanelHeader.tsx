/**
 * Native-header pieces for the `/panel` tuning form sheet.
 *
 * `PanelTitle` is the centered two-line title ("Tune" + the install command as a
 * monospace subtitle); `PanelResetButton` is the header-right reset that restores
 * every value to its default via the shared FadeContext. Both are kept at module
 * scope so the native header isn't re-committed on each panel render.
 */

import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';

import { useFadeStore } from '@/fade/FadeContext';
import { useTheme } from '@/theme';

const MONO = 'Menlo';
const INSTALL = 'npm install react-native-edge-fade';

export function PanelTitle() {
  const t = useTheme();
  return (
    <View style={s.title} pointerEvents="none">
      <Text style={[s.titleText, { color: t.text }]}>Tune</Text>
      <Text style={[s.subtitle, { color: t.faintText }]}>{INSTALL}</Text>
    </View>
  );
}

export function PanelResetButton({ color }: { color: string }) {
  const { reset } = useFadeStore();
  return (
    <Pressable onPress={reset} hitSlop={12}>
      <Ionicons name="refresh" size={22} color={color} />
    </Pressable>
  );
}

const s = StyleSheet.create({
  title: { alignItems: 'center' },
  titleText: { fontSize: 17, fontWeight: '700' },
  subtitle: { fontFamily: MONO, fontSize: 11, marginTop: 2 },
});
