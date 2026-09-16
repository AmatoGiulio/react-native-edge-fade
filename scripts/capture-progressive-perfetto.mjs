#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PUBLIC_PACKAGE = 'com.edgefadeexample';
const ANDROIDX_PACKAGE = 'com.edgefade.androidxref';
const ANDROIDX_ACTIVITY = 'com.edgefade.androidxref/.BenchmarkActivity';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const RESULTS_DIR = path.join(ROOT, 'benchmark-results', 'perfetto');
fs.mkdirSync(RESULTS_DIR, { recursive: true });

function parseArgs(argv) {
  const options = {
    renderer: 'both',
    edges: 'vertical',
    radiusPx: 98,
    durationMs: 12000,
    serial: '',
    allowEmulator: false,
    outDir: RESULTS_DIR,
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const value = () => {
      if (i + 1 >= argv.length) throw new Error(`Missing value after ${arg}`);
      return argv[++i];
    };
    switch (arg) {
      case '--renderer': options.renderer = value(); break;
      case '--edges': options.edges = value(); break;
      case '--radius-px': options.radiusPx = Number(value()); break;
      case '--duration-ms': options.durationMs = Number(value()); break;
      case '--serial': options.serial = value(); break;
      case '--allow-emulator': options.allowEmulator = true; break;
      case '--out-dir': options.outDir = path.resolve(value()); break;
      // Backward-compatible no-ops. Both renderers now use the same in-app
      // deterministic workload; adb swipe counts are intentionally ignored.
      case '--warmup-swipes': value(); break;
      case '--swipes': value(); break;
      case '--swipe-duration-ms': value(); break;
      default: throw new Error(`Unknown argument: ${arg}`);
    }
  }

  if (!['public', 'androidx', 'both'].includes(options.renderer)) {
    throw new Error('--renderer must be public, androidx or both');
  }
  if (!['vertical', 'four'].includes(options.edges)) {
    throw new Error('--edges must be vertical or four');
  }
  if (!Number.isFinite(options.radiusPx) || options.radiusPx < 1 || options.radiusPx > 150) {
    throw new Error('--radius-px must be in 1..150');
  }
  if (!Number.isFinite(options.durationMs) || options.durationMs < 3000) {
    throw new Error('--duration-ms must be >= 3000');
  }
  return options;
}

const options = parseArgs(process.argv.slice(2));
fs.mkdirSync(options.outDir, { recursive: true });

function adbArgs(args) {
  return options.serial ? ['-s', options.serial, ...args] : args;
}

function adb(args, { trim = true } = {}) {
  const result = spawnSync('adb', adbArgs(args), { encoding: 'utf8' });
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

function round(value, digits = 3) {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
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
    throw new Error(`${label} package ${pkg} is debuggable. Install the release variant before tracing.`);
  }
}

const model = adb(['shell', 'getprop', 'ro.product.model']);
const sdk = Number(adb(['shell', 'getprop', 'ro.build.version.sdk']));
const qemu = adb(['shell', 'getprop', 'ro.kernel.qemu']);
const isEmulator = qemu === '1' || /sdk_gphone|emulator/i.test(model);
if (sdk < 31) throw new Error(`Continuous progressive blur trace requires API 31+; device is API ${sdk}.`);
if (sdk < 33 && options.renderer !== 'public') {
  throw new Error(
    `AndroidX Official comparison requires API 33+. On API ${sdk}, use --renderer public to trace the GLES backend.`
  );
}
if (isEmulator && !options.allowEmulator) {
  throw new Error(`Detected an Android emulator (${model}). Use a physical device or pass --allow-emulator for smoke traces.`);
}

if (options.renderer === 'public' || options.renderer === 'both') {
  assertInstalledReleasePackage(PUBLIC_PACKAGE, 'Public Edge Fade');
}
if (options.renderer === 'androidx' || options.renderer === 'both') {
  assertInstalledReleasePackage(ANDROIDX_PACKAGE, 'AndroidX reference');
}

const sizeText = adb(['shell', 'wm', 'size']);
const sizeMatches = [...sizeText.matchAll(/(\d+)x(\d+)/g)];
if (!sizeMatches.length) throw new Error(`Could not parse device size from: ${sizeText}`);
const size = sizeMatches.at(-1);
const width = Number(size[1]);
const height = Number(size[2]);

const densityText = adb(['shell', 'wm', 'density']);
const overrideDensity = densityText.match(/Override density:\s*(\d+)/);
const physicalDensity = densityText.match(/Physical density:\s*(\d+)/);
const densityDpi = Number(overrideDensity?.[1] ?? physicalDensity?.[1]);
if (!densityDpi) throw new Error(`Could not parse device density from: ${densityText}`);
const densityScale = densityDpi / 160;
const radiusDp = options.radiusPx / densityScale;
const radiusInvariant = String(Number(options.radiusPx.toFixed(3)));

console.log(`Device: ${model} / API ${sdk} / ${width}x${height} / ${densityDpi}dpi (${round(densityScale)}x)`);
console.log(`Trace scene: ${round(radiusDp, 2)}dp / ${options.radiusPx}px / Smooth / ${options.edges}`);
console.log(`Capture: ${options.durationMs}ms / deterministic in-app auto-scroll for every renderer`);
console.log(`Public backend: ${sdk >= 33 ? 'AGSL API 33+' : 'GLES 3.0 API 31-32'}`);

function stopBothApps() {
  adb(['shell', 'am', 'force-stop', PUBLIC_PACKAGE]);
  if (sdk >= 33 && (options.renderer === 'androidx' || options.renderer === 'both')) {
    adb(['shell', 'am', 'force-stop', ANDROIDX_PACKAGE]);
  }
}

