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
const reuseApp = hasArg('--reuse-app');
const noPreview = hasArg('--no-preview');
const filePrefix = (readArg('--prefix') ?? '').trim();
const route = readArg('--route') ?? DEFAULT_ROUTE;
const requestedOutputDir =
  readArg('--output-dir') ?? 'benchmarks/progressive-showcase/current';
const outputDir = resolve(repoRoot, requestedOutputDir);

// Normalize the benchmark viewport to the same framing used by the visual
// reference crop. The raw device screenshots are still preserved; these
// values only define the deterministic comparison crop.
const focusEnabled = !hasArg('--no-focus');
const focusWidth = Number(readArg('--focus-width') ?? 530);
const focusHeight = Number(readArg('--focus-height') ?? 565);
const focusTopRatio = Number(readArg('--focus-top') ?? 0.34);

if (
  !Number.isFinite(focusWidth) ||
  !Number.isFinite(focusHeight) ||
  focusWidth <= 0 ||
  focusHeight <= 0
) {
  throw new Error('Focus width/height must be positive numbers.');
}
if (
  !Number.isFinite(focusTopRatio) ||
  focusTopRatio < 0 ||
  focusTopRatio > 1
) {
  throw new Error('--focus-top must be a normalized value between 0 and 1.');
}

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

async function waitForToggle(timeoutMs = 12000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;

  while (Date.now() < deadline) {
    try {
      const xml = dumpUi();
      const open = boundsForDescription(xml, 'Open tonight panel');
      if (open) return { state: 'closed', bounds: open };

      const close = boundsForDescription(xml, 'Close tonight panel');
      if (close) return { state: 'open', bounds: close };
    } catch (error) {
      lastError = error;
    }

    await sleep(250);
  }

  const suffix = lastError ? ` Last adb error: ${lastError.message}` : '';
  throw new Error(`Timed out waiting for showcase toggle.${suffix}`);
}

function launchShowcase() {
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
}

async function ensureClosedShowcase() {
  let toggle;

  try {
    toggle = await waitForToggle();
  } catch {
    console.log('[showcase-benchmark] route not ready; relaunching once...');
    launchShowcase();
    toggle = await waitForToggle();
  }

  if (toggle.state === 'open') {
    const tap = center(toggle.bounds);
    adb(['shell', 'input', 'tap', String(tap.x), String(tap.y)]);
    await waitForDescription('Open tonight panel', 8000);
    await sleep(650);
  }

  return waitForDescription('Open tonight panel', 8000);
}

function pngSize(png) {
  const signature = Buffer.from([
    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
  ]);
  if (
    !Buffer.isBuffer(png) ||
    png.length < 24 ||
    !png.subarray(0, 8).equals(signature) ||
    png.subarray(12, 16).toString('ascii') !== 'IHDR'
  ) {
    throw new Error('Expected a valid PNG with an IHDR chunk.');
  }

  return {
    width: png.readUInt32BE(16),
    height: png.readUInt32BE(20),
  };
}

function capture(name) {
  const filename = filePrefix ? `${filePrefix}-${name}.png` : `${name}.png`;
  const path = resolve(outputDir, filename);
  const png = adb(['exec-out', 'screencap', '-p'], {
    encoding: null,
  });

  const size = pngSize(png);
  writeFileSync(path, png);
  console.log(
    `[showcase-benchmark] ${name} PNG: ${size.width}x${size.height} · ${Math.round(png.length / 1024)} KiB`
  );
  return { path, filename, size };
}

function focusCropFor(size) {
  const aspect = focusWidth / focusHeight;
  let cropWidth = size.width;
  let cropHeight = cropWidth / aspect;

  // Usually the crop spans the full device width. Keep this generic for
  // unusually short/wide screenshots by fitting the requested aspect inside.
  if (cropHeight > size.height) {
    cropHeight = size.height;
    cropWidth = cropHeight * aspect;
  }

  const left = (size.width - cropWidth) / 2;
  const maxTop = Math.max(0, size.height - cropHeight);
  const top = Math.min(size.height * focusTopRatio, maxTop);

  return {
    left,
    top,
    width: cropWidth,
    height: cropHeight,
  };
}

function formatNumber(value) {
  return Number(value.toFixed(3));
}

function writeFocusArtifact(captureResult) {
  if (!focusEnabled) return null;

  const crop = focusCropFor(captureResult.size);
  const filename = captureResult.filename.replace(/\.png$/i, '-focus.svg');
  const path = resolve(outputDir, filename);
  const viewBox = [
    formatNumber(crop.left),
    formatNumber(crop.top),
    formatNumber(crop.width),
    formatNumber(crop.height),
  ].join(' ');

  const svg = [
    `<svg xmlns="http://www.w3.org/2000/svg" width="${focusWidth}" height="${focusHeight}" viewBox="${viewBox}">`,
    `  <image href="./${captureResult.filename}" x="0" y="0" width="${captureResult.size.width}" height="${captureResult.size.height}" />`,
    '</svg>',
    '',
  ].join('\n');

  writeFileSync(path, svg);
  console.log(
    `[showcase-benchmark] focus: ${filename} · crop ${viewBox} -> ${focusWidth}x${focusHeight}`
  );

  return { path, filename, crop };
}

function center(bounds) {
  return {
    x: Math.round((bounds.left + bounds.right) / 2),
    y: Math.round((bounds.top + bounds.bottom) / 2),
  };
}

