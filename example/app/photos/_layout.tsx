/**
 * Photos route group — a grid preview plus a native form-sheet editor.
 *
 * Both screens share one `FadeProvider` so the sheet's sliders drive the grid
 * behind them live. The editor is presented as a native iOS form sheet, so it
 * floats over the grid with a grabber and rounded corners.
 */

import { Stack } from 'expo-router';

import { FadeProvider } from './fade-context';

export default function PhotosLayout() {
  return (
    <FadeProvider>
      <Stack screenOptions={{ headerShown: false }}>
        <Stack.Screen name="index" />
        <Stack.Screen
          name="[id]"
          options={{ headerShown: false, animation: 'slide_from_right' }}
        />
        <Stack.Screen
          name="tune"
          options={{
            presentation: 'formSheet',
            animation: 'slide_from_bottom',
            sheetAllowedDetents: [0.55, 0.92],
            sheetGrabberVisible: true,
            sheetExpandsWhenScrolledToEdge: true,
            sheetCornerRadius: 28,
            contentStyle: { backgroundColor: '#141416' },
          }}
        />
      </Stack>
    </FadeProvider>
  );
}
