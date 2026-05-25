import { useLocalSearchParams, router } from 'expo-router';
import {
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { EdgeFadeView } from 'react-native-edge-fade';
import { PORTRAIT_ITEMS, getCatalogItem } from './lib/catalog';

// ── Palette ───────────────────────────────────────────────────────────────────

const C = {
  bg: '#111',
  surface: '#1a1a1a',
  border: '#222',
  text: '#f2f2f2',
  sub: '#666',
};

// ── Action icons (placeholder SF-symbol style) ────────────────────────────────

const ACTIONS = [
  { label: 'Copy', emoji: '📋' },
  { label: 'Save', emoji: '⬇️' },
  { label: 'Message', emoji: '💬' },
  { label: 'Mail', emoji: '✉️' },
  { label: 'Notes', emoji: '📝' },
  { label: 'More', emoji: '•••' },
];

// ── Contact avatar ────────────────────────────────────────────────────────────

function ContactAvatar({
  item,
  label,
}: {
  item: (typeof PORTRAIT_ITEMS)[number];
  label: string;
}) {
  return (
    <Pressable style={av.wrap} onPress={() => router.back()}>
      <Image source={item.source} style={av.avatar} />
      <Text style={av.name} numberOfLines={1}>
        {label}
      </Text>
    </Pressable>
  );
}

const av = StyleSheet.create({
  wrap: {
    alignItems: 'center',
    width: 100,
    marginRight: 4,
  },
  avatar: {
    width: 80,
    height: 80,
    borderRadius: 8,
    backgroundColor: '#222',
  },
  name: {
    marginTop: 6,
    fontSize: 11,
    color: C.sub,
    fontWeight: '500',
    textAlign: 'center',
  },
});

// ── App action tile ───────────────────────────────────────────────────────────

function ActionTile({ label, emoji }: { label: string; emoji: string }) {
  return (
    <Pressable style={tile.wrap} onPress={() => router.back()}>
      <View style={tile.icon}>
        <Text style={tile.emoji}>{emoji}</Text>
      </View>
      <Text style={tile.label} numberOfLines={1}>
        {label}
      </Text>
    </Pressable>
  );
}

const tile = StyleSheet.create({
  wrap: {
    alignItems: 'center',
    width: 72,
    marginRight: 4,
  },
  icon: {
    width: 56,
    height: 56,
    borderRadius: 14,
    backgroundColor: C.surface,
    borderWidth: 1,
    borderColor: C.border,
    alignItems: 'center',
    justifyContent: 'center',
  },
  emoji: {
    fontSize: 24,
  },
  label: {
    marginTop: 6,
    fontSize: 11,
    color: C.sub,
    fontWeight: '500',
    textAlign: 'center',
  },
});

// ── Share sheet ───────────────────────────────────────────────────────────────

// Names for the portrait avatars (just for visual polish)
const CONTACT_NAMES = [
  'Sofia',
  'Marco',
  'Elena',
  'Luca',
  'Anna',
  'Davide',
  'Sara',
  'Matteo',
  'Giulia',
  'Francesco',
  'Laura',
  'Riccardo',
];

export default function ShareSheet() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const item = getCatalogItem(id ?? '');

  return (
    <View style={s.root}>
      {/* Handle bar */}
      <View style={s.handle} />

      {/* Title row */}
      <View style={s.titleRow}>
        <Text style={s.title}>Share</Text>
        {item && (
          <View style={[s.accentDot, { backgroundColor: item.accent }]} />
        )}
      </View>

      {/* ── Contacts row ── */}
      <Text style={s.sectionLabel}>Suggested</Text>
      <EdgeFadeView
        left={120}
        right={120}
        curve="gentle"
        color={C.bg}
        style={s.stripWrap}
      >
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={s.stripContent}
        >
          {PORTRAIT_ITEMS.map((contact, i) => (
            <ContactAvatar
              key={contact.id}
              item={contact}
              label={CONTACT_NAMES[i % CONTACT_NAMES.length] ?? ''}
            />
          ))}
        </ScrollView>
      </EdgeFadeView>

      {/* Separator */}
      <View style={s.separator} />

      {/* ── Apps / actions row ── */}
      <Text style={s.sectionLabel}>Actions</Text>
      <EdgeFadeView
        left={60}
        right={60}
        curve="smooth"
        mode="overlay"
        color={C.bg}
        style={s.stripWrap}
      >
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={s.stripContent}
        >
          {ACTIONS.map((action) => (
            <ActionTile key={action.label} {...action} />
          ))}
        </ScrollView>
      </EdgeFadeView>

      {/* Cancel button */}
      <Pressable style={s.cancelBtn} onPress={() => router.back()}>
        <Text style={s.cancelText}>Cancel</Text>
      </Pressable>
    </View>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────

const s = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: C.bg,
    paddingTop: 12,
  },
  handle: {
    alignSelf: 'center',
    width: 36,
    height: 4,
    borderRadius: 2,
    backgroundColor: '#333',
    marginBottom: 20,
  },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 24,
    marginBottom: 20,
  },
  title: {
    fontSize: 20,
    fontWeight: '700',
    color: C.text,
    letterSpacing: -0.3,
  },
  accentDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
  },
  sectionLabel: {
    fontSize: 12,
    fontWeight: '600',
    color: C.sub,
    letterSpacing: 0.6,
    paddingHorizontal: 24,
    marginBottom: 14,
  },
  stripWrap: {
    height: 96,
    marginBottom: 8,
  },
  stripContent: {
    paddingHorizontal: 24,
    alignItems: 'flex-start',
    paddingTop: 2,
  },
  separator: {
    height: 1,
    backgroundColor: C.border,
    marginHorizontal: 24,
    marginVertical: 20,
  },
  cancelBtn: {
    marginHorizontal: 24,
    marginTop: 28,
    paddingVertical: 16,
    borderRadius: 14,
    backgroundColor: C.surface,
    alignItems: 'center',
  },
  cancelText: {
    fontSize: 16,
    fontWeight: '600',
    color: C.text,
  },
});
