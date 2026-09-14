#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PUBLIC_PACKAGE = 'com.edgefadeexample';
const ANDROIDX_PACKAGE = 'com.edgefade.androidxref';
const ANDROIDX_ACTIVITY = 'com.edgefade.androidxref/.BenchmarkActivity';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const RESULTS_DIR = path.join(ROOT, 'benchmark-results', 'androidx-official');
fs.mkdirSync(RESULTS_DIR, { recursive: true });

function parseArgs(argv) {
  const options = {
    edges: 'vertical',
    radiusPx: 144,
    swipes: 14,
    swipeDurationMs: 180,
    warmupSwipes: 4,
    blocks: 2,
    cooldownSeconds: 2,
    serial: '',
    allowEmulator: false,
    aggregateOut: '',
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const value = () => {
      if (i + 1 >= argv.length) throw new Error(`Missing value after ${arg}`);
      return argv[++i];
    };
    switch (arg) {
      case '--edges': options.edges = value(); break;
      case '--radius-px': options.radiusPx = Number(value()); break;
      case '--swipes': options.swipes = Number(value()); break;
      case '--swipe-duration-ms': options.swipeDurationMs = Number(value()); break;
      case '--warmup-swipes': options.warmupSwipes = Number(value()); break;
      case '--blocks': options.blocks = Number(value()); break;
      case '--cooldown-seconds': options.cooldownSeconds = Number(value()); break;
      case '--serial': options.serial = value(); break;
      case '--allow-emulator': options.allowEmulator = true; break;
      case '--aggregate-out': options.aggregateOut = value(); break;
      default: throw new Error(`Unknown argument: ${arg}`);
    }
  }
  if (!['vertical', 'four'].includes(options.edges)) throw new Error('--edges must be vertical or four');
  if (!Number.isFinite(options.radiusPx) || options.radiusPx < 1 || options.radiusPx > 150) {
    throw new Error('--radius-px must be in 1..150');
  }
  for (const key of ['swipes', 'swipeDurationMs', 'warmupSwipes', 'blocks', 'cooldownSeconds']) {
    if (!Number.isFinite(options[key]) || options[key] < 0) throw new Error(`Invalid --${key}`);
  }
  if (options.blocks < 1) throw new Error('--blocks must be >= 1');
  return options;
}

const options = parseArgs(process.argv.slice(2));

function adb(args, { trim = true } = {}) {
  const fullArgs = options.serial ? ['-s', options.serial, ...args] : args;
  const result = spawnSync('adb', fullArgs, { encoding: 'utf8' });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    const prefix = options.serial ? `adb -s ${options.serial}` : 'adb';
    throw new Error(`${prefix} ${args.join(' ')} failed:\n${result.stdout}${result.stderr}`);
  }
  const output = `${result.stdout ?? ''}${result.stderr ?? ''}`;
  return trim ? output.trim() : output;
}

function sleep(ms) {
  if (ms <= 0) return;
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function percentile(values, p) {
  if (!values.length) return Number.NaN;
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.max(0, Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1));
  return sorted[index];
}

function median(values) {
  if (!values.length) return Number.NaN;
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

function round(value, digits = 3) {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

function parseFrameStats(text) {
  const durations = [];
  const intervals = [];
  let missed = 0;
  let inProfile = false;
  let indices = null;

  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === '---PROFILEDATA---') {
      inProfile = !inProfile;
      if (!inProfile) indices = null;
      continue;
    }
    if (!inProfile || !line) continue;

    if (line.startsWith('Flags,')) {
      const headers = line.split(',');
      indices = {
        flags: headers.indexOf('Flags'),
        intendedVsync: headers.indexOf('IntendedVsync'),
        frameDeadline: headers.indexOf('FrameDeadline'),
        frameInterval: headers.indexOf('FrameInterval'),
        frameCompleted: headers.indexOf('FrameCompleted'),
      };
      continue;
    }
    if (!indices) continue;

    const parts = line.split(',');
    const required = [indices.flags, indices.intendedVsync, indices.frameCompleted];
    if (required.some((index) => index < 0 || index >= parts.length)) continue;

    let flags;
    let intended;
    let completed;
    try {
      flags = BigInt(parts[indices.flags]);
      intended = BigInt(parts[indices.intendedVsync]);
      completed = BigInt(parts[indices.frameCompleted]);
    } catch {
      continue;
    }
    if (flags !== 0n || completed <= intended) continue;

    const durationMs = Number(completed - intended) / 1_000_000;
    if (!(durationMs > 0 && durationMs <= 1000)) continue;
    durations.push(durationMs);

    if (indices.frameInterval >= 0 && indices.frameInterval < parts.length) {
      try {
        const interval = BigInt(parts[indices.frameInterval]);
        if (interval > 0n) intervals.push(Number(interval) / 1_000_000);
      } catch {}
    }
    if (indices.frameDeadline >= 0 && indices.frameDeadline < parts.length) {
      try {
        const deadline = BigInt(parts[indices.frameDeadline]);
        if (deadline > 0n && completed > deadline) missed++;
      } catch {}
    }
  }

  if (!durations.length) {
    throw new Error('No valid framestats rows were found. Make sure the benchmark Activity is visible and scrolling.');
  }

  return {
    frames: durations.length,
    frameIntervalMs: round(median(intervals)),
    p50Ms: round(percentile(durations, 0.50)),
    p95Ms: round(percentile(durations, 0.95)),
    p99Ms: round(percentile(durations, 0.99)),
    maxMs: round(percentile(durations, 1.00)),
    missedDeadline: missed,
    missedPct: round((missed * 100) / durations.length, 2),
  };
}

