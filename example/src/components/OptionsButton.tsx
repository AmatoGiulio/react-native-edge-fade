/**
 * Native-header options button — opens the `/panel` tuning form sheet.
 * Kept at module scope so it isn't re-created on every screen render (which the
 * `headerRight: () => ...` inline form would do).
 */

import { Pressable } from 'react-native';
import { router } from 'expo-router';

import { SymbolIcon } from '@/components/SymbolIcon';

export function OptionsButton({ color }: { color: string }) {
  return (
    <Pressable onPress={() => router.push('/panel')} hitSlop={12}>
      <SymbolIcon name="slider.horizontal.below.rectangle" color={color} />
    </Pressable>
  );
}
