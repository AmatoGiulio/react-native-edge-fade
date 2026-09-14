import { useRef, useState } from 'react';
import {
  Alert,
  PixelRatio,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  UIManager,
  View,
  type GestureResponderEvent,
} from 'react-native';
import { Stack, router } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { EdgeFadeView } from 'react-native-edge-fade';

import type NativeBlurLabType from '../../src/BlurLabNativeComponent';

type Backend = 'off' | 'legacy' | 'agsl' | 'public' | 'androidx';
const BACKENDS: readonly Backend[] = [
  'off',
  'legacy',
  'agsl',
  'public',
  'androidx',
];
const LABELS: Record<Backend, string> = {
  off: 'Off',
  legacy: 'Legacy',
  agsl: 'AGSL',
  public: 'Public RC',
  androidx: 'AndroidX',
};

// Both progressive renderers cap their actual shader radius at 150 px. Keep
// this interactive A/B scene inside that range in *dp* too: otherwise a dense
// device can request e.g. 48dp -> 168px, making Public RC correctly fall back to
// Legacy while the AGSL lab clamps to 150px. That looked like a renderer
// fidelity difference even though the two buttons were no longer exercising
// the same backend/radius.
const MAX_PROGRESSIVE_RADIUS_PX = 150;
const RADIUS_DENSITY = PixelRatio.get();
const MAX_PROGRESSIVE_RADIUS_DP =
  Math.floor((MAX_PROGRESSIVE_RADIUS_PX / RADIUS_DENSITY) * 100) / 100;

const ALBUMS = [
  '#37b9a7',
  '#5aa7d4',
  '#ef647b',
  '#9183c5',
  '#da974d',
  '#829961',
];
const TITLES = [
  ['Night Drive', 'Paper Satellites'],
  ['Slow Motion', 'Studio North'],
  ['Blue Hours', 'The Evening Club'],
  ['Soft Landing', 'Common Ground'],
  ['Parallel Lines', 'Minor Weather'],
  ['After the Rain', 'Quiet Company'],
];
const TRACKS = Array.from({ length: 48 }, (_, i) => ({
  id: String(i),
  title: TITLES[i % TITLES.length]![0],
  artist: TITLES[i % TITLES.length]![1],
  color: ALBUMS[i % ALBUMS.length],
  time: `${3 + (i % 3)}:${String((i * 13) % 60).padStart(2, '0')}`,
}));

export default function ProgressiveBlurRoute() {
  if (Platform.OS !== 'android') {
    return (
      <Message text="This experiment is Android-only. The published iOS renderer is unchanged." />
    );
  }
  if (!UIManager.getViewManagerConfig('EdgeFadeBlurLab')) {
    return (
      <Message text="Rebuild the Android example to register EdgeFadeBlurLab. A Metro reload alone is not enough." />
    );
  }
  // Do not load an Android-only host component on iOS/web or an old binary.
  const NativeBlurLab = require('../../src/BlurLabNativeComponent')
    .default as typeof NativeBlurLabType;
  return <PlaylistLab NativeBlurLab={NativeBlurLab} />;
}

function Message({ text }: { text: string }) {
  return (
    <View style={s.message}>
      <Stack.Screen options={{ headerShown: false }} />
      <Text style={s.title}>Progressive blur</Text>
      <Text style={s.description}>{text}</Text>
      <Pressable onPress={() => router.back()}>
        <Text style={s.link}>Back</Text>
      </Pressable>
    </View>
  );
}

