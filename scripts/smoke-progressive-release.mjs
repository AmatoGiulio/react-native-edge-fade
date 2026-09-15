#!/usr/bin/env node

import { spawnSync } from 'node:child_process';

const PACKAGE = 'com.edgefadeexample';

function parseArgs(argv) {
  const options = {
    serial: '',
    edges: 'vertical',
    swipes: 10,
    settleMs: 5200,
    allowEmulator: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const value = () => {
      if (i + 1 >= argv.length) throw new Error(`Missing value after ${arg}`);
      return argv[++i];
    };
    switch (arg) {
      case '--serial': options.serial = value(); break;
      case '--edges': options.edges = value(); break;
      case '--swipes': options.swipes = Number(value()); break;
      case '--settle-ms': options.settleMs = Number(value()); break;
      case '--allow-emulator': options.allowEmulator = true; break;
      default: throw new Error(`Unknown argument: ${arg}`);
    }
  }
  if (!['vertical', 'four'].includes(options.edges)) {
    throw new Error('--edges must be vertical or four');
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
  const result = spawnSync('adb', adbArgs(args), { encoding: 'utf8' });
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

const model = shell('getprop ro.product.model');
const sdk = Number(shell('getprop ro.build.version.sdk'));
const qemu = shell('getprop ro.kernel.qemu');
const isEmulator = qemu === '1' || /sdk_gphone|emulator/i.test(model);
if (isEmulator && !options.allowEmulator) {
  throw new Error(`Detected emulator ${model}. Use a physical device or pass --allow-emulator for functional smoke only.`);
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

const accelerometerRotation = shell('settings get system accelerometer_rotation', { allowFailure: true }) || '1';
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

adb(['logcat', '-c']);

try {
  startSmoke();
  driveSwipes(options.swipes);
  sleep(options.settleMs);

  // Background/foreground lifecycle.
  shell('input keyevent KEYCODE_HOME');
  sleep(900);
  shell(`am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PACKAGE}`);
  sleep(1500);
  driveSwipes(Math.max(4, Math.floor(options.swipes / 2)));

  // Deterministic portrait -> landscape -> portrait. Restore the user's rotation
  // settings in finally even if the app or assertion fails.
  shell('settings put system accelerometer_rotation 0');
  shell('settings put system user_rotation 1');
  sleep(1700);
  driveSwipes(3);
  shell('settings put system user_rotation 0');
  sleep(1700);
  driveSwipes(3);
  sleep(options.settleMs);
} finally {
  shell(`settings put system accelerometer_rotation ${accelerometerRotation}`, { allowFailure: true });
  shell(`settings put system user_rotation ${userRotation}`, { allowFailure: true });
}

const logcat = adb(['logcat', '-d'], { trim: false });
const failures = [];

if (/FATAL EXCEPTION|AndroidRuntime: FATAL/i.test(logcat)) {
  failures.push('fatal Android exception observed');
}
if (logcat.includes('Progressive strip draw failed; using mask fallback.')) {
  failures.push('progressive draw failure observed');
}
if (logcat.includes('Progressive strip shader creation failed')) {
  failures.push('progressive shader creation failure observed');
}
if (logcat.includes('Progressive strip configuration failed')) {
  failures.push('progressive configuration failure observed');
}

if (sdk >= 33) {
  if (!logcat.includes('Using pure progressive AGSL blur on API 33+')) {
    failures.push('Public Progressive activation log was not observed');
  }
  if (!/blurRadius 0(?:\.0+)?px outside 1\.\./.test(logcat)) {
    failures.push('radius 0 -> Mask transition was not observed');
  }
} else if (!logcat.includes('Progressive unavailable; using mask fallback: requires API 33+')) {
  failures.push('API <33 did not explicitly report the required Mask fallback');
}

if (failures.length) {
  console.error('\nFAIL');
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exitCode = 1;
} else {
  console.log('\nPASS');
  console.log(`  API ${sdk >= 33 ? '33+ Public Progressive activation + radius fallback/recovery' : '<33 explicit Mask fallback'}`);
  console.log('  rapid scroll + background/foreground + portrait/landscape/portrait: no native failure observed');
}
