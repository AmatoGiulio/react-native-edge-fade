import { Pressable, StyleSheet, Text, View } from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useVideoPlayer, VideoView } from 'expo-video';
import { EdgeFadeView } from 'react-native-edge-fade';

const SOURCE = require('../assets/video/test-pattern.mp4');

type Surface = 'textureView' | 'surfaceView';

/**
 * Blur over playing video. Android can only blur a TextureView: a SurfaceView
 * composites outside the view hierarchy, so EdgeFadeView falls back to mask.
 */
export default function VideoScreen() {
  const params = useLocalSearchParams<{ surface?: string }>();
  const surface: Surface =
    params.surface === 'surfaceView' ? 'surfaceView' : 'textureView';

  const player = useVideoPlayer(SOURCE, (p) => {
    p.loop = true;
    p.muted = true;
    p.play();
  });

  return (
    <View style={styles.root}>
      <EdgeFadeView
        key={surface}
        testID="video-edge-fade"
        mode="blur"
        top={160}
        bottom={220}
        blurRadius={28}
        style={styles.fill}
      >
        <VideoView
          player={player}
          surfaceType={surface}
          contentFit="cover"
          nativeControls={false}
          style={styles.fill}
        />
      </EdgeFadeView>
      <View style={styles.bar}>
        {(['textureView', 'surfaceView'] as const).map((value) => (
          <Pressable
            key={value}
            accessibilityRole="button"
            accessibilityLabel={value}
            onPress={() => router.setParams({ surface: value })}
            style={[styles.chip, surface === value && styles.chipActive]}
          >
            <Text
              style={[
                styles.chipText,
                surface === value && styles.chipTextActive,
              ]}
            >
              {value}
            </Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#000000' },
  fill: { flex: 1, backgroundColor: '#000000' },
  bar: {
    position: 'absolute',
    top: '45%',
    alignSelf: 'center',
    flexDirection: 'row',
    gap: 8,
  },
  chip: {
    borderRadius: 20,
    backgroundColor: 'rgba(0,0,0,0.55)',
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  chipActive: { backgroundColor: '#ffffff' },
  chipText: { color: '#ffffff', fontWeight: '600' },
  chipTextActive: { color: '#000000' },
});
