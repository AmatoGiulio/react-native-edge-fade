import { memo, useEffect } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useVideoPlayer, VideoView } from 'expo-video';
import type { FootageItem } from '../constants/catalog';

// Fixed card dimensions — all cards share the same scaffold
const CARD_W = 88;
const CARD_H = 140;
const CARD_RADIUS = 14;

interface Props {
  footage: FootageItem;
  active: boolean;
  onPress: () => void;
}

export const FootageCard = memo(function FootageCard({
  footage,
  active,
  onPress,
}: Props) {
  const player = useVideoPlayer(footage.source, (p) => {
    p.loop = true;
    p.muted = true;
  });

  useEffect(() => {
    if (active) {
      player.play();
    } else {
      player.pause();
    }
  }, [active, player]);

  return (
    <Pressable
      onPress={onPress}
      style={[s.root, active ? s.rootActive : s.rootIdle]}
    >
      {/* Video — only mounted when active to limit decoders */}
      {active && (
        <VideoView
          player={player}
          style={StyleSheet.absoluteFill}
          contentFit="cover"
          nativeControls={false}
        />
      )}

      {/* Inactive: same dark card + centered play icon */}
      {!active && (
        <>
          <View style={s.idleBg} />
          <View style={s.iconWrap}>
            <Ionicons
              name="play-circle"
              size={28}
              color="rgba(255,255,255,0.25)"
            />
          </View>
        </>
      )}

      {/* Bottom label — always present on both states */}
      <View style={s.labelRow}>
        <Text style={s.title} numberOfLines={1}>
          {footage.title}
        </Text>
      </View>
    </Pressable>
  );
});

const s = StyleSheet.create({
  root: {
    width: CARD_W,
    height: CARD_H,
    borderRadius: CARD_RADIUS,
    overflow: 'hidden',
    marginRight: 10,
    borderWidth: 1.5,
  },
  rootActive: {
    borderColor: 'rgba(255,255,255,0.65)',
  },
  rootIdle: {
    borderColor: 'rgba(255,255,255,0.12)',
  },
  idleBg: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: '#1a1a1a',
  },
  iconWrap: {
    ...StyleSheet.absoluteFillObject,
    alignItems: 'center',
    justifyContent: 'center',
    // push icon slightly above the label
    paddingBottom: 22,
  },
  labelRow: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    paddingHorizontal: 8,
    paddingBottom: 8,
    paddingTop: 16,
    // subtle gradient-like fade using a semi-transparent scrim
    backgroundColor: 'rgba(0,0,0,0.45)',
  },
  title: {
    fontSize: 10,
    fontWeight: '600',
    color: 'rgba(255,255,255,0.8)',
    letterSpacing: 0.3,
  },
});
