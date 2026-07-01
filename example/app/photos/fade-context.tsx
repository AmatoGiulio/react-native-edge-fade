/**
 * Shared edge-fade tuning state for the Photos route group.
 *
 * The grid preview (`index`) and the editor sheet (`tune`) live on separate
 * routes, so their state is lifted into a context provided by the group layout.
 * Both read the same config live: dragging a slider in the sheet reflows the
 * grid behind it instantly.
 */

import { createContext, useContext, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import type { CurvePreset } from 'react-native-edge-fade';

export type Frost = 'dark' | 'light';

export interface FadeConfig {
  blur: number;
  top: number;
  bottom: number;
  curve: CurvePreset;
  frost: Frost;
}

/** Named starting points; sliders fine-tune from there. */
export interface FadePreset extends FadeConfig {
  id: string;
  label: string;
}

export const PRESETS: FadePreset[] = [
  {
    id: 'subtle',
    label: 'Subtle',
    blur: 14,
    top: 110,
    bottom: 110,
    curve: 'smoother',
    frost: 'dark',
  },
  {
    id: 'frosted',
    label: 'Frosted',
    blur: 30,
    top: 150,
    bottom: 160,
    curve: 'smoother',
    frost: 'dark',
  },
  {
    id: 'navbar',
    label: 'Nav bar',
    blur: 24,
    top: 130,
    bottom: 0,
    curve: 'smooth',
    frost: 'dark',
  },
  {
    id: 'heavy',
    label: 'Heavy',
    blur: 52,
    top: 240,
    bottom: 240,
    curve: 'smooth',
    frost: 'dark',
  },
];

const DEFAULT_PRESET = PRESETS[1]!; // Frosted

export const FROST_COLOR: Record<Frost, string> = {
  dark: '#000000',
  light: '#FFFFFF',
};

interface FadeContextValue extends FadeConfig {
  /** Active preset id, or null once a slider diverges from it. */
  presetId: string | null;
  /** Apply a named preset wholesale. */
  applyPreset: (id: string) => void;
  /** Patch one or more fields (marks the config as custom). */
  patch: (next: Partial<FadeConfig>) => void;
}

const FadeContext = createContext<FadeContextValue | null>(null);

export function FadeProvider({ children }: { children: ReactNode }) {
  const [config, setConfig] = useState<FadeConfig>(() =>
    stripId(DEFAULT_PRESET)
  );
  const [presetId, setPresetId] = useState<string | null>(DEFAULT_PRESET.id);

  const value = useMemo<FadeContextValue>(
    () => ({
      ...config,
      presetId,
      applyPreset: (id) => {
        const preset = PRESETS.find((p) => p.id === id);
        if (!preset) return;
        setConfig(stripId(preset));
        setPresetId(id);
      },
      patch: (next) => {
        setConfig((prev) => ({ ...prev, ...next }));
        setPresetId(null);
      },
    }),
    [config, presetId]
  );

  return <FadeContext.Provider value={value}>{children}</FadeContext.Provider>;
}

export function useFade(): FadeContextValue {
  const ctx = useContext(FadeContext);
  if (!ctx) throw new Error('useFade must be used within a FadeProvider');
  return ctx;
}

function stripId(p: FadePreset): FadeConfig {
  const { blur, top, bottom, curve, frost } = p;
  return { blur, top, bottom, curve, frost };
}
