/**
 * Before/after comparison for the photo detail: the raw image on the left, the
 * same image with the live edge fade on the right, split by a draggable divider.
 *
 * Both halves share one frame so top/bottom edges line up — the eye reads the
 * difference (crisp vs faded) as the handle wipes across. The faded overlay is
 * clipped from the right by an animated width and is driven by the SHARED fade
 * store, so it stays in sync with the panel and the auto-demo.
 */

import { useCallback } from 'react';
import { Image, StyleSheet, Text, View } from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withSpring,
  type SharedValue,
} from 'react-native-reanimated';
import type { ImageSourcePropType } from 'react-native';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';
import type { CubicBezierCurve, EdgeFadeMode } from 'react-native-edge-fade';

const HANDLE = 40;
const KNOB_R = 14;
const KNOB_CORE_R = 8;
const KNOB_ACTIVE_SCALE = 1.3;
const SPRING = { damping: 15, stiffness: 220, mass: 0.5 } as const;

export interface BeforeAfterPhotoProps {
  source: ImageSourcePropType;
  top: SharedValue<number>;
  bottom: SharedValue<number>;
  left: SharedValue<number>;
  right: SharedValue<number>;
  radius: SharedValue<number>;
  curve: CubicBezierCurve;
  mode: EdgeFadeMode;
  blurRadius: number;
  color: string | undefined;
  /**
   * Page background shown through the faded edges on the "On" side. In mask mode
   * the edges go transparent, so without an opaque backdrop the raw base image
   * would show through and the comparison would look like a no-op.
   */
  background: string;
}

export function BeforeAfterPhoto({
  source,
  top,
  bottom,
  left,
  right,
  radius,
  curve,
  mode,
  blurRadius,
  color,
  background,
}: BeforeAfterPhotoProps) {
  // Divider position (fraction 0..1 of the frame width) and the measured width.
  const split = useSharedValue(0.5);
  const frameW = useSharedValue(0);
  const start = useSharedValue(0.5);
  const active = useSharedValue(0);

  const onLayout = useCallback((w: number) => frameW.set(w), [frameW]);

  const pan = Gesture.Pan()
    .onTouchesDown(() => {
      active.set(1);
    })
    .onStart(() => {
      start.set(split.get());
    })
    .onUpdate((e) => {
      const w = frameW.get();
      if (w <= 0) return;
      const next = start.get() + e.translationX / w;
      split.set(Math.max(0, Math.min(1, next)));
    })
    .onFinalize(() => {
      active.set(0);
    });

  // Faded overlay is anchored right and revealed from the divider to the edge.
  const overlayStyle = useAnimatedStyle(() => ({
    width: `${(1 - split.get()) * 100}%`,
  }));
  const handleStyle = useAnimatedStyle(() => ({
    left: `${split.get() * 100}%`,
  }));
  const knobStyle = useAnimatedStyle(() => ({
    transform: [
      {
        scale: withSpring(active.get() ? KNOB_ACTIVE_SCALE : 1, SPRING),
      },
    ],
  }));
  // Pins the faded layer to the frame's full width (right-anchored) so it lines
  // up with the base image even though its parent clipper is only a slice wide.
  const fullWidthStyle = useAnimatedStyle(() => ({
    position: 'absolute',
    right: 0,
    top: 0,
    bottom: 0,
    width: frameW.get() || 1,
  }));

  return (
    <View
      style={s.frame}
      onLayout={(e) => onLayout(e.nativeEvent.layout.width)}
    >
      {/* Base: raw image, no fade */}
      <Image source={source} style={s.fill} resizeMode="cover" />
      <View style={s.labelLeft} pointerEvents="none">
        <Text style={s.labelText}>Off</Text>
      </View>

      {/* Overlay: faded image, clipped from the right, aligned to the frame */}
      <Animated.View style={[s.overlay, overlayStyle]} pointerEvents="none">
        <Animated.View
          style={[fullWidthStyle, { backgroundColor: background }]}
        >
          <AnimatedEdgeFadeView
            top={top}
            bottom={bottom}
            left={left}
            right={right}
            radius={radius}
            curve={curve}
            mode={mode}
            blurRadius={blurRadius}
            color={color}
            style={s.fill}
          >
            <Image source={source} style={s.fill} resizeMode="cover" />
          </AnimatedEdgeFadeView>
        </Animated.View>
        <View style={s.labelRight} pointerEvents="none">
          <Text style={s.labelText}>On</Text>
        </View>
      </Animated.View>

      {/* Divider + draggable handle */}
      <GestureDetector gesture={pan}>
        <Animated.View style={[s.handleHit, handleStyle]}>
          <View style={s.line} />
          <Animated.View style={[s.knob, knobStyle]}>
            <View style={s.knobCore} />
          </Animated.View>
        </Animated.View>
      </GestureDetector>
    </View>
  );
}

const s = StyleSheet.create({
  frame: { width: '100%', height: '100%', overflow: 'hidden' },
  fill: { width: '100%', height: '100%' },

  overlay: {
    position: 'absolute',
    right: 0,
    top: 0,
    bottom: 0,
    overflow: 'hidden',
  },

  handleHit: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    width: HANDLE,
    marginLeft: -HANDLE / 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
  line: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    width: 2,
    backgroundColor: 'rgba(255,255,255,0.9)',
  },
  knob: {
    width: KNOB_R * 2,
    height: KNOB_R * 2,
    borderRadius: KNOB_R,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#fff',
    shadowColor: '#000',
    shadowOpacity: 0.28,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 3 },
    elevation: 6,
  },
  knobCore: {
    width: KNOB_CORE_R * 2,
    height: KNOB_CORE_R * 2,
    borderRadius: KNOB_CORE_R,
    backgroundColor: '#4A90E2',
  },

  labelLeft: {
    position: 'absolute',
    left: 10,
    bottom: 10,
    backgroundColor: 'rgba(0,0,0,0.45)',
    borderRadius: 8,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  labelRight: {
    position: 'absolute',
    right: 10,
    bottom: 10,
    backgroundColor: 'rgba(0,0,0,0.45)',
    borderRadius: 8,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  labelText: { color: '#fff', fontSize: 12, fontWeight: '600' },
});
