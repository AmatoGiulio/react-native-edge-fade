import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const PACKAGE = 'com.edgefadeexample';
const ROUTE = 'edgefade://showcase';
const UI_DUMP = '/sdcard/edgefade-showcase-diagnostic.xml';
const STAGES = ['FULL', 'CAP', 'GAUSS'];

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const runId = gitHead() ?? 'unknown';
const outputDir = resolve(
  repoRoot,
  readArg('--output-dir') ??
    `benchmarks/progressive-showcase/diagnostic-runs/${runId}`
);
const requestedSerial = readArg('--serial') ?? process.env.ADB_SERIAL;
const settleMs = Number(readArg('--settle-ms') ?? 1000);
const rebuild = hasArg('--build');
const noPreview = hasArg('--no-preview');

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
    cwd: options.cwd,
    encoding: hasEncoding ? options.encoding : 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    stdio: options.stdio ?? ['ignore', 'pipe', 'pipe'],
  });
}

function connectedDevices() {
  return run('adb', ['devices'])
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
  (devices.length === 1 ? devices[0] : undefined);

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

function nodeBounds(xml, predicate) {
  const nodes = xml.match(/<node\b[^>]*>/g) ?? [];
  for (const node of nodes) {
    if (!predicate(node)) continue;
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

function boundsForDescription(xml, description) {
  return nodeBounds(xml, (node) =>
    node.includes(`content-desc="${description}"`)
  );
}

function hasText(xml, text) {
  return (
    xml.includes(`text="${text}"`) ||
    xml.includes(`content-desc="${text}"`)
  );
}

function center(bounds) {
  return {
    x: Math.round((bounds.left + bounds.right) / 2),
    y: Math.round((bounds.top + bounds.bottom) / 2),
  };
}

async function waitFor(predicate, label, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;

  while (Date.now() < deadline) {
    try {
      const xml = dumpUi();
      const value = predicate(xml);
      if (value) return value;
    } catch (error) {
      lastError = error;
    }
    await sleep(250);
  }

  const suffix = lastError ? ` Last adb error: ${lastError.message}` : '';
  throw new Error(`Timed out waiting for ${label}.${suffix}`);
}

async function waitForDescription(description, timeoutMs = 15000) {
  return waitFor(
    (xml) => boundsForDescription(xml, description),
    `accessibility label "${description}"`,
    timeoutMs
  );
}

async function waitForStage(stage, timeoutMs = 8000) {
  return waitFor(
    (xml) => (hasText(xml, stage) ? stage : null),
    `debug stage ${stage}`,
    timeoutMs
  );
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
    ROUTE,
    '-p',
    PACKAGE,
  ]);
}

async function panelState() {
  return waitFor((xml) => {
    const open = boundsForDescription(xml, 'Open perfection menu');
    if (open) return { state: 'closed', bounds: open };

    const close = boundsForDescription(xml, 'Close perfection menu');
    if (close) return { state: 'open', bounds: close };

    return null;
  }, 'perfection panel toggle');
}

async function ensureClosed() {
  let toggle = await panelState();
  if (toggle.state === 'closed') return toggle.bounds;

  // "Close perfection menu" is a full-screen backdrop. Tapping its geometric
  // center is unreliable because the open-menu content can sit on top of that
  // coordinate and intercept the touch. Use safe points in the upper backdrop
  // instead, then retry once if Android still reports the panel as open.
  const safePoints = [
    {
      x: Math.round((toggle.bounds.left + toggle.bounds.right) / 2),
      y: Math.round(
        toggle.bounds.top +
          (toggle.bounds.bottom - toggle.bounds.top) * 0.22
      ),
    },
    {
      x: Math.round(
        toggle.bounds.left +
          (toggle.bounds.right - toggle.bounds.left) * 0.35
      ),
      y: Math.round(
        toggle.bounds.top +
          (toggle.bounds.bottom - toggle.bounds.top) * 0.30
      ),
    },
  ];

  for (const point of safePoints) {
    adb([
      'shell',
      'input',
      'tap',
      String(point.x),
      String(point.y),
    ]);

    try {
      const openBounds = await waitForDescription('Open perfection menu', 3000);
      await sleep(400);
      return openBounds;
    } catch {
      toggle = await panelState();
      if (toggle.state === 'closed') {
        await sleep(400);
        return toggle.bounds;
      }
    }
  }

  throw new Error(
    'Could not close perfection menu after tapping safe backdrop points.'
  );
}

async function ensureOpen() {
  const toggle = await panelState();
  if (toggle.state === 'open') return toggle.bounds;

  const tap = center(toggle.bounds);
  adb(['shell', 'input', 'tap', String(tap.x), String(tap.y)]);
  await waitForDescription('Close perfection menu');
  await sleep(450);
  return waitForDescription('Close perfection menu');
}

function stageBounds(xml) {
  for (const stage of STAGES) {
    const bounds = nodeBounds(
      xml,
      (node) =>
        node.includes(`text="${stage}"`) ||
        node.includes(`content-desc="${stage}"`)
    );
    if (bounds) return { stage, bounds };
  }
  return null;
}

async function debugButtonBounds() {
  return waitFor(
    (xml) => {
      const direct = boundsForDescription(xml, 'Cycle progressive debug stage');
      if (direct) return direct;

      // React Native may expose the visible Text child but not the Pressable's
      // accessibilityLabel to uiautomator. The FULL/CAP/GAUSS chip itself is
      // still tappable, so fall back to the visible stage node bounds.
      return stageBounds(xml)?.bounds ?? null;
    },
    'diagnostic stage chip'
  );
}

async function currentStage() {
  const xml = dumpUi();
  return stageBounds(xml)?.stage ?? null;
}

async function setStage(target) {
  for (let attempt = 0; attempt < 4; attempt += 1) {
    const stage = await currentStage();
    if (stage === target) return;

    const bounds = await debugButtonBounds();
    const tap = center(bounds);
    adb(['shell', 'input', 'tap', String(tap.x), String(tap.y)]);
    await sleep(250);
  }

  throw new Error(
    `Could not switch diagnostic stage to ${target}; current=${await currentStage()}`
  );
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
  const filename = `${runId}-${name}.png`;
  const path = resolve(outputDir, filename);
  const png = adb(['exec-out', 'screencap', '-p'], { encoding: null });
  const size = pngSize(png);
  writeFileSync(path, png);
  console.log(
    `[showcase-diagnostic] ${name}: ${size.width}x${size.height} · ${Math.round(png.length / 1024)} KiB`
  );
  return { name, file: filename, size };
}

function gitHead() {
  try {
    return run('git', ['rev-parse', '--short', 'HEAD'], {
      cwd: repoRoot,
    }).trim();
  } catch {
    return null;
  }
}

if (!Number.isFinite(settleMs) || settleMs < 0) {
  throw new Error('--settle-ms must be a non-negative number.');
}

mkdirSync(outputDir, { recursive: true });

console.log(`[showcase-diagnostic] device: ${serial}`);
console.log(`[showcase-diagnostic] output: ${outputDir}`);

if (rebuild) {
  const gradle =
    process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
  console.log('[showcase-diagnostic] installing Debug APK...');
  run(gradle, ['app:installDebug'], {
    cwd: resolve(repoRoot, 'example/android'),
    stdio: 'inherit',
  });
}

adb(['shell', 'am', 'force-stop', PACKAGE]);
launchShowcase();

await waitFor(
  (xml) => stageBounds(xml)?.bounds ?? null,
  'visible FULL/CAP/GAUSS diagnostic chip',
  20000
);
await ensureClosed();
await setStage('FULL');
await sleep(settleMs);

const captures = [];

for (const stage of STAGES) {
  await ensureClosed();
  await setStage(stage);
  await sleep(350);
  captures.push(capture(`closed-${stage.toLowerCase()}`));

  await ensureOpen();
  await sleep(500);
  captures.push(capture(`open-${stage.toLowerCase()}`));

  await ensureClosed();
  await sleep(250);
}

// Leave the app in the normal baseline diagnostic state.
await setStage('FULL');
await ensureClosed();

const metadata = {
  capturedAt: new Date().toISOString(),
  runId,
  commit: runId,
  serial,
  package: PACKAGE,
  route: ROUTE,
  device: adb(['shell', 'getprop', 'ro.product.model']).trim(),
  android: adb(['shell', 'getprop', 'ro.build.version.release']).trim(),
  wmSize: adb(['shell', 'wm', 'size']).trim(),
  stages: STAGES,
  captures: Object.fromEntries(
    captures.map((item) => [item.name, item.file])
  ),
};

writeFileSync(
  resolve(outputDir, `${runId}-meta.json`),
  `${JSON.stringify(metadata, null, 2)}\n`
);

const cacheBust = Date.now();
const rows = STAGES.map((stage) => {
  const id = stage.toLowerCase();
  return `
    <section>
      <h2>${stage}</h2>
      <div class="pair">
        <figure>
          <figcaption>CLOSED</figcaption>
          <img src="./${runId}-closed-${id}.png?t=${cacheBust}" alt="${stage} closed" />
        </figure>
        <figure>
          <figcaption>OPEN</figcaption>
          <img src="./${runId}-open-${id}.png?t=${cacheBust}" alt="${stage} open" />
        </figure>
      </div>
    </section>`;
}).join('\n');

const html = `<!doctype html>
<html>
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>Edge Fade Showcase Diagnostic</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    padding: 24px;
    background: #111;
    color: #eee;
    font-family: -apple-system, BlinkMacSystemFont, sans-serif;
  }
  h1 { margin: 0 0 8px; font-size: 20px; }
  .meta { color: #888; font: 11px/1.5 ui-monospace, monospace; margin-bottom: 24px; }
  section { margin: 0 0 32px; }
  h2 { margin: 0 0 10px; font-size: 14px; letter-spacing: .08em; }
  .pair { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
  figure { margin: 0; }
  figcaption { color: #999; font-size: 11px; margin-bottom: 6px; }
  img {
    display: block;
    width: 100%;
    max-height: 82vh;
    object-fit: contain;
    background: #1b1b1b;
    border: 1px solid #2f2f2f;
  }
</style>
</head>
<body>
  <h1>Progressive showcase diagnostic</h1>
  <div class="meta">
    commit ${metadata.commit ?? 'unknown'} · ${metadata.device} · Android ${metadata.android}
  </div>
  ${rows}
</body>
</html>
`;

const previewPath = resolve(outputDir, `${runId}-index.html`);
writeFileSync(previewPath, html);

console.log('[showcase-diagnostic] done');
for (const captureResult of captures) {
  console.log(`  ${captureResult.name}: ${resolve(outputDir, captureResult.file)}`);
}
console.log(`  meta: ${resolve(outputDir, `${runId}-meta.json`)}`);
console.log(`  preview: ${previewPath}`);

if (
  !noPreview &&
  process.platform === 'darwin' &&
  existsSync(previewPath)
) {
  try {
    run('open', [previewPath]);
  } catch {
    // Screenshot capture succeeded; opening the preview is convenience only.
  }
}
