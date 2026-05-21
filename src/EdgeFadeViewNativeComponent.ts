import {
  codegenNativeComponent,
  type ColorValue,
  type ViewProps,
} from 'react-native';
import type { Float } from 'react-native/Libraries/Types/CodegenTypesNamespace';

// Flat native props produced by the JS normalization layer.
// Sizes are in dp (0 = edge disabled). Curves are preset names or
// comma-separated alpha stop strings (from cubicBezier / stops serialization).
// mode: "mask" | "overlay"
interface NativeProps extends ViewProps {
  fadeTop?: Float;
  fadeBottom?: Float;
  fadeLeft?: Float;
  fadeRight?: Float;
  curveTop?: string;
  curveBottom?: string;
  curveLeft?: string;
  curveRight?: string;
  /** "mask" | "overlay" */
  mode?: string;
  overlayColor?: ColorValue;
  overlayColorTop?: ColorValue;
  overlayColorBottom?: ColorValue;
  overlayColorLeft?: ColorValue;
  overlayColorRight?: ColorValue;
  fadeRadius?: Float;
}

export default codegenNativeComponent<NativeProps>('EdgeFadeView');
