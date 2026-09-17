#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { requireProgressiveBackend } from './progressive-backend.mjs';

const PACKAGE = 'com.edgefadeexample';
const ADB_MAX_BUFFER = 64 * 1024 * 1024;
const ZERO_RADIUS_IDENTITY_LOG =
  'Blur identity active: blurRadius 0px (no blur, no Mask fallback).';

function parseArgs(argv) {
  const options = {
    serial: '',
    edges: 'vertical',
    swipes: 10,
    settleMs: 5200,
    allowEmulator: false,
    expectedBackend: 'auto',
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const value = () => {
      if (i + 1 >= argv.length) throw new Error(`Missing value after ${arg}`);
      return argv[++i];
    };
    switch (arg) {
      case '--serial':
        options.serial = value();
        break;
      case '--expected-backend':
        options.expectedBackend = value();
        break;
      case '--edges':
        options.edges = value();
        break;
      case '--swipes':
        options.swipes = Number(value());
        break;
      case '--settle-ms':
        options.settleMs = Number(value());
        break;
      case '--allow-emulator':
        options.allowEmulator = true;
        break;
      default:
        throw new Error(`Unknown argument: ${arg}`);
    }
  }
  if (!['vertical', 'four'].includes(options.edges)) {
    throw new Error('--edges must be vertical or four');
  }
  if (!['auto', 'agsl', 'androidx', 'gles'].includes(options.expectedBackend)) {
    throw new Error('--expected-backend must be auto, agsl, androidx or gles');
  }
  if (!Number.isFinite(options.swipes) || options.swipes < 1) {
    throw new Error('--swipes must be >= 1');
  }
  if (!Number.isFinite(options.settleMs) || options.settleMs < 4500) {
    throw new Error('--settle-ms must be >= 4500 so the full radius cycle executes');
  }
  return options;
}

const options = parseArgs(process.argv.slice(2));

function adbArgs(args) {
  return options.serial ? ['-s', options.serial, ...args] : args;
}