function startRenderer(renderer) {
  stopBothApps();
  sleep(400);

  if (renderer === 'public') {
    adb(['logcat', '-c']);
    const uri = `edgefade://progressive-blur-perf?edges=${options.edges}&radiusPx=${radiusInvariant}&effect=on&workload=auto`;
    adb(['shell', `am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PUBLIC_PACKAGE}`]);
    sleep(1800);
    const logcat = adb(['logcat', '-d'], { trim: false });
    const activation = sdk >= 33
      ? 'EdgeFadeProgressive: Using pure progressive AGSL blur on API 33+'
      : 'EdgeFadeProgressive: Using GLES 3.0 continuous progressive blur on API 31-32';
    if (!logcat.includes(activation)) {
      throw new Error(
        `Public Progressive activation was not observed for ${sdk >= 33 ? 'AGSL' : 'GLES'}; refusing to capture the wrong backend.`
      );
    }
  } else {
    adb([
      'shell', 'am', 'start', '-W', '-n', ANDROIDX_ACTIVITY,
      '--es', 'edges', options.edges,
      '--ef', 'radiusPx', radiusInvariant,
      '--es', 'curve', 'smooth',
      '--es', 'effect', 'on',
      '--es', 'workload', 'auto',
    ]);
    sleep(1800);
  }
}

function resetFrameStats(pkg) {
  adb(['shell', 'dumpsys', 'gfxinfo', pkg, 'reset']);
}

function recentFrameActivity(pkg) {
  const dump = adb(['shell', 'dumpsys', 'gfxinfo', pkg, 'framestats'], { trim: false });
  const intendedVsyncNs = [];
  let header = null;
  let intendedIndex = -1;
  let waitingForHeader = false;

  for (const rawLine of dump.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line === '---PROFILEDATA---') {
      if (header !== null) {
        header = null;
        intendedIndex = -1;
        waitingForHeader = false;
      } else {
        waitingForHeader = true;
      }
      continue;
    }
    if (waitingForHeader && line.startsWith('Flags,')) {
      header = line.split(',');
      intendedIndex = header.indexOf('IntendedVsync');
      waitingForHeader = false;
      continue;
    }
    if (header !== null && intendedIndex >= 0 && /^\d+,/.test(line)) {
      const parts = line.split(',');
      const intended = Number(parts[intendedIndex]);
      if (Number.isFinite(intended) && intended > 0) intendedVsyncNs.push(intended);
    }
  }

  const unique = [...new Set(intendedVsyncNs)].sort((a, b) => a - b);
  const spanMs = unique.length > 1 ? (unique.at(-1) - unique[0]) / 1_000_000 : 0;
  return { frames: unique.length, spanMs };
}

function perfettoConfig(pkg) {
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
      atrace_apps: "${pkg}"
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

function waitForChild(child) {
  return new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      if (code === 0) resolve();
      else reject(new Error(`perfetto exited with code ${code ?? 'null'} signal ${signal ?? 'null'}`));
    });
  });
}

async function capture(renderer) {
  const pkg = renderer === 'public' ? PUBLIC_PACKAGE : ANDROIDX_PACKAGE;
  console.log(`\n=== Perfetto: ${renderer} / ${options.edges} / ${options.radiusPx}px ===`);
  console.log('Workload: deterministic in-app auto-scroll');

  startRenderer(renderer);
  // Both apps have already been animating during startup. Reset stats only
  // after the workload has reached steady state.
  sleep(500);
  resetFrameStats(pkg);

  const id = `${process.pid}-${Date.now()}`;
  const remoteTrace = `/data/misc/perfetto-traces/edgefade-${renderer}-${id}.perfetto-trace`;
  const localTrace = path.join(
    options.outDir,
    `${timestamp()}-${renderer}-${options.edges}-${String(options.radiusPx).replace('.', 'p')}px.perfetto-trace`,
  );

  try {
    try { adb(['shell', 'rm', '-f', remoteTrace]); } catch {}

    const perfetto = spawn(
      'adb',
      adbArgs(['shell', 'perfetto', '--txt', '-c', '-', '-o', remoteTrace]),
      { stdio: ['pipe', 'inherit', 'inherit'] },
    );
    const perfettoDone = waitForChild(perfetto);
    perfetto.stdin.end(perfettoConfig(pkg));
    await perfettoDone;

    const activity = recentFrameActivity(pkg);
    console.log(`Steady-state diagnostic: ${activity.frames} recent gfx frames / ${round(activity.spanMs, 1)}ms recent-buffer span`);
    if (activity.frames < 30 || activity.spanMs < 1000) {
      throw new Error(
        `${renderer} did not produce a sustained rendering workload during the trace ` +
        `(${activity.frames} frames / ${round(activity.spanMs, 1)}ms). Refusing an invalid comparison.`
      );
    }

    adb(['pull', remoteTrace, localTrace]);
    console.log(`Trace: ${localTrace}`);
    if (renderer === 'public') {
      if (sdk >= 33) {
        console.log('Public AGSL slices: EdgeFade.progressive.recordContent / drawSharp / recordStrip.* / drawStrip.*');
      } else {
        console.log(
          'Public GLES slices: EdgeFade.progressive.gles.draw / recordContent / render / source / horizontal / vertical / drawSharp / drawOutput'
        );
      }
    }
    return localTrace;
  } finally {
    try { adb(['shell', 'rm', '-f', remoteTrace]); } catch {}
  }
}

const renderers = options.renderer === 'both' ? ['public', 'androidx'] : [options.renderer];
const traces = [];
for (const renderer of renderers) {
  traces.push(await capture(renderer));
  sleep(1500);
}

console.log('\nPerfetto capture complete.');
for (const trace of traces) console.log(`  ${trace}`);
console.log('Both traces used the same deterministic 4.2s triangular auto-scroll workload.');