function PlaylistLab({
  NativeBlurLab,
}: {
  NativeBlurLab: typeof NativeBlurLabType;
}) {
  const insets = useSafeAreaInsets();
  const [backend, setBackend] = useState<Backend>('public');
  const [radius, setRadius] = useState(24);
  const [allEdges, setAllEdges] = useState(false);
  const [linear, setLinear] = useState(false);
  const [status, setStatus] = useState({
    requested: '',
    active: '',
    reason: '',
    androidxAvailable: false,
  });
  // Switching Public RC <-> Blur Lab swaps the native parent and therefore
  // remounts the ScrollView. Keep one shared offset so a visual A/B never
  // compares two different pieces of the playlist after the user has scrolled.
  const scrollOffsetRef = useRef(0);
  const rememberScrollOffset = (offset: number) => {
    scrollOffsetRef.current = offset;
  };

  const publicCandidate = backend === 'public';
  const confirmed = !publicCandidate && status.requested === backend;
  const fallback = confirmed && status.active !== backend;
  const androidxEnabled =
    status.androidxAvailable && Number(Platform.Version) >= 33;

  // Fast Refresh can preserve the old 48dp state after this scene's range is
  // tightened. Clamp at render time as well as in the slider so a stale state
  // can never silently push Public RC over the native 150px eligibility cap.
  const effectiveRadius = Math.min(radius, MAX_PROGRESSIVE_RADIUS_DP);
  const effectiveRadiusPx = effectiveRadius * RADIUS_DENSITY;
  const radiusLabel = Number.isInteger(effectiveRadius)
    ? effectiveRadius.toFixed(0)
    : effectiveRadius.toFixed(2);

  // Old native binaries still emit the pre-fix event schema. Do not let a
  // Metro-only reload look like it tested the renamed native props/shader.
  if (
    !publicCandidate &&
    status.requested &&
    typeof status.androidxAvailable !== 'boolean'
  ) {
    return (
      <Message text="The native Blur Lab binary is outdated. Rebuild Android; a Metro reload does not include this fix." />
    );
  }

  return (
    <View
      style={[s.page, { paddingTop: insets.top, paddingBottom: insets.bottom }]}
    >
      <Stack.Screen options={{ headerShown: false }} />
      <View style={s.heading}>
        <Pressable onPress={() => router.back()} accessibilityRole="button">
          <Text style={s.eyebrow}>EDGE FADE / BLUR LAB</Text>
        </Pressable>
        <Text style={s.title}>After hours.</Text>
        <Text style={s.description}>
          One playlist. Compare the active native renderer.
        </Text>
      </View>

      <View style={s.backends}>
        {BACKENDS.map((item) => {
          const disabled = item === 'androidx' && !androidxEnabled;
          return (
            <Pressable
              key={item}
              disabled={disabled}
              accessibilityRole="button"
              accessibilityState={{ selected: backend === item, disabled }}
              onPress={() => setBackend(item)}
              style={[
                s.backend,
                backend === item && s.selected,
                disabled && s.disabled,
              ]}
            >
              <Text
                style={[s.backendLabel, backend === item && s.selectedLabel]}
              >
                {LABELS[item]}
              </Text>
            </Pressable>
          );
        })}
      </View>

      <View style={[s.backendStatus, fallback && s.failure]}>
        {publicCandidate ? (
          <>
            <Text testID="active-blur-backend" style={s.status}>
              Public EdgeFadeView / mode=blur / API {String(Platform.Version)}
            </Text>
            <Text style={s.publicHint}>
              Pure progressive Gaussian. Native activation is logged as
              {' `EdgeFadeProgressive`'}.
            </Text>
          </>
        ) : (
          <>
            <Text
              testID="active-blur-backend"
              style={[s.status, fallback && s.failureText]}
            >
              {confirmed
                ? `Requested: ${LABELS[backend]} / Active: ${status.active}`
                : 'Waiting for native confirmation...'}
            </Text>
            {fallback && (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel="Show renderer error"
                onPress={() => Alert.alert('Renderer error', status.reason)}
              >
                <Text numberOfLines={3} style={s.failureText}>
                  {status.reason}
                </Text>
                <Text style={s.failureLink}>
                  Not a valid comparison. Tap for the full diagnostic.
                </Text>
              </Pressable>
            )}
          </>
        )}
      </View>

      <View style={s.viewportFrame} collapsable={false}>
        {publicCandidate ? (
          <EdgeFadeView
            testID="public-progressive-blur"
            style={s.viewport}
            mode="blur"
            top={92}
            bottom={112}
            left={allEdges ? 48 : 0}
            right={allEdges ? 48 : 0}
            curve={linear ? 'linear' : 'smooth'}
            blurRadius={effectiveRadius}
            frostSaturation={1}
            frostLift={1}
            frostProgression={1}
          >
            <Playlist
              initialOffset={scrollOffsetRef.current}
              onOffsetChange={rememberScrollOffset}
            />
          </EdgeFadeView>
        ) : (
          <NativeBlurLab
            testID="progressive-blur-lab"
            style={s.viewport}
            backend={backend}
            blurRadius={effectiveRadius}
            fadeTop={92}
            fadeBottom={112}
            fadeLeft={allEdges ? 48 : 0}
            fadeRight={allEdges ? 48 : 0}
            curve={linear ? 'linear' : 'smooth'}
            progression={1}
            cornerRadius={24}
            onBackendChange={({ nativeEvent }) => setStatus(nativeEvent)}
          >
            <Playlist
              initialOffset={scrollOffsetRef.current}
              onOffsetChange={rememberScrollOffset}
            />
          </NativeBlurLab>
        )}
      </View>

      <View style={s.controls}>
        <View style={s.row}>
          <Text style={s.controlLabel}>Blur radius</Text>
          <Text style={s.value}>
            {radiusLabel} dp / {effectiveRadiusPx.toFixed(1)} px
          </Text>
        </View>
        <RadiusSlider
          value={effectiveRadius}
          max={MAX_PROGRESSIVE_RADIUS_DP}
          onChange={setRadius}
        />
        <View style={s.row}>
          <Pressable
            onPress={() => setAllEdges(!allEdges)}
            accessibilityRole="button"
          >
            <Text style={s.link}>
              {allEdges ? 'Four edges' : 'Top + bottom'}
            </Text>
          </Pressable>
          <Pressable
            onPress={() => setLinear(!linear)}
            accessibilityRole="button"
          >
            <Text style={s.link}>
              {linear ? 'Linear curve' : 'Smooth curve'}
            </Text>
          </Pressable>
        </View>
        <Text style={s.footnote}>
          {publicCandidate
            ? 'Public RC is pure progressive Gaussian: no saturation, lift, tint, opacity cross-fade or material grading. Radius range is density-normalized to <=150 px.'
            : status.requested && !status.androidxAvailable
              ? 'AndroidX is not compiled in this binary. AGSL is the dependency-free implementation. Radius range is density-normalized to <=150 px.'
              : 'AndroidX official is validated in the separate native reference APK.'}
        </Text>
      </View>
    </View>
  );
}

