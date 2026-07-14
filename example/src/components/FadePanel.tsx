import { useCallback, useState, type ReactNode } from 'react';
import { Platform, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { ColorValue, ImageSourcePropType, StyleProp, ViewStyle } from 'react-native';
import type { SFSymbol } from 'sf-symbols-typescript';
import { useHeaderHeight } from 'expo-router/react-navigation';

import { Host, Switch } from '@expo/ui';
import { Icon as UniIcon, type IconName } from '@expo/ui';
import { MenuView } from '@expo/ui/community/menu';
import type { MenuAction } from '@expo/ui/community/menu';

import Flare from '@expo/material-symbols/flare.xml';
import NorthEast from '@expo/material-symbols/north_east.xml';
import Waves from '@expo/material-symbols/waves.xml';
import WaterDrop from '@expo/material-symbols/water_drop.xml';
import Bolt from '@expo/material-symbols/bolt.xml';
import Eco from '@expo/material-symbols/eco.xml';
import UnfoldMore from '@expo/material-symbols/unfold_more.xml';

import { BezierPlot, DialRow } from '@/components/dial';
import { PanelTitle, PanelResetButton } from '@/components/PanelHeader';
import { SymbolIcon } from '@/components/SymbolIcon';
import { useFadeStore } from '@/fade/FadeContext';
import { BEZIER_PRESETS, type Bezier } from '@/fade/presets';
import { useScheme, useTheme, type AppPalette, type Scheme } from '@/theme';

const MONO = Platform.select({ ios: 'Menlo', default: 'monospace' });
const MODES = ['mask', 'blur', 'overlay'] as const;
const MODES_ANDROID = ['mask', 'blur', 'overlay', 'lens'] as const;
const DEFAULT_TINT = '#000000';

const TINT_PRESETS = [
  { label: 'black', value: '#000000' },
  { label: 'graphite', value: '#242424' },
  { label: 'white', value: '#FFFFFF' },
  { label: 'blue', value: '#1A4687' },
  { label: 'violet', value: '#6750A4' },
  { label: 'red', value: '#B3261E' },
] as const;

function fmt2(v: number): string {
  'worklet';
  return v.toFixed(2);
}

function fmtPx(v: number): string {
  'worklet';
  return `${Math.round(v)}px`;
}

function NativeIcon({ name, size = 22, color }: { name: IconName; size?: number; color?: ColorValue }) {
  return (
    <Host matchContents style={{ width: size, height: size }}>
      <UniIcon name={name} size={size} color={color} />
    </Host>
  );
}

/**
 * Theme-matched native toggle.
 *
 * The universal `Switch` has no color API — it inherits the Compose/SwiftUI
 * MaterialTheme. To pin exact colors we drop to the platform Compose `Switch`
 * on Android, which exposes a `colors` prop, and paint every state from the app
 * palette. On iOS the universal `Switch` follows the `Host` `seedColor` (tint).
 */
function NativeSwitch({
  value,
  onValueChange,
  palette,
  scheme,
}: {
  value: boolean;
  onValueChange: (v: boolean) => void;
  palette: AppPalette;
  scheme: Scheme;
}) {
  if (Platform.OS === 'android') {
    // Android-only import: requiring jetpack-compose on iOS crashes at runtime.
    const { Switch: ComposeSwitch } = require('@expo/ui/jetpack-compose');
    return (
      <Host matchContents colorScheme={scheme}>
        <ComposeSwitch
          value={value}
          onCheckedChange={onValueChange}
          colors={{
            checkedTrackColor: palette.accent,
            checkedThumbColor: '#FFFFFF',
            checkedBorderColor: palette.accent,
            uncheckedTrackColor: palette.controlActive,
            uncheckedThumbColor: '#FFFFFF',
            uncheckedBorderColor: palette.controlActive,
          }}
        />
      </Host>
    );
  }
  return (
    <Host matchContents colorScheme={scheme} seedColor={palette.accent}>
      <Switch value={value} onValueChange={onValueChange} />
    </Host>
  );
}

interface ThemedMenuProps {
  actions: MenuAction[];
  onSelect: (id: string) => void;
  palette: AppPalette;
  scheme: Scheme;
  style?: StyleProp<ViewStyle>;
  children: ReactNode;
}

/**
 * A menu whose popup surface and items follow the app palette.
 *
 * The community `MenuView` wraps its own internal `Host` and never forwards a
 * theme, so its Android popup can't be recolored. On Android we therefore build
 * the menu directly from the Compose `DropdownMenu` — which exposes a `color`
 * (surface) prop and per-item `elementColors` — inside a `Host` we own and seed.
 * iOS keeps `MenuView` (SwiftUI menus already honor the system appearance).
 */
function ThemedMenu(props: ThemedMenuProps) {
  if (Platform.OS === 'android') return <AndroidThemedMenu {...props} />;
  return (
    <MenuView
      actions={props.actions}
      onPressAction={(e) => props.onSelect(e.nativeEvent.event)}
      style={props.style}
    >
      {props.children}
    </MenuView>
  );
}

function AndroidThemedMenu({ actions, onSelect, palette, scheme, style, children }: ThemedMenuProps) {
  // Android-only import: requiring jetpack-compose on iOS crashes at runtime.
  const {
    DropdownMenu,
    DropdownMenuItem,
    Icon: ComposeIcon,
    Text: ComposeText,
    RNHostView,
  } = require('@expo/ui/jetpack-compose');
  const [expanded, setExpanded] = useState(false);
  const itemColors = { textColor: palette.text, leadingIconColor: palette.text };

  return (
    <View style={style}>
      <Host matchContents colorScheme={scheme} seedColor={palette.accent}>
        <DropdownMenu
          expanded={expanded}
          onDismissRequest={() => setExpanded(false)}
          color={palette.control}
        >
          <DropdownMenu.Trigger>
            <RNHostView matchContents>
              <Pressable
                onPress={() => setExpanded(true)}
                android_disableSound
                focusable={false}
                accessible={false}
              >
                {children}
              </Pressable>
            </RNHostView>
          </DropdownMenu.Trigger>
          <DropdownMenu.Items>
            {actions.map((a) => {
              // On Android `image` is an ImageSourcePropType (xml require); the
              // string form is an iOS SF Symbol and has no leading icon here.
              const icon = typeof a.image === 'string' || a.image == null ? null : a.image;
              return (
                <DropdownMenuItem
                  key={a.id ?? a.title}
                  elementColors={itemColors}
                  onClick={() => {
                    setExpanded(false);
                    onSelect(a.id ?? a.title);
                  }}
                >
                  <DropdownMenuItem.Text>
                    <ComposeText color={palette.text}>{a.title}</ComposeText>
                  </DropdownMenuItem.Text>
                  {icon && (
                    <DropdownMenuItem.LeadingIcon>
                      <ComposeIcon source={icon} size={24} tint={palette.text} />
                    </DropdownMenuItem.LeadingIcon>
                  )}
                </DropdownMenuItem>
              );
            })}
          </DropdownMenu.Items>
        </DropdownMenu>
      </Host>
    </View>
  );
}

export function FadePanel() {
  const {
    x1, y1, x2, y2,
    top, bottom, left, right, blur, radius,
    frostSat, frostLift, frostProg,
    mode, setMode,
    tint, setTint,
    showBands, setShowBands,
    autoDemo, setAutoDemo,
    preset, setPreset,
  } = useFadeStore();

  const scheme = useScheme();
  const t = useTheme();
  const topHeight = useHeaderHeight();
  const [showAdvanced, setShowAdvanced] = useState(false);

  const rowBg = t.control;
  const dialSurface = { surface: t.control, fillColor: t.controlActive };
  const modes = Platform.OS === 'android' ? MODES_ANDROID : MODES;

  const applyPreset = useCallback(
    (label: string, b: Bezier) => {
      x1.set(b.x1); y1.set(b.y1);
      x2.set(b.x2); y2.set(b.y2);
      setPreset(label);
    },
    [x1, y1, x2, y2, setPreset]
  );

  const markCustom = useCallback(() => setPreset('custom'), [setPreset]);

  const frostOn = tint !== undefined;
  const onFrostToggle = useCallback(
    (on: boolean) => setTint(on ? DEFAULT_TINT : undefined),
    [setTint]
  );

  const currentTintLabel =
    TINT_PRESETS.find(
      (item) => item.value.toLowerCase() === tint?.toLowerCase()
    )?.label ?? 'custom';

  const presetMenuActions: MenuAction[] = BEZIER_PRESETS.map((p) => ({
    id: p.label,
    title: p.label,
    titleColor: t.text,
    image: Platform.OS === 'ios'
      ? PRESET_SF[p.label]
      : PRESET_MATERIAL[p.label],
    imageColor: t.text,
  }));

  const tintMenuActions: MenuAction[] = TINT_PRESETS.map((tp) => ({
    id: tp.value,
    title: tp.label,
    titleColor: t.text,
  }));

  const rowStyle = {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: 12,
    paddingHorizontal: 14,
    height: 44,
    backgroundColor: rowBg,
  } as const;

  const advancedContent = (
    <>
      <DialRow label="x1" value={x1} min={0} max={1} step={0.01} format={fmt2} tint={scheme} {...dialSurface} onEnd={markCustom} />
      <DialRow label="y1" value={y1} min={0} max={1} step={0.01} format={fmt2} tint={scheme} {...dialSurface} onEnd={markCustom} />
      <DialRow label="x2" value={x2} min={0} max={1} step={0.01} format={fmt2} tint={scheme} {...dialSurface} onEnd={markCustom} />
      <DialRow label="y2" value={y2} min={0} max={1} step={0.01} format={fmt2} tint={scheme} {...dialSurface} onEnd={markCustom} />

      <DialRow label="radius" value={radius} min={0} max={48} step={1} format={fmtPx} tint={scheme} {...dialSurface} />

      <DialRow label="saturation" value={frostSat} min={0.5} max={1.6} step={0.05} format={fmt2} tint={scheme} {...dialSurface} disabled={mode !== 'blur'} />
      <DialRow label="lift" value={frostLift} min={0.7} max={1.2} step={0.02} format={fmt2} tint={scheme} {...dialSurface} disabled={mode !== 'blur'} />
      <DialRow label="transition" value={frostProg} min={0.05} max={0.9} step={0.05} format={fmt2} tint={scheme} {...dialSurface} disabled={mode !== 'blur'} />

      <View style={rowStyle}>
        <Text style={[s.monoLabel, { color: t.text }]}>frost tint</Text>
        <View style={s.flex} />
        <NativeSwitch value={frostOn} onValueChange={onFrostToggle} palette={t} scheme={scheme} />
      </View>

      {Platform.OS === 'ios' ? (
        <TintColorPickerIOS
          tint={tint}
          setTint={setTint}
          disabled={!frostOn}
          rowBg={rowBg}
          textColor={t.text}
          faintTextColor={t.faintText}
        />
      ) : (
        <View style={rowStyle}>
          <Text style={[s.monoLabel, { color: frostOn ? t.text : t.faintText }]}>
            tint color
          </Text>
          <View style={s.flex} />
          <ThemedMenu
            actions={tintMenuActions}
            onSelect={(id) => {
              if (frostOn) setTint(id);
            }}
            palette={t}
            scheme={scheme}
          >
            <View style={s.rowInline}>
              <Text style={[s.monoLabel, { color: frostOn ? t.text : t.faintText }]}>
                {currentTintLabel}
              </Text>
              <Text style={[s.tintDot, { color: frostOn ? (tint ?? DEFAULT_TINT) : t.faintText }]}>
                {'\u25CF'}
              </Text>
              <NativeIcon name={UnfoldMore} size={16} color={t.faintText} />
            </View>
          </ThemedMenu>
        </View>
      )}

      <View style={rowStyle}>
        <Text style={[s.monoLabel, { color: t.text }]}>auto demo</Text>
        <View style={s.flex} />
        <NativeSwitch value={autoDemo} onValueChange={setAutoDemo} palette={t} scheme={scheme} />
      </View>

      <View style={rowStyle}>
        <Text style={[s.monoLabel, { color: t.text }]}>debug bands</Text>
        <View style={s.flex} />
        <NativeSwitch value={showBands} onValueChange={setShowBands} palette={t} scheme={scheme} />
      </View>
    </>
  );

  return (
    <View style={{ flex: 1, backgroundColor: t.card }}>
      {/* Android form sheets don't host the Stack header (react-native-screens
          doesn't support nested stack rendering for Android sheets yet), so the
          native headerTitle/headerRight never render. Surface the title + reset
          as a fixed header OUTSIDE the ScrollView so it stays pinned instead of
          scrolling with the content. iOS keeps the real native header. */}
      {Platform.OS === 'android' && (
        <View style={s.sheetHeader}>
          <PanelTitle />
          <View style={s.sheetReset}>
            <PanelResetButton color={t.headerTint} />
          </View>
        </View>
      )}

      <ScrollView
        {...(Platform.OS === 'android' ? { nestedScrollEnabled: true } : null)}
        style={[s.scroll, { paddingTop: Platform.OS === 'android' ? 0 : topHeight }]}
        showsVerticalScrollIndicator={false}
        contentContainerStyle={s.body}
      >
        <View style={[s.seg, { backgroundColor: rowBg }]}>
          {modes.map((m) => {
            const selected = mode === m;
            return (
              <Pressable
                key={m}
                accessibilityRole="button"
                accessibilityState={{ selected }}
                style={[
                  s.segItem,
                  selected && { backgroundColor: t.controlActive },
                ]}
                onPress={() => setMode(m)}
              >
                <Text
                  style={[
                    s.segText,
                    { color: selected ? t.text : t.faintText },
                  ]}
                >
                  {m}
                </Text>
              </Pressable>
            );
          })}
        </View>

        <View style={s.padWrap}>
          <BezierPlot
            x1={x1} y1={y1} x2={x2} y2={y2}
            tint={scheme}
            variant="pad"
            showPresenceBands={mode === 'blur'}
            onEdit={markCustom}
          />
        </View>

        <ThemedMenu
          actions={presetMenuActions}
          onSelect={(id) => {
            const p = BEZIER_PRESETS.find((pr) => pr.label === id);
            if (p) applyPreset(p.label, p.value);
          }}
          palette={t}
          scheme={scheme}
          style={s.menuHeight}
        >
          <View style={rowStyle}>
            <Text style={[s.monoLabel, { color: t.faintText }]}>preset</Text>
            <View style={s.flex} />
            <View style={s.rowInline}>
              <Text style={[s.monoLabel, { color: t.text }]}>{preset}</Text>
              <NativeIcon
                name={Platform.OS === 'ios' ? 'chevron.up.chevron.down' : UnfoldMore}
                size={Platform.OS === 'ios' ? 12 : 16}
                color={t.faintText}
              />
            </View>
          </View>
        </ThemedMenu>
          {/*
        <DialRow label="top" value={top} min={0} max={320} step={2} format={fmtPx} tint={scheme} {...dialSurface} />
        <DialRow label="bottom" value={bottom} min={0} max={320} step={2} format={fmtPx} tint={scheme} {...dialSurface} />
        <DialRow label="left" value={left} min={0} max={320} step={2} format={fmtPx} tint={scheme} {...dialSurface} />
        <DialRow label="right" value={right} min={0} max={320} step={2} format={fmtPx} tint={scheme} {...dialSurface} />
        */}<DialRow label="blur" value={blur} min={0} max={100} step={1} format={fmtPx} tint={scheme} {...dialSurface} disabled={mode !== 'blur'} />

        {Platform.OS === 'android' ? (
          advancedContent
        ) : (
          <>
            <Pressable
              accessibilityRole="button"
              accessibilityState={{ expanded: showAdvanced }}
              style={s.advanced}
              onPress={() => setShowAdvanced((v) => !v)}
            >
              <Text style={[s.advancedLabel, { color: t.text }]}>advanced</Text>
              <SymbolIcon
                name={showAdvanced ? 'chevron.up' : 'chevron.down'}
                color={t.faintText}
                size={12}
              />
            </Pressable>

            {showAdvanced && advancedContent}
          </>
        )}
      </ScrollView>
    </View>
  );
}

function TintColorPickerIOS({
  tint,
  setTint,
  disabled,
  rowBg,
  textColor,
  faintTextColor,
}: {
  tint: string | undefined;
  setTint: (t: string | undefined) => void;
  disabled: boolean;
  rowBg: string;
  textColor: string;
  faintTextColor: string;
}) {
  const { ColorPicker } = require('@expo/ui/swift-ui');
  const { labelsHidden, disabled: disabledMod } = require('@expo/ui/swift-ui/modifiers');

  return (
    <Host matchContents={{ vertical: true }} style={{ width: '100%' }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', borderRadius: 12, paddingHorizontal: 14, height: 44, backgroundColor: rowBg }}>
        <Text style={[s.monoLabel, { color: disabled ? faintTextColor : textColor }]}>
          tint color
        </Text>
        <View style={{ flex: 1 }} />
        <ColorPicker
          selection={tint ?? DEFAULT_TINT}
          supportsOpacity={false}
          onSelectionChange={setTint}
          modifiers={[labelsHidden(), disabledMod(disabled)]}
        />
      </View>
    </Host>
  );
}

const PRESET_SF: Record<string, SFSymbol> = {
  default: 'sparkles' as SFSymbol,
  linear: 'line.diagonal' as SFSymbol,
  ease: 'wave.3.right' as SFSymbol,
  soft: 'drop' as SFSymbol,
  sharp: 'bolt' as SFSymbol,
  gentle: 'leaf' as SFSymbol,
};

const PRESET_MATERIAL: Record<string, ImageSourcePropType> = {
  default: Flare,
  linear: NorthEast,
  ease: Waves,
  soft: WaterDrop,
  sharp: Bolt,
  gentle: Eco,
};

const s = StyleSheet.create({
  scroll: { flex: 1, paddingHorizontal: 20 },
  body: {
    gap: 8,
    paddingTop: 12,
    paddingBottom: Platform.OS === 'android' ? 64 : 28,
    flexGrow: 1,
  },
  padWrap: { marginBottom: 6 },
  menuHeight: { height: 44 },
  sheetHeader: {
    justifyContent: 'center',
    alignItems: 'center',
    paddingTop: 10,
    paddingBottom: 12,
  },
  sheetReset: {
    position: 'absolute',
    right: 20,
    top: 0,
    bottom: 0,
    justifyContent: 'center',
  },
  flex: { flex: 1 },
  rowInline: { flexDirection: 'row', alignItems: 'center', gap: 6 },

  advanced: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    height: 44,
    borderRadius: 12,
    paddingHorizontal: 14,
    marginTop: 8,
  },
  advancedLabel: {
    fontFamily: MONO,
    fontSize: 14,
    fontWeight: '600',
  },

  seg: {
    flexDirection: 'row',
    borderRadius: 12,
    padding: 3,
    gap: 3,
    marginBottom: 6,
  },
  segItem: {
    flex: 1,
    paddingVertical: 8,
    borderRadius: 9,
    alignItems: 'center',
    justifyContent: 'center',
  },
  segText: {
    fontFamily: MONO,
    fontSize: 14,
    fontWeight: '600',
    textTransform: 'capitalize',
  },

  monoLabel: {
    fontFamily: MONO,
    fontSize: 14,
  },

  tintDot: {
    fontSize: 18,
  },
});
