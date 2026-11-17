# DAVIX — audit architetturale e roadmap dell'ecosistema

**Baseline analizzata:** branch `main`, commit `225f1f1252cab2c9f41e8d43b7616dc58999c8cd` (16 novembre 2026).  
**Scopo:** evolvere DAVIX Extractor e DAVIX Player in un ecosistema affidabile senza riscritture indiscriminate, perdita di dati o rottura della compatibilità degli APK.

Questo documento fotografa il codice e le pipeline effettivamente presenti nella baseline indicata. Una funzionalità implementata nel codice non è automaticamente considerata validata su dispositivo.

## 1. Stato verificato

### Applicazioni Android

- Due moduli Android distinti: `android/app` (Extractor) e `android/playerlite` (Player Lite).
- Entrambi sono app Kotlin native basate su Activity e View Android tradizionali; il progetto non usa attualmente Jetpack Compose.
- Entrambi integrano AndroidX Media3/ExoPlayer.
- Package ID da preservare:
  - Extractor: `it.davidealpegiani.skyunoextractor`
  - Player Lite: `it.davidealpegiani.skylite`
- Le schermate principali sono concentrate in due file molto grandi: Extractor `MainActivity.kt` (circa 2.095 righe) e Player `MainActivity.kt` (circa 1.848 righe). La logica di interfaccia, stato, persistenza e flussi di lavoro è quindi ancora fortemente accentrata.

### Extractor

- Importa file e playlist, analizza i canali, rimuove duplicati, consente selezione multipla, organizzazione in categorie e sottocartelle ed esportazione.
- La lettura e il parsing dei file avvengono in background, ma l'inserimento dei canali importati e parte della costruzione delle View vengono ancora eseguiti sul thread UI.
- La libreria è persistita in un singolo file JSON `channel_library_v1.json`; il ripristino ricostruisce l'intera lista in memoria.
- La PR #94 ha introdotto paginazione visuale di 100 canali per cartella e ha ridotto scansioni ripetute durante la selezione multipla. Non elimina però il costo dell'inserimento massivo, della serializzazione completa o della ricostruzione della schermata.

### Player Lite ed EPG

- Il Player carica la configurazione autorizzata dalla cache locale, organizza i canali per categorie e sottocartelle, gestisce preferiti, riproduzione Media3, navigazione TV e fullscreen.
- Il motore EPG scarica più fonti XMLTV, applica limiti di dimensione e quantità, usa `tvg-id`, nomi esatti e similarità testuale e memorizza le associazioni.
- Il matching automatico usa una soglia di similarità e un margine rispetto al secondo candidato; non espone ancora un punteggio di confidenza comprensibile né un flusso completo di revisione/correzione manuale persistente.
- Le fonti XMLTV sono attualmente definite nel codice. Serve una gestione versionata delle fonti, dei risultati, delle correzioni e delle associazioni non risolte.

### DAVIX Cloud Sync

- Backend Cloudflare Worker + D1 con codici di attivazione monouso, hash dei codici e dei token dispositivo, autorizzazione e licenza offline a scadenza.
- Il Player usa la cache locale quando la rete non è disponibile; la revoca viene recepita al successivo contatto online.
- L'endpoint di pubblicazione rifiuta attualmente configurazioni con più di **1.000 canali**: è un limite incompatibile con l'obiettivo di gestire playlist molto grandi.
- Il payload dei canali, inclusi gli URL dei flussi, viene conservato in D1 come JSON. La sorgente EPG personalizzata viene cifrata a riposo con la chiave amministrativa, ma non esiste ancora cifratura end-to-end dell'intera configurazione. Il backend/cloud può quindi elaborare e leggere il contenuto dei canali.
- La sincronizzazione deve evolvere verso trasferimenti a blocchi, versioni atomiche, checksum, ripresa e una strategia di protezione delle credenziali streaming.

### Aggiornamenti e firma APK

- Le due app hanno updater separati con logiche in parte duplicate.
- Nell'Extractor alcune query a `DownloadManager` sono eseguite sul thread principale e il polling della barra di avanzamento viene ripetuto ogni 400 ms sullo stesso thread. Anche il Player esegue query sincrone a `DownloadManager` nei percorsi di lifecycle/ripristino.
- Questo è un rischio concreto di blocco/ANR e la prima correzione tecnica da affrontare. Non dimostra, da solo, che sia l'unica origine di ogni ANR.
- La build di `main` del 16 novembre 2026 è terminata con successo. Il log di `apksigner` conferma per entrambi gli APK il certificato SHA-256 `dab3d773daa75228042327408766f0577372b10fefa836849d5c600a90d2fafe`, corrispondente alla firma stabile prevista.
- La documentazione di firma è stata aggiornata insieme a questo audit perché conteneva ancora istruzioni obsolete sulla mancata configurazione del keystore.

