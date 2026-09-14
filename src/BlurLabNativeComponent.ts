import { codegenNativeComponent, type ViewProps } from 'react-native';
import type {
  DirectEventHandler,
  Float,
} from 'react-native/Libraries/Types/CodegenTypesNamespace';

// Internal Android testbed. Deliberately NOT re-exported by index.tsx/native.ts.
interface NativeProps extends ViewProps {
  backend?: string;
  blurRadius?: Float;
  top?: Float;
  bottom?: Float;
  left?: Float;
  right?: Float;
  curve?: string;
  progression?: Float;
  cornerRadius?: Float;
  onBackendChange?: DirectEventHandler<
    Readonly<{ requested: string; active: string; reason: string }>
  >;
}

export default codegenNativeComponent<NativeProps>('EdgeFadeBlurLab', {
  excludedPlatforms: ['iOS'],
});
