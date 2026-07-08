/**
 * Native-header options button — opens the `/panel` tuning form sheet.
 * Kept at module scope so it isn't re-created on every screen render (which the
 * `headerRight: () => ...` inline form would do).
 */

import { Pressable } from 'react-native';
import { router } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';

export function OptionsButton({ color }: { color: string }) {
  return (
    <Pressable onPress={() => router.push('/panel')} hitSlop={12}>
      <Ionicons name="options-outline" size={22} color={color} />
    </Pressable>
  );
}