### Qualità e test

- Le pipeline compilano i due APK, verificano la firma, controllano il template HTML condiviso e pubblicano gli artefatti/release.
- Il backend Cloud Sync ha controlli TypeScript e dry-run del deploy.
- Nella struttura Android analizzata non è presente una suite significativa di test unitari/strumentali per parser, importazione massiva, persistenza, matching EPG, pairing e aggiornamenti.
- La build verde prova la compilazione e i controlli eseguiti dalla pipeline; non prova da sola la stabilità su telefoni e TV reali.

## 2. Gap principali rispetto al DAVIX obiettivo

| Area | Stato della baseline | Evoluzione necessaria |
|---|---|---|
| Architettura | Due Activity monolitiche | Separare UI, dominio, dati e servizi |
| Importazione | Parsing in background, inserimento UI massivo | Pipeline a blocchi, cancellabile e con backpressure |
| Archivio | JSON monolitico in memoria/file | Database indicizzato e migrazioni non distruttive |
| Playlist grandi | Paginazione visuale parziale | Test e rendering incrementale su dataset ampi |
| Cloud Sync | Limite 1.000 canali | Chunking, pubblicazione atomica e ripresa |
| Sicurezza cloud | Token hash, EPG URL cifrato, canali leggibili dal backend | Cifratura end-to-end e gestione chiavi |
| EPG | Matching automatico euristico | Confidence score, correzione manuale e memoria delle scelte |
| Updater | Due implementazioni, chiamate Binder sul main thread | Servizio robusto, asincrono e condiviso/standardizzato |
| Player TV | Funzioni TV già presenti | Audit completo D-pad, focus, sticky header e fullscreen |
| Test | Build e controlli statici | Test unitari, integrazione e prove su dispositivi |

## 3. Roadmap proposta

### Fase 0 — Integrità e compatibilità

1. Mantenere invariati package ID e certificato di firma.
2. Verificare versionCode/versionName monotoni per entrambe le app.
3. Definire backup e ripristino prima di qualsiasi migrazione dati.
4. Conservare una baseline degli APK, dei checksum, dei manifest e dei dati di esempio.
5. Separare build di test e release stabili, impedendo la pubblicazione di APK non firmati con la chiave permanente.

**Gate:** nessun aggiornamento può essere pubblicato se cambia inaspettatamente package ID/firma o se la migrazione dei dati non è verificabile.

### Fase 1 — Stabilità e ANR

1. Spostare tutte le query e operazioni `DownloadManager` su executor/IO.
2. Eliminare il polling sul main thread; ridurlo a intervalli ragionevoli e aggiornare la UI soltanto tramite il main Handler.
3. Rendere sicuri lifecycle, receiver, callback e cancellazione delle attività obsolete.
4. Evitare download duplicati e gestire in modo esplicito PENDING, RUNNING, PAUSED, SUCCESSFUL e FAILED.
5. Applicare lo stesso contratto di aggiornamento a Extractor e Player.
6. Analizzare separatamente gli ANR durante importazione e selezione di playlist grandi.

**Gate:** nessuna chiamata Binder/IO di aggiornamento sul main thread; test dei percorsi di ripresa, interruzione e completamento; prova reale sui dispositivi disponibili.

### Fase 2 — Motore dati e importazione scalabile

1. Introdurre un livello dati dedicato senza cambiare in un solo passaggio il formato esistente.
2. Definire uno schema versionato per canali, sorgenti, categorie, sottocartelle, selezioni, preferiti e metadati EPG.
3. Migrare dal JSON monolitico a un database locale indicizzato con migrazione e rollback/backup.
4. Importare a blocchi: lettura, parsing, deduplicazione e inserimento non devono richiedere la creazione simultanea di tutte le View.
5. Supportare annullamento, ripresa, errori per singolo file e report finale.
6. Evitare riscritture complete dell'archivio dopo ogni modifica.

