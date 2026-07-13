# UIScrollEdgeEffect (iOS 26) — pipeline reale e mappatura su edge-fade

Fonti primarie (2026-07-10): sebvidal.com/blog/whats-new-in-uikit-26, repo
jtrivedi/VariableBlurView e aheze/VariableBlurView (reverse engineering del
filtro privato), daprice/Variablur (replica Metal open-source del filtro),
twostraws/Inferno issue #47 (confronto col filtro privato), Apple docs
UIScrollEdgeEffect / CIFilter.maskedVariableBlurFilter.

## Cosa fa iOS 26 (osservazioni, non ipotesi)

API: `UIScrollEdgeEffect` con `style: .automatic | .soft | .hard`, esposto per
edge su UIScrollView (`topEdgeEffect`, ecc.). Applicato automaticamente sotto
nav bar/toolbar.

- **soft** = *variable blur* + *gradient scrim* il cui colore è **derivato dal
  contenuto sottostante** (non un bianco/nero fisso).
- **hard** = scrim più materico, senza hairline shadow (≈ material pre-26).
- Personalizzazione limitata: style e isHidden. Il resto è nel compositor.

## L'algoritmo del variable blur (reverse engineering consolidato)

Il filtro è `CAFilter(type: "variableBlur")` (privato, gira in CARenderServer
sul backdrop del layer). Input verificati:

| Input | Semantica |
|---|---|
| `inputRadius` | raggio massimo |
| `inputMaskImage` (CGImage) | **raggio per-pixel = alpha(mask) × inputRadius**: alpha 1 = blur pieno, alpha 0 = nitido |
| `inputNormalizeEdges = true` | i campioni fuori dai bounds vengono **rigettati e i pesi rinormalizzati** — niente fringe/vignetting ai bordi |

La replica open-source più fedele (Variablur, Metal) implementa:
- gaussiana **separabile a 2 pass** (H poi V), sigma = radius/3;
- spacing dei tap = max(1, radius/maxSamples), pesi gaussiani, somma
  normalizzata da somma pesi;
- normalizeEdges = sample rejection fuori dal rect + rinormalizzazione.
Lo stesso autore riconosce i possibili "streak artifacts" della separabile a
raggio variabile — Apple li rende invisibili perché il blur gira sul backdrop
**downsampled** dal compositor e con lo scrim sopra.

La mask usata dal sistema non è lineare: è un **gradiente eased** (la replica
di Trivedi la ship-pa come PNG con easing). Quindi la progressione del raggio
segue una curva, non t lineare.

## Cosa valida del nostro lavoro Android

1. `STRATEGY_PROGRESSIVE` (AGSL, raggio per-pixel) è architetturalmente
   **lo stesso approccio di Apple** — non i 3 band. Il 3-band è la
   simulazione; il variable blur è l'originale.
2. Il nostro problema di striature H/V nella variante separabile è lo stesso
   riconosciuto in Variablur: Apple lo assorbe con **downsampling** (non con
   il kernel 2D a spirale, che è più costoso).
3. `inputNormalizeEdges` ↔ il nostro clamp ai bounds: semantica simile
   (evitare il fringe), implementazione diversa (rejection+renorm vs clamp).

## Pipeline da replicare (proposta, in ordine)

1. **Raggio guidato dalla curva**: r(t) = blurRadius × presenceCurve(t)
   (eased), non r·t lineare — allinea la progressione alla mask eased di iOS.
2. **Separabile 2-pass su strip downsampled ~0.5×** poi upscale bilineare:
   ~4× meno lavoro E le striature spariscono sotto il downsampling — è il
   trucco del compositor Apple, non un compromesso.
3. **normalizeEdges**: al bordo esterno della banda, rigettare i campioni
   fuori dal rect e rinormalizzare (invece del solo clamp) per un bordo più
   pulito.
4. **Scrim adattivo (opzionale, stile soft)**: gradient veil il cui colore
   deriva dal contenuto della banda (es. media colore campionata a
   invalidazione, non per frame) — sostituisce il velo bianco fisso bocciato.
5. iOS: su iOS 26+ adottare direttamente `UIScrollEdgeEffect` dove la view è
   sotto system chrome; per le view custom `UIScrollEdgeElementContainerInteraction`.
   Il CAFilter privato resta rischioso per App Store.

## Cosa NON sappiamo ancora

- L'esatta forma dell'easing della mask di sistema (estraibile: dump del PNG
  della replica di Trivedi o screenshot-fitting della curva su device iOS 26).
- Il fattore di downsampling del backdrop di Apple.
- Come è derivato esattamente il colore dello scrim (media locale? tinta per
  fasce di luminanza?). Verificabile empiricamente con contenuti sintetici.
