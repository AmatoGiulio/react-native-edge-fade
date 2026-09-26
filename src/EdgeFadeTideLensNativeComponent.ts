import {
  codegenNativeComponent,
  type CodegenTypes,
  type ViewProps,
} from 'react-native';

// Experimental Android-only wrapper: refracts its children with the Marea
// surface lens published by a fieldmask EdgeFadeView, so views drawn above
// the host (menu, avatar, segment) are crossed by the same wave.
// Deliberately NOT re-exported by index.tsx/native.ts.
interface NativeProps extends ViewProps {
  /** Multiplier on the host's surface lens displacement. */
  tideLensStrength?: CodegenTypes.WithDefault<CodegenTypes.Float, 1>;
}

export default codegenNativeComponent<NativeProps>('EdgeFadeTideLens', {
  excludedPlatforms: ['iOS'],
});
