#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT_DIR = path.join(ROOT, 'benchmark-results', 'gallery-main');
const CASES = ['off', 'main'];

function parseArgs(argv) {
  const out = {
    serial: '',
    durationMs: 12000,
    warmupMs: 3000,
    cooldownMs: 1500,
    radiusPx: 80,
    cycleMs: 4200,
    samples: 2,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const take = () => argv[++i];
    if (arg === '--serial') out.serial = take();
    else if (arg === '--duration-ms') out.durationMs = Number(take());
    else if (arg === '--warmup-ms') out.warmupMs = Number(take());
    else if (arg === '--cooldown-ms') out.cooldownMs = Number(take());
    else if (arg === '--radius-px') out.radiusPx = Number(take());
    else if (arg === '--cycle-ms') out.cycleMs = Number(take());
    else if (arg === '--samples') out.samples = Number(take());
    else throw new Error(`Unknown argument: ${arg}`);
  }
  if (!Number.isFinite(out.radiusPx) || out.radiusPx < 1 || out.radiusPx > 150) {
    throw new Error('--radius-px must be in 1..150');
  }
  if (!Number.isInteger(out.samples) || out.samples < 1 || out.samples > 8) {
    throw new Error('--samples must be in 1..8');
  }
  return out;
}

const options = parseArgs(process.argv.slice(2));
fs.mkdirSync(OUT_DIR, { recursive: true });

