#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DEFAULT_OUT_DIR = path.join(ROOT, 'benchmark-results', 'gallery-main-perfetto');

function parseArgs(argv) {
  const options = {
    serial: '',
    effect: 'both',
    radiusPx: 80,
    cycleMs: 4200,
    durationMs: 12000,
    warmupMs: 3000,
    cooldownMs: 2500,
    outDir: DEFAULT_OUT_DIR,
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const take = () => {
      if (i + 1 >= argv.length) throw new Error(`Missing value after ${arg}`);
      return argv[++i];
    };
    if (arg === '--serial') options.serial = take();
    else if (arg === '--effect') options.effect = take();
    else if (arg === '--radius-px') options.radiusPx = Number(take());
    else if (arg === '--cycle-ms') options.cycleMs = Number(take());
    else if (arg === '--duration-ms') options.durationMs = Number(take());
    else if (arg === '--warmup-ms') options.warmupMs = Number(take());
    else if (arg === '--cooldown-ms') options.cooldownMs = Number(take());
    else if (arg === '--out-dir') options.outDir = path.resolve(take());
    else throw new Error(`Unknown argument: ${arg}`);
  }

  if (!['both', 'on', 'off'].includes(options.effect)) {
    throw new Error('--effect must be both, on or off');
  }
  if (!Number.isFinite(options.radiusPx) || options.radiusPx < 1 || options.radiusPx > 150) {
    throw new Error('--radius-px must be in 1..150');
  }
  if (!Number.isFinite(options.cycleMs) || options.cycleMs < 1000) {
    throw new Error('--cycle-ms must be >= 1000');
  }
  if (!Number.isFinite(options.durationMs) || options.durationMs < 5000) {
    throw new Error('--duration-ms must be >= 5000');
  }
  if (!Number.isFinite(options.warmupMs) || options.warmupMs < 1200) {
    throw new Error('--warmup-ms must be >= 1200');
  }
  return options;
}

const options = parseArgs(process.argv.slice(2));
fs.mkdirSync(options.outDir, { recursive: true });

function adbArgs(args) {
  return options.serial ? ['-s', options.serial, ...args] : args;
}

