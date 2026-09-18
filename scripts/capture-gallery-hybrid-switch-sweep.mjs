#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'benchmark-results', 'hybrid-switch-sweep');
const RENDERERS = ['hybrid-52', 'hybrid-56', 'hybrid-60', 'hybrid-64', 'agsl'];

const argv = process.argv.slice(2);
let serial = '';
let radiusPx = 80;
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--serial') serial = argv[++i];
  else if (argv[i] === '--radius-px') radiusPx = Number(argv[++i]);
  else throw new Error(`Unknown argument: ${argv[i]}`);
}
if (!serial) throw new Error('--serial is required');
if (!Number.isFinite(radiusPx) || radiusPx < 1 || radiusPx > 150) {
  throw new Error('--radius-px must be in 1..150');
}
fs.mkdirSync(OUT, { recursive: true });

function adb(args, trim = true) {
  const result = spawnSync('adb', ['-s', serial, ...args], {
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
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

function launch(renderer) {
  adb(['logcat', '-c']);
  shell(`am force-stop ${PACKAGE}`);
  sleep(300);
  const uri =
    `edgefade:///gallery-renderer-test?renderer=${renderer}` +
    `&radiusPx=${radiusPx}&cycleMs=4200&image=expo&static=1`;
  const output = shell(
    `am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PACKAGE}`,
    false,
  );
  if (!/Status:\s*ok/.test(output)) throw new Error(`Launch failed for ${renderer}`);
}

function verify(renderer) {
  for (let attempt = 0; attempt < 10; attempt++) {
    const logs = adb(['logcat', '-d', '-s', 'EdgeFade.BlurLab:I', '*:S'], false);
    const expected = `Renderer active: requested=${renderer} active=${renderer}`;
    if (logs.includes(expected)) return;
    const disabled = `Renderer active: requested=${renderer} active=off`;
    if (logs.includes(disabled)) throw new Error(`${renderer} disabled:\n${logs}`);
    sleep(250);
  }
  throw new Error(`Could not verify ${renderer}`);
}

function capture(renderer, stamp) {
  const remote = `/sdcard/edgefade-${renderer}.png`;
  const local = path.join(OUT, `${stamp}-${renderer}.png`);
  shell(`screencap -p ${remote}`);
  adb(['pull', remote, local]);
  shell(`rm -f ${remote}`);
  console.log(`${renderer}: ${local}`);
}

const stamp = new Date().toISOString().replace(/[:.]/g, '-');
for (const renderer of RENDERERS) {
  console.log(`\n${renderer}`);
  launch(renderer);
  sleep(1500);
  verify(renderer);
  // Static stress mode: identical offset/content for every backend.
  // Give Expo Image time to settle before taking the deterministic capture.
  sleep(1800);
  capture(renderer, stamp);
}
shell(`am force-stop ${PACKAGE}`);
console.log(`\nHybrid switch sweep: ${OUT}`);
