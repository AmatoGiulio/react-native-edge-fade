#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT_DIR = path.join(ROOT, 'benchmark-results', 'scaled-continuous');
const BASE_SCRIPT = path.join(ROOT, 'scripts', 'benchmark-gallery-scaled-continuous-matrix.mjs');
const REQUIRED_RENDERERS = ['off', 'scaled-continuous', 'agsl', 'androidx'];
const TOLERANCE_HZ = 5;

function parseArgs(argv) {
  const out = {
    serial: '',
    radiusPx: 80,
    cycleMs: 4200,
    image: 'expo',
    forwarded: [],
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const take = () => argv[++i];
    if (arg === '--serial') {
      const value = take();
      out.serial = value;
      out.forwarded.push(arg, value);
    } else if (arg === '--radius-px') {
      const value = take();
      out.radiusPx = Number(value);
      out.forwarded.push(arg, value);
    } else if (arg === '--cycle-ms') {
      const value = take();
      out.cycleMs = Number(value);
      out.forwarded.push(arg, value);
    } else if (arg === '--image') {
      const value = take();
      out.image = value;
      out.forwarded.push(arg, value);
    } else if (arg === '--max-attempts') {
      // Kept for CLI compatibility with the previous wrapper. The new runner
      // locks refresh instead of retrying whole matrices.
      take();
    } else {
      out.forwarded.push(arg);
      if (
        [
          '--duration-ms',
          '--warmup-ms',
          '--cooldown-ms',
          '--samples',
          '--refresh-tolerance-hz',
        ].includes(arg)
      ) {
        out.forwarded.push(take());
      }
    }
  }

  if (!out.serial) throw new Error('--serial is required');
  return out;
}

const options = parseArgs(process.argv.slice(2));

function adb(args, trim = true) {
  const result = spawnSync('adb', ['-s', options.serial, ...args], {
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

function round(value, digits = 2) {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

function readRefreshRate() {
  try {
    const latency = adb(['shell', 'dumpsys', 'SurfaceFlinger', '--latency'], false);
    for (const raw of latency.split(/\r?\n/)) {
      const line = raw.trim();
      if (!/^\d+$/.test(line)) continue;
      const periodNs = Number(line);
      if (periodNs >= 4_000_000 && periodNs <= 40_000_000) {
        return round(1_000_000_000 / periodNs);
      }
      break;
    }
  } catch {}
  return null;
}

function sameHz(a, b) {
  return Number.isFinite(a) && Number.isFinite(b) && Math.abs(a - b) <= TOLERANCE_HZ;
}

function readSetting(key) {
  const value = shell(`settings get system ${key}`);
  return value === 'null' || value === '' ? null : value;
}

function writeSetting(key, value) {
  if (value == null) shell(`settings delete system ${key}`);
  else shell(`settings put system ${key} ${value}`);
}

function lockRefresh(hz) {
  writeSetting('peak_refresh_rate', `${hz}.0`);
  writeSetting('min_refresh_rate', `${hz}.0`);
}

function launchPreflightGallery() {
  shell(`am force-stop ${PACKAGE}`);
  sleep(250);
  const uri =
    `edgefade:///gallery-renderer-test?renderer=off` +
    `&radiusPx=${options.radiusPx}&cycleMs=${options.cycleMs}&image=${options.image}`;
  const output = shell(
    `am start -W -a android.intent.action.VIEW -d '${uri}' -p ${PACKAGE}`,
    false,
  );
  if (!/Status:\s*ok/.test(output)) {
    throw new Error(`Could not launch preflight Gallery.\n${output}`);
  }
}

function waitForLockedRefresh(targetHz) {
  launchPreflightGallery();
  let consecutive = 0;
  let last = null;
  for (let probe = 1; probe <= 16; probe++) {
    sleep(400);
    const hz = readRefreshRate();
    last = hz;
    consecutive = sameHz(hz, targetHz) ? consecutive + 1 : 0;
    process.stdout.write(
      `\rrefresh lock: ${hz ?? 'n/a'}Hz (${consecutive}/2 stable @ ${targetHz}Hz)   `,
    );
    if (consecutive >= 2) {
      process.stdout.write('\n');
      shell(`am force-stop ${PACKAGE}`);
      return true;
    }
  }
  process.stdout.write('\n');
  shell(`am force-stop ${PACKAGE}`);
  console.log(`Could not hold ${targetHz}Hz (last=${last ?? 'n/a'}Hz).`);
  return false;
}

function newestReport(afterMs) {
  if (!fs.existsSync(OUT_DIR)) return null;
  const candidates = fs.readdirSync(OUT_DIR)
    .filter((name) => name.endsWith('-report.json'))
    .map((name) => {
      const full = path.join(OUT_DIR, name);
      return { full, mtime: fs.statSync(full).mtimeMs };
    })
    .filter((entry) => entry.mtime >= afterMs - 1000)
    .sort((a, b) => b.mtime - a.mtime);
  return candidates[0]?.full ?? null;
}

function reportMatchesRefresh(report, targetHz) {
  if (!sameHz(report?.device?.targetRefreshHz, targetHz)) return false;
  for (const renderer of REQUIRED_RENDERERS) {
    const rows = report.results.filter((row) => row.renderer === renderer);
    if (!rows.length || rows.some((row) => row.valid === false)) return false;
    if (rows.some((row) => !sameHz(row.refreshHzStart, targetHz) || !sameHz(row.refreshHzEnd, targetHz))) {
      return false;
    }
  }
  return true;
}

function runMatrix(targetHz) {
  const started = Date.now();
  const result = spawnSync(process.execPath, [BASE_SCRIPT, ...options.forwarded], {
    cwd: ROOT,
    encoding: 'utf8',
    stdio: 'inherit',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) return null;
  const reportPath = newestReport(started);
  if (!reportPath) return null;
  const report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
  return reportMatchesRefresh(report, targetHz) ? reportPath : null;
}

const original = {
  peak: readSetting('peak_refresh_rate'),
  min: readSetting('min_refresh_rate'),
};

// 60Hz is deliberate. This A/B compares absolute renderer cost and visual
// fidelity; forcing the lower stable mode prevents the OnePlus adaptive display
// from oscillating between 60/90Hz based on workload. Original user settings are
// restored unconditionally when the script exits.
const targetHz = 60;

try {
  console.log(`Locking display to ${targetHz}Hz for a single comparable matrix...`);
  lockRefresh(targetHz);
  sleep(500);

  if (!waitForLockedRefresh(targetHz)) {
    throw new Error(`Device did not honor the temporary ${targetHz}Hz refresh lock.`);
  }

  const reportPath = runMatrix(targetHz);
  if (!reportPath) {
    throw new Error(
      `Matrix was not fully ${targetHz}Hz despite the refresh lock. ` +
        'No result was accepted.',
    );
  }

  console.log(`\nACCEPTED SAME-REFRESH REPORT: ${reportPath}`);
} finally {
  writeSetting('peak_refresh_rate', original.peak);
  writeSetting('min_refresh_rate', original.min);
  console.log(
    `Restored refresh settings: peak=${original.peak ?? 'default'} min=${original.min ?? 'default'}`,
  );
}