mkdirSync(outputDir, { recursive: true });

console.log(`[showcase-benchmark] device: ${serial}`);
console.log(
  `[showcase-benchmark] launching showcase${reuseApp ? ' (reuse app)' : ''}...`
);

if (!reuseApp) {
  adb(['shell', 'am', 'force-stop', PACKAGE]);
}

launchShowcase();

// Deep-linking the same screen repeatedly can preserve the previous React state
// while only updating route params. Always normalize the screen to CLOSED before
// taking the first screenshot instead of assuming a fresh mount.
const openButton = await ensureClosedShowcase();

// Expo Image decoding can lag route interactivity by more than a frame on a
// cold launch. Wait long enough for the hero/stills to be materially present,
 // otherwise the benchmark is worthless.
await sleep(settleMs);

const closedCapture = capture('closed');
const closedFocus = writeFocusArtifact(closedCapture);
console.log(`[showcase-benchmark] closed: ${closedCapture.path}`);

const tap = center(openButton);
adb(['shell', 'input', 'tap', String(tap.x), String(tap.y)]);

await waitForDescription('Close tonight panel');
// OPEN_MS is 500ms; wait past the animation endpoint before the benchmark.
await sleep(750);

const openCapture = capture('open');
const openFocus = writeFocusArtifact(openCapture);
console.log(`[showcase-benchmark] open:   ${openCapture.path}`);

const metadata = {
  capturedAt: new Date().toISOString(),
  serial,
  package: PACKAGE,
  route,
  device: adb(['shell', 'getprop', 'ro.product.model']).trim(),
  android: adb(['shell', 'getprop', 'ro.build.version.release']).trim(),
  wmSize: adb(['shell', 'wm', 'size']).trim(),
  files: {
    closed: closedCapture.filename,
    open: openCapture.filename,
    closedFocus: closedFocus?.filename ?? null,
    openFocus: openFocus?.filename ?? null,
  },
  focus: focusEnabled
    ? {
        normalizedSize: {
          width: focusWidth,
          height: focusHeight,
        },
        topRatio: focusTopRatio,
        closedCrop: closedFocus?.crop ?? null,
        openCrop: openFocus?.crop ?? null,
      }
    : null,
};

const metaFilename = filePrefix ? `${filePrefix}-meta.json` : 'meta.json';
writeFileSync(
  resolve(outputDir, metaFilename),
  `${JSON.stringify(metadata, null, 2)}\n`
);

const previewPath = resolve(
  outputDir,
  filePrefix ? `${filePrefix}-index.html` : 'index.html'
);
const previewClosed = metadata.files.closedFocus ?? metadata.files.closed;
const previewOpen = metadata.files.openFocus ?? metadata.files.open;
const focusLabel = metadata.focus
  ? `${metadata.focus.normalizedSize.width}×${metadata.focus.normalizedSize.height} normalized crop · top ${metadata.focus.topRatio}`
  : 'full screenshot';

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
  h1 { margin: 0 0 6px; font-size: 18px; font-weight: 600; }
  .lead {
    margin: 0 0 18px;
    color: #999;
    font: 11px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace;
  }
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
    object-fit: contain;
    background: #222;
    border: 1px solid #333;
  }
  .focused img {
    aspect-ratio: ${focusWidth} / ${focusHeight};
    max-height: calc(100vh - 145px);
  }
  details {
    margin-top: 20px;
    border-top: 1px solid #2b2b2b;
    padding-top: 14px;
  }
  summary {
    cursor: pointer;
    color: #999;
    font-size: 12px;
  }
  details .grid { margin-top: 14px; }
  details img { max-height: 75vh; }
  .meta {
    margin-top: 16px;
    font: 11px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace;
    color: #888;
  }
</style>
</head>
<body>
  <h1>Progressive showcase — normalized focus</h1>
  <p class="lead">${focusLabel}. Raw captures are preserved below.</p>
  <div class="grid focused">
    <figure>
      <figcaption>Closed · focus</figcaption>
      <img src="./${previewClosed}?t=${Date.now()}" alt="Closed focused benchmark" />
    </figure>
    <figure>
      <figcaption>Open · focus</figcaption>
      <img src="./${previewOpen}?t=${Date.now()}" alt="Open focused benchmark" />
    </figure>
  </div>
  <details>
    <summary>Full device screenshots</summary>
    <div class="grid">
      <figure>
        <figcaption>Closed · full</figcaption>
        <img src="./${metadata.files.closed}?t=${Date.now()}" alt="Closed full benchmark" />
      </figure>
      <figure>
        <figcaption>Open · full</figcaption>
        <img src="./${metadata.files.open}?t=${Date.now()}" alt="Open full benchmark" />
      </figure>
    </div>
  </details>
  <div class="meta">
    ${metadata.device} · Android ${metadata.android} · ${metadata.wmSize}
  </div>
</body>
</html>
`;
if (!noPreview) {
  writeFileSync(previewPath, previewHtml);
}

console.log(
  `[showcase-benchmark] saved benchmark pair in ${outputDir}`
);

if (!noPreview) {
  console.log(`[showcase-benchmark] preview: ${previewPath}`);

  if (autoOpen && process.platform === 'darwin' && existsSync(previewPath)) {
    try {
      run('open', [previewPath]);
    } catch {
      // The benchmark itself succeeded; preview opening is convenience only.
    }
  }
}