function adb(args, { trim = true, input = undefined } = {}) {
  const result = spawnSync('adb', adbArgs(args), {
    encoding: 'utf8',
    input,
    maxBuffer: 64 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${result.stdout ?? ''}${result.stderr ?? ''}`);
  }
  const output = `${result.stdout ?? ''}${result.stderr ?? ''}`;
  return trim ? output.trim() : output;
}

function shell(command, options = {}) {
  return adb(['shell', command], options);
}

function sleep(ms) {
  if (ms <= 0) return;
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function timestamp() {
  return new Date().toISOString().replace(/[:.]/g, '-');
}

function assertReleasePackage() {
  const packagePath = adb(['shell', 'pm', 'path', PACKAGE], { trim: false });
  if (!/^package:/m.test(packagePath)) throw new Error(`${PACKAGE} is not installed.`);
  const dump = adb(['shell', 'dumpsys', 'package', PACKAGE], { trim: false });
  if (/pkgFlags=\[[^\]]*DEBUGGABLE/m.test(dump)) {
    throw new Error(`${PACKAGE} is debuggable. Install the release variant before tracing.`);
  }
}

function batteryTemp() {
  const dump = adb(['shell', 'dumpsys', 'battery'], { trim: false });
  const match = dump.match(/temperature:\s*(\d+)/);
  return match ? Number(match[1]) / 10 : null;
}

function refreshRate() {
  const display = adb(['shell', 'dumpsys', 'display'], { trim: false });
  const values = [...display.matchAll(/(?:mRefreshRate|refreshRate)\s*[=:]\s*([\d.]+)/g)]
    .map((match) => Number(match[1]))
    .filter(Number.isFinite);
  return values.length ? values[0] : null;
}

function launch(effectEnabled) {
  shell(`am force-stop ${PACKAGE}`);
  sleep(400);
  const effect = effectEnabled ? 'on' : 'off';
  const uri =
    `edgefade:///?stress=auto&effect=${effect}` +
    `&radiusPx=${options.radiusPx}&cycleMs=${options.cycleMs}`;
  const result = shell(
    `am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PACKAGE}`,
    { trim: false }
  );
  if (!/Status:\s*ok/.test(result)) throw new Error(`Launch failed for ${effect}.`);
  sleep(options.warmupMs);
  const pid = shell(`pidof ${PACKAGE}`);
  if (!pid) throw new Error(`${PACKAGE} is not alive after warm-up.`);
}

function perfettoConfig() {
  return `
buffers: {
  size_kb: 65536
  fill_policy: RING_BUFFER
}
data_sources: {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "wm"
      atrace_categories: "am"
      atrace_categories: "input"
      atrace_categories: "binder_driver"
      atrace_categories: "hal"
      atrace_apps: "${PACKAGE}"
    }
  }
}
data_sources: {
  config {
    name: "android.surfaceflinger.frametimeline"
  }
}
duration_ms: ${Math.round(options.durationMs)}
flush_period_ms: 1000
`;
}

function capture(effectEnabled, device) {
  const label = effectEnabled ? 'main' : 'off';
  console.log(`\n=== Main Gallery Perfetto: ${label} ===`);
  launch(effectEnabled);

  const tempStartC = batteryTemp();
  const refreshStartHz = refreshRate();
  adb(['shell', 'dumpsys', 'gfxinfo', PACKAGE, 'reset']);

  const id = `${process.pid}-${Date.now()}`;
  const remoteTrace = `/data/misc/perfetto-traces/edgefade-main-${label}-${id}.perfetto-trace`;
  const base = `${timestamp()}-${label}-${options.radiusPx}px`;
  const tracePath = path.join(options.outDir, `${base}.perfetto-trace`);
  const gfxPath = path.join(options.outDir, `${base}-gfxinfo.txt`);
  const frameStatsPath = path.join(options.outDir, `${base}-framestats.txt`);
  const metadataPath = path.join(options.outDir, `${base}-metadata.json`);

  try {
    try { adb(['shell', 'rm', '-f', remoteTrace]); } catch {}
    adb(
      ['shell', 'perfetto', '--txt', '-c', '-', '-o', remoteTrace],
      { input: perfettoConfig(), trim: false }
    );

    const gfx = adb(['shell', 'dumpsys', 'gfxinfo', PACKAGE], { trim: false });
    const frameStats = adb(['shell', 'dumpsys', 'gfxinfo', PACKAGE, 'framestats'], { trim: false });
    fs.writeFileSync(gfxPath, gfx);
    fs.writeFileSync(frameStatsPath, frameStats);
    adb(['pull', remoteTrace, tracePath]);

    const metadata = {
      branch: 'benchmark/gallery-main-baseline',
      renderer: label,
      radiusPx: options.radiusPx,
      cycleMs: options.cycleMs,
      durationMs: options.durationMs,
      device,
      temperatureC: { start: tempStartC, end: batteryTemp() },
      refreshHz: { start: refreshStartHz, end: refreshRate() },
    };
    fs.writeFileSync(metadataPath, `${JSON.stringify(metadata, null, 2)}\n`);

    console.log(`Trace: ${tracePath}`);
    console.log(`Metadata: ${metadataPath}`);
    return tracePath;
  } finally {
    try { shell(`am force-stop ${PACKAGE}`); } catch {}
    try { adb(['shell', 'rm', '-f', remoteTrace]); } catch {}
  }
}

assertReleasePackage();

const device = {
  model: adb(['shell', 'getprop', 'ro.product.model']),
  sdk: Number(adb(['shell', 'getprop', 'ro.build.version.sdk'])),
  size: adb(['shell', 'wm', 'size']),
  density: adb(['shell', 'wm', 'density']),
};

console.log(`Main Gallery Perfetto / ${device.model} / API ${device.sdk}`);
console.log(`radius=${options.radiusPx}px / cycle=${options.cycleMs}ms / duration=${options.durationMs}ms`);

const cases = options.effect === 'both'
  ? [false, true]
  : [options.effect === 'on'];
const traces = [];
for (let i = 0; i < cases.length; i++) {
  traces.push(capture(cases[i], device));
  if (i < cases.length - 1) sleep(options.cooldownMs);
}

console.log('\nCapture complete. Inspect these slices in the MAIN trace:');
console.log('  EdgeFade.dispatchDraw');
console.log('  EdgeFade.blur');
for (const trace of traces) console.log(`  ${trace}`);
