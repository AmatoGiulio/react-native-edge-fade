import { test } from 'node:test';
import assert from 'node:assert/strict';
import { requireProgressiveBackend } from '../../scripts/progressive-backend.mjs';

const official =
  'EdgeFadeProgressive: Using official AndroidX progressive blur on API 33+';
const port = 'EdgeFadeProgressive: Using pure progressive AGSL blur on API 33+';
const gles =
  'EdgeFadeProgressive: Using GLES 3.0 continuous progressive blur on API 31-32';

test('identifies each actual renderer on eligible Android versions', () => {
  assert.equal(requireProgressiveBackend(official, 33), 'androidx');
  assert.equal(requireProgressiveBackend(port, 36), 'agsl');
  assert.equal(requireProgressiveBackend(gles, 31), 'gles');
});

test('never accepts the port as a successful official API benchmark', () => {
  assert.throws(() => requireProgressiveBackend(port, 36, 'androidx'));
  assert.throws(() => requireProgressiveBackend(official, 36, 'agsl'));
});

test('rejects fallback, ambiguous logs and impossible Android versions', () => {
  assert.throws(() => requireProgressiveBackend('using mask fallback', 36));
  assert.throws(() => requireProgressiveBackend(official + port, 36));
  assert.throws(() => requireProgressiveBackend(official, 32));
  assert.throws(() => requireProgressiveBackend(gles, 30));
});
