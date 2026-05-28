import { useLocalSearchParams, router } from 'expo-router';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { EdgeFadeView } from 'react-native-edge-fade';

import { getCatalogItem } from '../constants/catalog';
import {
  ContactAvatar,
  type PresenceStatus,
} from '../components/ContactAvatar';
import { ActionTile, type IoniconName } from '../components/ActionTile';
import { useAppTheme } from '../hooks/useAppTheme';

// ── Contacts ──────────────────────────────────────────────────────────────────
// seed → DiceBear Dylan avatar (unique per name)

const CONTACTS: Array<{ name: string; status: PresenceStatus }> = [
  { name: 'Sofia', status: 'online' },
  { name: 'Marco', status: 'online' },
  { name: 'Elena', status: 'dnd' },
  { name: 'Luca', status: 'online' },
  { name: 'Anna', status: 'away' },
  { name: 'Davide', status: 'online' },
  { name: 'Sara', status: 'offline' },
  { name: 'Matteo', status: 'online' },
  { name: 'Giulia', status: 'dnd' },
  { name: 'Francesco', status: 'away' },
  { name: 'Laura', status: 'online' },
  { name: 'Riccardo', status: 'offline' },
];

// ── Actions ───────────────────────────────────────────────────────────────────

const ACTIONS: Array<{ label: string; icon: IoniconName; tint?: string }> = [
  { label: 'AirDrop', icon: 'radio-outline', tint: '#34aadc' },
  { label: 'Message', icon: 'chatbubble-outline', tint: '#2dd36f' },
  { label: 'Mail', icon: 'mail-outline' },
  { label: 'Save', icon: 'bookmark-outline' },
  { label: 'Copy', icon: 'copy-outline' },
  { label: 'Notes', icon: 'document-text-outline' },
  { label: 'Remix', icon: 'shuffle-outline' },
  { label: 'More', icon: 'ellipsis-horizontal' },
];

// ── Screen ────────────────────────────────────────────────────────────────────

export default function ShareSheet() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const item = getCatalogItem(id ?? '');
  const t = useAppTheme();

  return (
    <View style={[s.root, { backgroundColor: t.bg }]}>
      {/* Title */}
      <View style={s.titleRow}>
        <Text style={[s.title, { color: t.text }]}>Share</Text>
        {item && (
          <View style={[s.accentDot, { backgroundColor: item.accent }]} />
        )}
      </View>

      {/* Contacts */}
      <Text style={[s.sectionLabel, { color: t.sub }]}>Suggested</Text>
      <EdgeFadeView
        left={120}
        right={120}
        curve="smooth"
        mode="overlay"
        color={t.bg}
        style={s.stripWrap}
      >
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={s.stripContent}
        >
          {CONTACTS.map((c) => (
            <ContactAvatar
              key={c.name}
              seed={c.name}
              label={c.name}
              status={c.status}
            />
          ))}
        </ScrollView>
      </EdgeFadeView>

      <View style={[s.separator, { backgroundColor: t.border }]} />

      {/* Actions */}
      <Text style={[s.sectionLabel, { color: t.sub }]}>Actions</Text>
      <EdgeFadeView
        left={60}
        right={60}
        curve="smooth"
        mode="overlay"
        color={t.bg}
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

      {/* Cancel */}
      <Pressable
        style={[s.cancelBtn, { backgroundColor: t.surface }]}
        onPress={() => router.back()}
      >
        <Text style={[s.cancelText, { color: t.text }]}>Cancel</Text>
      </Pressable>
    </View>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────

const s = StyleSheet.create({
  root: { flex: 1, paddingVertical: 32 },
  handle: {
    alignSelf: 'center',
    width: 36,
    height: 4,
    borderRadius: 2,
    marginBottom: 20,
  },
  titleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingHorizontal: 24,
    marginBottom: 20,
  },
  title: { fontSize: 20, fontWeight: '700', letterSpacing: -0.3 },
  accentDot: { width: 8, height: 8, borderRadius: 4 },
  sectionLabel: {
    fontSize: 12,
    fontWeight: '600',
    letterSpacing: 0.6,
    paddingHorizontal: 24,
    marginBottom: 14,
  },
  stripWrap: { height: 108, marginBottom: 8 },
  stripContent: {
    paddingHorizontal: 24,
    alignItems: 'flex-start',
    paddingTop: 2,
  },
  separator: { height: 1, marginHorizontal: 24, marginVertical: 20 },
  cancelBtn: {
    marginHorizontal: 24,
    marginTop: 28,
    paddingVertical: 16,
    borderRadius: 14,
    alignItems: 'center',
  },
  cancelText: { fontSize: 16, fontWeight: '600' },
});