function totalPssMb(text) {
  const match = text.match(/TOTAL PSS:\s+(\d+)/);
  return match ? round(Number(match[1]) / 1024, 2) : Number.NaN;
}

function radiusLabel(value) {
  return String(Number(value.toFixed(3))).replace('.', 'p');
}

function timestamp() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}

function assertInstalledReleasePackage(pkg, label) {
  const packagePath = adb(['shell', 'pm', 'path', pkg]);
  if (!/^package:/m.test(packagePath)) throw new Error(`${label} package ${pkg} is not installed.`);
  const dump = adb(['shell', 'dumpsys', 'package', pkg], { trim: false });
  if (/pkgFlags=\[[^\]]*DEBUGGABLE/m.test(dump)) {
    throw new Error(`${label} package ${pkg} is debuggable. Install the release variant before benchmarking.`);
  }
}

assertInstalledReleasePackage(PUBLIC_PACKAGE, 'Public Edge Fade');
assertInstalledReleasePackage(ANDROIDX_PACKAGE, 'AndroidX reference');

const sizeText = adb(['shell', 'wm', 'size']);
const sizeMatches = [...sizeText.matchAll(/(\d+)x(\d+)/g)];
if (!sizeMatches.length) throw new Error(`Could not parse device size from: ${sizeText}`);
const size = sizeMatches.at(-1);
const width = Number(size[1]);
const height = Number(size[2]);
const x = Math.round(width * 0.50);
const yTop = Math.round(height * 0.34);
const yBottom = Math.round(height * 0.78);

const densityText = adb(['shell', 'wm', 'density']);
const overrideDensity = densityText.match(/Override density:\s*(\d+)/);
const physicalDensity = densityText.match(/Physical density:\s*(\d+)/);
const densityDpi = Number(overrideDensity?.[1] ?? physicalDensity?.[1]);
if (!densityDpi) throw new Error(`Could not parse device density from: ${densityText}`);
const densityScale = densityDpi / 160;
const radiusDp = options.radiusPx / densityScale;
const radiusInvariant = String(Number(options.radiusPx.toFixed(3)));
const label = radiusLabel(options.radiusPx);

const model = adb(['shell', 'getprop', 'ro.product.model']);
const sdk = Number(adb(['shell', 'getprop', 'ro.build.version.sdk']));
const qemu = adb(['shell', 'getprop', 'ro.kernel.qemu']);
const isEmulator = qemu === '1' || /sdk_gphone|emulator/i.test(model);
if (sdk < 33) throw new Error(`AndroidX progressive BlurRadiusSpec requires API 33+; device is API ${sdk}.`);
if (isEmulator && !options.allowEmulator) {
  throw new Error(`Detected an Android emulator (${model}). Use a physical device or pass --allow-emulator only for smoke tests.`);
}
if (isEmulator) console.warn('EMULATOR SMOKE TEST ONLY: do not use these frame/GPU numbers for renderer decisions.');

console.log(`Device: ${model} / API ${sdk} / ${width}x${height} / ${densityDpi}dpi (${round(densityScale)}x)`);
console.log(`Scene: ${round(radiusDp, 2)}dp public = ${options.radiusPx}px official / Smooth / ${options.edges} / neutral pure Gaussian`);
console.log(`Method: discarded warm-up per renderer + ${options.blocks} balanced ABBA/BAAB block(s)`);

function startRenderer(name) {
  if (name === 'public') {
    adb(['shell', 'am', 'force-stop', PUBLIC_PACKAGE]);
    sleep(400);
    const uri = `edgefade://progressive-blur-perf?backend=progressive&edges=${options.edges}&radiusPx=${radiusInvariant}`;
    const command = `am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PUBLIC_PACKAGE}`;
    adb(['shell', command]);
  } else {
    adb(['shell', 'am', 'force-stop', ANDROIDX_PACKAGE]);
    sleep(400);
    adb([
      'shell', 'am', 'start', '-W', '-n', ANDROIDX_ACTIVITY,
      '--es', 'edges', options.edges,
      '--ef', 'radiusPx', radiusInvariant,
      '--es', 'curve', 'smooth',
    ]);
  }
  sleep(2000);
}

