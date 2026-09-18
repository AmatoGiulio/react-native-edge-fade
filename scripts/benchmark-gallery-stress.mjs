#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DEFAULT_OUT_DIR = path.join(ROOT, 'benchmark-results', 'gallery-stress');

function parseArgs(argv) {
  const options = {
    serial: '',
    durationMs: 15000,
    warmupMs: 3000,
    cooldownMs: 2000,
    radiusPx: 140,
    cycleMs: 4200,
    samples: 2,
    imageRenderer: 'expo',
    outDir: DEFAULT_OUT_DIR,
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const value = () => {
      if (i + 1 >= argv.length) throw new Error(`Missing value after ${arg}`);
      return argv[++i];
    };

    switch (arg) {
      case '--serial': options.serial = value(); break;
      case '--duration-ms': options.durationMs = Number(value()); break;
      case '--warmup-ms': options.warmupMs = Number(value()); break;
      case '--cooldown-ms': options.cooldownMs = Number(value()); break;
      case '--radius-px': options.radiusPx = Number(value()); break;
      case '--cycle-ms': options.cycleMs = Number(value()); break;
      case '--samples': options.samples = Number(value()); break;
      case '--image-renderer': options.imageRenderer = value(); break;
      case '--out-dir': options.outDir = path.resolve(value()); break;
      default: throw new Error(`Unknown argument: ${arg}`);
    }
  }

  if (!Number.isFinite(options.durationMs) || options.durationMs < 5000) {
    throw new Error('--duration-ms must be >= 5000');
  }
  if (!Number.isFinite(options.warmupMs) || options.warmupMs < 1200) {
    throw new Error('--warmup-ms must be >= 1200');
  }
  if (!Number.isFinite(options.cooldownMs) || options.cooldownMs < 0) {
    throw new Error('--cooldown-ms must be >= 0');
  }
  if (!Number.isFinite(options.radiusPx) || options.radiusPx < 1 || options.radiusPx > 150) {
    throw new Error('--radius-px must be in 1..150');
  }
  if (!Number.isFinite(options.cycleMs) || options.cycleMs < 1000) {
    throw new Error('--cycle-ms must be >= 1000');
  }
  if (!Number.isInteger(options.samples) || options.samples < 1 || options.samples > 6) {
    throw new Error('--samples must be an integer in 1..6');
  }
  if (!['expo', 'native', 'solid'].includes(options.imageRenderer)) {
    throw new Error('--image-renderer must be expo, native or solid');
  }

  return options;
}

const options = parseArgs(process.argv.slice(2));
fs.mkdirSync(options.outDir, { recursive: true });

function adbArgs(args) {
  return options.serial ? ['-s', options.serial, ...args] : args;
}

function adb(args, { trim = true } = {}) {
  const result = spawnSync('adb', adbArgs(args), {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    const prefix = options.serial ? `adb -s ${options.serial}` : 'adb';
    throw new Error(`${prefix} ${args.join(' ')} failed:\n${result.stdout ?? ''}${result.stderr ?? ''}`);
  }
  const output = `${result.stdout ?? ''}${result.stderr ?? ''}`;
  return trim ? output.trim() : output;
}

function adbShell(command, { trim = true } = {}) {
  return adb(['shell', command], { trim });
}

function sleep(ms) {
  if (ms <= 0) return;
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function round(value, digits = 3) {
  if (!Number.isFinite(value)) return null;
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

function percentile(sorted, p) {
  if (!sorted.length) return null;
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(sorted.length * p) - 1));
  return sorted[index];
}

function timestamp() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}

function imageRendererLabel() {
  switch (options.imageRenderer) {
    case 'native': return 'React Native Image';
    case 'solid': return 'solid View cells';
    default: return 'expo-image';
  }
}

