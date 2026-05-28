import { memo, useMemo } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { Image } from 'expo-image';
import Ionicons from '@expo/vector-icons/Ionicons';
import { router } from 'expo-router';
import { useAppTheme } from '../hooks/useAppTheme';

// ── Status ────────────────────────────────────────────────────────────────────

export type PresenceStatus = 'online' | 'away' | 'dnd' | 'offline';

const STATUS_CONFIG: Record<
  PresenceStatus,
  { bg: string; icon?: 'remove' | 'moon'; iconColor?: string }
> = {
  online: { bg: '#22c55e' },
  away: { bg: '#f59e0b' },
  dnd: { bg: '#ef4444', icon: 'remove', iconColor: '#fff' },
  offline: { bg: '#6b7280', icon: 'moon', iconColor: '#fff' },
};

// ── DiceBear ──────────────────────────────────────────────────────────────────

function avatarUrl(seed: string) {
  return `https://api.dicebear.com/9.x/dylan/svg?seed=${encodeURIComponent(seed)}`;
}

// ── Layout ────────────────────────────────────────────────────────────────────

const AVATAR_SIZE = 66;
const DOT_SIZE = 13;

// ── Props ─────────────────────────────────────────────────────────────────────

interface Props {
  seed: string; // contact name — used as DiceBear seed
  label: string; // display name below avatar
  status: PresenceStatus;
}

// ── Themed styles ─────────────────────────────────────────────────────────────

function createStyles(sub: string) {
  return StyleSheet.create({
    name: {
      marginTop: 6,
      fontSize: 11,
      color: sub,
      fontWeight: '500',
      textAlign: 'center',
    },
  });
}

// ── Component ─────────────────────────────────────────────────────────────────

export const ContactAvatar = memo(function ContactAvatar({
  seed,
  label,
  status,
}: Props) {
  const t = useAppTheme();
  const themed = useMemo(() => createStyles(t.sub), [t.sub]);
  const cfg = STATUS_CONFIG[status];

  return (
    <Pressable
      style={({ pressed }) => [s.wrap, pressed && s.wrapPressed]}
      onPress={() => router.back()}
    >
      <View style={s.avatarWrap}>
        <Image
          source={{ uri: avatarUrl(seed) }}
          style={s.avatar}
          contentFit="cover"
          transition={300}
        />

        {/* Presence dot */}
        <View style={[s.dot, { backgroundColor: cfg.bg, borderColor: t.bg }]}>
          {cfg.icon && (
            <Ionicons
              name={cfg.icon}
              size={cfg.icon === 'moon' ? 7 : 8}
              color={cfg.iconColor}
            />
          )}
        </View>
      </View>

      <Text style={themed.name} numberOfLines={1}>
        {label}
      </Text>
    </Pressable>
  );
});

// ── Styles ────────────────────────────────────────────────────────────────────

const s = StyleSheet.create({
  wrap: {
    alignItems: 'center',
    width: 68,
    marginRight: 20,
  },
  wrapPressed: {
    opacity: 0.7,
    transform: [{ scale: 0.92 }],
  },
  avatarWrap: {
    width: AVATAR_SIZE,
    height: AVATAR_SIZE,
  },
  avatar: {
    width: AVATAR_SIZE,
    height: AVATAR_SIZE,
    borderRadius: AVATAR_SIZE / 2,
    backgroundColor: '#e5e5e5',
  },
  dot: {
    position: 'absolute',
    bottom: 0,
    right: 0,
    width: DOT_SIZE,
    height: DOT_SIZE,
    borderRadius: DOT_SIZE / 2,
    borderWidth: 2,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
