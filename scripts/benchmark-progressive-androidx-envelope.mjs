#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const COMPARATOR = path.join(ROOT, 'scripts', 'benchmark-progressive-vs-androidx.mjs');
const RESULTS_DIR = path.join(ROOT, 'benchmark-results', 'androidx-official', 'envelope');
fs.mkdirSync(RESULTS_DIR, { recursive: true });

function parseArgs(argv) {
  const options = {
    edges: 'both',
    radiiPx: [],
    defaultBlurRadiusDp: 28,
    swipes: 14,
    swipeDurationMs: 180,
    warmupSwipes: 4,
    blocks: 1,
    cooldownSeconds: 2,
    serial: '',
    allowEmulator: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const value = () => {
      if (i + 1 >= argv.length) throw new Error(`Missing value after ${arg}`);
      return argv[++i];
    };
    switch (arg) {
      case '--edges': options.edges = value(); break;
      case '--radii-px': options.radiiPx = value().split(',').filter(Boolean).map(Number); break;
      case '--default-blur-radius-dp': options.defaultBlurRadiusDp = Number(value()); break;
      case '--swipes': options.swipes = Number(value()); break;
      case '--swipe-duration-ms': options.swipeDurationMs = Number(value()); break;
      case '--warmup-swipes': options.warmupSwipes = Number(value()); break;
      case '--blocks': options.blocks = Number(value()); break;
      case '--cooldown-seconds': options.cooldownSeconds = Number(value()); break;
      case '--serial': options.serial = value(); break;
      case '--allow-emulator': options.allowEmulator = true; break;
      default: throw new Error(`Unknown argument: ${arg}`);
    }
  }
  if (!['both', 'vertical', 'four'].includes(options.edges)) throw new Error('--edges must be both, vertical or four');
  if (options.radiiPx.some((value) => !Number.isFinite(value))) throw new Error('--radii-px must be a comma-separated number list');
  if (!Number.isFinite(options.defaultBlurRadiusDp) || options.defaultBlurRadiusDp <= 0) throw new Error('Invalid --default-blur-radius-dp');
  if (!Number.isFinite(options.blocks) || options.blocks < 1) throw new Error('--blocks must be >= 1');
  return options;
}

const options = parseArgs(process.argv.slice(2));