function assertInstalledReleasePackage() {
  const packagePath = adb(['shell', 'pm', 'path', PACKAGE]);
  if (!/^package:/m.test(packagePath)) {
    throw new Error(`${PACKAGE} is not installed.`);
  }
  const dump = adb(['shell', 'dumpsys', 'package', PACKAGE], { trim: false });
  if (/pkgFlags=\[[^\]]*DEBUGGABLE/m.test(dump)) {
    throw new Error(`${PACKAGE} is debuggable. Install the release variant before benchmarking.`);
  }
}

function readSetting(name) {
  try {
    const value = adb(['shell', 'settings', 'get', 'system', name]);
    return value === 'null' ? null : value;
  } catch {
    return null;
  }
}

function parseBatteryTemperature() {
  try {
    const dump = adb(['shell', 'dumpsys', 'battery'], { trim: false });
    const match = dump.match(/^\s*temperature:\s*(\d+)/m);
    return match ? Number(match[1]) / 10 : null;
  } catch {
    return null;
  }
}

function parseGfxSummary(text) {
  const number = (pattern) => {
    const match = text.match(pattern);
    return match ? Number(match[1]) : null;
  };
  const janky = text.match(/Janky frames:\s*(\d+)\s*\(([\d.]+)%\)/);

  return {
    totalFrames: number(/Total frames rendered:\s*(\d+)/),
    jankyFrames: janky ? Number(janky[1]) : null,
    jankyPercent: janky ? Number(janky[2]) : null,
    p50Ms: number(/50th percentile:\s*(\d+)ms/),
    p90Ms: number(/90th percentile:\s*(\d+)ms/),
    p95Ms: number(/95th percentile:\s*(\d+)ms/),
    p99Ms: number(/99th percentile:\s*(\d+)ms/),
    missedVsync: number(/Number Missed Vsync:\s*(\d+)/),
    highInputLatency: number(/Number High input latency:\s*(\d+)/),
    slowUiThread: number(/Number Slow UI thread:\s*(\d+)/),
    slowBitmapUploads: number(/Number Slow bitmap uploads:\s*(\d+)/),
    slowIssueDrawCommands: number(/Number Slow issue draw commands:\s*(\d+)/),
    frameDeadlineMissed: number(/Number Frame deadline missed:\s*(\d+)/),
  };
}

function parseFrameStats(text) {
  const durationsMs = [];
  const frameIntervalsNs = [];
  let recentDeadlineMisses = 0;
  let header = null;
  let inside = false;

  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line === '---PROFILEDATA---') {
      inside = !inside;
      if (!inside) header = null;
      continue;
    }
    if (!inside) continue;

    if (line.startsWith('Flags,')) {
      header = line.split(',');
      continue;
    }
    if (!header || !/^\d+,/.test(line)) continue;

    const values = line.split(',');
    const index = (name) => header.indexOf(name);
    const flagsIndex = index('Flags');
    const intendedIndex = index('IntendedVsync');
    const completedIndex = index('FrameCompleted');
    const deadlineIndex = index('FrameDeadline');
    const intervalIndex = index('FrameInterval');

    const flags = Number(values[flagsIndex]);
    const intended = Number(values[intendedIndex]);
    const completed = Number(values[completedIndex]);
    if (flags !== 0 || !Number.isFinite(intended) || !Number.isFinite(completed) || completed <= intended) {
      continue;
    }

    const durationMs = (completed - intended) / 1_000_000;
    if (durationMs > 0 && durationMs < 1000) durationsMs.push(durationMs);

    if (intervalIndex >= 0) {
      const interval = Number(values[intervalIndex]);
      if (Number.isFinite(interval) && interval > 1_000_000 && interval < 100_000_000) {
        frameIntervalsNs.push(interval);
      }
    }

    if (deadlineIndex >= 0) {
      const deadline = Number(values[deadlineIndex]);
      if (Number.isFinite(deadline) && deadline > 0 && completed > deadline) {
        recentDeadlineMisses += 1;
      }
    }
  }

  durationsMs.sort((a, b) => a - b);
  frameIntervalsNs.sort((a, b) => a - b);
  const intervalNs = percentile(frameIntervalsNs, 0.5);

  return {
    recentFrames: durationsMs.length,
    recentP50Ms: round(percentile(durationsMs, 0.5)),
    recentP95Ms: round(percentile(durationsMs, 0.95)),
    recentP99Ms: round(percentile(durationsMs, 0.99)),
    recentMaxMs: round(durationsMs.at(-1) ?? null),
    recentDeadlineMisses,
    frameIntervalMs: intervalNs == null ? null : round(intervalNs / 1_000_000),
    measuredRefreshHz: intervalNs == null ? null : round(1_000_000_000 / intervalNs, 2),
  };
}

