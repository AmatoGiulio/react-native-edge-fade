# AGENTS.md — Boris Protocol

## Principio

La codebase è un sistema.
Ogni modifica deve preservare o migliorare il sistema.

Non sei autorizzato a scrivere codice finché non hai dimostrato di aver capito:

- il problema;
- il contesto minimo;
- il comportamento attuale;
- il rischio della modifica;
- il criterio con cui giudicherai il risultato.

## Regola centrale

> Nessuna decisione senza evidenza.
> Nessuna implementazione senza baseline.
> Nessun refactor senza confine.
> Nessuna ottimizzazione senza confronto prima/dopo.

## Budget del contesto

Il contesto è una risorsa limitata.

Prima di leggere file:

1. dichiara quali file vuoi aprire;
2. spiega perché;
3. indica quale informazione cerchi;
4. non aprire file non necessari;
5. non fare scan globale della repo;
6. non riscrivere file interi se basta una patch locale.

Se ti manca contesto, chiedi il file minimo necessario o usa ricerca mirata.

## Fasi obbligatorie

### 0. Dimostra

Prima di procedere, rispondi:

- qual è il vero problema?
- quali prove hai?
- cosa stai assumendo?
- cosa non sai?
- cosa potrebbe rendere falsa la tua ipotesi?

Se non puoi rispondere, non implementare.

---

## Contratto sulle Evidenze (Obbligatorio)

Ogni affermazione deve essere classificata esplicitamente.

### [OSSERVAZIONE]

Deriva direttamente da:

- codice sorgente;
- documentazione ufficiale;
- benchmark;
- profiler;
- log;
- misurazioni.

Non contiene interpretazioni.

---

### [DEDUZIONE]

È una conclusione logica ottenuta da una o più osservazioni.

Deve sempre indicare chiaramente quali osservazioni la supportano.

---

### [IPOTESI]

È una spiegazione plausibile ma non ancora dimostrata.

Ogni ipotesi deve indicare:

- perché è plausibile;
- quali osservazioni la supportano;
- come può essere verificata;
- quale risultato la confermerebbe;
- quale risultato la smentirebbe.

Le ipotesi non autorizzano modifiche al codice.

---

### [SPECULAZIONE]

È una possibilità priva di evidenze sufficienti.

Non può essere utilizzata per prendere decisioni progettuali.

Può essere utilizzata esclusivamente per proporre nuovi esperimenti o nuove linee di indagine.

---

## Regole

- Non presentare mai una deduzione come un'osservazione.
- Non presentare mai un'ipotesi come un fatto.
- Non prendere decisioni basandoti su speculazioni.
- Se le evidenze non sono sufficienti, fermati e dichiara esplicitamente quali informazioni mancano.
- Ogni proposta di refactor deve indicare esplicitamente su quali osservazioni e deduzioni si basa.
- Non assegnare probabilità numeriche
se non derivano da misurazioni statistiche.

- Valuta invece la qualità delle evidenze.

Livelli consentiti:

- Molto forte
- Forte
- Media
- Debole
- Assente

Non implementare.

Costruisci l'esperimento.


L'emulatore è una risorsa persistente.

Non avviarlo.

Non chiuderlo.

Non fare wipe-data.

Usa quello disponibile.

Se non esiste, fermati e chiedi istruzioni.

È vietato terminare emulatori o modificare il loro stato senza richiesta esplicita.
---

### 1. Comprendi

Produci:

- obiettivo in una frase;
- vincoli;
- cose fuori scope;
- comportamento attuale;
- comportamento desiderato.

### 2. Esplora

Leggi solo i file necessari.

Per ogni file letto indica:

- perché è rilevante;
- cosa hai trovato;
- se serve altro o no.

### 3. Modella

Descrivi il sistema prima di cambiarlo:

- pipeline;
- lifecycle;
- dati in ingresso;
- rendering;
- cache;
- invalidazioni;
- punti caldi;
- rischi.

### 4. Progetta

Proponi massimo tre soluzioni.

Per ogni soluzione valuta:

- semplicità;
- performance;
- rischio regressione;
- impatto API;
- impatto visivo;
- manutenzione futura.

Scegli la soluzione più semplice che soddisfa completamente il problema.

### 5. Implementa

Regole:

- modifica minima;
- nessuna nuova dipendenza salvo prova forte;
- nessun refactor fuori scope;
- nessun cambio API pubblico senza approvazione;
- nessun cambio visivo non richiesto;
- ogni cache deve avere una chiara politica di invalidazione;
- evitare allocazioni per frame;
- evitare lavoro inutile nel main thread.

### 6. Valida

Ripeti gli stessi criteri definiti prima dell’implementazione.

Valuta:

- correttezza;
- performance;
- memoria;
- GPU;
- CPU;
- stabilità;
- sicurezza;
- regressioni visive;
- complessità introdotta.

### 7. Critica

Assumi che la tua soluzione sia sbagliata.

Cerca:

- assunzioni false;
- edge case;
- regressioni;
- peggioramenti nascosti;
- codice fragile;
- cache invalidata male;
- allocazioni non necessarie;
- differenze Android/iOS.

Se trovi un problema, correggilo o dichiaralo come rischio residuo.

## Output finale obbligatorio

Ogni task deve chiudersi con:

```md
## Summary

## Files changed

## Why this change

## Baseline before

## Result after

## Delta

## Risks remaining

## What I deliberately did not change

## Tests / measurements still missing
```

## Divieti assoluti

- Non usare frasi vaghe tipo "should be faster" senza dati o motivo tecnico.
- Non dire "optimized" senza spiegare cosa è stato ridotto.
- Non introdurre caching senza invalidazione.
- Non rimuovere un approccio esistente prima di aver confrontato l’alternativa.
- Non scegliere l’implementazione elegante se quella semplice è più stabile.