# Performance Baseline Playbook

## Principio

Performance senza baseline è opinione.

Prima di modificare codice, definisci:

- cosa misuri;
- come lo misuri;
- su quale scenario;
- su quale device;
- con quali limiti.

## Scenari minimi

1. EdgeFade statico su view semplice.
2. Lista lunga con scroll lento.
3. Lista lunga con scroll veloce.
4. Più EdgeFade nella stessa screen.
5. Props aggiornate spesso.
6. Progressive blur attivo.
7. Curva eased estrema.
8. Device lento o throttled.

## Metriche

### Generali

- FPS medio;
- frame drops;
- time to first render;
- prop update cost;
- RAM peak;
- allocazioni runtime.

### Android

- main thread time;
- render thread time;
- GC pressure;
- allocations per frame;
- invalidation count;
- shader recreation count;
- bitmap/layer allocation count;
- GPU overdraw se misurabile.

### iOS

- main thread time;
- Core Animation FPS;
- layer count;
- texture/memory pressure;
- offscreen rendering;
- mask/layer invalidation count;
- allocation spikes;
- Time Profiler hotspots.

## Baseline iOS raccolta

### Ambiente

| Parametro | Valore |
|---|---|
| Device | Simulatore iPhone 17 Pro (iOS 26.1) |
| Xcode | 26.3 |
| App | EdgeFadeExample (Expo dev client, bridgeless, Hermes 0.14.1) |
| Profiler | Argent React Profiler (Hermes CPU + commit trace) |
| Strumentazione nativa | EF_BENCH macro in EdgeFadeView.mm (scrive `NSTemporaryDirectory()/edgefade_bench.csv`) |

### Metriche JS thread (React profiler)

Risultato: **tutti i 36 React commit sotto 16ms, 0 hot commit.**

Il JS thread non è un collo di bottiglia in nessuno scenario misurato:

| Commit | Self duration | Totale renders |
|---|---|---|
| Min | < 1ms | — |
| Max | < 16ms | — |
| GalleryScreen | — | 1 render |
| AnimatedEdgeFadeView | — | 1 render |

### Metriche native iOS

**Non acquisibili via React Profiler.** Serve Instruments (Time Profiler, Core Animation) per:
- CA commit duration (frame rate effettivo)
- GPU utilization (Metal/CA Instrument)
- drawInContext: costi per EdgeFadeBlurMaskLayer
- Layer count e offscreen pass count

Tentativi falliti:
- `os_signpost_interval_begin/end`: incompatibile con Xcode 26.3/SDK 26.2 (richiede compile-time constant per ID)
- `os_log_info` / `os_log_debug` con custom subsystem: non catturato da `log stream` in questo setup
- `NSLog` / fprintf: catturabile via `log stream --process` ma il flusso di navigazione Expo Dev Launcher intralcia la cattura
- `xctrace record --attach 'EdgeFadeExample'`: non cattura dati (solo RunIssues.storedata)
- File write su `NSTemporaryDirectory()`: aggiunta ma non verificata a causa della navigazione dev launcher

### Bug trovato

**[OSSERVAZIONE]** Crash `SIGABRT` in `-[UIViewPropertyAnimator dealloc]` quando l'animator è .active (paused) al momento del rilascio.

Causa: `_teardownFadeLayers` chiamava `[animator stopAnimation:YES]` senza prima portare l'animator allo stato `.inactive`. UIKit crasha su dealloc di un animator paused in `.active`.

Fix:
```objc
// Prima fermare l'animazione, poi finalizzare
[_blurAnimators[e][k] stopAnimation:YES];
[_blurAnimators[e][k] finishAnimationAtPosition:UIViewAnimatingPositionEnd];
```

**Raffica:** anche invertendo l'ordine (prima finishAnimationAtPosition: poi stopAnimation:) l'animator potrebbe crashare. Fix robusto:
```objc
// finishAnimationAtPosition: BEFORE releasing
[animator finishAnimationAtPosition:UIViewAnimatingPositionEnd];
[animator stopAnimation:YES];
```

### Strumentazione EF_BENCH (aggiunta permanente)

Quattro hotspot nativi misurati:

| Metodo | Evento trigger |
|---|---|
| `_updateLayerFrames` | layoutSubviews (ogni frame scroll) o updateProps |
| `_applyBlurFraction` | `_blurRadius` change (solo aggiornamento props) |
| `_syncBlurMaskLayers` | fade values change via updateProps |
| `updateProps: (total)` | ogni cambio prop Fabric |

Per la lettura, via host:
```bash
# Dopo aver scrollato l'app in blur mode:
find ~/Library/Developer/CoreSimulator/Devices/<UDID>/data/Containers/Data/Application -name "edgefade_bench.csv" -exec cat {} \;
```

## Baseline Android

**Non raccolta.** Nessun emulatore o device fisico disponibile (`adb devices` vuoto).

Metriche target:
- RenderNode recording count per frame
- RenderEffect.apply() cost
- Allocationi per frame (RevealLinearGradient, Paint)
- GC pressure
- Jank / frame drops

## Tabella baseline

| Scenario | Metric | Before | After | Delta | Pass/Fail | Notes |
|---|---|---|---:|---:|---:|---|---|
| Static view (mask) | JS commit time | <1ms | — | — | ✅ | |
| Blur mode scroll | JS commit time | 3-15ms | — | — | ✅ | Nessun commit >16ms |
| Blur mode scroll | CA commit time | ❌ | ❌ | — | ❓ | Non misurato (mancano Instruments) |
| Blur mode scroll | GPU time | ❌ | ❌ | — | ❓ | Non misurato |
| Blur mode scroll | Allocazioni native | ❌ | ❌ | — | ❓ | Non misurato |
| Blur mode build (12 animators) | Layer count | 12+ UIVisualEffectView | — | — | ⚠️ | Rischio layer inflation |
| Modal switch (mask→blur) | Time to interactive | ❌ | ❌ | — | ❓ | Non misurato |
| _teardownFadeLayers | Crash rate | Crash su dealloc | 0 crash | — | ✅ | Fix applicato |

## Limiti della baseline attuale

1. **JS thread solo**: React Profiler non misura CA commit, GPU, o main-thread nativo.
2. **Nessun device fisico**: simulatore ha path di rendering diverso da device reale.
3. **Nessuna baseline Android**: manca completamente.
4. **Singola modalità testata**: blur mode su un singolo EdgeFadeView wrapping scroll, non liste multiple.
5. **Singola piattaforma iOS testata**: iPhone 17 Pro (simulatore), non device meno potenti.
6. **Strumentazione EF_BENCH**: aggiunta ma non validata (scrittura file non verificata).

## Regole di interpretazione

Non basta un miglioramento isolato.

Una modifica è buona se:

- migliora una metrica importante senza peggiorarne un'altra critica;
- non peggiora la qualità visiva;
- non aumenta eccessivamente la complessità;
- resta stabile su Android e iOS.

## Se non puoi misurare

Scrivi:

```md
Metica non misurata: ...
Perché: ...
Proxy usato: ...
Affidabilità proxy: ...
Rischio: ...
```