function ensureBackendActivation(effectEnabled, sdk) {
  const logcat = adb(['logcat', '-d'], { trim: false });
  if (!effectEnabled) {
    const identity = 'Blur identity active: blurRadius 0px (no blur, no Mask fallback).';
    if (!logcat.includes(identity)) {
      throw new Error('0px identity activation was not observed; refusing to benchmark the wrong baseline.');
    }
    return;
  }

  if (sdk >= 33) {
    const activations = options.radiusPx >= 110
      ? [
          'Using HWUI-scaled progressive blur on API 33+',
        ]
      : [
          'Using official AndroidX progressive blur on API 33+',
          'Using pure progressive AGSL blur on API 33+',
        ];
    if (!activations.some((activation) => logcat.includes(activation))) {
      throw new Error(
        `Expected progressive backend activation was not observed for API ${sdk} / radius ${options.radiusPx}px.`
      );
    }
  } else {
    const activation =
      'Using GLES 3.0 continuous progressive blur on API 31-32';
    if (!logcat.includes(activation)) {
      throw new Error(`Progressive backend activation was not observed for API ${sdk}.`);
    }
  }
  if (/Progressive unavailable; using mask fallback|progressive draw failed|progressive frame unavailable/i.test(logcat)) {
    throw new Error('A progressive fallback/failure was observed during warm-up.');
  }
}

function launchCase(effectEnabled, sdk) {
  adb(['logcat', '-c']);
  adbShell(`am force-stop ${PACKAGE}`);
  sleep(400);

  const effect = effectEnabled ? 'on' : 'off';
  const uri = `edgefade:///?stress=auto&effect=${effect}&radiusPx=${options.radiusPx}&cycleMs=${options.cycleMs}&image=${options.imageRenderer}`;
  const launch = adbShell(
    `am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PACKAGE}`,
    { trim: false }
  );

  if (!/Status:\s*ok/.test(launch)) {
    throw new Error(`Gallery stress route failed to launch:\n${launch}`);
  }

  sleep(options.warmupMs);
  ensureBackendActivation(effectEnabled, sdk);
}

function resetGraphicsStats() {
  adb(['shell', 'dumpsys', 'gfxinfo', PACKAGE, 'reset']);
}

