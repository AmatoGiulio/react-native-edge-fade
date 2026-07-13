# Progressive AGSL blur (STRATEGY_PROGRESSIVE, API 33+)

Stesso protocollo dei baseline (vedi ../bands3/README.md).

Strategia: 1 nodo per edge con RenderEffect = chain di 2 RuntimeShader AGSL
(pass orizzontale + verticale), gaussian separabile con raggio per-pixel
`r(t) = blurRadius · t` lungo l'asse della banda. Lo strip è disegnato opaco
clippato alla banda: a t=0 lo shader restituisce il contenuto invariato,
quindi niente seam interno, niente maschera DST_IN, niente cross-fade
sharp/blur → niente ghosting per costruzione.

Kernel v2: 25 tap a spaziatura σ/4 (σ = r/2, copertura ±3σ). La v1 a 13 tap
(passo r/6) mostrava striature/moiré visibili nelle zone a blur pieno —
crop_bottom_band.png (v1) vs crop_bottom_band_v2.png.

## CPU (nanoTime, µs) — primo render (kernel v1; v2 non rimisurato)

| mark | draw #0 (cold) | draw #1 |
|---|---|---|
| bl_contentRecord | 3562 | 26 |
| bl_sharpBase | 10 | 7 |
| bl_compositeLevels | 1006 | 170 |
| bl_frostVeil | 2 | 1 |

## Frame stats scroll (~435 frame) — confronto a 3 (kernel v2)

| Metrica | bands3 | progressive | singlePass |
|---|---|---|---|
| Janky legacy | 55.1% | 34.9% | 1.6% |
| Frame p50 | 24ms | 17ms | 16ms |
| Frame p90 | 29ms | 22ms | 17ms |
| Frame p95 | 32ms | 23ms | 18ms |
| GPU p50 | 19ms | 15ms | 14ms |
| GPU p90 | 21ms | 18ms | 15ms |
| PSS post-scroll | ~498 MB | ~492 MB | ~481 MB |

[OSSERVAZIONE] La progressive sta tra le due: rampa visiva vera come bands3,
costo GPU vicino al singlePass (2 shader pass sul solo strip vs 3 gaussian
full-pipeline). p50 dentro il budget 60Hz; p90-95 leggermente sopra.

## Resa visiva

- Rampa di raggio continua (non 3 gradini): a metà banda il contenuto è già
  ben occluso, estetica sovrapponibile al bands3.
- Nessun ghosting possibile (mai sovrapposizione sharp+blur).
- Kernel v2 pulito; possibile residuo di pattern su contenuto molto uniforme,
  da verificare su device reale.

## Limiti / tuning aperti

- Costo shader ∝ tap count: 25×2 tap per pixel di strip. Riducibile con
  downsample del contenuto prima del blur (mip trick) se p90 su device reale
  non basta.
- `r(t)` lineare in t geometrico; si può agganciare alla presence curve per
  coerenza percettiva con la mask/overlay semantics.
- Richiede API 33 (RuntimeShader); sotto, fallback automatico a bands3.

## v4 / v4.1 — variable blur Apple-style (2026-07-10 pomeriggio)

Pipeline (vedi ../../APPLE_SCROLL_EDGE_EFFECT.md): raggio per-pixel =
blurRadius × alpha della presence-curve mask (LinearGradient legato come
input shader — semantica identica a inputMaskImage di iOS), gaussiana
separabile H+V (σ=r/3) su strip DOWNSAMPLED 0.5× e upscale bilineare al
composite. v4.1 aggiunge jitter per-pixel sull'asse del blur (rompe il
retino da undersampling visto in v4) e tap ±10 a σ·0.35.

Frame stats scroll (batch standard, 433 frame, gallery 2 edge attivi):

| Metrica | bands3 lineare | singlePass | v4.1 |
|---|---|---|---|
| Frame p50 / p95 | 24 / 32ms | 16 / 18ms | **16 / 21ms** |
| Jank legacy | 55% | 1.6% | **6.9%** |
| GPU p50 / p90 | 19 / 21ms | 14 / 15ms | **14 / 16ms** |

Visivo (crop_text_v41.png): progressione continua guidata dalla curva,
attacco senza ghosting, niente bande, niente retino. Artefatto residuo:
leggera texture di rumore ad alti raggi (dal jitter), percettivamente
organica.
