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
                  // Include the large detent so that when the native ColorPicker
                  // presents its system modal, iOS can expand this sheet instead
                  // of abruptly dismissing it (a single non-large detent leaves
                  // UIKit no room and it animates the sheet away).
                  sheetAllowedDetents: [0.7, 1.0],
                  sheetGrabberVisible: true,
                  sheetExpandsWhenScrolledToEdge: true,
                  sheetCornerRadius: 28,
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
