# Open Music — verifiche per la prima pubblicazione Google Play

Stato al 5 novembre 2026: **non autorizzata la pubblicazione**; PR #1 ancora
Draft, branch `feat/android-local-player-mvp`. Questo registro non è una
certificazione del comportamento su dispositivo.

## Architettura e qualità

- Kotlin/Compose, Media3 ExoPlayer con sessione nel servizio; player indipendente
  dal ciclo di vita dell'Activity. SQLite/Room non utilizzati.
- Playlist locali atomiche e JSON portabile v1 invariato; matching conservativo
  SHA-256 e cache locale persistente con verifiche della generazione MediaStore.
- Accesso a MediaStore consentito solo dopo permesso; Google Drive
  sincronizzazione playlist manuale e backup audio manuale separato.
- UI scura e accento `#ED8840`; transizioni discrete delle schermate senza
  modifiche al servizio di riproduzione.
- Nessuna integrazione Lyrics online da includere o pubblicizzare.

## Build e pacchetti

- Target Android API 36, min API 26, compile API 36.
- La configurazione release usa R8 e resource shrinking. La build `qa`
  rimane firmata stabilmente e non minificata; la variante `qaOptimized`
  usa R8 e **la medesima chiave QA** per il test sul dispositivo.
- `bundleRelease` genera un AAB **preparatorio non firmato**.
  L'upload su Play richiede una **nuova chiave di upload dedicata**, la
  configurazione Play App Signing e l'OAuth Android con il certificato Play.
  La chiave QA NON diventa automaticamente una chiave di produzione.
- Il workflow PR misura dimensioni di APK debug, APK release, AAB e quota
  compressa DEX/native/risorse; le dimensioni di download per dispositivo
  vanno verificate con bundletool o Play Console, non dedotte dall'AAB.
- Se presenti librerie native di terzi, verificarne la compatibilità con
  pagine di memoria da 16 KB e l'allineamento ELF/APK/AAB.

## Test Android reali ancora da eseguire

1. **Riproduzione:** MP3/FLAC, cuffie/Bluetooth, focus audio, Home, blocco
   schermo, notifiche, interruzioni, controllo della coda e seek.
2. **Dati:** 0/1/molte playlist, modifica ed eliminazione, import/export JSON,
   chiusura/riavvio, riconciliazione SHA, file spostati/rinominati o mancanti,
   dati ancora presenti dopo aggiornamento sulla **stessa firma QA**.
3. **Compatibilità:** Android 8–16, gesture e pulsanti Back, tema/insets,
   schermi stretti, font grandi, TalkBack, movimento ridotto e gestione
   dei permessi negati.
4. **Prestazioni:** circa 800 brani, tempi di avvio, scansione e cache
   fredda/calda, memoria, jank, consumo batteria e rete; non inferire
   prestazioni dal semplice successo di Gradle.
5. **R8:** installare la variante `app-qa-optimized.apk`, confrontarla
   alla QA non minificata, ripetere i percorsi player + Google OAuth/Drive,
   verificare l'assenza di classi eliminate necessarie tramite reflection
   o serializzazione.
6. **Google Drive:** OAuth su dispositivo reale, sincronizzazione offline
   e conflitti, backup audio su rete Wi-Fi e ripristino senza sovrascritture.

## Bloccanti Play Console / prodotto

- Politica privacy: verificare [bozza](../PRIVACY.md) con il comportamento
  definitivo, predisporre URL pubblico stabile **e accesso dall'app**.
- Data Safety coerente con letture audio, archiviazione locale, Android Auto
  Backup, Google Drive facoltativo e dipendenze; verificare con l'account.
- Dichiarazione `mediaPlayback` foreground service: descrizione, impatto
  interruzione e video dimostrativo richiesto in Play Console.
- Chiave upload/Play App Signing e firma OAuth Android per certificato Play;
  AAB definitivo firmato; versionCode e ID package da confermare.
- Scheda store: testo e screenshot accurati, icona, feature graphic,
  categoria, classificazione contenuti, target audience e dati di contatto.
- Licenze/attribuzioni dipendenze e asset: audit della release ridotta.
- Verificare l'eventuale obbligo di closed testing e accesso produzione
  **sull'account di Open Music**, senza riutilizzare automaticamente
  test effettuati per altre app.
- Non dichiarare completata la verifica R8 o il QA telefono prima di una
  prova effettiva dell'APK ottimizzato.

La distribuzione dei pacchetti via GitHub Releases è **QA sperimentale**,
non equivale a Play Store né autorizza il merge della PR.
