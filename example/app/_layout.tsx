import { Platform } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
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
import Web from '@expo/material-symbols/web.xml';
import Apps from '@expo/material-symbols/apps.xml';
import GraphicEq from '@expo/material-symbols/graphic_eq.xml';
import Videocam from '@expo/material-symbols/videocam.xml';
import GridView from '@expo/material-symbols/grid_view.xml';
const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 2, staleTime: 30 * 60 * 1000 } },
});
const flex1 = { flex: 1 };

export default function RootLayout() {
  const scheme = useScheme();
  const t = PALETTES[scheme];
  const isDark = scheme === 'dark';

  return (
    <GestureHandlerRootView style={flex1}>
      <SafeAreaProvider>
        <QueryClientProvider client={queryClient}>
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
                    <Stack.Toolbar.Menu
                      icon={Platform.OS === 'ios' ? 'square.grid.2x2' : Apps}
                      tintColor={t.headerTint}
                      accessibilityLabel="demos"
                      cornerRadius={20}
                    >
                      <Stack.Toolbar.MenuAction
                        icon={Platform.OS === 'ios' ? 'waveform' : GraphicEq}
                        onPress={() => router.push('/meeting')}
                      >
                        Live blur
                      </Stack.Toolbar.MenuAction>
                      <Stack.Toolbar.MenuAction
                        icon={Platform.OS === 'ios' ? 'video' : Videocam}
                        onPress={() => router.push('/video')}
                      >
                        Video surface
                      </Stack.Toolbar.MenuAction>
                      <Stack.Toolbar.MenuAction
                        icon={Platform.OS === 'ios' ? 'safari' : Web}
                        onPress={() => router.push('/webview')}
                      >
                        WebView
                      </Stack.Toolbar.MenuAction>
                      <Stack.Toolbar.MenuAction
                        icon={
                          Platform.OS === 'ios' ? 'square.split.1x2' : GridView
                        }
                        onPress={() => router.push('/debug')}
                      >
                        Debug bands
                      </Stack.Toolbar.MenuAction>
                    </Stack.Toolbar.Menu>
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
                  name="debug"
                  options={{
                    headerShown: true,
                    headerTransparent: true,
                    headerTitle: 'Debug',
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
                  name="webview"
                  options={{
                    headerShown: true,
                    headerTransparent: true,
                    headerTitle: 'WebView',
                    headerBackButtonDisplayMode: 'minimal',
                    headerTintColor: 'transparent',
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
                    headerRight: () => (
                      <PanelResetButton color={t.headerTint} />
                    ),

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
        </QueryClientProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
