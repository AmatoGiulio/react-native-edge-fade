#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const DEFAULT_OUT_DIR = path.join(ROOT, 'benchmark-results', 'gallery-perfetto');

function parseArgs(argv) {
  const options = {
    serial: '',
    effect: 'both',
    imageRenderer: 'expo',
    radiusPx: 140,
    cycleMs: 4200,
    durationMs: 12000,
    warmupMs: 3000,
    cooldownMs: 2000,
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
      case '--effect': options.effect = value(); break;
      case '--image-renderer': options.imageRenderer = value(); break;
      case '--radius-px': options.radiusPx = Number(value()); break;
      case '--cycle-ms': options.cycleMs = Number(value()); break;
      case '--duration-ms': options.durationMs = Number(value()); break;
      case '--warmup-ms': options.warmupMs = Number(value()); break;
      case '--cooldown-ms': options.cooldownMs = Number(value()); break;
      case '--out-dir': options.outDir = path.resolve(value()); break;
      default: throw new Error(`Unknown argument: ${arg}`);
    }
  }

  if (!['both', 'on', 'off'].includes(options.effect)) {
    throw new Error('--effect must be both, on or off');
  }
  if (!['expo', 'native', 'solid'].includes(options.imageRenderer)) {
    throw new Error('--image-renderer must be expo, native or solid');
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
  if (!Number.isFinite(options.cooldownMs) || options.cooldownMs < 0) {
    throw new Error('--cooldown-ms must be >= 0');
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

function timestamp() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}

function assertInstalledReleasePackage() {
  const packagePath = adb(['shell', 'pm', 'path', PACKAGE]);
  if (!/^package:/m.test(packagePath)) {
    throw new Error(`${PACKAGE} is not installed.`);
  }
  const dump = adb(['shell', 'dumpsys', 'package', PACKAGE], { trim: false });
  if (/pkgFlags=\[[^\]]*DEBUGGABLE/m.test(dump)) {
    throw new Error(`${PACKAGE} is debuggable. Install the release variant before tracing.`);
  }
}

function ensureBackendActivation(effectEnabled, sdk) {
  const logcat = adb(['logcat', '-d'], { trim: false });
  if (!effectEnabled) {
    const identity = 'Blur identity active: blurRadius 0px (no blur, no Mask fallback).';
    if (!logcat.includes(identity)) {
      throw new Error('0px identity activation was not observed; refusing to trace the wrong baseline.');
    }
    return;
  }

  const activation = sdk >= 33
    ? 'Using pure progressive AGSL blur on API 33+ (direct edge-local dispatch).'
    : 'Using GLES 3.0 continuous progressive blur on API 31-32';
  if (!logcat.includes(activation)) {
    throw new Error(`Progressive backend activation was not observed for API ${sdk}.`);
  }
  if (/Progressive unavailable; using mask fallback|progressive draw failed|progressive frame unavailable/i.test(logcat)) {
    throw new Error('A progressive fallback/failure was observed during warm-up.');
  }
}

function launch(effectEnabled, sdk) {
  adb(['logcat', '-c']);
  adbShell(`am force-stop ${PACKAGE}`);
  sleep(400);

  const effect = effectEnabled ? 'on' : 'off';
  const uri = `edgefade:///?stress=auto&effect=${effect}&radiusPx=${options.radiusPx}&cycleMs=${options.cycleMs}&image=${options.imageRenderer}`;
  const result = adbShell(
    `am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PACKAGE}`,
    { trim: false }
  );
  if (!/Status:\s*ok/.test(result)) {
    throw new Error(`Gallery stress route failed to launch:\n${result}`);
  }

  sleep(options.warmupMs);
  ensureBackendActivation(effectEnabled, sdk);
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

function captureCase(effectEnabled, sdk) {
  const label = effectEnabled ? 'blur' : 'baseline';
  console.log(`\n=== Gallery Perfetto: ${label} / ${options.imageRenderer} ===`);
  launch(effectEnabled, sdk);

  adb(['shell', 'dumpsys', 'gfxinfo', PACKAGE, 'reset']);

  const id = `${process.pid}-${Date.now()}`;
  const remoteTrace = `/data/misc/perfetto-traces/edgefade-gallery-${label}-${id}.perfetto-trace`;
  const fileBase = `${timestamp()}-${options.imageRenderer}-${label}-${options.radiusPx}px`;
  const localTrace = path.join(options.outDir, `${fileBase}.perfetto-trace`);
  const gfxPath = path.join(options.outDir, `${fileBase}-gfxinfo.txt`);
  const frameStatsPath = path.join(options.outDir, `${fileBase}-framestats.txt`);

  try {
    try { adb(['shell', 'rm', '-f', remoteTrace]); } catch {}

    console.log(`Capturing ${options.durationMs}ms deterministic auto-scroll...`);
    adb(
      ['shell', 'perfetto', '--txt', '-c', '-', '-o', remoteTrace],
      { input: perfettoConfig(), trim: false }
    );

    const gfx = adb(['shell', 'dumpsys', 'gfxinfo', PACKAGE], { trim: false });
    const frameStats = adb(
      ['shell', 'dumpsys', 'gfxinfo', PACKAGE, 'framestats'],
      { trim: false }
    );
    fs.writeFileSync(gfxPath, gfx);
    fs.writeFileSync(frameStatsPath, frameStats);

    adb(['pull', remoteTrace, localTrace]);
    console.log(`Trace: ${localTrace}`);
    console.log(`gfxinfo: ${gfxPath}`);
    console.log(`framestats: ${frameStatsPath}`);
    return localTrace;
  } finally {
    try { adbShell(`am force-stop ${PACKAGE}`); } catch {}
    try { adb(['shell', 'rm', '-f', remoteTrace]); } catch {}
  }
}

assertInstalledReleasePackage();

const model = adb(['shell', 'getprop', 'ro.product.model']);
const sdk = Number(adb(['shell', 'getprop', 'ro.build.version.sdk']));
const qemu = adb(['shell', 'getprop', 'ro.kernel.qemu']);
const size = adb(['shell', 'wm', 'size']);
const density = adb(['shell', 'wm', 'density']);
const isEmulator = qemu === '1' || /sdk_gphone|emulator/i.test(model);

if (!Number.isFinite(sdk) || sdk < 31) {
  throw new Error(`Gallery progressive Perfetto capture requires API 31+; device is API ${sdk}.`);
}

console.log('Gallery deterministic Perfetto capture');
console.log(`Device: ${model} / API ${sdk}${isEmulator ? ' / emulator' : ' / physical'}`);
console.log(`Display: ${size.replace(/\s+/g, ' ')} / ${density.replace(/\s+/g, ' ')}`);
console.log(`Renderer: ${options.imageRenderer}`);
console.log(`Workload: triangular auto-scroll / cycle=${options.cycleMs}ms`);
console.log(`Blur target: ${options.radiusPx}px / top+bottom 110dp`);

const conditions = options.effect === 'both'
  ? [false, true]
  : [options.effect === 'on'];
const traces = [];
for (let i = 0; i < conditions.length; i++) {
  traces.push(captureCase(conditions[i], sdk));
  if (i < conditions.length - 1 && options.cooldownMs > 0) {
    sleep(options.cooldownMs);
  }
}

console.log('\nCapture complete.');
for (const trace of traces) console.log(`  ${trace}`);
if (sdk >= 33) {
  console.log('For the blur trace inspect:');
  console.log('  EdgeFade.dispatchDraw');
  console.log('  EdgeFade.progressive.strip.draw');
  console.log('  EdgeFade.progressive.prepare');
  console.log('  EdgeFade.progressive.recordContent');
  console.log('  EdgeFade.progressive.drawSharp');
  console.log('  EdgeFade.progressive.recordStrip.top / bottom');
  console.log('  EdgeFade.progressive.drawStrip.top / bottom');
} else {
  console.log('For the blur trace inspect EdgeFade.progressive.gles.* slices.');
}
