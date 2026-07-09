# Pronto Soccorso — Quando l’agente sta deragliando

Usa questo file quando l’agente:

- sta leggendo troppi file;
- sta facendo refactor laterali;
- sta ottimizzando senza dati;
- sta cambiando API;
- sta inventando architetture;
- sta dicendo “should be faster” senza prove;
- sta confondendo estetica con performance;
- sta riscrivendo invece di isolare.

## Stop immediato

Incolla questo:

```md
STOP.

Stai andando fuori protocollo.

Non procedere con altre modifiche.

Ritorna a:

1. problema reale;
2. file minimi necessari;
3. baseline;
4. ipotesi;
5. modifica minima;
6. criterio di accettazione.

Elenca cosa hai fatto fuori scope e come torni al perimetro corretto.
```

## Se sta leggendo troppi file

```md
Riduci il contesto.

Per ogni file che vuoi leggere devi indicare:

- perché serve;
- quale informazione cerchi;
- cosa succede se non lo leggi.

Non aprire altri file finché non hai giustificato quelli già aperti.
```

## Se sta implementando troppo presto

```md
Non sei autorizzato a implementare.

Prima devi dimostrare:

- pipeline attuale;
- punto caldo sospetto;
- metrica da migliorare;
- rischio della modifica;
- condizione di successo.
```

## Se propone single-pass come ovviamente migliore

```md
Non assumere che single-pass sia migliore.

Devi confrontare:

- 3 bande;
- single-pass shader;
- single-pass layer/composition.

Valuta qualità visiva, banding, CPU, GPU, RAM, invalidazioni e rischio cross-platform.

Solo dopo puoi raccomandare una strategia.
```

## Se introduce cache senza criterio

```md
Ogni cache senza invalidazione è un bug potenziale.

Per ogni cache dichiara:

- chiave;
- contenuto;
- lifecycle;
- invalidazione;
- rischio memory leak;
- comportamento con prop updates;
- comportamento con più istanze.
```

## Se dice “performance migliorata” senza dati

```md
La frase non è accettabile.

Sostituiscila con:

- metrica migliorata;
- prima;
- dopo;
- delta;
- scenario;
- metodo di misura;
- limiti della misura.

Se non hai dati, devi scrivere “non misurato”.
```

## Se il codice diventa più complesso

```md
La complessità è un costo.

Giustifica:

- quale problema risolve;
- perché una soluzione più semplice non basta;
- quale rischio elimina;
- quale costo futuro introduce.

Se non puoi giustificarla, semplifica.
```

## Se cambia API pubblica

```md
Non cambiare API pubblica.

Puoi farlo solo se dimostri che:

- è necessario;
- non esiste alternativa interna;
- il comportamento precedente è preservato o migrabile;
- la breaking change è documentata.
```

## Prompt di recupero totale

```md
Riparti da zero.

Produci solo questo output, senza codice:

1. Obiettivo reale
2. Confini del task
3. File minimi necessari
4. Pipeline attuale ipotizzata
5. Cosa va dimostrato
6. Baseline richiesta
7. Piano minimo
8. Rischi
9. Cosa non farai

Dopo questo aspetta conferma prima di implementare.
```