function Playlist({
  initialOffset,
  onOffsetChange,
}: {
  initialOffset: number;
  onOffsetChange: (offset: number) => void;
}) {
  const scrollRef = useRef<ScrollView>(null);
  const restorePendingRef = useRef(initialOffset > 0.5);

  const restorePosition = () => {
    if (initialOffset <= 0.5) {
      restorePendingRef.current = false;
      return;
    }
    scrollRef.current?.scrollTo({ y: initialOffset, animated: false });
  };

  return (
    <ScrollView
      ref={scrollRef}
      testID="progressive-playlist"
      style={s.list}
      contentContainerStyle={s.tracks}
      showsVerticalScrollIndicator={false}
      scrollEventThrottle={16}
      onLayout={() => requestAnimationFrame(restorePosition)}
      onContentSizeChange={restorePosition}
      onScroll={({ nativeEvent }) => {
        const offset = nativeEvent.contentOffset.y;
        if (restorePendingRef.current) {
          // A freshly mounted ScrollView can emit y=0 before the imperative
          // restore lands. Ignore that transient event so it cannot erase the
          // shared offset we are trying to preserve across backend swaps.
          if (Math.abs(offset - initialOffset) > 1) return;
          restorePendingRef.current = false;
        }
        onOffsetChange(offset);
      }}
    >
      {TRACKS.map((track, index) => (
        <View key={track.id} style={s.track}>
          <View
            pointerEvents="none"
            style={[s.cover, { backgroundColor: track.color }]}
          >
            <View style={s.coverDisc} />
            <Text style={s.coverNumber}>
              {String(index + 1).padStart(2, '0')}
            </Text>
          </View>
          <View style={s.trackText}>
            <Text style={s.trackTitle}>{track.title}</Text>
            <Text style={s.artist}>{track.artist}</Text>
          </View>
          <Text style={s.duration}>{track.time}</Text>
        </View>
      ))}
    </ScrollView>
  );
}

function RadiusSlider({
  value,
  max,
  onChange,
}: {
  value: number;
  max: number;
  onChange: (value: number) => void;
}) {
  const [width, setWidth] = useState(1);
  const update = (event: GestureResponderEvent) => {
    const position = Math.max(
      0,
      Math.min(1, event.nativeEvent.locationX / width)
    );
    // Keep ordinary movement readable at 0.1dp resolution, but preserve the
    // exact density-derived max at the end stop so both renderers receive the
    // same near-150px value without ever crossing the native cap.
    const next = position >= 1 ? max : Math.round(position * max * 10) / 10;
    onChange(Math.min(max, next));
  };
  const percent = max > 0 ? (value / max) * 100 : 0;
  return (
    <View
      style={s.slider}
      onLayout={({ nativeEvent }) =>
        setWidth(Math.max(1, nativeEvent.layout.width))
      }
      onStartShouldSetResponder={() => true}
      onMoveShouldSetResponder={() => true}
      onResponderGrant={update}
      onResponderMove={update}
      accessibilityRole="adjustable"
      accessibilityLabel="Blur radius"
      accessibilityValue={{ min: 0, max, now: value }}
      accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]}
      onAccessibilityAction={({ nativeEvent }) => {
        const delta = nativeEvent.actionName === 'increment' ? 1 : -1;
        onChange(
          Math.max(0, Math.min(max, Math.round((value + delta) * 10) / 10))
        );
      }}
    >
      <View pointerEvents="none" style={s.sliderRail}>
        <View
          style={[
            s.sliderFill,
            { width: `${Math.max(0, Math.min(100, percent))}%` },
          ]}
        />
      </View>
      <View
        pointerEvents="none"
        style={[s.thumb, { left: `${Math.max(0, Math.min(100, percent))}%` }]}
      />
    </View>
  );
}

