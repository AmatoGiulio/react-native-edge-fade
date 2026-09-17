#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT_DIR = path.join(ROOT, 'benchmark-results', 'gallery-renderers');
const BASE_SCRIPT = path.join(ROOT, 'scripts', 'benchmark-gallery-renderers.mjs');
const TARGET_HZ = 90;
const TOLERANCE_HZ = 5;
const REQUIRED_RENDERERS = ['off', 'agsl', 'androidx'];

function parseArgs(argv) {
  const out = {
    serial: '',
    radiusPx: 80,
    cycleMs: 4200,
    image: 'expo',
    maxAttempts: 3,
    forwarded: [],
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--serial') {
      const value = argv[++i];
      out.serial = value;
      out.forwarded.push(arg, value);
    } else if (arg === '--radius-px') {
      const value = argv[++i];
      out.radiusPx = Number(value);
      out.forwarded.push(arg, value);
    } else if (arg === '--cycle-ms') {
      const value = argv[++i];
      out.cycleMs = Number(value);
      out.forwarded.push(arg, value);
    } else if (arg === '--image') {
      const value = argv[++i];
      out.image = value;
      out.forwarded.push(arg, value);
    } else if (arg === '--max-attempts') {
      out.maxAttempts = Number(argv[++i]);
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
        out.forwarded.push(argv[++i]);
      }
    }
  }

  if (!out.serial) throw new Error('--serial is required');
  if (!Number.isInteger(out.maxAttempts) || out.maxAttempts < 1 || out.maxAttempts > 5) {
    throw new Error('--max-attempts must be in 1..5');
  }
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

function isTargetHz(hz) {
  return Number.isFinite(hz) && Math.abs(hz - TARGET_HZ) <= TOLERANCE_HZ;
}

function launchPreflightGallery() {
  shell(`am force-stop ${PACKAGE}`);
  sleep(300);
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

function waitForStable90Hz() {
  launchPreflightGallery();
  let consecutive = 0;
  let last = null;

  for (let probe = 1; probe <= 30; probe++) {
    sleep(500);
    const hz = readRefreshRate();
    last = hz;
    if (isTargetHz(hz)) consecutive += 1;
    else consecutive = 0;

    process.stdout.write(
      `\rpreflight refresh: ${hz ?? 'n/a'}Hz (${consecutive}/3 stable @ ${TARGET_HZ}Hz)   `,
    );

    if (consecutive >= 3) {
      process.stdout.write('\n');
      return true;
    }
  }

  process.stdout.write('\n');
  console.log(`Preflight did not settle at ${TARGET_HZ}Hz (last=${last ?? 'n/a'}Hz).`);
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

function reportIsUsable(report) {
  if (!isTargetHz(report?.device?.targetRefreshHz)) return false;

  for (const renderer of REQUIRED_RENDERERS) {
    const rows = report.results.filter((row) => row.renderer === renderer);
    if (!rows.length || rows.some((row) => row.valid === false)) return false;
    if (
      rows.some(
        (row) => !isTargetHz(row.refreshHzStart) || !isTargetHz(row.refreshHzEnd),
      )
    ) {
      return false;
    }
  }
  return true;
}

for (let attempt = 1; attempt <= options.maxAttempts; attempt++) {
  console.log(`\n=== Scale-space benchmark attempt ${attempt}/${options.maxAttempts} ===`);
  if (!waitForStable90Hz()) continue;

  const started = Date.now();
  const result = spawnSync(process.execPath, [BASE_SCRIPT, ...options.forwarded], {
    cwd: ROOT,
    encoding: 'utf8',
    stdio: 'inherit',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    console.log(`Matrix exited with status ${result.status}; retrying if possible.`);
    continue;
  }

  const reportPath = newestReport(started);
  if (!reportPath) {
    console.log('Could not locate the report produced by this attempt.');
    continue;
  }

  const report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
  if (reportIsUsable(report)) {
    console.log(`\nACCEPTED 90Hz REPORT: ${reportPath}`);
    process.exit(0);
  }

  console.log(`Rejected mixed-refresh report: ${reportPath}`);
}

throw new Error(
  `Could not obtain a stable ${TARGET_HZ}Hz off/agsl/androidx matrix after ${options.maxAttempts} attempts.`,
);
