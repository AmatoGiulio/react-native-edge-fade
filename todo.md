Faccio un audit serio. Mando in parallelo ricognizione codice nativo + ricerca competitor, intanto rileggo l'API JS.


Eseguito 2 agenti, esplorato il web, eseguito un comando, letto un file, utilizzato uno strumento
Agente eseguito
Opus 4.7
Deep audit: iOS native impl
Agente eseguito
Opus 4.7
Deep audit: Android native impl
Usato ToolSearch
Ha cercato sul web
react native fade edge gradient mask library npm 2026
Ha cercato sul web
rn-fade-wrapper vs react-native-fading-edge new architecture fabric performance
Eseguito
Survey JS API surface
Lettura
normalize.ts
Audit react-native-edge-fade
1. Chiarezza API
Punti forti

L'overload boolean | number | EdgeConfig per top/bottom/left/right è ergonomico: <EdgeFadeView bottom />, <EdgeFadeView bottom={120} />, <EdgeFadeView bottom={{ size, curve, color }} />. Tre livelli di concisione che coprono il 95% dei casi.
Inferenza automatica di mode da color (normalize.ts:78) — meno boilerplate.
Curve come unione discriminata ('smooth' | { type: 'cubicBezier', ... } | { type: 'stops', ... }) — type-safe.
Frizioni

Doppia API per Reanimated: il README spiega chiaramente che bisogna usare NativeEdgeFadeView con i prop flat (fadeTop ecc.) invece di top — ma è una crepa concettuale. Un'API ideale dovrebbe accettare Animated.Value | SharedValue sui prop pubblici. Soluzione tipica: esportare un Animated.createAnimatedComponent(EdgeFadeView) wrapper o supportare nativamente shared values nel codegen.
Unità ambigue: size è in "dp" sul JS ma su Android arriva in pixel (EdgeFadeViewManager.kt:25 non moltiplica per density). Su iOS arriva in points. Risultato: stesso valore = dimensioni diverse tra le due piattaforme su devices ad alto DPI. Bug reale.
RTL non gestito: left/right sono assoluti, non start/end. Le app localizzate ar/he hanno il fade sul lato sbagliato.
radius vs style.borderRadius: due strade per la stessa cosa, EdgeFadeView.tsx:31-33 fa overwrite/merge silenzioso. Documentare o consolidare.
prop.color su un EdgeConfig implica overlay anche se l'utente ha messo mode="mask" esplicitamente — non c'è errore, viene ignorato. Validare o tipizzare.
2. Performance
Android — il vero hotspot
saveLayer(null) full-view in mask mode (EdgeFadeView.kt:286): alloca un offscreen RT della dimensione dell'intera view per ogni frame, anche se la zona di fade è il 5% della superficie. Limitare al union(edgeRects). È il guadagno potenziale più grosso.
drawMask invalida sempre tutto il backing store — setNeedsDisplayInRect: per edge sarebbe meno costoso (vale anche per iOS).
runCatching { RuntimeShader(AGSL_SRC) } (EdgeFadeView.kt:380) ingoia eventuali errori di compilazione shader e degrada silenziosamente a LinearGradient — minimo loggare.
iOS — molte piccole allocazioni
overlayColors alloca ~128 UIColor/CGColor per rebuild (4 edge × 32 stop). Usare direttamente CGColorRef (EdgeFadeView.mm:159-164).
locationsForCurve rialloca 32 NSNumber per preset ad ogni rebuild — gli stop sono identici per tutti i preset, cache-abile una volta.
drawInContext: ridisegna l'intero bounds anche se solo un edge è cambiato.
_overlayContainer extra UIView (EdgeFadeView.mm:420-423): un compositing pass in più senza motivo, i CAGradientLayer possono essere figli diretti di self.layer.
RCTUIColorFromSharedColor chiamato ad ogni updateProps senza memo su color identity.
_syncMaskLayer chiama setNeedsDisplay su ogni prop change, anche se solo la curve cambia (e potrebbe non aver cambiato nulla di visibile).
Trasversale
memo() di EdgeFadeView sull'oggetto props non aiuta perché EdgeConfig viene tipicamente passato come literal inline → identità nuova ogni render. Documentare o passare i config come module-level constants.
Diff prop su iOS confronta SharedColor per pointer identity, non per valore → rebuild colore inutili.
3. Scelte tecnologiche native
Piattaforma	Tecnica usata	Valutazione
Android API 33+	AGSL RuntimeShader per-pixel + LUT 32-entry	Ottima scelta, è lo stato dell'arte; nessun banding.
Android API 24-32	LinearGradient 64-stop + PorterDuffXfermode(DST_IN)	Solido; PorterDuffXfermode è deprecato dall'API 29 → migrare a Paint.setBlendMode(BlendMode.DST_IN).
iOS	CALayer custom + kCGBlendModeDestinationIn su drawInContext:	Funzionale ma CPU-bound. Sotto load (scroll + resize) un approccio CAGradientLayer + composizione di sublayers nel mask sarebbe GPU-accelerato. Per le custom curve servirebbe comunque qualche compromesso. Alternativa pesante ma "pulita": Metal shader (CAMetalLayer) — overkill probabilmente.
Web	CSS mask-image + mask-composite: intersect	Eccellente, zero banding, paga il browser.
Tecniche da considerare:

