# Informativa sulla privacy — Open Music (bozza prima della prima release)

Ultimo aggiornamento: 5 novembre 2026.

**Applicazione:** Open Music (`app.openmusic`). **Sviluppatore:** progetto open source
[falker47/open-music](https://github.com/falker47/open-music). Per richieste relative
alla privacy è disponibile il [canale di contatto del progetto](https://github.com/falker47/open-music/issues):
non pubblicare dati personali o file musicali in issue pubbliche.

Questa informativa descrive il comportamento della versione Android attualmente
sviluppata. Prima della pubblicazione su Google Play occorre verificarla rispetto
alla build definitiva, alla scheda Play e alle dichiarazioni Data Safety.

## Dati utilizzati sul dispositivo

Con il permesso Android di lettura dei file audio, Open Music consulta la libreria
MediaStore: titoli, artisti, album, durate, nomi dei file, metadati tecnici e,
quando disponibili, copertine e testi **incorporati nei file**. Può leggere i
byte degli audio locali per riprodurli e calcolarne l'impronta SHA-256. Le playlist,
le classificazioni manuali, la cache degli hash, lo stato di riconciliazione e
il registro delle revisioni Google Drive sono memorizzati nello spazio privato
dell'app. Le playlist portabili JSON v1 non contengono percorsi assoluti, URI locali
né token OAuth.

Non è previsto il caricamento automatico dei file audio a un servizio dello
sviluppatore. La riproduzione della musica locale non richiede un account Open Music.

## Google Drive — funzioni facoltative, avviate dall'utente

- **Sincronizzazione manuale delle playlist:** con specifica autorizzazione OAuth,
  Open Music legge/scrive revisioni di playlist, metadati dei brani e impronte
  SHA-256 nella cartella applicativa `appDataFolder` del **Google Drive dell'utente**.
- **Backup e ripristino audio manuali:** tramite autorizzazione separata
  `drive.file`, selezione esplicita dei file e conferma del trasferimento,
  Open Music può caricare copie audio nella cartella visibile «Open Music Audio Backup»
  del Google Drive dell'utente, o recuperare backup su richiesta.
- **Importazione/esportazione JSON:** il selettore documenti Android può consentire
  di scegliere un provider di archiviazione esterno; la destinazione è scelta
  dall'utente.

Le connessioni a Google Drive usano HTTPS e i token OAuth sono utilizzati in
memoria per l'operazione autorizzata, non incorporati negli export delle playlist.
La gestione dei dati conservati da Google è soggetta alle impostazioni e
all'informativa di Google. Scollegare Drive revoca l'accesso e rimuove il binding
locale, ma **non cancella automaticamente** i file già salvati nell'account Drive.

## Backup Android

La configurazione attuale consente il backup dell'app tramite i servizi Android
(`android:allowBackup=true`). In funzione della versione del sistema,
dell'account e delle impostazioni di backup del dispositivo, parte dei dati
privati dell'app potrebbe essere inclusa nei backup gestiti dal sistema.
Questo meccanismo è distinto dalla sincronizzazione Google Drive avviata in Open Music.

## Analisi, pubblicità e fornitori

Nel codice verificato per questa versione non sono integrati SDK pubblicitari
o analytics dello sviluppatore. Sono utilizzate librerie AndroidX/Media3 e Google
Play Services per le funzioni dell'app. Le richieste manuali a Google Drive
sono rivolte ai servizi Google; non risulta un backend proprietario dello sviluppatore
che riceva audio o playlist. La presente bozza va rivalutata se vengono aggiunti
nuovi servizi o SDK.

## Conservazione e cancellazione

I dati privati locali restano sul dispositivo fino alla cancellazione tramite
le impostazioni Android o alla disinstallazione, salve eventuali copie di backup
del sistema. L'eliminazione delle playlist dall'app non elimina i file audio
originali. I documenti esportati e i file Drive caricati su richiesta rimangono
nelle destinazioni scelte dall'utente finché non vengono rimossi lì. Per la
gestione o la revoca dell'accesso Google Drive usare anche le impostazioni
dell'account Google.

## Sicurezza e modifiche

L'accesso locale ai file segue i permessi Android, i file interni usano lo spazio
privato dell'app e i trasferimenti Google Drive impiegano HTTPS. Nessuna misura
elimina completamente il rischio; le chiavi di firma e i token non devono essere
inseriti nel repository pubblico. Questa informativa sarà aggiornata se cambiano
funzioni, provider o trattamento dei dati.

**Limite della versione corrente:** il recupero automatico dei testi musicali
da siti online non è disponibile né incluso fra i trattamenti descritti.
