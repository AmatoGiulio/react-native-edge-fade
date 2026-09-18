#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'benchmark-results', 'public-backend-quartet');
const BACKENDS = ['auto', 'agsl', 'androidx', 'scaled'];

const argv = process.argv.slice(2);
let serial = '';
let radiusPx = 150;
let curve = 'smooth';
let topDp = 92;
let bottomDp = 112;
let leftDp = 0;
let rightDp = 0;

for (let i = 0; i < argv.length; i++) {
  const arg = argv[i];
  const take = () => argv[++i];
  if (arg === '--serial') serial = take();
  else if (arg === '--radius-px') radiusPx = Number(take());
  else if (arg === '--curve') curve = take();
  else if (arg === '--top-dp') topDp = Number(take());
  else if (arg === '--bottom-dp') bottomDp = Number(take());
  else if (arg === '--left-dp') leftDp = Number(take());
  else if (arg === '--right-dp') rightDp = Number(take());
  else throw new Error(`Unknown argument: ${arg}`);
}

if (!serial) throw new Error('--serial is required');
if (!Number.isFinite(radiusPx) || radiusPx < 1 || radiusPx > 150) {
  throw new Error('--radius-px must be in 1..150');
}
for (const [name, value] of [
  ['top', topDp],
  ['bottom', bottomDp],
  ['left', leftDp],
  ['right', rightDp],
]) {
  if (!Number.isFinite(value) || value < 0 || value > 320) {
    throw new Error(`--${name}-dp must be in 0..320`);
  }
}

fs.mkdirSync(OUT, { recursive: true });

function adb(args, trim = true) {
  const result = spawnSync('adb', ['-s', serial, ...args], {
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${result.stdout ?? ''}${result.stderr ?? ''}`);
  }
  const output = `${result.stdout ?? ''}${result.stderr ?? ''}`;
  return trim ? output.trim() : output;
}

function shell(command, trim = true) {
  return adb(['shell', command], trim);
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function launch(backend) {
  adb(['logcat', '-c']);
  shell(`am force-stop ${PACKAGE}`);
  sleep(250);

  const params = new URLSearchParams({
    renderer: 'public',
    backend,
    radiusPx: String(radiusPx),
    cycleMs: '4200',
    image: 'expo',
    static: '1',
    fadeTopDp: String(topDp),
    fadeBottomDp: String(bottomDp),
    fadeLeftDp: String(leftDp),
    fadeRightDp: String(rightDp),
    curve,
  });

  const uri = `edgefade:///gallery-renderer-test?${params.toString()}`;
  const output = shell(
    `am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PACKAGE}`,
    false
  );
  if (!/Status:\s*ok/.test(output)) {
    throw new Error(`Launch failed for ${backend}:\n${output}`);
  }
}

function logs() {
  return adb(
    ['logcat', '-d', '-s', 'EdgeFadeProgressive:I', 'AndroidRuntime:E', '*:S'],
    false
  );
}

function expectedLog(backend) {
  if (backend === 'agsl') {
    return 'Using pure progressive AGSL blur on API 33+';
  }
  if (backend === 'androidx') {
    return 'Using official AndroidX progressive blur on API 33+';
  }
  if (backend === 'scaled') {
    return 'Using HWUI-scaled progressive blur on API 33+';
  }

  const verticalOnly = leftDp <= 0 && rightDp <= 0 && (topDp > 0 || bottomDp > 0);
  if (verticalOnly && radiusPx >= 110) {
    return 'Using HWUI-scaled progressive blur on API 33+';
  }
  return 'Using official AndroidX progressive blur on API 33+';
}

function verify(backend) {
  const expected = expectedLog(backend);
  let observed = '';

  for (let attempt = 0; attempt < 30; attempt++) {
    observed = logs();
    if (observed.includes(expected)) return;

    if (
      observed.includes('Progressive unavailable; using mask fallback') ||
      observed.includes('Progressive renderer configuration failed')
    ) {
      throw new Error(`${backend} failed:\n${observed}`);
    }

    sleep(100);
  }

  throw new Error(
    `Could not verify public backend ${backend}.\nExpected: ${expected}\nLogs:\n${observed}`
  );
}

function safe(value) {
  return value.replace(/[^a-zA-Z0-9._-]+/g, '_').slice(0, 80);
}

function capture(backend, stamp) {
  const remote = `/sdcard/edgefade-public-${backend}.png`;
  const local = path.join(
    OUT,
    `${stamp}-r${radiusPx}-t${topDp}-b${bottomDp}-l${leftDp}-r${rightDp}-${safe(curve)}-${backend}.png`
  );

  shell(`screencap -p ${remote}`);
  adb(['pull', remote, local]);
  shell(`rm -f ${remote}`);
  console.log(`${backend}: ${local}`);
}

const stamp = new Date().toISOString().replace(/[:.]/g, '-');

console.log(
  `Public backend quartet / radius=${radiusPx}px / top=${topDp}dp / bottom=${bottomDp}dp / left=${leftDp}dp / right=${rightDp}dp / curve=${curve}`
);

for (const backend of BACKENDS) {
  console.log(`\n${backend.toUpperCase()}`);
  launch(backend);
  sleep(500);
  verify(backend);
  sleep(1200);
  capture(backend, stamp);
}

shell(`am force-stop ${PACKAGE}`);
console.log(`\nVisual set: ${OUT}`);
