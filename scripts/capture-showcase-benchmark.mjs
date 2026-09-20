import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const DEFAULT_ROUTE = 'edgefade://showcase';
const UI_DUMP = '/sdcard/edgefade-showcase.xml';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function readArg(name) {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

function hasArg(name) {
  return process.argv.includes(name);
}

function sleep(ms) {
  return new Promise((resolveSleep) => setTimeout(resolveSleep, ms));
}

function run(command, args, options = {}) {
  const hasEncoding = Object.prototype.hasOwnProperty.call(options, 'encoding');
  return execFileSync(command, args, {
    // Important: encoding:null must stay null for binary adb output. Using ??
    // here converted null back to utf8 and corrupted screencap PNG bytes.
    encoding: hasEncoding ? options.encoding : 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    stdio: options.stdio ?? ['ignore', 'pipe', 'pipe'],
  });
}

const requestedSerial = readArg('--serial') ?? process.env.ADB_SERIAL;
const settleMs = Number(readArg('--settle-ms') ?? 2200);
const autoOpen = !hasArg('--no-open');
const route = readArg('--route') ?? DEFAULT_ROUTE;
const requestedOutputDir =
  readArg('--output-dir') ?? 'benchmarks/progressive-showcase/current';
const outputDir = resolve(repoRoot, requestedOutputDir);

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

  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  if (!Buffer.isBuffer(png) || png.length < signature.length || !png.subarray(0, 8).equals(signature)) {
    throw new Error(
      `adb screencap did not return a valid PNG buffer for "${name}".`
    );
  }

  writeFileSync(path, png);
  console.log(
    `[showcase-benchmark] ${name} PNG: ${Math.round(png.length / 1024)} KiB`
  );
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
  route,
  '-p',
  PACKAGE,
]);

const openButton = await waitForDescription('Open tonight panel');

// Expo Image decoding can lag route interactivity by more than a frame on a
// cold launch. Wait long enough for the hero/stills to be materially present,
 // otherwise the benchmark is worthless.
await sleep(settleMs);

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
  route,
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

const previewPath = resolve(outputDir, 'index.html');
const previewHtml = `<!doctype html>
<html>
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>Progressive Showcase Benchmark</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    padding: 24px;
    font-family: -apple-system, BlinkMacSystemFont, sans-serif;
    background: #111;
    color: #eee;
  }
  h1 { margin: 0 0 18px; font-size: 18px; font-weight: 600; }
  .grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 18px;
    align-items: start;
  }
  figure { margin: 0; }
  figcaption {
    margin: 0 0 8px;
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: .08em;
    color: #aaa;
  }
  img {
    display: block;
    width: 100%;
    max-height: calc(100vh - 100px);
    object-fit: contain;
    background: #222;
    border: 1px solid #333;
  }
  .meta {
    margin-top: 16px;
    font: 11px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace;
    color: #888;
  }
</style>
</head>
<body>
  <h1>Progressive showcase — current benchmark</h1>
  <div class="grid">
    <figure>
      <figcaption>Closed</figcaption>
      <img src="./closed.png?t=${Date.now()}" alt="Closed benchmark" />
    </figure>
    <figure>
      <figcaption>Open</figcaption>
      <img src="./open.png?t=${Date.now()}" alt="Open benchmark" />
    </figure>
  </div>
  <div class="meta">
    ${metadata.device} · Android ${metadata.android} · ${metadata.wmSize}
  </div>
</body>
</html>
`;
writeFileSync(previewPath, previewHtml);

console.log(
  `[showcase-benchmark] saved benchmark pair in ${outputDir}`
);
console.log(`[showcase-benchmark] preview: ${previewPath}`);

if (autoOpen && process.platform === 'darwin' && existsSync(previewPath)) {
  try {
    run('open', [previewPath]);
  } catch {
    // The benchmark itself succeeded; preview opening is convenience only.
  }
}
