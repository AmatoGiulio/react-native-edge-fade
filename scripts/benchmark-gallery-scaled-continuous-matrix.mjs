#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT_DIR = path.join(ROOT, 'benchmark-results', 'scaled-continuous');
const RENDERERS = ['off', 'scaled-continuous', 'agsl', 'androidx'];

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
  if (renderer === 'off') return;

  let lastDiagnostic = '';

  for (let attempt = 1; attempt <= 8; attempt++) {
    const logs = rendererLogs();

    if (renderer === 'public') {
      const activated =
        logs.includes('Using official AndroidX progressive blur on API 33+') ||
        logs.includes('Using pure progressive AGSL blur on API 33+') ||
        logs.includes('Using GLES 3.0 continuous progressive blur on API 31-32');
      if (activated) return;

      lastDiagnostic = `attempt ${attempt}: public progressive activation log not found`;
      if (/Progressive unavailable|draw failed|frame unavailable|fallback/i.test(logs)) {
        throw new Error(
          `Public renderer failed during activation.\n${logs.trim()}`
        );
      }
    } else {
      const expected = `Renderer active: requested=${renderer} active=${renderer}`;
      if (logs.includes(expected)) return;

      const disabled = `Renderer active: requested=${renderer} active=off`;
      if (logs.includes(disabled)) {
        throw new Error(
          `${renderer} renderer was requested but native reported active=off.\n${logs.trim()}`
        );
      }
      lastDiagnostic = `attempt ${attempt}: missing '${expected}'`;
    }

    sleep(350);
  }

  const logs = rendererLogs();
  throw new Error(
    `Renderer verification failed for ${renderer}.\n${lastDiagnostic}` +
      (logs.trim() ? `\n${logs.trim()}` : '')
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

  if (renderer === 'off' && targetRefreshHz == null && stableWithinCase) {
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
if (sdk < 33) throw new Error('Renderer matrix requires API 33+ for AGSL/AndroidX Lab backends.');

const runId = timestamp();
console.log(`Scaled continuous matrix / ${model} / API ${sdk}`);
console.log(`radius=${options.radiusPx}px / image=${options.image} / samples=${options.samples}`);
console.log(`refresh guard tolerance=±${options.refreshToleranceHz}Hz; first stable OFF case sets baseline`);

const results = [];
for (let sample = 0; sample < options.samples; sample++) {
  const order = sample % 2 === 0 ? RENDERERS : [...RENDERERS].reverse();
  for (const renderer of order) {
    results.push(runCase(renderer, sample + 1, runId, sample === 0));
  }
}

const aggregateByRenderer = Object.fromEntries(
  RENDERERS.map((renderer) => [renderer, aggregate(results, renderer)])
);
const baseline = aggregateByRenderer.off;
const deltas = Object.fromEntries(
  RENDERERS.filter((renderer) => renderer !== 'off').map((renderer) => {
    const current = aggregateByRenderer[renderer];
    return [
      renderer,
      {
        jankDeltaPctPoints:
          baseline.weightedJankPercent == null || current.weightedJankPercent == null
            ? null
            : current.weightedJankPercent - baseline.weightedJankPercent,
        p95DeltaMs:
          baseline.medianP95Ms == null || current.medianP95Ms == null
            ? null
            : current.medianP95Ms - baseline.medianP95Ms,
        p99DeltaMs:
          baseline.medianP99Ms == null || current.medianP99Ms == null
            ? null
            : current.medianP99Ms - baseline.medianP99Ms,
        deadlineDelta: current.deadlineMissed - baseline.deadlineMissed,
      },
    ];
  })
);

const report = {
  runId,
  device: { model, sdk, targetRefreshHz },
  options,
  results,
  aggregate: aggregateByRenderer,
  deltasVsOff: deltas,
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
console.log(`Report: ${reportPath}`);
console.log('First-sample screenshots and raw gfxinfo are saved beside the report.');
