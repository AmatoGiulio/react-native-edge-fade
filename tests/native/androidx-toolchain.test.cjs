'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const config = require('../../scripts/configure-androidx-blur.cjs');

// Minimal fixtures following Expo's inspected Groovy bare-template contracts.
const root = `buildscript {
  repositories { google(); mavenCentral() }
  dependencies {
    classpath('com.android.tools.build:gradle')
    classpath('com.facebook.react:react-native-gradle-plugin')
    classpath('org.jetbrains.kotlin:kotlin-gradle-plugin')
  }
}
apply plugin: "expo-root-project"
apply plugin: "com.facebook.react.rootproject"
`;
const app = `apply plugin: "com.android.application"
apply plugin: "org.jetbrains.kotlin.android"
android {
  compileSdk rootProject.ext.compileSdkVersion
  defaultConfig {
    targetSdkVersion rootProject.ext.targetSdkVersion
    minSdkVersion rootProject.ext.minSdkVersion
  }
}
`;
const properties = '# custom user setting\norg.gradle.jvmargs=-Xmx4g\nedgeFadeAndroidxBlur=true\n';
const wrapper = 'distributionUrl=https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip\n';

function fixture(t, overrides = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'edgefade-androidx-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const files = { 'build.gradle': root, 'app/build.gradle': app, 'gradle.properties': properties,
    'gradle/wrapper/gradle-wrapper.properties': wrapper, ...overrides };
  for (const [name, data] of Object.entries(files)) {
    const file = path.join(dir, name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, data);
  }
  return dir;
}

test('pins AGP and establishes root SDK before Expo defaults', () => {
  const result = config.configureRoot(root);
  assert.match(result, /classpath\('com.android.tools.build:gradle:9.1.1'\)/);
  assert.ok(result.indexOf('ext.compileSdkVersion = 37') < result.indexOf('apply plugin: "expo-root-project"'));
  assert.match(result, /classpath\('org.jetbrains.kotlin:kotlin-gradle-plugin'\)/);
  assert.equal(config.configureRoot(result), result);
});

test('supports versioned, double-quoted and non-parenthesized AGP', () => {
  for (const line of ['classpath "com.android.tools.build:gradle:8.12.0"', 'classpath("com.android.tools.build:gradle:8.12.0")']) {
    const source = root.replace("classpath('com.android.tools.build:gradle')", line);
    assert.match(config.configureRoot(source), /com.android.tools.build:gradle:9.1.1/);
  }
});

test('preserves Windows CRLF and a UTF-8 BOM', () => {
  const source = '\uFEFF' + root.replace(/\n/g, '\r\n');
  const result = config.configureRoot(source);
  assert.ok(result.startsWith('\uFEFF'));
  assert.equal(result.replace(/\r\n/g, '').includes('\n'), false);
  assert.equal(config.configureRoot(result), result);
  const appCrlf = app.replace(/\n/g, '\r\n');
  assert.equal(config.configureApp(appCrlf), appCrlf);
});

test('app follows root SDK without changing runtime SDKs or Kotlin plugin', () => {
  assert.equal(config.configureApp(app), app);
  const output = config.configureApp(app.replace('compileSdk rootProject.ext.compileSdkVersion', 'compileSdkVersion 36'));
  assert.equal(output, app);
});

test('properties are exact and idempotent', () => {
  let source = 'android.newDsl=true\r\n# keep this comment\r\n';
  source = config.setProperty(source, 'android.newDsl', 'false');
  assert.equal(source, 'android.newDsl=false\r\n# keep this comment\r\n');
  assert.equal(config.setProperty(source, 'android.newDsl', 'false'), source);
  assert.throws(() => config.setProperty('android.newDsl=true\nandroid.newDsl=false\n', 'android.newDsl', 'false'), /Duplicate/);
});

test('configuration applies both AGP 9 compatibility flags without suppressing checks', (t) => {
  const dir = fixture(t);
  const changes = config.plan(dir);
  const props = changes.find((entry) => entry.name === 'gradle.properties').after;
  for (const line of ['edgeFadeAndroidxBlur=true', 'android.compileSdkVersion=37', 'android.builtInKotlin=false', 'android.newDsl=false']) {
    assert.ok(props.includes(line));
  }
  assert.ok(props.includes('org.gradle.jvmargs=-Xmx4g'));
  assert.doesNotMatch(props, /suppressUnsupportedCompileSdk|skip-metadata|checkAarMetadata/);
  assert.equal(fs.readFileSync(path.join(dir, 'build.gradle'), 'utf8'), root);
});

test('writes only changed native files and preserves first originals', (t) => {
  const dir = fixture(t);
  config.applyPlan(config.plan(dir));
  assert.deepEqual(config.plan(dir), []);
  assert.equal(fs.readFileSync(path.join(dir, 'build.gradle' + config.BACKUP_SUFFIX), 'utf8'), root);
  assert.equal(fs.readFileSync(path.join(dir, 'gradle.properties' + config.BACKUP_SUFFIX), 'utf8'), properties);
  assert.equal(fs.readFileSync(path.join(dir, 'gradle/wrapper/gradle-wrapper.properties'), 'utf8'), wrapper);
  config.applyPlan(config.plan(dir));
  assert.equal(fs.readFileSync(path.join(dir, 'build.gradle' + config.BACKUP_SUFFIX), 'utf8'), root);
});

test('rejects old wrappers before writes', (t) => {
  const dir = fixture(t, { 'gradle/wrapper/gradle-wrapper.properties': wrapper.replace('9.3.1', '8.13') });
  assert.throws(() => config.plan(dir), /Gradle >= 9.3.1/);
  assert.equal(fs.readFileSync(path.join(dir, 'build.gradle'), 'utf8'), root);
});

test('rejects custom layouts and newer explicit AGP instead of guessing', () => {
  assert.throws(() => config.configureRoot(root.replace(':gradle\')', ':gradle:9.2.0\')')), /custom\/newer/);
  assert.throws(() => config.configureRoot(root.replace('expo-root-project', 'custom-root')), /Expo root plugin/);
  assert.throws(() => config.configureApp(app.replace('compileSdk rootProject.ext.compileSdkVersion', 'compileSdk = libs.versions.compileSdk')), /app compileSdk/);
});

test('detects concurrent file edits before writing', (t) => {
  const dir = fixture(t);
  const changes = config.plan(dir);
  fs.appendFileSync(path.join(dir, 'gradle.properties'), '# newer local change\n');
  assert.throws(() => config.applyPlan(changes), /changed while planning/);
  assert.equal(fs.readFileSync(path.join(dir, 'build.gradle'), 'utf8'), root);
});
