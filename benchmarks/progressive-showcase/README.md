# Progressive showcase benchmark

Run from the repository root:

```bash
yarn benchmark:showcase
```

The script:

1. selects the single connected Android device;
2. force-stops the example app;
3. opens `edgefade://showcase` directly;
4. waits for the closed state to be interactive;
5. captures `closed.png`;
6. taps the accessible **TONIGHT** trigger;
7. waits for the open animation to settle;
8. captures `open.png`;
9. writes deterministic normalized focus crops for both states.

The raw device screenshots are preserved, while the comparison artifacts use a
fixed **530×565** viewport matching the reference framing. On a standard phone
capture the crop spans the full screen width and starts at 34% of the source
height, so the benchmark concentrates on the progressive-blur/material area
instead of wasting most of the comparison on unrelated UI.

Outputs are overwritten on every run:

```text
benchmarks/progressive-showcase/current/
  closed.png
  open.png
  closed-focus.svg
  open-focus.svg
  meta.json
```

With more than one connected device:

```bash
yarn benchmark:showcase --serial <adb-serial>
```

or set `ADB_SERIAL`.

The example app must already be installed and, for a development build, Metro must be available.


## Preview

After every run the script also writes:

```text
benchmarks/progressive-showcase/current/index.html
```

On macOS it opens that page automatically with the normalized **Closed** and
**Open** crops side by side. The full screenshots remain available in a
collapsible section underneath.

Disable auto-open with:

```bash
yarn benchmark:showcase --no-open
```

Cold Expo Image decoding can take longer than route interactivity. The default benchmark waits 2200ms before the closed capture. Override it when needed:

```bash
yarn benchmark:showcase --settle-ms 3500
```

## Focus normalization

The default comparison crop is intentionally deterministic:

```text
output: 530 × 565
horizontal crop: centered, full device width when possible
vertical start: 0.34 × source height
aspect ratio: identical to the reference crop
```

Override it when calibrating a different reference:

```bash
yarn benchmark:showcase --focus-top 0.32
yarn benchmark:showcase --focus-width 530 --focus-height 565
```

Disable focused artifacts entirely with:

```bash
yarn benchmark:showcase --no-focus
```

The material sweep also consumes the generated `*-focus.svg` artifacts, so
reference and target panels are displayed at the same normalized aspect and
visual size.