function runCase({ effectEnabled, ordinal, sdk, runId }) {
  const label = effectEnabled ? 'blur' : 'baseline';
  console.log(`\n[${ordinal}] ${label.toUpperCase()} / ${options.imageRenderer} / ${options.durationMs}ms`);

  launchCase(effectEnabled, sdk);
  resetGraphicsStats();
  const tempStartC = parseBatteryTemperature();
  sleep(options.durationMs);

  const gfxSummaryRaw = adb(['shell', 'dumpsys', 'gfxinfo', PACKAGE], { trim: false });
  const frameStatsRaw = adb(['shell', 'dumpsys', 'gfxinfo', PACKAGE, 'framestats'], { trim: false });
  const tempEndC = parseBatteryTemperature();

  adbShell(`am force-stop ${PACKAGE}`);

  const summary = parseGfxSummary(gfxSummaryRaw);
  const recent = parseFrameStats(frameStatsRaw);
  const result = {
    label,
    effectEnabled,
    imageRenderer: options.imageRenderer,
    ordinal,
    durationMs: options.durationMs,
    warmupMs: options.warmupMs,
    radiusPx: effectEnabled ? options.radiusPx : 0,
    requestedRadiusPx: options.radiusPx,
    cycleMs: options.cycleMs,
    tempStartC,
    tempEndC,
    ...summary,
    ...recent,
  };

  const fileBase = `${runId}-${options.imageRenderer}-${String(ordinal).padStart(2, '0')}-${label}`;
  fs.writeFileSync(path.join(options.outDir, `${fileBase}-gfxinfo.txt`), gfxSummaryRaw);
  fs.writeFileSync(path.join(options.outDir, `${fileBase}-framestats.txt`), frameStatsRaw);

  const jankText = result.jankyPercent == null ? 'n/a' : `${result.jankyPercent}%`;
  const deadlineText = result.frameDeadlineMissed == null ? 'n/a' : result.frameDeadlineMissed;
  const refreshText = result.measuredRefreshHz == null ? 'n/a' : `${result.measuredRefreshHz}Hz`;
  console.log(
    `  frames=${result.totalFrames ?? 'n/a'} jank=${jankText} deadlineMiss=${deadlineText} ` +
    `p50/p95/p99=${result.p50Ms ?? 'n/a'}/${result.p95Ms ?? 'n/a'}/${result.p99Ms ?? 'n/a'}ms ` +
    `gfxFrameInterval=${refreshText}`
  );

  if (options.cooldownMs > 0) sleep(options.cooldownMs);
  return result;
}

function median(values) {
  const valid = values.filter(Number.isFinite).sort((a, b) => a - b);
  if (!valid.length) return null;
  const middle = Math.floor(valid.length / 2);
  if (valid.length % 2 === 1) return valid[middle];
  return (valid[middle - 1] + valid[middle]) / 2;
}

function aggregate(results, label) {
  const rows = results.filter((row) => row.label === label);
  const totalFrames = rows.reduce((sum, row) => sum + (row.totalFrames ?? 0), 0);
  const totalJanky = rows.reduce((sum, row) => sum + (row.jankyFrames ?? 0), 0);
  const totalDeadlineMissed = rows.reduce((sum, row) => sum + (row.frameDeadlineMissed ?? 0), 0);

  return {
    samples: rows.length,
    totalFrames,
    totalJanky,
    weightedJankPercent: totalFrames > 0 ? round((totalJanky / totalFrames) * 100, 3) : null,
    totalDeadlineMissed,
    medianP50Ms: round(median(rows.map((row) => row.p50Ms))),
    medianP95Ms: round(median(rows.map((row) => row.p95Ms))),
    medianP99Ms: round(median(rows.map((row) => row.p99Ms))),
    medianRecentP95Ms: round(median(rows.map((row) => row.recentP95Ms))),
    medianMeasuredRefreshHz: round(median(rows.map((row) => row.measuredRefreshHz)), 2),
    medianTemperatureRiseC: round(
      median(rows.map((row) =>
        Number.isFinite(row.tempStartC) && Number.isFinite(row.tempEndC)
          ? row.tempEndC - row.tempStartC
          : null
      )),
      2
    ),
  };
}

assertInstalledReleasePackage();

const model = adb(['shell', 'getprop', 'ro.product.model']);
const sdk = Number(adb(['shell', 'getprop', 'ro.build.version.sdk']));
const qemu = adb(['shell', 'getprop', 'ro.kernel.qemu']);
const size = adb(['shell', 'wm', 'size']);
const density = adb(['shell', 'wm', 'density']);
const peakRefreshRate = readSetting('peak_refresh_rate');
const minRefreshRate = readSetting('min_refresh_rate');
const isEmulator = qemu === '1' || /sdk_gphone|emulator/i.test(model);

