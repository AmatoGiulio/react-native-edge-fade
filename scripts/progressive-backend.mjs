const activations = {
  agsl: 'Using pure progressive AGSL blur on API 33+',
  androidx: 'Using official AndroidX progressive blur on API 33+',
  gles: 'Using GLES 3.0 continuous progressive blur on API 31-32',
};

export function requireProgressiveBackend(logcat, sdk, expected = 'auto') {
  if (!['auto', ...Object.keys(activations)].includes(expected)) {
    throw new Error('Expected backend must be auto, agsl, androidx or gles');
  }
  const allowed = sdk >= 33 ? ['agsl', 'androidx'] : sdk >= 31 ? ['gles'] : [];
  const active = allowed.filter((backend) =>
    logcat.includes(activations[backend])
  );
  if (active.length !== 1 || (expected !== 'auto' && active[0] !== expected)) {
    throw new Error(
      `Progressive backend mismatch: expected ${expected}, observed ${active.join(', ') || 'none'} on API ${sdk}`
    );
  }
  return active[0];
}