function driveSwipes(count) {
  for (let i = 0; i < count; i++) {
    const fromY = i % 2 === 0 ? yBottom : yTop;
    const toY = i % 2 === 0 ? yTop : yBottom;
    adb(['shell', 'input', 'swipe', String(x), String(fromY), String(x), String(toY), String(options.swipeDurationMs)]);
    sleep(90);
  }
}

console.log('\nWarm-up (discarded):');
for (const name of ['public', 'androidx']) {
  console.log(`  ${name}`);
  startRenderer(name);
  driveSwipes(options.warmupSwipes);
  sleep(500);
}

function runOne(name, run) {
  const pkg = name === 'public' ? PUBLIC_PACKAGE : ANDROIDX_PACKAGE;
  startRenderer(name);
  adb(['shell', 'dumpsys', 'gfxinfo', pkg, 'reset']);
  driveSwipes(options.swipes);
  sleep(1000);

  const framesText = adb(['shell', 'dumpsys', 'gfxinfo', pkg, 'framestats'], { trim: false });
  const memoryText = adb(['shell', 'dumpsys', 'meminfo', pkg], { trim: false });
  const stats = parseFrameStats(framesText);
  const pss = totalPssMb(memoryText);

  const prefix = path.join(RESULTS_DIR, `${timestamp()}-${name}-${options.edges}-${label}px-r${run}`);
  fs.writeFileSync(`${prefix}-framestats.txt`, framesText, 'utf8');
  fs.writeFileSync(`${prefix}-meminfo.txt`, memoryText, 'utf8');

  const summary = {
    Renderer: name,
    Edges: options.edges,
    Run: run,
    DensityDpi: densityDpi,
    RadiusDpPublic: round(radiusDp),
    RadiusPx: options.radiusPx,
    Frames: stats.frames,
    FrameIntervalMs: stats.frameIntervalMs,
    P50Ms: stats.p50Ms,
    P95Ms: stats.p95Ms,
    P99Ms: stats.p99Ms,
    MaxMs: stats.maxMs,
    MissedDeadline: stats.missedDeadline,
    MissedPct: stats.missedPct,
    TotalPssMb: pss,
  };
  fs.writeFileSync(`${prefix}-summary.json`, `${JSON.stringify(summary, null, 2)}\n`, 'utf8');
  return summary;
}

const sequence = [];
for (let block = 0; block < options.blocks; block++) {
  sequence.push(...(block % 2 === 0
    ? ['public', 'androidx', 'androidx', 'public']
    : ['androidx', 'public', 'public', 'androidx']));
}

const runs = [];
for (let i = 0; i < sequence.length; i++) {
  const name = sequence[i];
  console.log(`\n[${i + 1}/${sequence.length}] ${name}`);
  const result = runOne(name, i + 1);
  runs.push(result);
  console.table([result]);
  if (i < sequence.length - 1) sleep(options.cooldownSeconds * 1000);
}

const aggregate = ['androidx', 'public'].map((renderer) => {
  const items = runs.filter((item) => item.Renderer === renderer);
  return {
    Renderer: renderer,
    Edges: options.edges,
    RadiusDpPublic: round(radiusDp),
    RadiusPx: options.radiusPx,
    Runs: items.length,
    FrameIntervalMs: round(median(items.map((item) => item.FrameIntervalMs))),
    P50MedianMs: round(median(items.map((item) => item.P50Ms))),
    P95MedianMs: round(median(items.map((item) => item.P95Ms))),
    P99MedianMs: round(median(items.map((item) => item.P99Ms))),
    MissedPctMedian: round(median(items.map((item) => item.MissedPct)), 2),
    PssMedianMb: round(median(items.map((item) => item.TotalPssMb)), 2),
  };
});

console.log('\nAggregate (balanced ABBA/BAAB):');
console.table(aggregate);
const publicRow = aggregate.find((item) => item.Renderer === 'public');
const androidxRow = aggregate.find((item) => item.Renderer === 'androidx');
console.log('\nPublic / AndroidX median ratios:');
console.log(`  p50: ${round(publicRow.P50MedianMs / androidxRow.P50MedianMs)}x`);
console.log(`  p95: ${round(publicRow.P95MedianMs / androidxRow.P95MedianMs)}x`);
console.log(`  p99: ${round(publicRow.P99MedianMs / androidxRow.P99MedianMs)}x`);

const aggregatePath = options.aggregateOut
  ? path.resolve(options.aggregateOut)
  : path.join(RESULTS_DIR, `${timestamp()}-${options.edges}-${label}px-aggregate.json`);
fs.mkdirSync(path.dirname(aggregatePath), { recursive: true });
fs.writeFileSync(aggregatePath, `${JSON.stringify(aggregate, null, 2)}\n`, 'utf8');
console.log(`\nAggregate JSON: ${aggregatePath}`);
console.log(`Raw framestats, meminfo and JSON summaries: ${RESULTS_DIR}`);
