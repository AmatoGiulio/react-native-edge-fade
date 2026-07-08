/**
 * Edge-fade tuning panel for the `/panel` form-sheet route.
 *
 * Layout (top → bottom): a themed segmented Mode control, the custom BezierPlot
 * pad, a native Menu preset trigger, the DialKit-style parameter rows (monospace,
 * drag to change), and the Frost tint / Debug bands rows (mono label + native
 * Toggle / ColorPicker). All surfaces share one row background; the color well
 * stays visible but disabled when the frost tint is off.
 */

import { useCallback } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { SFSymbol } from 'sf-symbols-typescript';
import { useHeaderHeight } from '@react-navigation/elements';

import {
  Host,
  HStack,
  Spacer,
  Image,
  Text as UIText,
  Menu,
  Button,
  Toggle,
  ColorPicker,
} from '@expo/ui/swift-ui';
import {
  font,
  foregroundStyle,
  background,
  cornerRadius,
  padding,
  frame,
  labelsHidden,
  disabled,
} from '@expo/ui/swift-ui/modifiers';

const MONO = 'Menlo';

import { BezierPlot, DialRow } from '@/components/dial';
import { useFadeStore } from '@/fade/FadeContext';
import { BEZIER_PRESETS, type Bezier } from '@/fade/presets';
import { useScheme, useTheme } from '@/theme';

const MODES = ['mask', 'blur', 'overlay'] as const;
const DEFAULT_TINT = '#000000';

// SF Symbol per preset, shown on the native menu items. (Material Symbols are
// the Jetpack Compose equivalent; they'd apply to an Android @expo/ui panel,
// which this iOS demo doesn't build.)
const PRESET_SF: Record<string, SFSymbol> = {
  default: 'sparkles',
  linear: 'line.diagonal',
  ease: 'wave.3.right',
  soft: 'drop',
  sharp: 'bolt',
  gentle: 'leaf',
};

const FILL = 10000; // ≈ .infinity for frame(maxWidth:) so a control fills its row

function fmt2(v: number): string {
  'worklet';
  return v.toFixed(2);
}

function fmtPx(v: number): string {
  'worklet';
  return `${Math.round(v)}px`;
}

// Shared SwiftUI modifier sets so the native rows match the DialRow surface.
const rowMods = (bg: string) => [
  padding({ horizontal: 14 }),
  frame({ maxWidth: FILL, height: 44 }),
  background(bg),
  cornerRadius(12),
];
const labelMods = (color: string) => [
  font({ family: MONO, size: 14 }),
  foregroundStyle(color),
];