function adb(args) {
  const fullArgs = options.serial ? ['-s', options.serial, ...args] : args;
  const result = spawnSync('adb', fullArgs, { encoding: 'utf8' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`adb ${args.join(' ')} failed:\n${result.stdout}${result.stderr}`);
  return `${result.stdout ?? ''}${result.stderr ?? ''}`.trim();
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

function round(value, digits = 3) {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

function timestamp() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}

function csvEscape(value) {
  const text = String(value);
  return /[",\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

const densityText = adb(['shell', 'wm', 'density']);
const overrideDensity = densityText.match(/Override density:\s*(\d+)/);
const physicalDensity = densityText.match(/Physical density:\s*(\d+)/);
const densityDpi = Number(overrideDensity?.[1] ?? physicalDensity?.[1]);
if (!densityDpi) throw new Error(`Could not parse device density from: ${densityText}`);
const densityScale = densityDpi / 160;
const defaultRadiusPx = Math.min(150, options.defaultBlurRadiusDp * densityScale);
const requestedRadii = options.radiiPx.length ? options.radiiPx : [64, defaultRadiusPx, 120, 144];
const radii = [...new Set(requestedRadii.map((value) => round(Math.min(150, Math.max(1, value)), 3)))];
const edgeModes = options.edges === 'both' ? ['vertical', 'four'] : [options.edges];

console.log('Public Progressive vs AndroidX Official performance envelope');
console.log(`Density: ${densityDpi}dpi (${round(densityScale)}x)`);
console.log(`Public default: ${options.defaultBlurRadiusDp}dp = ${round(defaultRadiusPx, 1)}px`);
console.log(`Radii: ${radii.join(', ')} px`);
console.log(`Edges: ${edgeModes.join(', ')}`);
console.log(`Each point: discarded warm-up + ${options.blocks} balanced ABBA/BAAB block(s)`);

const rows = [];
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'edgefade-androidx-envelope-'));

try {
  for (const edges of edgeModes) {
    for (const radiusPx of radii) {
      console.log(`\n=== ${edges} / ${radiusPx}px ===`);
      const aggregatePath = path.join(tempDir, `${edges}-${String(radiusPx).replace('.', 'p')}.json`);
      const args = [
        COMPARATOR,
        '--edges', edges,
        '--radius-px', String(radiusPx),
        '--swipes', String(options.swipes),
        '--swipe-duration-ms', String(options.swipeDurationMs),
        '--warmup-swipes', String(options.warmupSwipes),
        '--blocks', String(options.blocks),
        '--cooldown-seconds', String(options.cooldownSeconds),
        '--aggregate-out', aggregatePath,
      ];
      if (options.serial) args.push('--serial', options.serial);
      if (options.allowEmulator) args.push('--allow-emulator');

      const result = spawnSync(process.execPath, args, { stdio: 'inherit' });
      if (result.error) throw result.error;
      if (result.status !== 0) throw new Error(`Comparator failed for ${edges} / ${radiusPx}px with exit ${result.status}`);

      const aggregate = JSON.parse(fs.readFileSync(aggregatePath, 'utf8'));
      const publicRow = aggregate.find((item) => item.Renderer === 'public');
      const androidxRow = aggregate.find((item) => item.Renderer === 'androidx');
      if (!publicRow || !androidxRow) throw new Error(`Missing Public Progressive or AndroidX Official aggregate for ${edges} / ${radiusPx}px`);

      rows.push({
        Edges: edges,
        RadiusPx: radiusPx,
        RadiusDpPublic: round(radiusPx / densityScale),
        IsPublicDefault: Math.abs(radiusPx - defaultRadiusPx) < 0.6,
        PublicP50Ms: publicRow.P50MedianMs,
        PublicP95Ms: publicRow.P95MedianMs,
        PublicP99Ms: publicRow.P99MedianMs,
        PublicMissedPct: publicRow.MissedPctMedian,
        AndroidxP50Ms: androidxRow.P50MedianMs,
        AndroidxP95Ms: androidxRow.P95MedianMs,
        AndroidxP99Ms: androidxRow.P99MedianMs,
        AndroidxMissedPct: androidxRow.MissedPctMedian,
        P50RatioPublicToAndroidx: round(publicRow.P50MedianMs / androidxRow.P50MedianMs),
        P95RatioPublicToAndroidx: round(publicRow.P95MedianMs / androidxRow.P95MedianMs),
        P99RatioPublicToAndroidx: round(publicRow.P99MedianMs / androidxRow.P99MedianMs),
      });
    }
  }
} finally {
  fs.rmSync(tempDir, { recursive: true, force: true });
}

rows.sort((a, b) => a.Edges.localeCompare(b.Edges) || a.RadiusPx - b.RadiusPx);
console.log('\nEnvelope summary:');
console.table(rows);

const stamp = timestamp();
const jsonPath = path.join(RESULTS_DIR, `${stamp}-public-vs-androidx-envelope.json`);
const csvPath = path.join(RESULTS_DIR, `${stamp}-public-vs-androidx-envelope.csv`);
fs.writeFileSync(jsonPath, `${JSON.stringify(rows, null, 2)}\n`, 'utf8');
const headers = Object.keys(rows[0] ?? {});
const csv = [
  headers.join(','),
  ...rows.map((row) => headers.map((header) => csvEscape(row[header])).join(',')),
].join('\n');
fs.writeFileSync(csvPath, `${csv}\n`, 'utf8');
console.log(`\nEnvelope JSON: ${jsonPath}`);
console.log(`Envelope CSV:  ${csvPath}`);
