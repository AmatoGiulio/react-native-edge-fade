# Task — 3 Bande vs Single-Pass Progressive Blur

## Contesto

L’implementazione attuale del progressive blur / eased edge fade usa un approccio a 3 bande.

Vogliamo valutare un approccio single-pass.

Non assumere che single-pass sia migliore.

## Obiettivo

Determinare quale strategia offre il miglior compromesso tra:

- qualità visiva;
- smoothness curva;
- rischio banding;
- CPU usage;
- GPU usage;
- RAM usage;
- allocazioni;
- semplicità;
- stabilità Android/iOS;
- manutenibilità.

## Regola principale

Non sostituire l’approccio a 3 bande finché il single-pass non dimostra di essere migliore o equivalente nei criteri principali.

## Fase 0 — Dimostra

```md
Ipotesi:
Single-pass potrebbe essere migliore perché...

Contro-ipotesi:
3 bande potrebbe essere migliore perché...

Cosa dobbiamo dimostrare:

Cosa non sappiamo ancora:
```

## Fase 1 — Analizza 3 bande

Descrivi:

- perché esistono 3 bande;
- cosa fa ogni banda;
- come viene simulata la curva eased;
- come viene controllato il blur;
- dove può causare artefatti;
- dove può aiutare contro banding;
- quali costi introduce;
- quali cache usa o dovrebbe usare;
- cosa viene invalidato quando cambiano props.

Output:

```md
## Current 3-band model

Pipeline:

Strengths:

Weaknesses:

Unknowns:
```

## Fase 2 — Progetta single-pass

Proponi almeno due varianti:

1. single-pass shader-based;
2. single-pass layer/mask/composition-based.

Per ognuna:

```md
Nome:
Come genera la curva:
Come gestisce blur progressivo:
Come evita banding:
Cosa gira su CPU:
Cosa gira su GPU:
Cache necessarie:
Invalidazione:
Limiti Android:
Limiti iOS:
Rischi:
```

## Fase 3 — Prototype flaggato

Implementa solo se ha senso dopo l’analisi.

Regole:

- mantieni entrambe le strategie;
- non rimuovere 3 bande;
- non cambiare API pubblica;
- usa flag interno;
- non alterare il comportamento default finché il confronto non è concluso.

Flag interno suggerito:

```ts
strategy: "bands" | "singlePass"
```

Se non vuoi esporre API pubblica, il flag deve restare interno alla demo/test harness.

## Fase 4 — Confronto

| Criterio | 3 bande | Single-pass | Vincitore | Note |
|---|---|---|---|---|
| Qualità curva | | | | |
| Smoothness | | | | |
| Banding risk | | | | |
| CPU usage | | | | |
| GPU usage | | | | |
| RAM | | | | |
| Allocazioni | | | | |
| Invalidazioni | | | | |
| Complessità codice | | | | |
| Stabilità Android | | | | |
| Stabilità iOS | | | | |

## Fase 5 — Decisione

Scegli:

- tenere 3 bande;
- passare a single-pass;
- mantenere entrambi dietro strategia interna;
- rimandare decisione per dati insufficienti.

La decisione deve contenere:

```md
Decisione:
Perché:
Dati a supporto:
Tradeoff:
Rischi residui:
Cosa eliminare:
Cosa mantenere:
Cosa misurare dopo:
```

## Condizione di uscita

Single-pass può diventare default solo se:

- qualità visiva >= 3 bande;
- banding non peggiora;
- performance uguale o migliore;
- RAM uguale o migliore;
- codice non diventa più fragile;
- comportamento Android/iOS resta coerente.
