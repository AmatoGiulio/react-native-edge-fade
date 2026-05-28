import { useColorScheme } from 'react-native';
import {
  DarkTheme,
  DefaultTheme,
  ThemeProvider,
} from '@react-navigation/native';
import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { palette } from '../constants/theme';
import { SafeAreaProvider } from 'react-native-safe-area-context';

export default function RootLayout() {
  const scheme = useColorScheme();
  const isDark = scheme !== 'light';
  const t = palette[isDark ? 'dark' : 'light'];

  return (
    <SafeAreaProvider>
      <ThemeProvider value={isDark ? DarkTheme : DefaultTheme}>
        <StatusBar style={isDark ? 'light' : 'dark'} />
        <Stack
          screenOptions={{
            headerShown: false,
            contentStyle: { backgroundColor: t.bg },
            animation: 'fade',
          }}
        >
          <Stack.Screen name="index" />
          <Stack.Screen name="item/[id]" />
          <Stack.Screen name="benchmark" />
          <Stack.Screen
            name="demo"
            options={{
              animation: 'slide_from_bottom',
              contentStyle: { backgroundColor: '#000' },
            }}
          />
          <Stack.Screen
            name="share"
            options={{
              presentation: 'formSheet',
              animation: 'slide_from_bottom',
              sheetAllowedDetents: [0.65, 1.0],
              sheetGrabberVisible: true,
              sheetExpandsWhenScrolledToEdge: true,
              sheetCornerRadius: 36,
              contentStyle: { backgroundColor: t.surface },
            }}
          />
        </Stack>
      </ThemeProvider>
    </SafeAreaProvider>
  );
}
