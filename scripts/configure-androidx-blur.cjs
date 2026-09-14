#!/usr/bin/env node
'use strict';

// Example-only opt-in. No node_modules edits, SDK downloads, clean or prebuild.
// Native files are generated/untracked, so rerun this after Expo prebuild.
const fs = require('node:fs');
const path = require('node:path');
const AGP = '9.1.1';
const SDK = 37;
const SDK_MINOR = 1;
const MIN_GRADLE = [9, 3, 1];
const BEGIN = '// @generated begin edge-fade-androidx-toolchain';
const END = '// @generated end edge-fade-androidx-toolchain';
const BACKUP_SUFFIX = '.before-androidx-blur';

function compare(a, b) {
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const delta = (a[i] || 0) - (b[i] || 0);
    if (delta) return delta;
  }
  return 0;
}

function replaceExactlyOnce(source, pattern, replacement, label) {
  if ([...source.matchAll(pattern)].length !== 1) {
    throw new Error(`Unrecognized ${label}; no files changed. Review this custom Gradle layout manually.`);
  }
  return source.replace(pattern, replacement);
}

function setProperty(source, key, value) {
  const eol = source.includes('\r\n') ? '\r\n' : '\n';
  const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const pattern = new RegExp(`^[ \\t]*${escaped}[ \\t]*(?:=|:)[^\\r\\n]*`, 'gm');
  const matches = [...source.matchAll(pattern)];
  if (matches.length > 1) throw new Error(`Duplicate ${key}; resolve duplicates before configuring AndroidX.`);
  if (matches.length) return source.replace(pattern, `${key}=${value}`);
  return source + (source.endsWith('\n') || !source ? '' : eol) + `${key}=${value}${eol}`;
}

