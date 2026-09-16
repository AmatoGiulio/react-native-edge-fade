import { codegenNativeComponent, type ViewProps } from 'react-native';
import type {
  DirectEventHandler,
  Float,
} from 'react-native/Libraries/Types/CodegenTypesNamespace';

// Internal Android testbed. Deliberately NOT re-exported by index.tsx/native.ts.
interface NativeProps extends ViewProps {
  backend?: string;
  blurRadius?: Float;
  // Never use top/bottom/left/right: Fabric's ViewProps also reads those keys
  // as Yoga offsets, moving the viewport instead of only changing the blur.
  fadeTop?: Float;
  fadeBottom?: Float;
  fadeLeft?: Float;
  fadeRight?: Float;
  curve?: string;
  progression?: Float;
  cornerRadius?: Float;
  onBackendChange?: DirectEventHandler<
    Readonly<{
      requested: string;
      active: string;
      reason: string;
      androidxAvailable: boolean;
    }>
  >;
}

export default codegenNativeComponent<NativeProps>('EdgeFadeBlurLab', {
  excludedPlatforms: ['iOS'],
});
