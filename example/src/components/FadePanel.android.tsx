/**
 * Android implementation of the edge-fade tuning panel.
 *
 * Keep the iOS implementation in:
 *   FadePanel.ios.tsx
 *
 * Save this file as:
 *   FadePanel.android.tsx
 *
 * SDK 55 note:
 * @expo/ui/jetpack-compose does not expose a native ColorPicker equivalent.
 * The tint color control therefore uses a native Material DropdownMenu with a
 * compact curated palette. All menu and toggle controls remain native Compose.
 */

import { useCallback, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useHeaderHeight } from '@react-navigation/elements';

import {
  DropdownMenu,
  DropdownMenuItem,
  Host,
  Icon,
  Row,
  Switch,
  Text as UIText,
} from '@expo/ui/jetpack-compose';
import {
  background,
  clickable,
  clip,
  fillMaxWidth,
  height,
  padding,
} from '@expo/ui/jetpack-compose/modifiers';
import type { ImageSourcePropType } from 'react-native';

import Flare from '@expo/material-symbols/flare.xml';
import NorthEast from '@expo/material-symbols/north_east.xml';
import Waves from '@expo/material-symbols/waves.xml';
import WaterDrop from '@expo/material-symbols/water_drop.xml';
import Bolt from '@expo/material-symbols/bolt.xml';
import Eco from '@expo/material-symbols/eco.xml';
import UnfoldMore from '@expo/material-symbols/unfold_more.xml';

import { BezierPlot, DialRow } from '@/components/dial';
import { SymbolIcon } from '@/components/SymbolIcon';
import { useFadeStore } from '@/fade/FadeContext';
import { BEZIER_PRESETS, type Bezier } from '@/fade/presets';
import { useScheme, useTheme } from '@/theme';

const MONO = 'monospace';

const MODES = ['mask', 'blur', 'overlay'] as const;
const DEFAULT_TINT = '#000000';

// Material Symbol per preset — parity with PRESET_SF on the iOS panel.
const PRESET_MATERIAL: Record<string, ImageSourcePropType> = {
  default: Flare,
  linear: NorthEast,
  ease: Waves,
  soft: WaterDrop,
  sharp: Bolt,
  gentle: Eco,
};

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

type NativeRowProps = {
  label: string;
  value: boolean;
  onValueChange: (value: boolean) => void;
  backgroundColor: string;
  textColor: string;
  activeColor: string;
};

