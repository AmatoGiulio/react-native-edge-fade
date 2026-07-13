/**
 * Cross-platform symbol icon.
 *
 * iOS — SF Symbol via `@expo/ui/swift-ui` (`Host` + `Image systemName`).
 * Android — Material Symbol (outlined XML vector drawable from
 * `@expo/material-symbols`) via `@expo/ui/jetpack-compose` (`Host` + `Icon`).
 *
 * The public API stays SF-Symbol-named; `MATERIAL_FOR_SF` maps each SF name
 * used in the app to its Material equivalent. Metro only bundles the XMLs
 * imported here. When @expo/ui ships the universal `Icon` (SDK 56+), this
 * component collapses to a single implementation.
 */

import { Platform, View } from 'react-native';
import type { ImageSourcePropType } from 'react-native';
import type { SFSymbol } from 'sf-symbols-typescript';

import DiscoverTune from '@expo/material-symbols/discover_tune.xml';
import InstantMix from '@expo/material-symbols/instant_mix.xml';
import KeyboardArrowDown from '@expo/material-symbols/keyboard_arrow_down.xml';
import KeyboardArrowUp from '@expo/material-symbols/keyboard_arrow_up.xml';
import Refresh from '@expo/material-symbols/refresh.xml';

export interface SymbolIconProps {
  name: SFSymbol;
  color: string;
  size?: number;
}

// SF Symbol name → Material Symbol drawable, for every symbol the app uses.
const MATERIAL_FOR_SF: Partial<Record<SFSymbol, ImageSourcePropType>> = {
  'slider.horizontal.below.rectangle': DiscoverTune,
  'slider.horizontal.3': InstantMix,
  'arrow.counterclockwise': Refresh,
  'chevron.up': KeyboardArrowUp,
  'chevron.down': KeyboardArrowDown,
};

function AndroidSymbolIcon({ name, color, size = 22 }: SymbolIconProps) {
  const source = MATERIAL_FOR_SF[name];
  if (!source) {
    return <View style={{ width: size, height: size }} />;
  }
  const { Host, Icon } = require('@expo/ui/jetpack-compose');
  return (
    <Host matchContents style={{ width: size, height: size }}>
      <Icon source={source} size={size} tint={color} />
    </Host>
  );
}

export function SymbolIcon(props: SymbolIconProps) {
  if (Platform.OS === 'android') {
    return <AndroidSymbolIcon {...props} />;
  }
  if (Platform.OS !== 'ios') {
    const { size = 22 } = props;
    return <View style={{ width: size, height: size }} />;
  }
  const { Host, Image } = require('@expo/ui/swift-ui');
  const { name, color, size = 22 } = props;
  return (
    <Host matchContents>
      <Image systemName={name} size={size} color={color} />
    </Host>
  );
}
