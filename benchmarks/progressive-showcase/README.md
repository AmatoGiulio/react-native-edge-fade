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
8. captures `open.png`.

Outputs are overwritten on every run:

```text
benchmarks/progressive-showcase/current/
  closed.png
  open.png
  meta.json
```

With more than one connected device:

```bash
yarn benchmark:showcase --serial <adb-serial>
```

or set `ADB_SERIAL`.

The example app must already be installed and, for a development build, Metro must be available.