function adb(args, { trim = true, allowFailure = false } = {}) {
  const result = spawnSync('adb', adbArgs(args), {
    encoding: 'utf8',
    maxBuffer: ADB_MAX_BUFFER,
  });
  if (result.error) throw result.error;
  const output = `${result.stdout ?? ''}${result.stderr ?? ''}`;
  if (result.status !== 0 && !allowFailure) {
    const prefix = options.serial ? `adb -s ${options.serial}` : 'adb';
    throw new Error(`${prefix} ${args.join(' ')} failed:\n${output}`);
  }
  return trim ? output.trim() : output;
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function shell(command, config) {
  return adb(['shell', command], config);
}

function relevantLogcat() {
  return adb(
    [
      'logcat',
      '-d',
      '-v',
      'brief',
      'EdgeFadeProgressive:V',
      'AndroidRuntime:E',
      'libc:F',
      'DEBUG:E',
      '*:S',
    ],
    { trim: false }
  );
}

function failureExcerpt(logcat) {
  const lines = logcat.split(/\r?\n/);
  const markers = [
    'GLES progressive renderer creation failed',
    'GLES progressive configuration failed',
    'GLES progressive frame unavailable',
    'GLES progressive draw failed',
    'Progressive strip shader creation failed',
    'Progressive strip configuration failed',
    'Progressive strip draw failed',
    'FATAL EXCEPTION',
    'Fatal signal',
  ];
  const first = lines.findIndex((line) => markers.some((marker) => line.includes(marker)));
  if (first < 0) return '';
  return lines.slice(first, Math.min(lines.length, first + 24)).join('\n').trim();
}

const model = shell('getprop ro.product.model');
const sdk = Number(shell('getprop ro.build.version.sdk'));
const qemu = shell('getprop ro.kernel.qemu');
const isEmulator = qemu === '1' || /sdk_gphone|emulator/i.test(model);
if (isEmulator && !options.allowEmulator) {
  throw new Error(
    `Detected emulator ${model}. Use a physical device or pass --allow-emulator for functional smoke only.`
  );
}

const packagePath = shell(`pm path ${PACKAGE}`);
if (!packagePath.startsWith('package:')) {
  throw new Error(`${PACKAGE} is not installed.`);
}
const packageDump = shell(`dumpsys package ${PACKAGE}`, { trim: false });
if (/pkgFlags=\[[^\]]*DEBUGGABLE/m.test(packageDump)) {
  throw new Error('Release smoke requires a non-debuggable example build.');
}

const sizeText = shell('wm size');
const sizeMatches = [...sizeText.matchAll(/(\d+)x(\d+)/g)];
if (!sizeMatches.length) throw new Error(`Could not parse wm size: ${sizeText}`);
const [, widthText, heightText] = sizeMatches.at(-1);
const width = Number(widthText);
const height = Number(heightText);
const x = Math.round(width * 0.5);
const yTop = Math.round(height * 0.34);
const yBottom = Math.round(height * 0.78);

const accelerometerRotation =
  shell('settings get system accelerometer_rotation', { allowFailure: true }) || '1';
const userRotation = shell('settings get system user_rotation', { allowFailure: true }) || '0';
const uri = `edgefade://progressive-blur-smoke?edges=${options.edges}`;

function startSmoke() {
  shell(`am force-stop ${PACKAGE}`);
  sleep(300);
  shell(`am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PACKAGE}`);
  sleep(1200);
}

function driveSwipes(count) {
  for (let i = 0; i < count; i++) {
    const fromY = i % 2 === 0 ? yBottom : yTop;
    const toY = i % 2 === 0 ? yTop : yBottom;
    shell(`input swipe ${x} ${fromY} ${x} ${toY} 170`);
    sleep(100);
  }
}

console.log(`Device: ${model} / API ${sdk} / ${width}x${height}`);
console.log(`Smoke: ${options.edges} / radius cycle 98 -> 0 -> 150 -> 1 -> 98 px`);
console.log('Curve cycle: analytical Smooth + serialized custom cubicBezier');

adb(['logcat', '-c']);
let capturedLogs = '';
const checkpointLogs = () => {
  // The device log ring is bounded. Preserve renderer activation/failure
  // evidence during the smoke instead of depending on a single large dump at
  // the end, where early activation can already have been evicted.
  capturedLogs += relevantLogcat();
};

try {
  startSmoke();
  checkpointLogs();

  driveSwipes(options.swipes);
  sleep(options.settleMs);
  checkpointLogs();

  // Background/foreground lifecycle.
  shell('input keyevent KEYCODE_HOME');
  sleep(900);
  shell(`am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PACKAGE}`);
  sleep(1500);
  driveSwipes(Math.max(4, Math.floor(options.swipes / 2)));
  checkpointLogs();

  // Deterministic portrait -> landscape -> portrait. Restore the user's rotation
  // settings in finally even if the app or assertion fails.
  shell('settings put system accelerometer_rotation 0');
  shell('settings put system user_rotation 1');
  sleep(1700);
  driveSwipes(3);
  checkpointLogs();

  shell('settings put system user_rotation 0');
  sleep(1700);
  driveSwipes(3);
  sleep(options.settleMs);
  checkpointLogs();
} finally {
  shell(`settings put system accelerometer_rotation ${accelerometerRotation}`, {
    allowFailure: true,
  });
  shell(`settings put system user_rotation ${userRotation}`, {
    allowFailure: true,
  });
}

const logcat = `${capturedLogs}\n${relevantLogcat()}`;
const failures = [];

if (/FATAL EXCEPTION|AndroidRuntime: FATAL|Fatal signal/i.test(logcat)) {
  failures.push('fatal Android/native exception observed');
}
for (const [needle, message] of [
  ['fadeRadius is not supported by progressive blur', 'rounded corners incorrectly fell back to Mask'],
  ['Progressive strip draw failed; using mask fallback.', 'API 33+ progressive draw failure observed'],
  ['Progressive strip shader creation failed', 'API 33+ progressive shader creation failure observed'],
  ['Progressive strip configuration failed', 'API 33+ progressive configuration failure observed'],
  ['GLES progressive renderer creation failed', 'API 31-32 GLES renderer creation failure observed'],
  ['GLES progressive configuration failed', 'API 31-32 GLES configuration failure observed'],
  ['GLES progressive frame unavailable', 'API 31-32 GLES frame was unavailable'],
  ['GLES progressive draw failed', 'API 31-32 GLES draw failure observed'],
]) {
  if (logcat.includes(needle)) failures.push(message);
}
if (logcat.includes('curve cannot be represented by the progressive radius mask')) {
  failures.push('serialized custom curve fell back to Mask');
}
if (/mask fallback: blurRadius 0(?:\.0+)?px/i.test(logcat)) {
  failures.push('radius 0 incorrectly fell back to Mask');
}
if (!logcat.includes(ZERO_RADIUS_IDENTITY_LOG)) {
  failures.push('platform-independent radius 0 identity activation log was not observed');
}

let backendSummary;
if (sdk >= 33) {
  try {
    const active = requireProgressiveBackend(logcat, sdk, options.expectedBackend);
    backendSummary = `33+ ${active} Public Progressive activation + 0px identity + radius recovery`;
  } catch (error) {
    failures.push(error.message);
  }
} else if (sdk >= 31) {
  try {
    requireProgressiveBackend(logcat, sdk, options.expectedBackend);
  } catch (error) {
    failures.push(error.message);
  }
  if (/Progressive unavailable; using mask fallback: requires API 31\+/i.test(logcat)) {
    failures.push('API 31-32 incorrectly took the API <31 Mask fallback');
  }
  backendSummary = '31-32 GLES continuous progressive activation + 0px identity + radius recovery';
} else {
  if (options.expectedBackend !== 'auto') {
    failures.push(`Expected ${options.expectedBackend}, but progressive blur requires API 31+`);
  }
  if (!logcat.includes('Progressive unavailable; using mask fallback: requires API 31+')) {
    failures.push('API <31 did not explicitly report the required nonzero Mask fallback');
  }
  backendSummary = '<31 nonzero Mask fallback + platform-independent 0px identity';
}

if (failures.length) {
  console.error('\nFAIL');
  for (const failure of failures) console.error(`  - ${failure}`);
  const excerpt = failureExcerpt(logcat);
  if (excerpt) {
    console.error('\nNative failure excerpt:');
    console.error(excerpt);
  }
  process.exitCode = 1;
} else {
  console.log('\nPASS');
  console.log(`  API ${backendSummary}`);
  if (sdk >= 31) {
    console.log('  analytical + custom curve transitions: no progressive fallback observed');
  } else {
    console.log('  nonzero blur phases explicitly used Mask; the 0px phase stayed sharp identity');
  }
  console.log(
    '  rapid scroll + background/foreground + portrait/landscape/portrait: no native failure observed'
  );
}