**Gate:** test ripetibili con playlist da 1.000, 10.000 e almeno 50.000 canali; nessuna perdita o duplicazione; UI reattiva durante import e selezione.

### Fase 3 — Cloud Sync robusto e protetto

1. Rimuovere il limite funzionale di 1.000 canali tramite upload/download a blocchi.
2. Pubblicare una nuova versione solo dopo aver verificato tutti i blocchi e il checksum complessivo.
3. Rendere atomico il passaggio dalla configurazione precedente a quella nuova.
4. Introdurre cifratura end-to-end della configurazione e delle credenziali, con una strategia di gestione/recupero delle chiavi che non dipenda dal solo server.
5. Conservare la cache locale valida e non interrompere il Player in caso di errore temporaneo di rete.
6. Rendere chiari all'utente stato licenza, ultima sincronizzazione e stato della revoca.

**Gate:** interruzione di rete a ogni fase senza configurazioni parziali; verifica checksum; dispositivo offline; revoca; ripristino.

### Fase 4 — EPG intelligente

1. Gestire le fonti XMLTV come configurazione aggiornabile e verificabile.
2. Separare identificazione del canale e scelta del palinsesto.
3. Produrre un punteggio di confidenza e distinguere corrispondenze certe, probabili e ambigue.
4. Non assegnare automaticamente un candidato ambiguo.
5. Consentire correzioni manuali persistenti e priorità per singolo canale.
6. Memorizzare le associazioni corrette e riutilizzarle agli aggiornamenti successivi.
7. Gestire fusi orari, orari estivi, programmi senza titolo, canali rinominati e fonti mancanti.

**Gate:** dataset di riferimento con associazioni note, falsi positivi misurati e possibilità di correggere ogni associazione.

### Fase 5 — Player di livello TV

1. Audit completo della navigazione esclusivamente con D-pad/telecomando.
2. Focus visibile, coerente e mai perso durante espansione cartelle, cambio canale e fullscreen.
3. Intestazioni di tutte le categorie sticky e liste scorrevoli indipendenti.
4. Video e lista canali con proporzioni adattive al display.
5. Fullscreen realmente privo di elementi estranei, con overlay controllato e richiamabile.
6. Cambio canale, preferiti, ricerca, EPG e recupero playback con tempi e stati comprensibili.
7. Verifica su Android TV, Google TV, Fire TV e smartphone/tablet.

**Gate:** checklist di navigazione completa con telecomando fisico e prove su risoluzioni differenti.

### Fase 6 — Qualità continua e distribuzione

1. Test unitari per parser, deduplicazione, categorie, migrazioni, EPG e formati di scambio.
2. Test d'integrazione per Cloud Sync, pairing, licenze, checksum e revoca.
3. Test statici per evitare regressioni note (main-thread I/O, chiavi/ID, manifest e versioni).
4. Build riproducibili e firma verificata per ogni APK.
5. Changelog, checksum SHA-256, manifest OTA e artefatti con nomi/versioni non ambigui.
6. Pubblicazione stabile solo dopo tutti i gate automatici.

## 4. Regole non negoziabili

- Non cambiare package ID o firma degli APK.
- Non cancellare né sovrascrivere playlist, categorie, preferiti, EPG o attivazioni senza backup e migrazione verificata.
- Non sostituire un flusso funzionante con un placeholder.
- Non dichiarare superato un test che non è stato eseguito.
- Non confondere compilazione riuscita con test reale su dispositivo.
- Non introdurre una riscrittura totale se una migrazione graduale è più sicura.
- Ogni intervento deve essere circoscritto, revisionabile e accompagnato da criteri di accettazione.
- Le PR restano separate per area; la pubblicazione su `main` avviene dopo revisione e conferma del merge.

## 5. Primo ordine di lavoro

1. Correggere l'updater in entrambe le app e verificare il comportamento asincrono.
2. Analizzare e alleggerire il percorso di inserimento/rendering dell'Extractor, non soltanto il numero di righe mostrate.
3. Aggiungere test per parser, deduplicazione e persistenza prima di introdurre il database.
4. Progettare il protocollo Cloud Sync a blocchi e la cifratura end-to-end.
5. Evolvere il matching EPG e la revisione manuale.
6. Eseguire l'audit UX del Player su TV e telecomando.

La strategia è incrementale: DAVIX deve diventare più robusto a ogni rilascio, senza perdere ciò che già funziona.