const s = StyleSheet.create({
  page: { flex: 1, backgroundColor: '#f7f7f5' },
  heading: { paddingHorizontal: 24, paddingTop: 14, paddingBottom: 16 },
  eyebrow: {
    fontSize: 10,
    letterSpacing: 2,
    fontWeight: '700',
    color: '#656560',
    marginBottom: 12,
  },
  title: {
    fontSize: 34,
    letterSpacing: -1.2,
    fontWeight: '700',
    color: '#181918',
  },
  description: { fontSize: 12, lineHeight: 19, color: '#74756f', marginTop: 6 },
  backends: {
    flexDirection: 'row',
    marginHorizontal: 20,
    marginBottom: 8,
    backgroundColor: '#e9e9e5',
    borderRadius: 12,
    padding: 4,
  },
  backend: {
    flex: 1,
    paddingVertical: 10,
    alignItems: 'center',
    borderRadius: 9,
  },
  selected: { backgroundColor: '#202520' },
  backendLabel: { color: '#63665e', fontSize: 10, fontWeight: '600' },
  selectedLabel: { color: '#ffffff' },
  disabled: { opacity: 0.35 },
  backendStatus: {
    marginHorizontal: 20,
    marginBottom: 10,
    paddingHorizontal: 6,
    paddingVertical: 4,
    // The public status has an extra two-line hint. Without a common minimum,
    // switching backend changes the flex viewport height and moves the bottom
    // fade relative to the playlist, which invalidates visual comparisons.
    minHeight: 52,
  },
  failure: { backgroundColor: '#fce8e5', borderRadius: 8, padding: 10 },
  failureText: { color: '#9b2620', fontSize: 11, lineHeight: 15 },
  failureLink: {
    color: '#9b2620',
    fontSize: 10,
    fontWeight: '600',
    marginTop: 4,
  },
  publicHint: { color: '#74756f', fontSize: 9, lineHeight: 13, marginTop: 2 },
  viewportFrame: {
    flex: 1,
    minHeight: 0,
    marginHorizontal: 16,
    backgroundColor: '#ffffff',
    borderRadius: 24,
    overflow: 'hidden',
  },
  viewport: { flex: 1 },
  list: { flex: 1 },
  tracks: { paddingHorizontal: 18, paddingVertical: 16 },
  track: { flexDirection: 'row', alignItems: 'center', height: 64, gap: 13 },
  cover: {
    width: 44,
    height: 44,
    borderRadius: 10,
    overflow: 'hidden',
    justifyContent: 'flex-end',
    padding: 6,
  },
  coverDisc: {
    position: 'absolute',
    width: 46,
    height: 46,
    borderRadius: 23,
    borderWidth: 9,
    borderColor: '#ffffff50',
    left: 14,
    top: -8,
  },
  coverNumber: { color: '#ffffff', fontSize: 9, fontWeight: '600' },
  trackText: { flex: 1 },
  trackTitle: { color: '#202322', fontSize: 13, fontWeight: '600' },
  artist: { color: '#858a85', fontSize: 11, marginTop: 5 },
  duration: {
    fontSize: 10,
    color: '#999d96',
    fontVariant: ['tabular-nums'],
  },
  controls: { paddingHorizontal: 26, paddingTop: 18, paddingBottom: 12 },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  controlLabel: { fontSize: 12, color: '#3c413a', fontWeight: '600' },
  value: { fontSize: 11, color: '#7a8076', fontVariant: ['tabular-nums'] },
  slider: { height: 38, justifyContent: 'center', marginHorizontal: 10 },
  sliderRail: {
    height: 5,
    borderRadius: 3,
    backgroundColor: '#dcded6',
    overflow: 'hidden',
  },
  sliderFill: { height: 5, backgroundColor: '#4d6743' },
  thumb: {
    position: 'absolute',
    width: 20,
    height: 20,
    borderRadius: 10,
    marginLeft: -10,
    backgroundColor: '#4d6743',
  },
  link: {
    color: '#4d6743',
    fontWeight: '600',
    fontSize: 12,
    paddingVertical: 8,
  },
  status: { color: '#3c413a', fontSize: 11, lineHeight: 15, minHeight: 15 },
  footnote: {
    color: '#93978e',
    fontSize: 9,
    lineHeight: 13,
    marginTop: 4,
    // Keep a fixed note block height so backend swaps never resize the viewport.
    minHeight: 39,
  },
  message: {
    flex: 1,
    justifyContent: 'center',
    padding: 28,
    backgroundColor: '#f7f7f5',
  },
});