function configureRoot(source) {
  const eol = source.includes('\r\n') ? '\r\n' : '\n';
  const bom = source.startsWith('\uFEFF') ? '\uFEFF' : '';
  source = source.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n');
  const finish = (value) => bom + value.replace(/\n/g, eol);
  const coordinate = /\bcom\.android\.tools\.build:gradle(?::([^'"\s)]+))?/g;
  const dependencies = [...source.matchAll(coordinate)];
  if (dependencies.length !== 1) throw new Error('Expected one literal AGP classpath dependency; no files changed.');
  const current = dependencies[0][1];
  if (current && (!/^\d+\.\d+\.\d+$/.test(current) || compare(current.split('.').map(Number), [9, 1, 1]) > 0)) {
    throw new Error(`Refusing to replace custom/newer AGP ${current}. Review this profile manually.`);
  }
  source = replaceExactlyOnce(
    source,
    /(^[ \t]*classpath[ \t]*(?:\([ \t]*)?)(['"])com\.android\.tools\.build:gradle(?::[^'"\r\n]+)?\2([ \t]*\)?[ \t]*;?[ \t]*(?:\/\/[^\r\n]*)?$)/gm,
    (_, prefix, quote, suffix) => `${prefix}${quote}com.android.tools.build:gradle:${AGP}${quote}${suffix}`,
    'root AGP dependency'
  );
  const block = [
    BEGIN,
    '// Expo expects an integer major version. Apply the minor version after each module DSL.',
    `ext.compileSdkVersion = ${SDK}`,
    'subprojects { p ->',
    '  ["com.android.application", "com.android.library"].each { pluginId ->',
    '    p.plugins.withId(pluginId) {',
    '      p.extensions.getByName("androidComponents").finalizeDsl { androidDsl ->',
    `        androidDsl.compileSdk = ${SDK}`,
    `        androidDsl.compileSdkMinor = ${SDK_MINOR}`,
    '      }',
    '    }',
    '  }',
    '}',
    END,
  ].join('\n');
  if (source.includes(BEGIN) || source.includes(END)) {
    return finish(replaceExactlyOnce(source, /\/\/ @generated begin edge-fade-androidx-toolchain[\s\S]*?\/\/ @generated end edge-fade-androidx-toolchain/g, block, 'managed SDK block'));
  }
  return finish(replaceExactlyOnce(source, /^[ \t]*apply plugin:[ \t]*['"]expo-root-project['"][ \t]*;?[ \t]*$/gm, (match) => `${block}\n${match}`, 'Expo root plugin'));
}

function configureApp(source) {
  const eol = source.includes('\r\n') ? '\r\n' : '\n';
  source = source.replace(/\r\n/g, '\n');
  // The template inherits this from the root; also handle an explicit integer.
  return replaceExactlyOnce(source,
    /^([ \t]*)compileSdk(?:Version)?[ \t]+(?:rootProject\.ext\.compileSdkVersion|\d+)[ \t]*;?[ \t]*(?:\/\/[^\r\n]*)?$/gm,
    '$1compileSdk rootProject.ext.compileSdkVersion', 'app compileSdk').replace(/\n/g, eol);
}

function plan(androidDir) {
  const names = ['build.gradle', 'app/build.gradle', 'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties'];
  const originals = {};
  for (const name of names) {
    const file = path.join(androidDir, name);
    if (!fs.existsSync(file)) throw new Error(`Missing ${file}. Generate Android first with yarn example expo prebuild --platform android.`);
    const data = fs.readFileSync(file, 'utf8');
    if (data.includes('\u0000')) throw new Error(`Unsupported encoding in ${file}; expected UTF-8.`);
    originals[name] = data;
  }
  const wrapper = originals[names[3]].match(/^distributionUrl=.*\/gradle-(\d+)\.(\d+)(?:\.(\d+))?-(?:bin|all)\.zip[ \t]*\r?$/m);
  if (!wrapper || compare(wrapper.slice(1).map((v) => Number(v || 0)), MIN_GRADLE) < 0) {
    throw new Error('Gradle >= 9.3.1 is required. Update the wrapper before rerunning; no files changed.');
  }
  if (Number(wrapper[1]) >= 10) throw new Error('This legacy Kotlin/DSL profile targets Gradle 9, not Gradle 10.');
  let properties = originals['gradle.properties'];
  for (const [key, value] of Object.entries({
    edgeFadeAndroidxBlur: 'true',
    'android.compileSdkVersion': String(SDK),
    // Documented AGP 9 opt-outs, NOT SDK or AAR metadata suppression.
    'android.builtInKotlin': 'false',
    'android.newDsl': 'false',
  })) properties = setProperty(properties, key, value);
  const outputs = {
    'build.gradle': configureRoot(originals['build.gradle']),
    'app/build.gradle': configureApp(originals['app/build.gradle']),
    'gradle.properties': properties,
  };
  return Object.entries(outputs).map(([name, after]) => ({
    file: path.join(androidDir, name), name, before: originals[name], after,
  })).filter((entry) => entry.before !== entry.after);
}

function applyPlan(changes) {
  // Finish ALL validation before writing. Preserve the first original on reruns.
  for (const entry of changes) {
    if (fs.readFileSync(entry.file, 'utf8') !== entry.before) {
      throw new Error(`${entry.name} changed while planning; no files changed.`);
    }
  }
  for (const entry of changes) {
    try { fs.writeFileSync(entry.file + BACKUP_SUFFIX, entry.before, { flag: 'wx' }); }
    catch (error) { if (error.code !== 'EEXIST') throw error; }
  }
  const written = [];
  try {
    for (const entry of changes) { written.push(entry); fs.writeFileSync(entry.file, entry.after); }
  } catch (error) {
    for (const entry of written) fs.writeFileSync(entry.file, entry.before);
    throw error;
  }
}

function main(args) {
  const flags = new Set(args);
  if (args.some((arg) => !['--dry-run', '--check'].includes(arg)) || flags.size !== args.length || flags.size > 1) {
    throw new Error('Usage: node scripts/configure-androidx-blur.cjs [--dry-run | --check]');
  }
  const androidDir = path.resolve(__dirname, '../example/android');
  const changes = plan(androidDir);
  console.log(`AndroidX example profile: AGP ${AGP}, compileSdk ${SDK}.${SDK_MINOR}, Gradle >= ${MIN_GRADLE.join('.')}.`);
  if (flags.has('--check')) {
    if (changes.length) throw new Error(`Profile not applied: ${changes.map((entry) => entry.name).join(', ')}`);
    console.log('PASS: source configuration matches. This is not an Android build result.');
    return;
  }
  if (!flags.has('--dry-run')) applyPlan(changes);
  for (const entry of changes) console.log(`${flags.has('--dry-run') ? 'Would update' : 'Updated'} ${entry.name}`);
  if (!changes.length) console.log('Already configured; no files changed.');
  if (changes.length && !flags.has('--dry-run')) console.log(`Originals saved as *${BACKUP_SUFFIX} next to each changed file.`);
  console.log('No targetSdk, minSdk, Kotlin version, wrapper, node_modules or rendering changes.');
  console.log('Next: yarn example android. Rerun this helper after regenerating Android with Expo prebuild.');
}

module.exports = { AGP, SDK, SDK_MINOR, BACKUP_SUFFIX, configureRoot, configureApp, setProperty, plan, applyPlan };
if (require.main === module) {
  try { main(process.argv.slice(2)); }
  catch (error) { console.error(`AndroidX setup failed: ${error.message}`); process.exitCode = 1; }
}
