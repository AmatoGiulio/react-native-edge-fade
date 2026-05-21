import { useState } from 'react';
import {
  Image,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { EdgeFadeView } from 'react-native-edge-fade';
import BenchmarkScreen from './BenchmarkScreen';

// ── Palette ───────────────────────────────────────────────────────────────────

const C = {
  bg: '#0d0d0d',
  surface: '#161616',
  border: '#222',
  text: '#f0f0f0',
  sub: '#888',
  muted: '#444',
};

// ── Data ──────────────────────────────────────────────────────────────────────

const avatar = (seed: string, size = 80) =>
  `https://api.dicebear.com/9.x/glass/png?seed=${seed}&size=${size}`;

const PEOPLE = [
  { seed: 'Christian', name: 'Christian', role: 'Design', accent: '#818cf8' },
  { seed: 'Alex', name: 'Alex', role: 'iOS', accent: '#34d399' },
  { seed: 'Jordan', name: 'Jordan', role: 'Product', accent: '#fb923c' },
  { seed: 'Morgan', name: 'Morgan', role: 'PM', accent: '#f472b6' },
  { seed: 'Casey', name: 'Casey', role: 'Backend', accent: '#60a5fa' },
  { seed: 'Riley', name: 'Riley', role: 'QA', accent: '#a78bfa' },
  { seed: 'Taylor', name: 'Taylor', role: 'DevOps', accent: '#fbbf24' },
  { seed: 'Sam', name: 'Sam', role: 'Data', accent: '#2dd4bf' },
] as const;

const MESSAGES = [
  {
    seed: 'Morgan',
    name: 'Morgan',
    text: 'The new fade animation looks incredible 🔥',
    time: '2m',
    unread: 3,
  },
  {
    seed: 'Casey',
    name: 'Casey',
    text: 'Backend changes are shipped, ready for QA',
    time: '14m',
    unread: 0,
  },
  {
    seed: 'Alex',
    name: 'Alex',
    text: 'iOS build passed, no animation glitches ✅',
    time: '1h',
    unread: 1,
  },
  {
    seed: 'Riley',
    name: 'Riley',
    text: 'All regression tests are green',
    time: '3h',
    unread: 0,
  },
  {
    seed: 'Jordan',
    name: 'Jordan',
    text: 'Can we increase the fade size on the header?',
    time: '5h',
    unread: 0,
  },
  {
    seed: 'Taylor',
    name: 'Taylor',
    text: 'Deploy pipeline is ready to go',
    time: '8h',
    unread: 0,
  },
  {
    seed: 'Sam',
    name: 'Sam',
    text: 'Metrics are looking great after the update 📈',
    time: '1d',
    unread: 0,
  },
  {
    seed: 'Christian',
    name: 'Christian',
    text: "Let's ship v1.0 tomorrow!",
    time: '1d',
    unread: 2,
  },
  {
    seed: 'Morgan',
    name: 'Morgan',
    text: 'Agreed — everything looks solid',
    time: '2d',
    unread: 0,
  },
  {
    seed: 'Alex',
    name: 'Alex',
    text: 'AGSL shader performance is insane on API 33+ 🚀',
    time: '2d',
    unread: 0,
  },
] as const;

// ── Person card ───────────────────────────────────────────────────────────────

function PersonCard({ seed, name, role, accent }: (typeof PEOPLE)[number]) {
  return (
    <View style={[card.root, { borderColor: accent + '30' }]}>
      <View style={[card.ring, { borderColor: accent + '80' }]}>
        <Image source={{ uri: avatar(seed) }} style={card.avatar} />
      </View>
      <Text style={card.name} numberOfLines={1}>
        {name}
      </Text>
      <View style={[card.badge, { backgroundColor: accent + '18' }]}>
        <Text style={[card.role, { color: accent }]}>{role}</Text>
      </View>
    </View>
  );
}

const card = StyleSheet.create({
  root: {
    width: 84,
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 4,
    marginHorizontal: 4,
    borderRadius: 16,
    borderWidth: 1,
    backgroundColor: C.surface,
    gap: 6,
  },
  ring: {
    borderRadius: 24,
    borderWidth: 2,
    padding: 2,
  },
  avatar: {
    width: 48,
    height: 48,
    borderRadius: 22,
    backgroundColor: '#222',
  },
  name: {
    fontSize: 11,
    fontWeight: '600',
    color: C.text,
    letterSpacing: 0.1,
  },
  badge: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 20,
  },
  role: {
    fontSize: 10,
    fontWeight: '600',
    letterSpacing: 0.3,
  },
});

// ── Message row ───────────────────────────────────────────────────────────────