function adb(args, trim = true) {
  const prefix = options.serial ? ['-s', options.serial] : [];
  const result = spawnSync('adb', [...prefix, ...args], {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${result.stdout ?? ''}${result.stderr ?? ''}`);
  const text = `${result.stdout ?? ''}${result.stderr ?? ''}`;
  return trim ? text.trim() : text;
}

function shell(command, trim = true) {
  return adb(['shell', command], trim);
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function timestamp() {
  return new Date().toISOString().replace(/[:.]/g, '-');
}

function metric(text, regex) {
  const match = text.match(regex);
  return match ? Number(match[1]) : null;
}

function parseMetrics(text) {
  const janky = text.match(/Janky frames:\s*(\d+)\s*\(([\d.]+)%\)/);
  return {
    totalFrames: metric(text, /Total frames rendered:\s*(\d+)/),
    jankyFrames: janky ? Number(janky[1]) : null,
    jankyPercent: janky ? Number(janky[2]) : null,
    p50Ms: metric(text, /50th percentile:\s*(\d+)ms/),
    p90Ms: metric(text, /90th percentile:\s*(\d+)ms/),
    p95Ms: metric(text, /95th percentile:\s*(\d+)ms/),
    p99Ms: metric(text, /99th percentile:\s*(\d+)ms/),
    deadlineMissed: metric(text, /Number Frame deadline missed:\s*(\d+)/),
    missedVsync: metric(text, /Number Missed Vsync:\s*(\d+)/),
  };
}

function assertReleasePackage() {
  const packagePath = adb(['shell', 'pm', 'path', PACKAGE], false);
  if (!/^package:/m.test(packagePath)) {
    throw new Error(`${PACKAGE} is not installed.`);
  }

  const dump = adb(['shell', 'dumpsys', 'package', PACKAGE], false);
  if (/pkgFlags=\[[^\]]*DEBUGGABLE/m.test(dump)) {
    throw new Error(
      `${PACKAGE} is debuggable. Install the release variant before benchmarking.`
    );
  }
}

function launch(label) {
  const effect = label === 'main' ? 'on' : 'off';
  shell(`am force-stop ${PACKAGE}`);
  sleep(400);
  const uri =
    `edgefade:///?stress=auto&effect=${effect}` +
    `&radiusPx=${options.radiusPx}&cycleMs=${options.cycleMs}`;
  const launchResult = shell(
    `am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PACKAGE}`,
    false
  );
  if (!/Status:\s*ok/.test(launchResult)) throw new Error(`Launch failed for ${label}`);

  const pid = shell(`pidof ${PACKAGE}`, false).trim();
  if (!pid) throw new Error(`${PACKAGE} did not stay alive after launching ${label}.`);
}

function screenshot(label, runId) {
  const remote = `/sdcard/gallery-main-${label}.png`;
  const local = path.join(OUT_DIR, `${runId}-${label}.png`);
  shell(`screencap -p ${remote}`);
  adb(['pull', remote, local]);
  shell(`rm -f ${remote}`);
}

function runCase(label, sample, runId, capture) {
  console.log(`\n${label.toUpperCase()} / sample ${sample}`);
  launch(label);
  sleep(1000);
  if (capture) screenshot(label, runId);
  sleep(Math.max(0, options.warmupMs - 1000));
  adb(['shell', 'dumpsys', 'gfxinfo', PACKAGE, 'reset']);
  sleep(options.durationMs);
  const raw = adb(['shell', 'dumpsys', 'gfxinfo', PACKAGE], false);
  const row = {
    label,
    sample,
    radiusPx: options.radiusPx,
    durationMs: options.durationMs,
    ...parseMetrics(raw),
  };
  fs.writeFileSync(path.join(OUT_DIR, `${runId}-${label}-${sample}-gfxinfo.txt`), raw);
  shell(`am force-stop ${PACKAGE}`);
  console.log(
    `frames=${row.totalFrames ?? 'n/a'} jank=${row.jankyPercent ?? 'n/a'}% ` +
      `p95=${row.p95Ms ?? 'n/a'}ms p99=${row.p99Ms ?? 'n/a'}ms ` +
      `deadline=${row.deadlineMissed ?? 'n/a'}`
  );
  sleep(options.cooldownMs);
  return row;
}

function median(values) {
  const list = values.filter(Number.isFinite).sort((a, b) => a - b);
  if (!list.length) return null;
  const mid = Math.floor(list.length / 2);
  return list.length % 2 ? list[mid] : (list[mid - 1] + list[mid]) / 2;
}

function aggregate(results, label) {
  const rows = results.filter((row) => row.label === label);
  const frames = rows.reduce((sum, row) => sum + (row.totalFrames ?? 0), 0);
  const janky = rows.reduce((sum, row) => sum + (row.jankyFrames ?? 0), 0);
  return {
    samples: rows.length,
    frames,
    weightedJankPercent: frames ? (janky / frames) * 100 : null,
    medianP95Ms: median(rows.map((row) => row.p95Ms)),
    medianP99Ms: median(rows.map((row) => row.p99Ms)),
    deadlineMissed: rows.reduce((sum, row) => sum + (row.deadlineMissed ?? 0), 0),
  };
}

const sdk = Number(shell('getprop ro.build.version.sdk'));
const model = shell('getprop ro.product.model');
assertReleasePackage();

const runId = timestamp();
console.log(`Gallery main baseline / ${model} / API ${sdk}`);
console.log(`radius=${options.radiusPx}px / samples=${options.samples}`);

const results = [];
for (let sample = 0; sample < options.samples; sample++) {
  const order = sample % 2 === 0 ? CASES : [...CASES].reverse();
  for (const label of order) {
    results.push(runCase(label, sample + 1, runId, sample === 0));
  }
}

const off = aggregate(results, 'off');
const main = aggregate(results, 'main');
const report = {
  runId,
  device: { model, sdk },
  options,
  results,
  aggregate: { off, main },
  deltaVsOff: {
    jankDeltaPctPoints:
      off.weightedJankPercent == null || main.weightedJankPercent == null
        ? null
        : main.weightedJankPercent - off.weightedJankPercent,
    p95DeltaMs:
      off.medianP95Ms == null || main.medianP95Ms == null
        ? null
        : main.medianP95Ms - off.medianP95Ms,
    p99DeltaMs:
      off.medianP99Ms == null || main.medianP99Ms == null
        ? null
        : main.medianP99Ms - off.medianP99Ms,
    deadlineDelta: main.deadlineMissed - off.deadlineMissed,
  },
};

const reportPath = path.join(OUT_DIR, `${runId}-report.json`);
fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);

console.log('\n=== Aggregate ===');
console.log(JSON.stringify(report.aggregate, null, 2));
console.log(`Report: ${reportPath}`);
console.log('First-sample screenshots and raw gfxinfo are saved beside the report.');
