# Baseline Clean (2026-07-10)

Codice originale senza ottimizzazioni Tentativo 2/3. Solo BenchMark instrumentation.

## gfxinfo (scroll, 159 frames)

| Metrica | Valore |
|---|---|
| Janky frames (modern) | 40 (25.16%) |
| Janky frames (legacy) | 72 (45.28%) |
| 50th percentile | 23ms |
| 90th percentile | 28ms |
| 95th percentile | 29ms |
| 99th percentile | 32ms |
| Slow issue draw commands | 39 |
| Frame deadline missed | 40 |

## Benchmark dispatchDraw (steady-state, run 2)

| Fase | Durata |
|---|---|
| bl_contentRecord | 6047 µs |
| bl_levelNodes | 94 µs |
| bl_sharpBase | 8 µs |
| bl_compositeLevels | 1861 µs |
| bl_frostVeil | 6 µs |

## Note
- ScrollListener già rimosso (blur non si aggiorna durante scroll)
- HDR/LargeDisplay + 4x MSAA attivi nell'emulatore
- Pixel_8 API 36, 1080×2400
