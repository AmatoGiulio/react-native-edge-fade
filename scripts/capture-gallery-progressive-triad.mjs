#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(
  ROOT,
  'benchmark-results',
  'progressive-fidelity-triad'
);
const RENDERERS = ['androidx', 'hwui-scaled', 'public'];

const argv = process.argv.slice(2);
let serial = '';
let radiusPx = 140;
let curve = 'smooth';
let edges = 'vertical';
let edgeDp = 110;

for (let i = 0; i < argv.length; i++) {
  const arg = argv[i];
  const take = () => argv[++i];
  if (arg === '--serial') serial = take();
  else if (arg === '--radius-px') radiusPx = Number(take());
  else if (arg === '--curve') curve = take();
  else if (arg === '--edges') edges = take();
  else if (arg === '--edge-dp') edgeDp = Number(take());
  else throw new Error(`Unknown argument: ${arg}`);
}

if (!serial) throw new Error('--serial is required');
if (!Number.isFinite(radiusPx) || radiusPx < 1 || radiusPx > 150) {
  throw new Error('--radius-px must be in 1..150');
}
if (!Number.isFinite(edgeDp) || edgeDp < 0 || edgeDp > 300) {
  throw new Error('--edge-dp must be in 0..300');
}
if (!['vertical', 'horizontal', 'all'].includes(edges)) {
  throw new Error('--edges must be vertical, horizontal or all');
}
if (!curve) throw new Error('--curve must not be empty');

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
  const text = `${result.stdout ?? ''}${result.stderr ?? ''}`;
  return trim ? text.trim() : text;
}

function shell(command, trim = true) {
  return adb(['shell', command], trim);
}

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function geometry() {
  return {
    top: edges === 'vertical' || edges === 'all' ? edgeDp : 0,
    bottom: edges === 'vertical' || edges === 'all' ? edgeDp : 0,
    left: edges === 'horizontal' || edges === 'all' ? edgeDp : 0,
    right: edges === 'horizontal' || edges === 'all' ? edgeDp : 0,
  };
}

function launch(renderer) {
  adb(['logcat', '-c']);
  shell(`am force-stop ${PACKAGE}`);
  sleep(300);

  const g = geometry();
  const params = new URLSearchParams({
    renderer,
    radiusPx: String(radiusPx),
    cycleMs: '4200',
    image: 'expo',
    static: '1',
    fadeTopDp: String(g.top),
    fadeBottomDp: String(g.bottom),
    fadeLeftDp: String(g.left),
    fadeRightDp: String(g.right),
    curve,
  });
  const uri = `edgefade:///gallery-renderer-test?${params.toString()}`;
  const output = shell(
    `am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PACKAGE}`,
    false
  );
  if (!/Status:\s*ok/.test(output)) {
    throw new Error(`Launch failed for ${renderer}:\n${output}`);
  }
}

function logs() {
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

function verify(renderer) {
  let lastUi = '';
  let lastLogs = '';

  for (let attempt = 0; attempt < 20; attempt++) {
    try {
      shell('uiautomator dump /sdcard/edgefade-production-ui.xml >/dev/null 2>&1');
      lastUi = shell('cat /sdcard/edgefade-production-ui.xml', false);
    } catch {}

    lastLogs = logs();

    if (renderer === 'public') {
      const uiReady =
        lastUi.includes('gallery-renderer requested=public active=public');
      const backendReady =
        radiusPx >= 110
          ? lastLogs.includes(
              'Using HWUI-scaled progressive blur on API 33+'
            )
          : lastLogs.includes(
              'Using official AndroidX progressive blur on API 33+'
            ) ||
            lastLogs.includes(
              'Using pure progressive AGSL blur on API 33+'
            );
      if (uiReady && backendReady) return;
    } else {
      const expectedUi =
        `gallery-renderer requested=${renderer} active=${renderer}`;
      const expectedLog =
        `Renderer active: requested=${renderer} active=${renderer}`;
      const hwuiSpecific =
        renderer === 'hwui-scaled' &&
        lastLogs.includes(
          'Using HWUI scaled continuous Gaussian benchmark path (0.75x strips).'
        );

      if (
        lastUi.includes(expectedUi) ||
        lastLogs.includes(expectedLog) ||
        hwuiSpecific
      ) {
        return;
      }

      if (
        lastUi.includes(
          `gallery-renderer requested=${renderer} active=off`
        ) ||
        lastLogs.includes(
          `Renderer active: requested=${renderer} active=off`
        )
      ) {
        throw new Error(
          `${renderer} backend is disabled.\n${lastLogs}`
        );
      }
    }

    sleep(300);
  }

  throw new Error(
    `Could not verify ${renderer}.\nUI dump:\n${lastUi}\nLogs:\n${lastLogs}`
  );
}

function safe(value) {
  return value.replace(/[^a-zA-Z0-9._-]+/g, '_').slice(0, 80);
}

function capture(renderer, stamp) {
  const remote = `/sdcard/edgefade-production-${renderer}.png`;
  const local = path.join(
    OUT,
    `${stamp}-r${radiusPx}-${edges}-${safe(curve)}-${renderer}.png`
  );
  shell(`screencap -p ${remote}`);
  adb(['pull', remote, local]);
  shell(`rm -f ${remote}`);
  console.log(`${renderer}: ${local}`);
}

const stamp = new Date().toISOString().replace(/[:.]/g, '-');
console.log(
  `Progressive fidelity triad / radius=${radiusPx}px / edges=${edges} / curve=${curve}`
);

for (const renderer of RENDERERS) {
  console.log(`\n${renderer}`);
  launch(renderer);
  sleep(1500);
  verify(renderer);
  sleep(1800);
  capture(renderer, stamp);
}

shell(`am force-stop ${PACKAGE}`);
console.log(`\nVisual set: ${OUT}`);