function MessageRow({
  seed,
  name,
  text,
  time,
  unread,
}: (typeof MESSAGES)[number]) {
  return (
    <View style={msg.root}>
      <Image source={{ uri: avatar(seed, 48) }} style={msg.avatar} />
      <View style={msg.body}>
        <View style={msg.header}>
          <Text style={msg.name}>{name}</Text>
          <Text style={msg.time}>{time}</Text>
        </View>
        <Text style={[msg.text, unread > 0 && msg.textBold]} numberOfLines={1}>
          {text}
        </Text>
      </View>
      {unread > 0 && (
        <View style={msg.pill}>
          <Text style={msg.pillText}>{unread}</Text>
        </View>
      )}
    </View>
  );
}

const msg = StyleSheet.create({
  root: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 16,
    gap: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: C.border,
  },
  avatar: {
    width: 46,
    height: 46,
    borderRadius: 23,
    backgroundColor: '#222',
  },
  body: {
    flex: 1,
    gap: 3,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  name: {
    fontSize: 14,
    fontWeight: '600',
    color: C.text,
  },
  time: {
    fontSize: 12,
    color: C.muted,
  },
  text: {
    fontSize: 13,
    color: C.sub,
  },
  textBold: {
    color: C.text,
    fontWeight: '500',
  },
  pill: {
    minWidth: 20,
    height: 20,
    borderRadius: 10,
    backgroundColor: '#818cf8',
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 6,
  },
  pillText: {
    fontSize: 11,
    fontWeight: '700',
    color: '#fff',
  },
});

// ── Demo screen ───────────────────────────────────────────────────────────────

function DemoScreen({ onBenchmark }: { onBenchmark: () => void }) {
  return (
    <View style={s.screen}>
      {/* Header */}
      <View style={s.header}>
        <View>
          <Text style={s.title}>Messages</Text>
          <Text style={s.subtitle}>10 conversations</Text>
        </View>
        <Pressable style={s.benchBtn} onPress={onBenchmark}>
          <Text style={s.benchText}>Benchmark</Text>
        </Pressable>
      </View>

      {/* Team strip */}
      <Text style={s.section}>Team</Text>
      <EdgeFadeView left={20} right={20} curve="gentle" style={s.strip}>
        <ScrollView
          horizontal
          nestedScrollEnabled
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={s.stripContent}
        >
          {PEOPLE.map((p) => (
            <PersonCard key={p.seed} {...p} />
          ))}
        </ScrollView>
      </EdgeFadeView>

      {/* Divider */}
      <View style={s.divider} />

      {/* Messages list */}
      <Text style={s.section}>Recent</Text>
      <EdgeFadeView top={40} bottom={80} curve="smooth" style={s.list}>
        <ScrollView nestedScrollEnabled showsVerticalScrollIndicator={false}>
          {MESSAGES.map((m) => (
            <MessageRow key={m.seed + m.time} {...m} />
          ))}
        </ScrollView>
      </EdgeFadeView>
    </View>
  );
}

// ── Root ──────────────────────────────────────────────────────────────────────

export default function App() {
  const [screen, setScreen] = useState<'demo' | 'benchmark'>('demo');

  return (
    <View style={s.root}>
      {screen === 'demo' ? (
        <DemoScreen onBenchmark={() => setScreen('benchmark')} />
      ) : (
        <View style={{ flex: 1 }}>
          <Pressable style={s.backBtn} onPress={() => setScreen('demo')}>
            <Text style={s.backText}>← Demo</Text>
          </Pressable>
          <BenchmarkScreen />
        </View>
      )}
    </View>
  );
}

// ── Styles ────────────────────────────────────────────────────────────────────

const s = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: C.bg,
  },
  screen: {
    flex: 1,
    paddingTop: Platform.OS === 'android' ? 48 : 60,
  },

  // Header
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-end',
    paddingHorizontal: 20,
    marginBottom: 20,
  },
  title: {
    fontSize: 28,
    fontWeight: '700',
    color: C.text,
    letterSpacing: -0.5,
  },
  subtitle: {
    fontSize: 13,
    color: C.muted,
    marginTop: 2,
  },
  benchBtn: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: C.border,
  },
  benchText: {
    fontSize: 12,
    color: C.sub,
    fontWeight: '500',
  },

  // Section label
  section: {
    fontSize: 12,
    fontWeight: '700',
    color: C.muted,
    letterSpacing: 0.8,
    textTransform: 'uppercase',
    paddingHorizontal: 20,
    marginBottom: 10,
  },

  // People strip
  strip: {
    height: 128,
    marginBottom: 4,
  },
  stripContent: {
    paddingHorizontal: 20,
    alignItems: 'center',
  },

  // Divider
  divider: {
    height: StyleSheet.hairlineWidth,
    backgroundColor: C.border,
    marginHorizontal: 20,
    marginVertical: 16,
  },

  // Messages list
  list: {
    flex: 1,
  },

  // Back button (benchmark → demo)
  backBtn: {
    paddingTop: Platform.OS === 'android' ? 48 : 60,
    paddingHorizontal: 20,
    paddingBottom: 8,
  },
  backText: {
    fontSize: 14,
    color: C.sub,
    fontWeight: '500',
  },
});
