import { Platform, View, type ViewProps } from 'react-native';
import { requireNativeView } from 'expo';

export interface CrateCanvasProps extends ViewProps {
  /** Thumbnail file names under the module's `assets/crate/` (hero: `crate/h/`). */
  covers: string[];
  /** Change it to close the open cover from outside the grid. */
  closeSignal?: number;
  /** A cover was pressed; its hero is already flying out. */
  onCoverPress?: (event: { nativeEvent: { index: number } }) => void;
  /** The open cover started flying back. */
  onClose?: () => void;
}

function Placeholder({ style }: CrateCanvasProps) {
  return <View style={style} />;
}

// Android only: iOS renders an empty surface until the Metal port lands.
export const CrateCanvas: React.ComponentType<CrateCanvasProps> =
  Platform.OS === 'android' ? requireNativeView('CrateCanvas') : Placeholder;