function NativeSwitchRow({
  label,
  value,
  onValueChange,
  backgroundColor,
  textColor,
  activeColor,
}: NativeRowProps) {
  return (
    <Host style={s.nativeHost}>
      <Row
        horizontalArrangement="spaceBetween"
        verticalAlignment="center"
        modifiers={[
          fillMaxWidth(),
          height(44),

          clip({ type: 'roundedCorner', radius: 12 }),
          background(backgroundColor),
          padding(14, 0, 14, 0),
        ]}
      >
        <UIText color={textColor} style={{ fontFamily: MONO, fontSize: 14 }}>
          {label}
        </UIText>

        <Switch
          value={value}
          onCheckedChange={onValueChange}
          colors={{
            checkedThumbColor: '#FFFFFF',
            checkedTrackColor: activeColor,
          }}
        />
      </Row>
    </Host>
  );
}

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
    frostSat,
    frostLift,
    frostProg,
    mode,
    setMode,
    tint,
    setTint,
    showBands,
    setShowBands,
    autoDemo,
    setAutoDemo,
    preset,
    setPreset,
  } = useFadeStore();

  const scheme = useScheme();
  const t = useTheme();
  const topHeight = useHeaderHeight();

  const [showAdvanced, setShowAdvanced] = useState(false);
  const [presetMenuOpen, setPresetMenuOpen] = useState(false);
  const [tintMenuOpen, setTintMenuOpen] = useState(false);

  const rowBg = t.control;
  const dialSurface = {
    surface: t.control,
    fillColor: t.controlActive,
  };

  const applyPreset = useCallback(
    (label: string, b: Bezier) => {
      x1.set(b.x1);
      y1.set(b.y1);
      x2.set(b.x2);
      y2.set(b.y2);
      setPreset(label);
      setPresetMenuOpen(false);
    },
    [setPreset, x1, x2, y1, y2]
  );

  const markCustom = useCallback(() => {
    setPreset('custom');
  }, [setPreset]);

  const frostOn = tint !== undefined;

  const onFrostToggle = useCallback(
    (enabled: boolean) => {
      setTint(enabled ? (tint ?? DEFAULT_TINT) : undefined);
    },
    [setTint, tint]
  );

  const selectTint = useCallback(
    (value: string) => {
      setTint(value);
      setTintMenuOpen(false);
    },
    [setTint]
  );

  const currentTintLabel =
    TINT_PRESETS.find(
      (item) => item.value.toLowerCase() === tint?.toLowerCase()
    )?.label ?? 'custom';

  return (
    <View style={s.root} collapsable={false}>
      <ScrollView
        style={[s.scroll, { paddingTop: topHeight }]}
        showsVerticalScrollIndicator={false}
        contentContainerStyle={s.body}
      >
        <View style={[s.seg, { backgroundColor: rowBg }]}>
          {MODES.map((item) => {
            const selected = mode === item;

            return (
              <Pressable
                key={item}
                accessibilityRole="button"
                accessibilityState={{ selected }}
                style={[
                  s.segItem,
                  selected && { backgroundColor: t.controlActive },
                ]}
                onPress={() => setMode(item)}
              >
                <Text
                  style={[
                    s.segText,
                    {
                      color: selected ? t.text : t.faintText,
                    },
                  ]}
                >
                  {item}
                </Text>
              </Pressable>
            );
          })}
        </View>

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

        <Host style={s.nativeHost}>
          <DropdownMenu
            expanded={presetMenuOpen}
            onDismissRequest={() => setPresetMenuOpen(false)}
          >
            <DropdownMenu.Trigger>
              <Row
                horizontalArrangement="spaceBetween"
                verticalAlignment="center"
                modifiers={[
                  fillMaxWidth(),
                  height(44),
                  //background(rowBg),
                  clip({ type: 'roundedCorner', radius: 12 }),
                  background(rowBg),
                  clickable(() => setPresetMenuOpen(true)),
                  padding(14, 0, 14, 0),
                ]}
              >
                <UIText
                  color={t.faintText}
                  style={{ fontFamily: MONO, fontSize: 14 }}
                >
                  preset
                </UIText>

                <Row verticalAlignment="center">
                  <UIText
                    color={t.text}
                    style={{ fontFamily: MONO, fontSize: 14 }}
                  >
                    {preset}
                  </UIText>

                  <UIText color={t.faintText} style={{ fontSize: 14 }}>
                    {'  '}
                  </UIText>

                  <Icon source={UnfoldMore} size={16} tint={t.faintText} />
                </Row>
              </Row>
            </DropdownMenu.Trigger>

            <DropdownMenu.Items>
              {BEZIER_PRESETS.map((item) => (
                <DropdownMenuItem
                  key={item.label}
                  onClick={() => applyPreset(item.label, item.value)}
                >
                  <DropdownMenuItem.LeadingIcon>
                    <Icon
                      source={PRESET_MATERIAL[item.label] ?? Flare}
                      size={20}
                      tint={t.text}
                    />
                  </DropdownMenuItem.LeadingIcon>
                  <DropdownMenuItem.Text>
                    <UIText>{item.label}</UIText>
                  </DropdownMenuItem.Text>
                </DropdownMenuItem>
              ))}
            </DropdownMenu.Items>
          </DropdownMenu>
        </Host>

        <DialRow
          label="top"
          value={top}
          min={0}
          max={320}
          step={2}
          format={fmtPx}
          tint={scheme}
          {...dialSurface}
        />

        <DialRow
          label="bottom"
          value={bottom}
          min={0}
          max={320}
          step={2}
          format={fmtPx}
          tint={scheme}
          {...dialSurface}
        />

        <DialRow
          label="left"
          value={left}
          min={0}
          max={320}
          step={2}
          format={fmtPx}
          tint={scheme}
          {...dialSurface}
        />

        <DialRow
          label="right"
          value={right}
          min={0}
          max={320}
          step={2}
          format={fmtPx}
          tint={scheme}
          {...dialSurface}
        />

        <DialRow
          label="blur"
          value={blur}
          min={0}
          max={100}
          step={1}
          format={fmtPx}
          tint={scheme}
          {...dialSurface}
          disabled={mode !== 'blur'}
        />

        <Pressable
          accessibilityRole="button"
          accessibilityState={{ expanded: showAdvanced }}
          style={s.advanced}
          onPress={() => setShowAdvanced((value) => !value)}
        >
          <Text style={[s.advancedLabel, { color: t.text }]}>advanced</Text>

          <SymbolIcon
            name={showAdvanced ? 'chevron.up' : 'chevron.down'}
            color={t.faintText}
            size={12}
          />
        </Pressable>

        {showAdvanced && (
          <>
            <DialRow
              label="x1"
              value={x1}
              min={0}
              max={1}
              step={0.01}
              format={fmt2}
              tint={scheme}
              {...dialSurface}
              onEnd={markCustom}
            />

            <DialRow
              label="y1"
              value={y1}
              min={0}
              max={1}
              step={0.01}
              format={fmt2}
              tint={scheme}
              {...dialSurface}
              onEnd={markCustom}
            />

            <DialRow
              label="x2"
              value={x2}
              min={0}
              max={1}
              step={0.01}
              format={fmt2}
              tint={scheme}
              {...dialSurface}
              onEnd={markCustom}
            />

            <DialRow
              label="y2"
              value={y2}
              min={0}
              max={1}
              step={0.01}
              format={fmt2}
              tint={scheme}
              {...dialSurface}
              onEnd={markCustom}
            />

            <DialRow
              label="radius"
              value={radius}
              min={0}
              max={48}
              step={1}
              format={fmtPx}
              tint={scheme}
              {...dialSurface}
            />

            <DialRow
              label="saturation"
              value={frostSat}
              min={0.5}
              max={1.6}
              step={0.05}
              format={fmt2}
              tint={scheme}
              {...dialSurface}
              disabled={mode !== 'blur'}
            />

            <DialRow
              label="lift"
              value={frostLift}
              min={0.7}
              max={1.2}
              step={0.02}
              format={fmt2}
              tint={scheme}
              {...dialSurface}
              disabled={mode !== 'blur'}
            />

            <DialRow
              label="transition"
              value={frostProg}
              min={0.05}
              max={0.9}
              step={0.05}
              format={fmt2}
              tint={scheme}
              {...dialSurface}
              disabled={mode !== 'blur'}
            />

            <NativeSwitchRow
              label="frost tint"
              value={frostOn}
              onValueChange={onFrostToggle}
              backgroundColor={rowBg}
              textColor={t.text}
              activeColor={t.controlActive}
            />

            <Host style={s.nativeHost}>
              <DropdownMenu
                expanded={tintMenuOpen && frostOn}
                onDismissRequest={() => setTintMenuOpen(false)}
              >
                <DropdownMenu.Trigger>
                  <Row
                    horizontalArrangement="spaceBetween"
                    verticalAlignment="center"
                    modifiers={[
                      fillMaxWidth(),
                      height(44),
                      clip({ type: 'roundedCorner', radius: 12 }),
                      background(rowBg),
                      clickable(() => frostOn && setTintMenuOpen(true)),
                      padding(14, 0, 14, 0),
                    ]}
                  >
                    <UIText
                      color={frostOn ? t.text : t.faintText}
                      style={{ fontFamily: MONO, fontSize: 14 }}
                    >
                      tint color
                    </UIText>

                    <Row verticalAlignment="center">
                      <UIText
                        color={frostOn ? t.text : t.faintText}
                        style={{ fontFamily: MONO, fontSize: 14 }}
                      >
                        {currentTintLabel}
                      </UIText>

                      <UIText
                        color={frostOn ? (tint ?? DEFAULT_TINT) : t.faintText}
                        style={{ fontSize: 18 }}
                      >
                        {'  '}●{'  '}
                      </UIText>

                      <Icon source={UnfoldMore} size={16} tint={t.faintText} />
                    </Row>
                  </Row>
                </DropdownMenu.Trigger>

                <DropdownMenu.Items>
                  {TINT_PRESETS.map((item) => (
                    <DropdownMenuItem
                      key={item.value}
                      onClick={() => selectTint(item.value)}
                    >
                      <DropdownMenuItem.Text>
                        <UIText>{item.label}</UIText>
                      </DropdownMenuItem.Text>
                    </DropdownMenuItem>
                  ))}
                </DropdownMenu.Items>
              </DropdownMenu>
            </Host>

            <NativeSwitchRow
              label="auto demo"
              value={autoDemo}
              onValueChange={setAutoDemo}
              backgroundColor={rowBg}
              textColor={t.text}
              activeColor={t.controlActive}
            />

            <NativeSwitchRow
              label="debug bands"
              value={showBands}
              onValueChange={setShowBands}
              backgroundColor={rowBg}
              textColor={t.text}
              activeColor={t.controlActive}
            />
          </>
        )}
      </ScrollView>
    </View>
  );
}

const s = StyleSheet.create({
  root: {
    flex: 1,
  },

  scroll: {
    flex: 1,
    paddingHorizontal: 20,
  },

  body: {
    flexGrow: 1,
    gap: 8,
    paddingTop: 12,
    paddingBottom: 28,
  },

  padWrap: {
    marginBottom: 6,
  },

  nativeHost: {
    //width: '100%',
    height: 44,
  },

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
});
