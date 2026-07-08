/**
 * Native SF Symbol icon (iOS) for the app's nav/header buttons — rendered via
 * @expo/ui's SwiftUI `Image(systemName:)`, so no icon font (Ionicons etc.) is
 * shipped. Wrap it in a Pressable for tap handling; the Host sizes to the glyph.
 *
 * Android: @expo/ui 55 exposes no universal Icon, so this renders iOS-only. When
 * @expo/ui is upgraded, swap this for the universal `Icon` + `@expo/material-symbols`.
 */

import { Host, Image } from '@expo/ui/swift-ui';
import type { SFSymbol } from 'sf-symbols-typescript';

export interface SymbolIconProps {
  name: SFSymbol;
  color: string;
  size?: number;
}

export function SymbolIcon({ name, color, size = 22 }: SymbolIconProps) {
  return (
    <Host matchContents>
      <Image systemName={name} size={size} color={color} />
    </Host>
  );
}
