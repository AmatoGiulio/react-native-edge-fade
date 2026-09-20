import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const captureScript = resolve(repoRoot, 'scripts', 'capture-showcase-benchmark.mjs');
const runsRoot = resolve(
  repoRoot,
  'benchmarks',
  'progressive-showcase',
  'sweep',
  'runs'
);

function runId(date = new Date()) {
  const pad = (value) => String(value).padStart(2, '0');
  return [
    date.getFullYear(),
    pad(date.getMonth() + 1),
    pad(date.getDate()),
  ].join('-') +
    '_' +
    [pad(date.getHours()), pad(date.getMinutes()), pad(date.getSeconds())].join('-');
}

const outputRoot = resolve(runsRoot, runId());

function readArg(name) {
  const index = process.argv.indexOf(name);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

function hasArg(name) {
  return process.argv.includes(name);
}

function run(command, args, options = {}) {
  return execFileSync(command, args, {
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024,
    stdio: options.stdio ?? 'inherit',
  });
}

const serial = readArg('--serial') ?? process.env.ADB_SERIAL;
const settleMs = readArg('--settle-ms') ?? '2200';
const full = hasArg('--full');
const autoOpen = !hasArg('--no-open');
const referenceClosed = readArg('--reference-closed');
const referenceOpen = readArg('--reference-open');

// Stage 2: the first sweep ruled out the old 0.96 wash and showed the useful
// range around material 0.24 with a full-length progression. Sweep onset/depth
// next, without changing the measured 150px radius.
const quickProfiles = [];
for (const material of [0.16, 0.24, 0.32]) {
  for (const scale of [0.62, 0.70, 0.78]) {
    const m = String(Math.round(material * 100)).padStart(2, '0');
    const s = String(Math.round(scale * 100));
    quickProfiles.push({
      id: 'm' + m + '-s' + s,
      material,
      progression: 1.0,
      scale,
    });
  }
}

const fullProfiles = [];
for (const material of [0.08, 0.16, 0.24, 0.32, 0.40]) {
  for (const progression of [0.94, 1.0]) {
    for (const scale of [0.62, 0.70, 0.78]) {
      const m = String(Math.round(material * 100)).padStart(2, '0');
      const p = String(Math.round(progression * 100));
      const s = String(Math.round(scale * 100));
      fullProfiles.push({
        id: 'm' + m + '-p' + p + '-s' + s,
        material,
        progression,
        scale,
      });
    }
  }
}

const profiles = full ? fullProfiles : quickProfiles;
const referenceRoot = resolve(repoRoot, 'benchmarks', 'progressive-showcase', 'reference');
if (referenceClosed || referenceOpen) {
  if (!referenceClosed || !referenceOpen) {
    throw new Error('Pass both --reference-closed and --reference-open.');
  }
  mkdirSync(referenceRoot, { recursive: true });
  copyFileSync(resolve(referenceClosed), resolve(referenceRoot, 'closed.png'));
  copyFileSync(resolve(referenceOpen), resolve(referenceRoot, 'open.png'));
}

const hasReference =
  existsSync(resolve(referenceRoot, 'closed.png')) &&
  existsSync(resolve(referenceRoot, 'open.png'));
mkdirSync(outputRoot, { recursive: true });

if (hasReference) {
  copyFileSync(
    resolve(referenceRoot, 'closed.png'),
    resolve(outputRoot, 'reference-closed.png')
  );
  copyFileSync(
    resolve(referenceRoot, 'open.png'),
    resolve(outputRoot, 'reference-open.png')
  );
}

console.log('[showcase-sweep] run: ' + outputRoot);
console.log(
  '[showcase-sweep] ' +
    profiles.length +
    ' profiles · radius=150px · closedDepth=112 · stage2 material/field-depth sweep'
);

for (const [index, profile] of profiles.entries()) {
  const route =
    'edgefade://showcase?bench=' +
    profile.material +
    ',' +
    profile.progression +
    ',150,112,' +
    profile.scale;
  const relativeOutput =
    'benchmarks/progressive-showcase/sweep/runs/' +
    outputRoot.split('/').pop();
  const prefix =
    String(index + 1).padStart(2, '0') + '-' + profile.id;

  console.log(
    '\n[showcase-sweep] [' +
      (index + 1) +
      '/' +
      profiles.length +
      '] ' +
      profile.id +
      ' material=' +
      profile.material +
      ' progression=' +
      profile.progression +
      ' scale=' +
      profile.scale
  );

  const args = [
    captureScript,
    '--route', route,
    '--output-dir', relativeOutput,
    '--prefix', prefix,
    '--settle-ms', settleMs,
    '--no-open',
    '--no-preview',
  ];
  if (index > 0) args.push('--reuse-app');
  if (serial) args.push('--serial', serial);
  run(process.execPath, args);
}

const cards = profiles.map((profile, index) => {
  const prefix = String(index + 1).padStart(2, '0') + '-' + profile.id;
  return [
    '<section class="profile">',
    '<header><strong>' +
      prefix +
      '</strong><span>material ' +
      profile.material +
      ' · progression ' +
      profile.progression +
      ' · scale ' +
      profile.scale +
      '</span></header>',
    '<div class="pair">',
    '<figure><figcaption>closed</figcaption><img src="./' + prefix + '-closed.png?t=' + Date.now() + '"></figure>',
    '<figure><figcaption>open</figcaption><img src="./' + prefix + '-open.png?t=' + Date.now() + '"></figure>',
    '</div>',
    '</section>',
  ].join('\n');
}).join('\n');

const referenceSection = hasReference
  ? [
      '<section class="profile reference">',
      '<header><strong>REFERENCE</strong><span>fixed visual target</span></header>',
      '<div class="pair">',
      '<figure><figcaption>closed ref</figcaption><img src="./reference-closed.png?t=' + Date.now() + '"></figure>',
      '<figure><figcaption>open ref</figcaption><img src="./reference-open.png?t=' + Date.now() + '"></figure>',
      '</div>',
      '</section>',
    ].join('\n')
  : '<p class="missing-ref">Add benchmarks/progressive-showcase/reference/closed.png and open.png to pin the reference above the sweep.</p>';

const html = [
  '<!doctype html>',
  '<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">',
  '<title>Progressive Showcase Sweep</title>',
  '<style>',
  ':root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;padding:22px;background:#0d0d0d;color:#eee;font-family:-apple-system,BlinkMacSystemFont,sans-serif}',
  'h1{margin:0 0 6px;font-size:20px}.lead{margin:0 0 22px;color:#999;font-size:12px}.missing-ref{padding:12px;border:1px dashed #444;color:#aaa;font-size:12px}.profile{border-top:1px solid #2c2c2c;padding:18px 0 28px}.reference{background:#151515;padding-left:12px;padding-right:12px}',
  'header{display:flex;gap:14px;align-items:baseline;margin-bottom:10px}header strong{font-size:14px}header span{color:#999;font:11px ui-monospace,SFMono-Regular,Menlo,monospace}',
  '.pair{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}figure{margin:0}figcaption{margin-bottom:6px;color:#888;font-size:10px;text-transform:uppercase;letter-spacing:.09em}',
  'img{display:block;width:100%;max-height:82vh;object-fit:contain;background:#171717;border:1px solid #292929}',
  '</style></head><body>',
  '<h1>Progressive material sweep</h1>',
  '<p class="lead">Stage 2: radius 150px, progression near full-length. Compare material wash and vertical field depth.</p>',
  referenceSection,
  cards,
  '</body></html>',
].join('\n');

const previewPath = resolve(outputRoot, 'index.html');
writeFileSync(previewPath, html + '\n');
writeFileSync(
  resolve(outputRoot, 'profiles.json'),
  JSON.stringify(
    {
      run: outputRoot.split('/').pop(),
      full,
      profiles,
    },
    null,
    2
  ) + '\n'
);

console.log('\n[showcase-sweep] preview: ' + previewPath);

if (autoOpen && process.platform === 'darwin' && existsSync(previewPath)) {
  try {
    run('open', [previewPath]);
  } catch {
    // Captures succeeded; browser opening is convenience only.
  }
}
