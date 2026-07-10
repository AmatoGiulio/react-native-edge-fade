# Single-pass blur (SINGLE_PASS = true)

Stesso protocollo del baseline (vedi ../bands3/README.md): emulator Pixel_8
API 36, GalleryScreen, blur r=28, batch 10 swipe.

Strategia: 1 RenderNode per edge a raggio PIENO, composito attraverso una
maschera che segue la presence curve (opacity(t) = 1 − alpha(t)). Nessuna
rampa di raggio: il blur è uniforme e la curva ne regola la visibilità.

## CPU (nanoTime, µs) — primo render

| mark | draw #0 (cold) | draw #1 | bands3 #0 | bands3 #1 |
|---|---|---|---|---|
| bl_contentRecord | 1919 | 30 | 1020 | 96 |
| bl_sharpBase | 39 | 12 | 3 | 10 |
| bl_compositeLevels | 1031 | 148 | 1357 | 433 |
| bl_frostVeil | 2 | 1 | 4 | 1 |

Warm composite ~3× più economico (148 vs 433 µs) — coerente con 1 nodo/edge vs 3.

## Frame stats scroll (~433 frame) — confronto diretto

| Metrica | bands3 | singlePass |
|---|---|---|
| Janky frames | 6 (1.40%) | 5 (1.15%) |
| Janky legacy | 236 (55.14%) | 7 (1.62%) |
| Frame p50 | 24ms | 16ms |
| Frame p90 | 29ms | 17ms |
| Frame p95 | 32ms | 18ms |
| Frame p99 | 34ms | 24ms |
| GPU p50 | 19ms | 14ms |
| GPU p90 | 21ms | 15ms |
| PSS post-scroll | ~498 MB | ~481 MB |

[OSSERVAZIONE] Su emulatore il single-pass tiene il frame time dentro il
budget 60Hz (p95 18ms) dove il 3-band lo sfora sistematicamente (p50 già 24ms).
Il costo per frame è GPU-side (3 gaussian pass/edge vs 1).

## Resa visiva (crop_bottom_band.png nelle due cartelle)

- bands3: blur percepito cresce lungo la banda; già a metà banda il contenuto
  è irriconoscibile. Effetto "progressive blur" iOS-like, più materico.
- singlePass: banda interna molto più nitida; il blur pieno emerge solo verso
  il bordo esterno. Transizione più morbida ma il raggio non cresce.
- [IPOTESI, da verificare su device] il cross-fade sharp+blur del single-pass
  può produrre ghosting (doppia immagine) su contenuto ad alto contrasto a
  metà banda. Verificabile con pattern a righe; smentita se nessun doppio
  bordo visibile.

## Limiti

- Emulatore, non device reale: valori assoluti non rappresentativi; il delta
  relativo è il dato utile.
- PSS con varianza alta tra run (±20 MB osservati): il delta RAM è evidenza debole.
- Nessuna misura con 2+ edge attivi o radius animato.
