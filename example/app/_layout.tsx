import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { StyleSheet, View } from 'react-native';

const BG = '#080808';

export default function RootLayout() {
  return (
    <View style={s.root}>
      <StatusBar style="light" />
      <Stack
        screenOptions={{
          headerShown: false,
          contentStyle: { backgroundColor: BG },
          animation: 'fade',
        }}
      >
        <Stack.Screen name="index" />
        <Stack.Screen name="item/[id]" />
        <Stack.Screen name="benchmark" />
        <Stack.Screen
          name="share"
          options={{
            presentation: 'formSheet',
            animation: 'slide_from_bottom',
            sheetAllowedDetents: [0.5, 1.0],
            sheetGrabberVisible: true,
            sheetExpandsWhenScrolledToEdge: true,
            contentStyle: { backgroundColor: '#111' },
          }}
        />
      </Stack>
    </View>
  );
}

const s = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: BG,
  },
});
