# Edge Fade — Context minimo

## Progetto

`react-native-edge-fade` è una libreria React Native per applicare fade ai bordi di contenuti scrollabili o view.

La libreria deve mantenere:

- qualità visiva alta;
- API piccola;
- zero o pochissime dipendenze;
- compatibilità iOS / Android / Web dove applicabile;
- performance stabile durante scroll e animazioni.

## Priorità

Ordine di priorità:

1. stabilità visiva;
2. frame stability;
3. uso RAM controllato;
4. basso uso CPU;
5. basso costo GPU;
6. semplicità implementativa;
7. eleganza architetturale.

Non invertire queste priorità senza prova tecnica.

## Area attuale del task

Il task riguarda il lato nativo Android/iOS:

- rendering edge fade;
- progressive blur;
- easing curve;
- caching;
- uso RAM;
- uso CPU;
- uso GPU;
- confronto tra approccio a 3 bande e approccio single-pass.

## Assunzione da non fare

Non assumere che single-pass sia migliore.

Single-pass può essere:

- più elegante ma meno controllabile;
- più pulito ma più fragile su device reali;
- migliore per GPU ma peggiore per banding;
- più semplice nel codice ma meno fedele visivamente.

Serve confronto.

## Requisito non negoziabile

Ogni refactor performance deve avere dati prima/dopo o una motivazione tecnica verificabile.

Se non puoi misurare direttamente, devi dichiarare:

- cosa non è stato misurato;
- perché;
- quale proxy tecnico hai usato;
- quanto è affidabile quel proxy.
