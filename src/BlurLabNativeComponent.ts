import {
  codegenNativeComponent,
  type CodegenTypes,
  type ViewProps,
} from 'react-native';

// Internal Android testbed. Deliberately NOT re-exported by index.tsx/native.ts.
interface NativeProps extends ViewProps {
  backend?: string;
  blurRadius?: CodegenTypes.Float;
  // Never use top/bottom/left/right: Fabric's ViewProps also reads those keys
  // as Yoga offsets, moving the viewport instead of only changing the blur.
  fadeTop?: CodegenTypes.Float;
  fadeBottom?: CodegenTypes.Float;
  fadeLeft?: CodegenTypes.Float;
  fadeRight?: CodegenTypes.Float;
  curve?: string;
  progression?: CodegenTypes.Float;
  cornerRadius?: CodegenTypes.Float;
  onBackendChange?: CodegenTypes.DirectEventHandler<
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
