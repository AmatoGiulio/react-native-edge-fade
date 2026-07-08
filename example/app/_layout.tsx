import {
  DarkTheme,
  DefaultTheme,
  ThemeProvider,
} from '@react-navigation/native';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { FadeProvider } from '@/fade/FadeContext';
import { OptionsButton } from '@/components/OptionsButton';
import { useScheme, PALETTES } from '@/theme';

const flex1 = { flex: 1 };

export default function RootLayout() {
  const scheme = useScheme();
  const t = PALETTES[scheme];
  const isDark = scheme === 'dark';

  // A stable render function for the header options button — declared per theme
  // so the native header isn't re-committed (which flickers the back button) on
  // every screen re-render.
  const renderOptions = () => <OptionsButton color={t.headerTint} />;

  return (
    <GestureHandlerRootView style={flex1}>
      <SafeAreaProvider>
        <FadeProvider>
          <ThemeProvider value={isDark ? DarkTheme : DefaultTheme}>
            <StatusBar style="auto" />
            <Stack
              screenOptions={{
                headerShown: false,
                contentStyle: { backgroundColor: t.bg },
                animation: 'fade',
              }}
            >
              <Stack.Screen
                name="index"
                options={{
                  headerShown: true,
                  headerTransparent: true,
                  headerTitle: 'Photos',
                  headerTintColor: t.headerTint,
                  headerRight: renderOptions,
                }}
              />
              <Stack.Screen
                name="photo/[id]"
                options={{
                  headerShown: true,
                  headerTransparent: true,
                  headerTitle: '',
                  headerBackButtonDisplayMode: 'minimal',
                  headerTintColor: t.headerTint,
                  headerRight: renderOptions,
                  animation: 'slide_from_right',
                }}
              />
              <Stack.Screen
                name="panel"
                options={{
                  presentation: 'formSheet',
                  animation: 'slide_from_bottom',
                  title: 'Tune',

                  // Include the large detent so that when the native ColorPicker
                  // presents its system modal, iOS can expand this sheet instead
                  // of abruptly dismissing it (a single non-large detent leaves
                  // UIKit no room and it animates the sheet away).
                  sheetAllowedDetents: [0.7, 1.0],
                  headerShown: true,
                  //sheetGrabberVisible: true,
                  sheetExpandsWhenScrolledToEdge: true,
                  sheetCornerRadius: 28,
                  headerTransparent: true,
                  //headerRight: renderOptions,
                  // formSheet gives the screen container an ambiguous width on the
                  // New Architecture, which shifts the whole panel sideways. Pinning
                  // width to 100% forces a deterministic frame. NOTE: do not pin
                  // height to 100% — that makes the content taller than the sheet and
                  // clips the top rows under the sheet's rounded top edge.
                  contentStyle: { backgroundColor: t.card },
                }}
              />
            </Stack>
          </ThemeProvider>
        </FadeProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
