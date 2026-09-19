import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROUTE = 'edgefade://showcase';
const UI_DUMP = '/sdcard/edgefade-showcase.xml';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const outputDir = resolve(
  repoRoot,
  'benchmarks',
  'progressive-showcase',
  'current'
);

function readArg(name) {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

function sleep(ms) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

function run(command, args, options = {}) {
  return execFileSync(command, args, {
    encoding: options.encoding ?? 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    stdio: options.stdio ?? ['ignore', 'pipe', 'pipe'],
  });
}

const requestedSerial = readArg('--serial') ?? process.env.ADB_SERIAL;

function connectedDevices() {
  const stdout = run('adb', ['devices']);
  return stdout
    .split('\n')
    .slice(1)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => line.split(/\s+/))
    .filter(([, state]) => state === 'device')
    .map(([serial]) => serial);
}

const devices = connectedDevices();
const serial =
  requestedSerial ??
  (devices.length === 1
    ? devices[0]
    : undefined);

if (!serial) {
  const detail =
    devices.length === 0
      ? 'No Android device is connected.'
      : `Multiple Android devices are connected: ${devices.join(', ')}. Pass --serial <id> or set ADB_SERIAL.`;
  throw new Error(detail);
}

function adb(args, options = {}) {
  return run('adb', ['-s', serial, ...args], options);
}

function dumpUi() {
  adb(['shell', 'uiautomator', 'dump', UI_DUMP]);
  return adb(['shell', 'cat', UI_DUMP]);
}

function boundsForDescription(xml, description) {
  const nodes = xml.match(/<node\b[^>]*>/g) ?? [];
  for (const node of nodes) {
    if (!node.includes(`content-desc="${description}"`)) continue;
    const match = node.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    if (!match) continue;
    return {
      left: Number(match[1]),
      top: Number(match[2]),
      right: Number(match[3]),
      bottom: Number(match[4]),
    };
  }
  return null;
}

async function waitForDescription(description, timeoutMs = 20000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;

  while (Date.now() < deadline) {
    try {
      const bounds = boundsForDescription(dumpUi(), description);
      if (bounds) return bounds;
    } catch (error) {
      lastError = error;
    }
    await sleep(350);
  }

  const suffix = lastError ? ` Last adb error: ${lastError.message}` : '';
  throw new Error(
    `Timed out waiting for accessibility label "${description}".${suffix}`
  );
}

function capture(name) {
  const path = resolve(outputDir, `${name}.png`);
  const png = adb(['exec-out', 'screencap', '-p'], {
    encoding: null,
  });
  writeFileSync(path, png);
  return path;
}

function center(bounds) {
  return {
    x: Math.round((bounds.left + bounds.right) / 2),
    y: Math.round((bounds.top + bounds.bottom) / 2),
  };
}

mkdirSync(outputDir, { recursive: true });

console.log(`[showcase-benchmark] device: ${serial}`);
console.log('[showcase-benchmark] launching showcase...');

adb(['shell', 'am', 'force-stop', PACKAGE]);
adb([
  'shell',
  'am',
  'start',
  '-W',
  '-a',
  'android.intent.action.VIEW',
  '-d',
  ROUTE,
  '-p',
  PACKAGE,
]);

const openButton = await waitForDescription('Open tonight panel');

// Give Expo Image one extra beat after the route becomes interactive so the
// benchmark does not capture placeholder/late-decoded imagery.
await sleep(900);

const closedPath = capture('closed');
console.log(`[showcase-benchmark] closed: ${closedPath}`);

const tap = center(openButton);
adb(['shell', 'input', 'tap', String(tap.x), String(tap.y)]);

await waitForDescription('Close tonight panel');
// OPEN_MS is 500ms; wait past the animation endpoint before the benchmark.
await sleep(750);

const openPath = capture('open');
console.log(`[showcase-benchmark] open:   ${openPath}`);

const metadata = {
  capturedAt: new Date().toISOString(),
  serial,
  package: PACKAGE,
  route: ROUTE,
  device: adb(['shell', 'getprop', 'ro.product.model']).trim(),
  android: adb(['shell', 'getprop', 'ro.build.version.release']).trim(),
  wmSize: adb(['shell', 'wm', 'size']).trim(),
  files: {
    closed: 'closed.png',
    open: 'open.png',
  },
};

writeFileSync(
  resolve(outputDir, 'meta.json'),
  `${JSON.stringify(metadata, null, 2)}\n`
);

console.log(
  `[showcase-benchmark] saved benchmark pair in ${outputDir}`
);
