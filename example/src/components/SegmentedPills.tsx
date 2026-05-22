import { Pressable, StyleSheet, Text, View } from 'react-native';

export function SegmentedPills<T extends string | number>({
  label,
  options,
  value,
  onSelect,
  format,
}: {
  label: string;
  options: readonly T[];
  value: T;
  onSelect: (value: T) => void;
  format?: (value: T) => string;
}) {
  return (
    <View style={styles.row}>
      <Text style={styles.label}>{label}</Text>
      <View style={styles.group}>
        {options.map((option) => (
          <Pressable
            key={String(option)}
            style={[styles.item, option === value && styles.active]}
            onPress={() => onSelect(option)}
          >
            <Text style={[styles.text, option === value && styles.activeText]}>
              {format ? format(option) : String(option)}
            </Text>
          </Pressable>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  label: {
    fontSize: 11,
    fontWeight: '600',
    color: '#555',
    width: 38,
    textTransform: 'uppercase',
  },
  group: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 4,
  },
  item: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#2a2a2a',
  },
  active: {
    backgroundColor: '#fff',
    borderColor: '#fff',
  },
  text: {
    fontSize: 12,
    color: '#666',
  },
  activeText: {
    color: '#000',
    fontWeight: '600',
  },
});
