import { Platform } from 'react-native';
import {
  DarkTheme,
  DefaultTheme,
  ThemeProvider,
} from 'expo-router/react-navigation';
import { Stack, router } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { FadeProvider } from '@/fade/FadeContext';
import { PanelTitle, PanelResetButton } from '@/components/PanelHeader';
import { useScheme, PALETTES } from '@/theme';

import DiscoverTune from '@expo/material-symbols/discover_tune.xml';

const flex1 = { flex: 1 };

export default function RootLayout() {
  const scheme = useScheme();
  const t = PALETTES[scheme];
  const isDark = scheme === 'dark';

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
                  headerTitleAlign: 'center',
                }}
              >
                <Stack.Toolbar placement="right" backgroundColor={t.card}>
                  <Stack.Toolbar.Button
                    icon={
                      Platform.OS === 'ios'
                        ? 'slider.horizontal.below.rectangle'
                        : DiscoverTune
                    }
                    tintColor={t.headerTint}
                    onPress={() => router.push('/panel')}
                  />
                </Stack.Toolbar>
              </Stack.Screen>
              <Stack.Screen
                name="photo/[id]"
                options={{
                  headerShown: true,
                  headerTransparent: true,
                  headerTitle: '',
                  headerBackButtonDisplayMode: 'minimal',
                  headerTintColor: t.headerTint,
                  animation: 'slide_from_right',
                }}
              >
                <Stack.Toolbar placement="right">
                  <Stack.Toolbar.Button
                    icon={
                      Platform.OS === 'ios'
                        ? 'slider.horizontal.below.rectangle'
                        : DiscoverTune
                    }
                    tintColor={t.headerTint}
                    onPress={() => router.push('/panel')}
                  />
                </Stack.Toolbar>
              </Stack.Screen>
              <Stack.Screen
                name="panel"
                options={{
                  presentation: 'formSheet',

                  headerTitle: () => <PanelTitle />,
                  headerRight: () => <PanelResetButton color={t.headerTint} />,

                  headerTransparent: Platform.OS === 'ios',
                  sheetAllowedDetents:
                    Platform.OS === 'ios' ? [0.7, 1.0] : [0.7],
                  sheetCornerRadius: Platform.OS === 'ios' ? 28 : 32,
                  headerShown: true,
                  headerTitleAlign: 'center',
                  contentStyle: {
                    backgroundColor:
                      Platform.OS === 'ios' ? 'transparent' : t.card,
                  },
                }}
              />
            </Stack>
          </ThemeProvider>
        </FadeProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
