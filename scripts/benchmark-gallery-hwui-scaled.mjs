#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT_DIR = path.join(ROOT, 'benchmark-results', 'gallery-hwui-scaled');
const RENDERERS = ['agsl', 'hwui-scaled'];

function parseArgs(argv) {
  const out = {
    serial: '',
    durationMs: 12000,
    warmupMs: 3000,
    cooldownMs: 1500,
    radiusPx: 80,
    cycleMs: 4200,
    samples: 2,
    image: 'expo',
    refreshToleranceHz: 5,
    maxAttempts: 4,
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
    else if (arg === '--image') out.image = take();
    else if (arg === '--refresh-tolerance-hz') out.refreshToleranceHz = Number(take());
    else if (arg === '--max-attempts') out.maxAttempts = Number(take());
    else throw new Error(`Unknown argument: ${arg}`);
  }
  if (!Number.isFinite(out.radiusPx) || out.radiusPx < 1 || out.radiusPx > 150) {
    throw new Error('--radius-px must be in 1..150');
  }
  if (!Number.isInteger(out.samples) || out.samples < 1 || out.samples > 5) {
    throw new Error('--samples must be in 1..5');
  }
  if (!['expo', 'native', 'solid'].includes(out.image)) {
    throw new Error('--image must be expo, native or solid');
  }
  if (!Number.isFinite(out.refreshToleranceHz) || out.refreshToleranceHz < 0) {
    throw new Error('--refresh-tolerance-hz must be >= 0');
  }
  if (!Number.isInteger(out.maxAttempts) || out.maxAttempts < 1 || out.maxAttempts > 8) {
    throw new Error('--max-attempts must be in 1..8');
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
  if (result.status !== 0) {
    throw new Error(`${result.stdout ?? ''}${result.stderr ?? ''}`);
  }
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

function round(value, digits = 2) {
  if (!Number.isFinite(value)) return null;
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

function number(text, regex) {
  const match = text.match(regex);
  return match ? Number(match[1]) : null;
}

function metrics(text) {
  const janky = text.match(/Janky frames:\s*(\d+)\s*\(([\d.]+)%\)/);
  return {
    totalFrames: number(text, /Total frames rendered:\s*(\d+)/),
    jankyFrames: janky ? Number(janky[1]) : null,
    jankyPercent: janky ? Number(janky[2]) : null,
    p50Ms: number(text, /50th percentile:\s*(\d+)ms/),
    p90Ms: number(text, /90th percentile:\s*(\d+)ms/),
    p95Ms: number(text, /95th percentile:\s*(\d+)ms/),
    p99Ms: number(text, /99th percentile:\s*(\d+)ms/),
    deadlineMissed: number(text, /Number Frame deadline missed:\s*(\d+)/),
    missedVsync: number(text, /Number Missed Vsync:\s*(\d+)/),
  };
}

function readRefreshRate() {
  try {
    const latency = adb(['shell', 'dumpsys', 'SurfaceFlinger', '--latency'], false);
    for (const raw of latency.split(/\r?\n/)) {
      const line = raw.trim();
      if (!/^\d+$/.test(line)) continue;
      const periodNs = Number(line);
      if (periodNs >= 4_000_000 && periodNs <= 40_000_000) {
        return { hz: round(1_000_000_000 / periodNs), source: 'SurfaceFlinger--latency' };
      }
      break;
    }
  } catch {}

  try {
    const display = adb(['shell', 'dumpsys', 'display'], false);
    const patterns = [
      /renderFrameRate\s*[=:]\s*([\d.]+)/g,
      /refreshRate\s*[=:]\s*([\d.]+)/g,
      /fps\s*[=:]\s*([\d.]+)/g,
    ];
    for (const pattern of patterns) {
      const values = [...display.matchAll(pattern)]
        .map((match) => Number(match[1]))
        .filter((value) => Number.isFinite(value) && value >= 30 && value <= 240);
      if (values.length) return { hz: round(values[0]), source: 'dumpsys-display' };
    }
  } catch {}

  return { hz: null, source: 'unavailable' };
}

function sameRefresh(a, b) {
  return Number.isFinite(a) &&
    Number.isFinite(b) &&
    Math.abs(a - b) <= options.refreshToleranceHz;
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

function launch(renderer) {
  adb(['logcat', '-c']);
  shell(`am force-stop ${PACKAGE}`);
  sleep(400);
  const uri =
    `edgefade:///gallery-renderer-test?renderer=${renderer}` +
    `&radiusPx=${options.radiusPx}&cycleMs=${options.cycleMs}&image=${options.image}`;
  const result = shell(
    `am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PACKAGE}`,
    false
  );
  if (!/Status:\s*ok/.test(result)) throw new Error(`Launch failed for ${renderer}`);
}

function rendererLogs() {
  return adb(
    [
      'logcat',
      '-d',
      '-s',
      'EdgeFadeProgressive:I',
      'EdgeFade.BlurLab:I',
      '*:S',
    ],
    false
  );
}

function verifyRenderer(renderer) {
  let lastUi = '';
  let lastLogs = '';

  for (let attempt = 1; attempt <= 20; attempt++) {
    try {
      shell('uiautomator dump /sdcard/edgefade-bench-ui.xml >/dev/null 2>&1');
      lastUi = shell('cat /sdcard/edgefade-bench-ui.xml', false);
      const expectedUi = `gallery-renderer requested=${renderer} active=${renderer}`;
      if (lastUi.includes(expectedUi)) return;

      const disabledUi = `gallery-renderer requested=${renderer} active=off`;
      if (lastUi.includes(disabledUi)) {
        throw new Error(`${renderer} disabled according to UI state.\n${lastUi}`);
      }
    } catch (error) {
      if (String(error).includes('disabled according to UI state')) throw error;
    }

    lastLogs = rendererLogs();
    const expectedLog = `Renderer active: requested=${renderer} active=${renderer}`;
    const hwuiLog =
      renderer === 'hwui-scaled' &&
      lastLogs.includes(
        'Using HWUI scaled continuous Gaussian benchmark path (0.75x strips).'
      );
    if (lastLogs.includes(expectedLog) || hwuiLog) return;

    const disabledLog = `Renderer active: requested=${renderer} active=off`;
    if (lastLogs.includes(disabledLog)) {
      throw new Error(`${renderer} disabled according to native log.\n${lastLogs}`);
    }
    sleep(250);
  }

  throw new Error(
    `Renderer verification failed for ${renderer}.\nUI dump:\n${lastUi}\nBlurLab logs:\n${lastLogs}`
  );
}
function screenshot(renderer, runId) {
  const remote = `/sdcard/gallery-${renderer}.png`;
  const local = path.join(OUT_DIR, `${runId}-${renderer}.png`);
  shell(`screencap -p ${remote}`);
  adb(['pull', remote, local]);
  shell(`rm -f ${remote}`);
}

let targetRefreshHz = null;

function runCase(renderer, sample, runId, captureVisual) {
  console.log(`\n${renderer.toUpperCase()} / sample ${sample}`);
  launch(renderer);
  sleep(1000);
  verifyRenderer(renderer);
  if (captureVisual) screenshot(renderer, runId);
  sleep(Math.max(0, options.warmupMs - 1000));

  const refreshStart = readRefreshRate();
  adb(['shell', 'dumpsys', 'gfxinfo', PACKAGE, 'reset']);
  sleep(options.durationMs);
  const refreshEnd = readRefreshRate();
  const raw = adb(['shell', 'dumpsys', 'gfxinfo', PACKAGE], false);

  let valid = true;
  let invalidReason = null;
  const stableWithinCase =
    refreshStart.hz == null ||
    refreshEnd.hz == null ||
    sameRefresh(refreshStart.hz, refreshEnd.hz);

  if (!stableWithinCase) {
    valid = false;
    invalidReason = `refresh changed during case: ${refreshStart.hz}Hz -> ${refreshEnd.hz}Hz`;
  }

  if (targetRefreshHz == null && stableWithinCase) {
    targetRefreshHz = refreshStart.hz ?? refreshEnd.hz;
  }

  if (
    valid &&
    targetRefreshHz != null &&
    ((refreshStart.hz != null && !sameRefresh(refreshStart.hz, targetRefreshHz)) ||
      (refreshEnd.hz != null && !sameRefresh(refreshEnd.hz, targetRefreshHz)))
  ) {
    valid = false;
    invalidReason =
      `refresh differs from baseline ${targetRefreshHz}Hz: ` +
      `${refreshStart.hz ?? 'n/a'}Hz -> ${refreshEnd.hz ?? 'n/a'}Hz`;
  }

  const result = {
    renderer,
    sample,
    radiusPx: options.radiusPx,
    durationMs: options.durationMs,
    refreshHzStart: refreshStart.hz,
    refreshHzEnd: refreshEnd.hz,
    refreshSourceStart: refreshStart.source,
    refreshSourceEnd: refreshEnd.source,
    valid,
    invalidReason,
    ...metrics(raw),
  };
  fs.writeFileSync(
    path.join(OUT_DIR, `${runId}-${renderer}-${sample}-gfxinfo.txt`),
    raw
  );
  shell(`am force-stop ${PACKAGE}`);

  const validity = result.valid ? '' : ` INVALID(${result.invalidReason})`;
  console.log(
    `frames=${result.totalFrames ?? 'n/a'} jank=${result.jankyPercent ?? 'n/a'}% ` +
      `p95=${result.p95Ms ?? 'n/a'}ms p99=${result.p99Ms ?? 'n/a'}ms ` +
      `deadline=${result.deadlineMissed ?? 'n/a'} ` +
      `refresh=${result.refreshHzStart ?? 'n/a'}->${result.refreshHzEnd ?? 'n/a'}Hz${validity}`
  );
  sleep(options.cooldownMs);
  return result;
}

function median(values) {
  const list = values.filter(Number.isFinite).sort((a, b) => a - b);
  if (!list.length) return null;
  const m = Math.floor(list.length / 2);
  return list.length % 2 ? list[m] : (list[m - 1] + list[m]) / 2;
}

function aggregate(results, renderer) {
  const allRows = results.filter((row) => row.renderer === renderer);
  const rows = allRows.filter((row) => row.valid !== false);
  const frames = rows.reduce((sum, row) => sum + (row.totalFrames ?? 0), 0);
  const janky = rows.reduce((sum, row) => sum + (row.jankyFrames ?? 0), 0);
  return {
    samples: rows.length,
    invalidSamples: allRows.length - rows.length,
    frames,
    weightedJankPercent: frames ? (janky / frames) * 100 : null,
    medianP95Ms: median(rows.map((row) => row.p95Ms)),
    medianP99Ms: median(rows.map((row) => row.p99Ms)),
    deadlineMissed: rows.reduce((sum, row) => sum + (row.deadlineMissed ?? 0), 0),
  };
}

assertReleasePackage();

const sdk = Number(shell('getprop ro.build.version.sdk'));
const model = shell('getprop ro.product.model');
if (sdk < 33) throw new Error('Renderer matrix requires API 33+ for AGSL/HWUI-scaled backends.');

const runId = timestamp();
console.log(`Gallery HWUI scaled matrix / ${model} / API ${sdk}`);
console.log(`radius=${options.radiusPx}px / image=${options.image} / samples=${options.samples}`);
console.log(`paired refresh blocks / tolerance=±${options.refreshToleranceHz}Hz / max attempts=${options.maxAttempts}`);

const results = [];
const rejectedBlocks = [];
for (let sample = 0; sample < options.samples; sample++) {
  let accepted = false;

  for (let attempt = 1; attempt <= options.maxAttempts; attempt++) {
    targetRefreshHz = null;
    const order = sample % 2 === 0
      ? ['agsl', 'hwui-scaled']
      : ['hwui-scaled', 'agsl'];
    const block = [];

    console.log(`\n=== Sample ${sample + 1}, block attempt ${attempt}/${options.maxAttempts} ===`);
    for (const renderer of order) {
      block.push(runCase(renderer, sample + 1, runId, sample === 0 && attempt === 1));
    }

    if (block.every((row) => row.valid !== false)) {
      results.push(...block);
      accepted = true;
      console.log(`Accepted sample ${sample + 1} @ ${targetRefreshHz ?? 'unknown'}Hz`);
      break;
    }

    rejectedBlocks.push(block);
    console.log(`Rejected sample ${sample + 1}: refresh mismatch; retrying whole block.`);
    sleep(Math.max(2500, options.cooldownMs));
  }

  if (!accepted) {
    throw new Error(
      `Could not obtain a refresh-matched sample ${sample + 1} after ${options.maxAttempts} attempts.`
    );
  }
}
const aggregateByRenderer = Object.fromEntries(
  RENDERERS.map((renderer) => [renderer, aggregate(results, renderer)])
);
const agsl = aggregateByRenderer.agsl;
const hwui = aggregateByRenderer['hwui-scaled'];
const deltaHwuiVsAgsl = {
  jankDeltaPctPoints:
    agsl.weightedJankPercent == null || hwui.weightedJankPercent == null
      ? null
      : hwui.weightedJankPercent - agsl.weightedJankPercent,
  p95DeltaMs:
    agsl.medianP95Ms == null || hwui.medianP95Ms == null
      ? null
      : hwui.medianP95Ms - agsl.medianP95Ms,
  p99DeltaMs:
    agsl.medianP99Ms == null || hwui.medianP99Ms == null
      ? null
      : hwui.medianP99Ms - agsl.medianP99Ms,
  deadlineDelta: hwui.deadlineMissed - agsl.deadlineMissed,
};

const report = {
  runId,
  device: { model, sdk, targetRefreshHz },
  options,
  results,
  rejectedBlocks,
  aggregate: aggregateByRenderer,
  deltaHwuiVsAgsl,
};
const reportPath = path.join(OUT_DIR, `${runId}-report.json`);
fs.writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);

console.log('\n=== Aggregate (valid refresh-matched samples only) ===');
for (const renderer of RENDERERS) {
  const row = aggregateByRenderer[renderer];
  console.log(
    `${renderer.padEnd(8)} valid=${row.samples} invalid=${row.invalidSamples} ` +
      `jank=${row.weightedJankPercent?.toFixed(2) ?? 'n/a'}% ` +
      `p95=${row.medianP95Ms ?? 'n/a'}ms p99=${row.medianP99Ms ?? 'n/a'}ms ` +
      `deadline=${row.deadlineMissed}`
  );
}
console.log(
  `HWUI vs AGSL: Δp95=${deltaHwuiVsAgsl.p95DeltaMs ?? 'n/a'}ms ` +
    `Δp99=${deltaHwuiVsAgsl.p99DeltaMs ?? 'n/a'}ms ` +
    `Δjank=${deltaHwuiVsAgsl.jankDeltaPctPoints?.toFixed(2) ?? 'n/a'}pp ` +
    `Δdeadline=${deltaHwuiVsAgsl.deadlineDelta}`
);
console.log(`Report: ${reportPath}`);
console.log('First-sample screenshots and raw gfxinfo are saved beside the report.');