if (!Number.isFinite(sdk) || sdk < 31) {
  throw new Error(`Gallery progressive stress benchmark requires API 31+; device is API ${sdk}.`);
}

const runId = timestamp();
console.log('Gallery deterministic stress benchmark');
console.log(`Device: ${model} / API ${sdk}${isEmulator ? ' / emulator' : ' / physical'}`);
console.log(`Display: ${size.replace(/\s+/g, ' ')} / ${density.replace(/\s+/g, ' ')}`);
console.log(`Refresh settings: peak=${peakRefreshRate ?? 'n/a'} min=${minRefreshRate ?? 'n/a'}`);
console.log(`Scene: real Home Photo Grid / FlashList / 4 columns / ${imageRendererLabel()}`);
console.log(`Workload: triangular auto-scroll / cycle=${options.cycleMs}ms`);
console.log(`Blur: ${options.radiusPx}px physical target / top+bottom 110dp`);
console.log(`Capture: ${options.samples} sample(s) per condition / ${options.durationMs}ms each`);
console.log(`Order: balanced pair reversal to reduce chronological/thermal bias`);

const sequence = [];
for (let sample = 0; sample < options.samples; sample++) {
  if (sample % 2 === 0) {
    sequence.push(false, true);
  } else {
    sequence.push(true, false);
  }
}

const results = [];
for (let i = 0; i < sequence.length; i++) {
  results.push(runCase({
    effectEnabled: sequence[i],
    ordinal: i + 1,
    sdk,
    runId,
  }));
}

const baseline = aggregate(results, 'baseline');
const blur = aggregate(results, 'blur');
const comparison = {
  weightedJankDeltaPctPoints:
    baseline.weightedJankPercent == null || blur.weightedJankPercent == null
      ? null
      : round(blur.weightedJankPercent - baseline.weightedJankPercent, 3),
  medianP95DeltaMs:
    baseline.medianP95Ms == null || blur.medianP95Ms == null
      ? null
      : round(blur.medianP95Ms - baseline.medianP95Ms),
  medianP99DeltaMs:
    baseline.medianP99Ms == null || blur.medianP99Ms == null
      ? null
      : round(blur.medianP99Ms - baseline.medianP99Ms),
  deadlineMissDelta: blur.totalDeadlineMissed - baseline.totalDeadlineMissed,
};

const report = {
  runId,
  device: {
    model,
    sdk,
    isEmulator,
    size,
    density,
    peakRefreshRate,
    minRefreshRate,
  },
  options,
  results,
  aggregate: { baseline, blur, comparison },
};

const reportPath = path.join(
  options.outDir,
  `${runId}-${options.imageRenderer}-report.json`
);
fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);

console.log('\n=== Aggregate ===');
console.log(
  `BASELINE  frames=${baseline.totalFrames} jank=${baseline.weightedJankPercent ?? 'n/a'}% ` +
  `deadlineMiss=${baseline.totalDeadlineMissed} p95=${baseline.medianP95Ms ?? 'n/a'}ms p99=${baseline.medianP99Ms ?? 'n/a'}ms`
);
console.log(
  `BLUR      frames=${blur.totalFrames} jank=${blur.weightedJankPercent ?? 'n/a'}% ` +
  `deadlineMiss=${blur.totalDeadlineMissed} p95=${blur.medianP95Ms ?? 'n/a'}ms p99=${blur.medianP99Ms ?? 'n/a'}ms`
);
console.log(
  `DELTA     jank=${comparison.weightedJankDeltaPctPoints ?? 'n/a'}pp ` +
  `deadlineMiss=${comparison.deadlineMissDelta} p95=${comparison.medianP95DeltaMs ?? 'n/a'}ms ` +
  `p99=${comparison.medianP99DeltaMs ?? 'n/a'}ms`
);
console.log(`Report: ${reportPath}`);
console.log('Raw gfxinfo + framestats files were saved beside the report.');