iOS: cornerCurve = .continuous + CACornerMask per radius — diventa Apple-style squircle. Sostituire UIScreen.mainScreen.scale con traitCollection.displayScale (multi-window iPad).
Android: RenderEffect.createRuntimeShaderEffect (API 31+) eliminerebbe saveLayer manuale; BlendMode.DST_IN invece di PorterDuffXfermode.
Entrambe: aggiungere un equivalente Compose / SwiftUI per consumo "vanilla" senza wrapping RN.
4. Competitor nella community
Lib	Tecnica	Punti deboli vs il tuo
react-native-fading-edge	wrapping di react-native-linear-gradient + @react-native-masked-view/masked-view	2 dipendenze peer pesanti; New Arch supportata ma indiretta; nessuna AGSL shader; banding visibile su sfondi scuri.
rn-fade-wrapper	Native: CAGradientLayer (iOS) + Canvas+LinearGradient (Android)	Sola modalità overlay; nessuna mask trasparente vera; nessuna curve personalizzata; no AGSL; no web.
react-native-linear-gradient	gradient generico	Solo primitive — devi comporlo a mano con masked-view.
@react-native-masked-view/masked-view	mask generico	Primitiva, niente curve, niente AGSL.
Tuo posizionamento: sei l'unica lib che combina (a) zero peer deps, (b) AGSL per-pixel su Android moderno, (c) mask reale (DST_IN), (d) custom curves (cubic-bezier + stops), (e) web first-class. Tecnicamente sopra a tutti gli altri.

Quello che NON hai e loro hanno parzialmente: ecosistema/star/visibilità.

5. Opinione
Codice sopra la media di quello che si trova su npm in questa nicchia. La scelta AGSL + LUT è da addetti ai lavori, ed è ciò che ti differenzia tecnicamente. La crepa più seria non è tecnica ma di consistency: dp vs px su Android e RTL sono bug user-visible che bloccherebbero un'adozione in app i18n-aware. Fixarli prima del 1.0.0.

In ordine di ROI per la prima major:

dp/density su Android (size * density in tutti i setter del ViewManager). Bug.
RTL mapping (layoutDirection == LTR ? leftPx : rightPx). Bug.
saveLayer ristretto alle edge rects su Android. Free FPS.
iOS: rimuovere _overlayContainer, cachare NSNumber* locations, dropparе UIColor round-trip in overlayColors. Micro ma diretti.
BlendMode.DST_IN invece di PorterDuffXfermode (modernizzazione + future-proofing).
Riposizionare Reanimated: o esportare AnimatedEdgeFadeView wrapper, o documentare ancora più in alto nel README — è la feature killer per scroll-driven UI.
Compose interop (Modifier.fadingEdge parallelo) — apre un mercato Kotlin nativo che oggi non hai.
In sintesi: il prodotto è già il migliore della categoria su feature/tecnologia. Le ottimizzazioni qui sopra sono il delta per passare da "buona libreria di nicchia" a "default scelto senza pensarci".

Sources:

rn-fade-wrapper
react-native-fading-edge
react-native-linear-gradient
Actual Gradient Borders in React Native (MaskedView)
Improve UX in React Native with Native Fade Gradients