export function FadePanel() {
  const {
    x1,
    y1,
    x2,
    y2,
    top,
    bottom,
    left,
    right,
    blur,
    radius,
    mode,
    setMode,
    tint,
    setTint,
    showBands,
    setShowBands,
    preset,
    setPreset,
  } = useFadeStore();

  const scheme = useScheme();
  const t = useTheme();

  const applyPreset = useCallback(
    (label: string, b: Bezier) => {
      x1.set(b.x1);
      y1.set(b.y1);
      x2.set(b.x2);
      y2.set(b.y2);
      setPreset(label);
    },
    [x1, y1, x2, y2, setPreset]
  );

  // Called (on the JS thread) when the user edits the curve by hand — via the
  // pad or an x/y dial row — so the preset label reverts to 'custom'.
  const markCustom = useCallback(() => setPreset('custom'), [setPreset]);

  const frostOn = tint !== undefined;
  const onFrostToggle = useCallback(
    (on: boolean) => setTint(on ? DEFAULT_TINT : undefined),
    [setTint]
  );

  const rowTint = scheme;
  // Match the DialRow fill-row background exactly so slider / preset / toggle
  // rows all read as the same surface.
  const rowBg = scheme === 'dark' ? 'rgba(255,255,255,0.05)' : '#F2F2F4';
  const topHeight = useHeaderHeight();
  return (
    <View style={[s.root]} collapsable={false}>
      <ScrollView
        style={[s.scroll, { paddingTop: topHeight }]}
        showsVerticalScrollIndicator={false}
        contentContainerStyle={s.body}
      >
        {/* Mode — custom segmented so it uses the panel's own theme colors
            (the native control only exposes a selected-tint, not the track). */}
        <View style={[s.seg, { backgroundColor: rowBg }]}>
          {MODES.map((m) => (
            <Pressable
              key={m}
              style={[
                s.segItem,
                mode === m && { backgroundColor: t.controlActive },
              ]}
              onPress={() => setMode(m)}
            >
              <Text
                style={[
                  s.segText,
                  { color: mode === m ? t.text : t.faintText },
                ]}
              >
                {m}
              </Text>
            </Pressable>
          ))}
        </View>

        {/* Curve pad */}
        <View style={s.padWrap}>
          <BezierPlot
            x1={x1}
            y1={y1}
            x2={x2}
            y2={y2}
            tint={scheme}
            variant="pad"
            showPresenceBands={mode === 'blur'}
            onEdit={markCustom}
          />
        </View>

        {/* Preset — native Menu; the trigger is a monospace row showing the
            current preset, each item carries its SF Symbol */}
        <Host style={s.menuHost}>
          <Menu
            label={
              <HStack
                spacing={6}
                modifiers={[
                  padding({ horizontal: 14 }),
                  frame({ maxWidth: FILL, height: 44 }),
                  background(rowBg),
                  cornerRadius(12),
                ]}
              >
                <UIText
                  modifiers={[
                    font({ family: MONO, size: 14 }),
                    foregroundStyle(t.faintText),
                  ]}
                >
                  preset
                </UIText>
                <Spacer />
                <UIText
                  modifiers={[
                    font({ family: MONO, size: 14 }),
                    foregroundStyle(t.text),
                  ]}
                >
                  {preset}
                </UIText>
                <Image
                  systemName="chevron.up.chevron.down"
                  size={12}
                  color={t.faintText}
                />
              </HStack>
            }
          >
            {BEZIER_PRESETS.map((p) => (
              <Button
                key={p.label}
                label={p.label}
                systemImage={PRESET_SF[p.label]}
                onPress={() => applyPreset(p.label, p.value)}
              />
            ))}
          </Menu>
        </Host>

        {/* Curve control points */}
        <DialRow
          label="x1"
          value={x1}
          min={0}
          max={1}
          step={0.01}
          format={fmt2}
          tint={rowTint}
          onEnd={markCustom}
        />
        <DialRow
          label="y1"
          value={y1}
          min={0}
          max={1}
          step={0.01}
          format={fmt2}
          tint={rowTint}
          onEnd={markCustom}
        />
        <DialRow
          label="x2"
          value={x2}
          min={0}
          max={1}
          step={0.01}
          format={fmt2}
          tint={rowTint}
          onEnd={markCustom}
        />
        <DialRow
          label="y2"
          value={y2}
          min={0}
          max={1}
          step={0.01}
          format={fmt2}
          tint={rowTint}
          onEnd={markCustom}
        />

        {/* Edge sizes */}
        <DialRow
          label="top"
          value={top}
          min={0}
          max={320}
          step={2}
          format={fmtPx}
          tint={rowTint}
        />
        <DialRow
          label="bottom"
          value={bottom}
          min={0}
          max={320}
          step={2}
          format={fmtPx}
          tint={rowTint}
        />
        <DialRow
          label="left"
          value={left}
          min={0}
          max={320}
          step={2}
          format={fmtPx}
          tint={rowTint}
        />
        <DialRow
          label="right"
          value={right}
          min={0}
          max={320}
          step={2}
          format={fmtPx}
          tint={rowTint}
        />
        {mode === 'blur' && (
          <DialRow
            label="blur"
            value={blur}
            min={0}
            max={100}
            step={1}
            format={fmtPx}
            tint={rowTint}
          />
        )}
        <DialRow
          label="radius"
          value={radius}
          min={0}
          max={48}
          step={1}
          format={fmtPx}
          tint={rowTint}
        />

        {/* Frost tint / tint color / debug bands — native HStack rows: SwiftUI
            centers the label and the control on one baseline (alignment center),
            so there's no RN↔native vertical mismatch. */}
        <View style={{ height: 8 }} />
        <Host matchContents={{ vertical: true }} style={[s.rowHost]}>
          <HStack alignment="center" modifiers={rowMods(rowBg)}>
            <UIText modifiers={labelMods(t.text)}>frost tint</UIText>
            <Spacer />
            <Toggle
              isOn={frostOn}
              onIsOnChange={onFrostToggle}
              modifiers={[labelsHidden()]}
            />
          </HStack>
        </Host>

        <Host matchContents={{ vertical: true }} style={s.rowHost}>
          <HStack alignment="center" modifiers={rowMods(rowBg)}>
            <UIText modifiers={labelMods(frostOn ? t.text : t.faintText)}>
              tint color
            </UIText>
            <Spacer />
            <ColorPicker
              selection={tint ?? DEFAULT_TINT}
              supportsOpacity={false}
              onSelectionChange={setTint}
              modifiers={[labelsHidden(), disabled(!frostOn)]}
            />
          </HStack>
        </Host>

        <Host matchContents={{ vertical: true }} style={s.rowHost}>
          <HStack alignment="center" modifiers={rowMods(rowBg)}>
            <UIText modifiers={labelMods(t.text)}>debug bands</UIText>
            <Spacer />
            <Toggle
              isOn={showBands}
              onIsOnChange={setShowBands}
              modifiers={[labelsHidden()]}
            />
          </HStack>
        </Host>
      </ScrollView>
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1 },
  scroll: { flex: 1, paddingHorizontal: 20 },
  body: { gap: 8, paddingTop: 12, paddingBottom: 28, flexGrow: 1 },
  padWrap: { marginBottom: 6 },

  // Custom themed segmented control for Mode.
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

  // Native SwiftUI blocks fill the row width; the HStack inside handles height.
  menuHost: { width: '100%', height: 44 },
  rowHost: { width: '100%' },
  // Extra gap above the toggle group to set it apart from the sliders.
  rowGroupTop: { marginTop: 24 },
});
