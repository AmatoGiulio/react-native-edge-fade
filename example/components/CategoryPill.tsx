import { memo, useMemo } from 'react';
import { Pressable, StyleSheet, Text } from 'react-native';
import { useAppTheme } from '../hooks/useAppTheme';

interface PillItem {
  label: string;
  accent: string;
}

interface Props {
  cat: PillItem;
  active: boolean;
  onPress: () => void;
}

function createStyles(border: string) {
  return StyleSheet.create({
    inactive: {
      backgroundColor: 'transparent',
      borderColor: border,
    },
  });
}

const s = StyleSheet.create({
  root: {
    paddingHorizontal: 16,
    paddingVertical: 7,
    borderRadius: 20,
    borderWidth: 1,
    marginRight: 8,
  },
  text: { fontSize: 13, fontWeight: '600', letterSpacing: 0.1 },
});

export const CategoryPill = memo(function CategoryPill({
  cat,
  active,
  onPress,
}: Props) {
  const t = useAppTheme();
  const themed = useMemo(() => createStyles(t.border), [t.border]);

  const rootStyle = useMemo(
    () =>
      active
        ? [s.root, { backgroundColor: cat.accent, borderColor: cat.accent }]
        : [s.root, themed.inactive],
    [active, cat.accent, themed.inactive]
  );

  const textColor = useMemo(
    () => (active ? (cat.label === 'All' ? t.text : '#fff') : t.sub),
    [active, cat.label, t.text, t.sub]
  );

  return (
    <Pressable onPress={onPress} style={rootStyle}>
      <Text style={[s.text, { color: textColor }]}>{cat.label}</Text>
    </Pressable>
  );
});
