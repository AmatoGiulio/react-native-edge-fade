# Baseline — blur 3-band (bands3)

Data: 2026-07-10 · Device: emulator Pixel_8 (sdk_gphone64_arm64, API 36) · debug build, Metro attached.
Scenario: GalleryScreen (FlashList foto), mode="blur", blurRadius=28, fadeBottom attivo, frost veil default.
Batch: 10 swipe (5 giù + 5 su, 400ms, delay 300ms).

## Osservazione chiave (evidenza forte)

Durante lo scroll `EdgeFadeView.dispatchDraw` NON viene reinvocato: il nodo
`content` referenzia i RenderNode dei figli, che HWUI aggiorna per riferimento.
Il costo per frame del blur è interamente GPU/RenderThread. Il bench nanoTime
(CSV) misura solo le invalidazioni (primo render, cambio props/layout).
Prova: CSV vuoto dopo 428 frame di scroll; popolato solo al primo render.

## CPU (nanoTime, µs) — primo render (bench_firstrender.csv)

| mark | draw #0 (cold) | draw #1 |
|---|---|---|
| bl_contentRecord | 1020 | 96 |
| bl_levelNodes | 70 | 80 |
| bl_sharpBase | 3 | 10 |
| bl_compositeLevels | 1357 | 433 |
| bl_frostVeil | 4 | 1 |

## Frame stats scroll (gfxinfo_scroll.txt, ~428 frame)

- Janky frames: 6 (1.40%) · legacy: 236 (55.14%)
- Frame time: p50 24ms · p90 29ms · p95 32ms · p99 34ms
- GPU: p50 19ms · p90 21ms

## RAM (dumpsys meminfo, PSS KB)

- idle post-load: ~452 MB PSS · post-scroll: ~498 MB PSS
- NB: campo Graphics=0 sull'emulatore (accounting GPU non attendibile) —
  il confronto RAM tra strategie va fatto su TOTAL PSS a parità di scenario,
  meglio se ripetuto su device reale.

## Limiti dichiarati

- Emulatore: blur HWUI gira su GPU host via ANGLE/SwiftShader; i tempi GPU non
  sono rappresentativi di un device reale in valore assoluto, ma il confronto
  relativo bands3 vs singlePass sullo stesso emulatore resta valido come proxy.
- Manca un run di controllo mode="mask" per isolare il costo del blur dal costo
  della lista.

Visual: visual_gallery_blur28.png (full-res).
