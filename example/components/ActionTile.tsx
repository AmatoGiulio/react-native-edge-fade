import { memo, useMemo } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { router } from 'expo-router';
import { useAppTheme } from '../hooks/useAppTheme';

export type IoniconName = React.ComponentProps<typeof Ionicons>['name'];

interface Props {
  label: string;
  icon: IoniconName;
  /** Optional tint for the icon (defaults to theme text color) */
  tint?: string;
}

function createStyles(surface: string, border: string, sub: string) {
  return StyleSheet.create({
    iconBox: {
      width: 56,
      height: 56,
      borderRadius: 14,
      backgroundColor: surface,
      borderWidth: 1,
      borderColor: border,
      alignItems: 'center',
      justifyContent: 'center',
    },
    label: {
      marginTop: 6,
      fontSize: 11,
      color: sub,
      fontWeight: '500',
      textAlign: 'center',
    },
  });
}

const s = StyleSheet.create({
  wrap: { alignItems: 'center', width: 72, marginRight: 4 },
});

export const ActionTile = memo(function ActionTile({
  label,
  icon,
  tint,
}: Props) {
  const t = useAppTheme();
  const themed = useMemo(
    () => createStyles(t.surface, t.border, t.sub),
    [t.surface, t.border, t.sub]
  );

  return (
    <Pressable style={s.wrap} onPress={() => router.back()}>
      <View style={themed.iconBox}>
        <Ionicons name={icon} size={26} color={tint ?? t.text} />
      </View>
      <Text style={themed.label} numberOfLines={1}>
        {label}
      </Text>
    </Pressable>
  );
});
