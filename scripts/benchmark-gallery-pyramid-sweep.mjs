#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const RUNNER = path.join(ROOT, 'scripts', 'benchmark-gallery-renderers.mjs');

function parseArgs(argv) {
  const out = {
    serial: '',
    targetRefreshHz: 90,
    refreshToleranceHz: 5,
    samples: 2,
    image: 'expo',
    radii: [40, 140],
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const take = () => argv[++i];
    if (arg === '--serial') out.serial = take();
    else if (arg === '--target-refresh-hz') out.targetRefreshHz = Number(take());
    else if (arg === '--refresh-tolerance-hz') out.refreshToleranceHz = Number(take());
    else if (arg === '--samples') out.samples = Number(take());
    else if (arg === '--image') out.image = take();
    else if (arg === '--radii') {
      out.radii = take().split(',').map(Number).filter(Number.isFinite);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  if (!out.serial) throw new Error('--serial is required');
  if (!out.radii.length || out.radii.some((radius) => radius < 1 || radius > 150)) {
    throw new Error('--radii must contain values in 1..150');
  }
  return out;
}

const options = parseArgs(process.argv.slice(2));

function sleep(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

function run(command, args, allowFailure = false) {
  const result = spawnSync(command, args, {
    cwd: ROOT,
    encoding: 'utf8',
    stdio: allowFailure ? 'pipe' : 'inherit',
    maxBuffer: 64 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (result.status !== 0 && !allowFailure) {
    throw new Error(`${command} exited with ${result.status}`);
  }
  return result;
}

function adb(args) {
  const result = spawnSync('adb', ['-s', options.serial, ...args], {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`${result.stdout ?? ''}${result.stderr ?? ''}`);
  }
  return `${result.stdout ?? ''}${result.stderr ?? ''}`;
}

function shell(command) {
  return adb(['shell', command]);
}

function readRefreshHz() {
  const output = adb(['shell', 'dumpsys', 'SurfaceFlinger', '--latency']);
  for (const raw of output.split(/\r?\n/)) {
    const line = raw.trim();
    if (!/^\d+$/.test(line)) continue;
    const periodNs = Number(line);
    if (periodNs >= 4_000_000 && periodNs <= 40_000_000) {
      return Math.round((1_000_000_000 / periodNs) * 100) / 100;
    }
    break;
  }
  return null;
}

function matchesTarget(hz) {
  return Number.isFinite(hz) &&
    Math.abs(hz - options.targetRefreshHz) <= options.refreshToleranceHz;
}

function stabilizeRefresh(radiusPx) {
  console.log(`\nPreflight refresh @ radius ${radiusPx}px`);
  shell(`am force-stop ${PACKAGE}`);
  sleep(400);

  const uri =
    `edgefade:///gallery-renderer-test?renderer=off` +
    `&radiusPx=${radiusPx}&cycleMs=4200&image=${options.image}`;
  const launch = shell(
    `am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PACKAGE}`
  );
  if (!/Status:\s*ok/.test(launch)) {
    throw new Error('Refresh preflight launch failed.');
  }

  sleep(1500);
  let consecutive = 0;
  const samples = [];
  for (let attempt = 0; attempt < 16; attempt++) {
    const hz = readRefreshHz();
    samples.push(hz);
    if (matchesTarget(hz)) {
      consecutive += 1;
      if (consecutive >= 3) {
        console.log(`Refresh stable at ${hz}Hz before benchmark.`);
        shell(`am force-stop ${PACKAGE}`);
        sleep(500);
        return;
      }
    } else {
      consecutive = 0;
    }
    sleep(400);
  }

  shell(`am force-stop ${PACKAGE}`);
  throw new Error(
    `Could not stabilize refresh near ${options.targetRefreshHz}Hz. ` +
    `Observed: ${samples.map((value) => value ?? 'n/a').join(', ')}`
  );
}

for (const radiusPx of options.radii) {
  stabilizeRefresh(radiusPx);

  console.log(`\n=== Radius ${radiusPx}px ===`);
  run(process.execPath, [
    RUNNER,
    '--serial', options.serial,
    '--radius-px', String(radiusPx),
    '--samples', String(options.samples),
    '--image', options.image,
    '--refresh-tolerance-hz', String(options.refreshToleranceHz),
  ]);

  sleep(3000);
}
