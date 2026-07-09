# Task — Native Refactor Android/iOS con baseline dati

## Obiettivo

Rifattorizzare il lato nativo di `react-native-edge-fade` migliorando:

- RAM usage;
- CPU usage;
- GPU usage;
- caching;
- allocazioni runtime;
- stabilità durante scroll;
- costo degli update props;
- qualità visiva invariata.

## Regola principale

Non implementare nulla finché non hai prodotto:

1. mappa della pipeline corrente;
2. baseline prima del refactor;
3. ipotesi dei punti caldi;
4. piano minimo di intervento;
5. criterio di accettazione.

## Fase 0 — Dimostra

Rispondi prima di toccare codice:

```md
## Prova di comprensione

Problema reale:

Perché pensiamo che esista:

Evidenze disponibili:

Assunzioni:

Informazioni mancanti:

Cosa potrebbe rendere sbagliata questa ipotesi:
```

## Fase 1 — Esplorazione minima

Apri solo i file nativi necessari.

Per ogni file:

```md
File:
Perché serve:
Cosa cerco:
Cosa ho trovato:
Serve altro? sì/no, perché:
```

## Fase 2 — Modello pipeline attuale

Descrivi per Android:

- view lifecycle;
- shader / AGSL usage;
- canvas / bitmap / layer usage;
- draw pass;
- invalidation strategy;
- cache presenti;
- allocazioni sospette;
- lavoro CPU;
- lavoro GPU.

Descrivi per iOS:

- UIView / CALayer lifecycle;
- mask / overlay strategy;
- Core Animation usage;
- draw pass;
- invalidation strategy;
- cache presenti;
- allocazioni sospette;
- lavoro CPU;
- lavoro GPU.

## Fase 3 — Baseline prima

Misura o prepara misurazione per:

| Metrica | Android before | iOS before | Note |
|---|---:|---:|---|
| FPS medio | | | |
| Frame drops | | | |
| Main thread time | | | |
| Render/draw time | | | |
| RAM peak | | | |
| Allocazioni per frame | | | |
| GC pressure Android | | | |
| Layer/texture count iOS | | | |
| Prop update cost | | | |
| Init cost | | | |

Se non puoi misurare una metrica, dichiaralo.

## Fase 4 — Strategie

Proponi massimo 3 strategie.

Per ognuna:

```md
Nome strategia:
Cosa cambia:
Cosa resta invariato:
Impatto CPU atteso:
Impatto GPU atteso:
Impatto RAM atteso:
Rischio regressione visiva:
Rischio cross-platform:
Complessità:
Perché sì:
Perché no:
```

Scegli una sola strategia.

## Fase 5 — Implementazione

Vincoli:

- niente cambio API pubblica;
- niente nuove dipendenze;
- niente refactor fuori scope;
- niente cambio visivo intenzionale;
- nessuna cache senza invalidazione esplicita;
- evitare allocazioni per frame;
- evitare ricreazione shader/layer se props non cambiano;
- preservare comportamento Android/iOS.

## Fase 6 — Baseline dopo

Ripeti le stesse misurazioni della fase 3.

| Metrica | Before | After | Delta | Esito |
|---|---:|---:|---:|---|
| FPS medio | | | | |
| Frame drops | | | | |
| Main thread time | | | | |
| Render/draw time | | | | |
| RAM peak | | | | |
| Allocazioni per frame | | | | |
| GC pressure Android | | | | |
| Layer/texture count iOS | | | | |
| Prop update cost | | | | |
| Init cost | | | | |

## Fase 7 — Critica finale

Assumi che il refactor sia sbagliato.

Rispondi:

- quale regressione potrebbe essere stata introdotta?
- quale device potrebbe soffrire di più?
- quale cache potrebbe invalidarsi male?
- cosa succede con più EdgeFade nella stessa screen?
- cosa succede con props aggiornate spesso?
- cosa succede durante scroll veloce?
- cosa succede con blur/progressive fade attivo?
- cosa non è stato misurato?

## Condizione di accettazione

Il refactor passa solo se:

- qualità visiva invariata;
- nessuna API pubblica rotta;
- performance uguale o migliore;
- RAM uguale o migliore;
- complessità giustificata;
- rischi residui dichiarati.
