package app.openmusic

import android.content.ComponentName
import android.net.Uri
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.openmusic.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

private val Muted = MusicPalette.muted

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OpenMusicTheme { OpenMusicApp() } }
    }
}

@Composable
private fun OpenMusicApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val storage = remember { PlaylistStorage(context.applicationContext) }
    val kindStorage = remember { ManualAudioStorage(context.applicationContext) }
    val cloudCoordinator = remember { CloudSyncCoordinator(context.applicationContext) }
    val cloudJournal = remember { CloudJournalStorage(context.applicationContext) }
    val artworkCache = remember { LocalArtworkCache() }
    val hashCache = remember { MediaHashCache(context.applicationContext) }
    val playlistWriteMutex = remember { Mutex() }
    val kindWriteMutex = remember { Mutex() }
    // The playback engine lives in PlaybackService; this UI only holds a controller.
    var player by remember { mutableStateOf<MediaController?>(null) }
    var audioFiles by remember { mutableStateOf(emptyList<AudioFile>()) }
    var localCopies by remember { mutableStateOf(emptyList<LocalCopy>()) }
    var manualKinds by remember { mutableStateOf(emptyMap<String, ManualAudioOverride>()) }
    var latestAnalysis by remember { mutableStateOf("") }
    var resolvedMatches by remember { mutableStateOf(emptyMap<String, List<MatchResult>>()) }
    var verificationComplete by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var unreadableCount by remember { mutableIntStateOf(0) }
    var didScan by remember { mutableStateOf(false) }
    var analysisJob by remember { mutableStateOf<Job?>(null) }
    var onlyMusic by rememberSaveable { mutableStateOf(true) }
    var playlists by remember { mutableStateOf(emptyList<PortablePlaylist>()) }
    var deletedIds by remember { mutableStateOf(emptySet<String>()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var localLoaded by remember { mutableStateOf(false) }
    var cloudState by remember {
        mutableStateOf(runCatching { cloudJournal.load() }.getOrDefault(CloudJournal()))
    }
    val cloudLinked = cloudState.binding != null
    var cloudSyncError by remember { mutableStateOf<String?>(null) }
    var showCloudMenu by remember { mutableStateOf(false) }
    var cloudChoices by remember { mutableStateOf(emptyMap<String, CloudResolution>()) }
    var cloudConflicts by remember { mutableStateOf(emptyList<CloudConflict>()) }
    var showCloudDisconnect by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf(false) }
    var showDeleteChooser by remember { mutableStateOf(false) }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }
    var showQueuePicker by remember { mutableStateOf(false) }
    var queuePickSource by remember { mutableIntStateOf(0) }
    var queuePickPlaylistId by remember { mutableStateOf<String?>(null) }
    var queuePickSearch by remember { mutableStateOf("") }
    var pendingAdd by remember { mutableStateOf<AudioFile?>(null) }
    var pendingHash by remember { mutableStateOf<String?>(null) }
    var pendingHashError by remember { mutableStateOf<String?>(null) }
    var pendingHashRetry by remember { mutableIntStateOf(0) }
    var proposedName by remember { mutableStateOf("") }
    var exportPending by remember { mutableStateOf<PortablePlaylist?>(null) }
    var nowTitle by remember { mutableStateOf("") }
    var nowArtist by remember { mutableStateOf("") }
    var nowAlbum by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var queueMode by remember { mutableStateOf(PlaybackQueueCommands.LIBRARY) }
    var repeatOneEnabled by remember { mutableStateOf(false) }
    var queueEntries by remember { mutableStateOf(emptyList<MusicQueueEntry>()) }
    var currentQueueIndex by remember { mutableIntStateOf(-1) }
    var showQueue by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    val selected = playlists.firstOrNull { it.playlistId == selectedId }
    val matches = resolvedMatches[selectedId].orEmpty()
    val byEntryId = matches.associateBy { it.entryId }
    val mediaByUri = audioFiles.associateBy { it.uri }

    fun notifyUser(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun persist(updated: List<PortablePlaylist>): Boolean {
        return try {
            storage.save(updated)
            playlists = updated
            deletedIds = storage.loadState().deleted.map { it.playlistId }.toSet()
            true
        } catch (failure: Exception) {
            notifyUser("Impossibile salvare: " + (failure.localizedMessage ?: "errore"))
            false
        }
    }

    suspend fun refreshMatches(
        collection: List<PortablePlaylist>, copies: List<LocalCopy>,
    ) {
        // Matching thousands of entries must never happen during Compose recomposition.
        val results = withContext(Dispatchers.Default) {
            PlaylistMatcher.resolveAll(collection, copies)
        }
        resolvedMatches = results
    }

    suspend fun reconcile(
        found: List<AudioFile>,
        reason: String = "scansione",
        indexingMs: Long = 0L,
    ) {
        verifying = true
        verificationComplete = false
        unreadableCount = 0
        resolvedMatches = emptyMap()
        val fresh = ArrayList<LocalCopy>(found.size)
        val hints = ReconcilePriority.hints(playlists)
        // Metadata only schedules candidates first. It cannot force a match.
        // Remaining audio is always included if any playlist match is unresolved.
        val ordered = found.sortedByDescending {
            ReconcilePriority.score(it.asCopy().identity, hints)
        }
        var unreadable = 0
        var reused = 0
        var recomputed = 0
        var checked = 0
        var digestWallMs = 0L
        var matchingWallMs = 0L
        var exactCoverage = false
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            for (batch in ordered.chunked(24)) {
                val hashStarted = android.os.SystemClock.elapsedRealtime()
                // Bound memory and structured child lifetimes to one small batch.
                // MediaHashCache limits physical content readers to two.
                val results = coroutineScope {
                    batch.map { audio ->
                        async(Dispatchers.IO) {
                            val result = try {
                                hashCache.digest(audio)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                null
                            }
                            audio to result
                        }
                    }.awaitAll()
                }
                digestWallMs += android.os.SystemClock.elapsedRealtime() - hashStarted
                for ((audio, digest) in results) {
                    if (digest?.reused == true) reused++
                    else if (digest != null) recomputed++ else unreadable++
                    fresh.add(audio.asCopy(digest?.hash))
                }
                checked += batch.size
                localCopies = fresh.toList()
                val matchingStarted = android.os.SystemClock.elapsedRealtime()
                refreshMatches(playlists, localCopies)
                matchingWallMs += android.os.SystemClock.elapsedRealtime() - matchingStarted
                busy = "Verifica SHA-256 · " + checked + "/" + ordered.size +
                    " · cache " + reused

                // VERIFIED EXACT needs only one identical byte-for-byte copy.
                // If every entry in every playlist has one, no unexamined file can
                // turn it into a MISSING/REVIEW/AMBIGUOUS. Stop early safely.
                if (ReconcileCompleteness.allExact(playlists, resolvedMatches)) {
                    exactCoverage = true
                    break
                }
            }
            // Also update an empty inventory so MISSING is final when covered.
            if (ordered.isEmpty()) {
                localCopies = emptyList()
                refreshMatches(playlists, localCopies)
            }
            unreadableCount = unreadable
            val scannedAll = checked == found.size
            verificationComplete = exactCoverage || (scannedAll && unreadable == 0)
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            latestAnalysis = reason + ": " + (elapsed + indexingMs) +
                " ms · " + recomputed + " hash ricalcolati · " + reused +
                " dalla cache / " + checked + " verificati su " + found.size +
                if (exactCoverage && !scannedAll) " · playlist già tutte EXACT" else ""
            latestAnalysis += "\nLibreria " + indexingMs + " ms · SHA " +
                digestWallMs + " ms · matching " + matchingWallMs + " ms"
            val current = playlists.firstOrNull { it.playlistId == selectedId }
            val matched = current?.let { resolvedMatches[it.playlistId].orEmpty() }
                ?.count { it.status == MatchStatus.EXACT } ?: 0
            if (current != null) {
                notifyUser("SHA verificati " + matched + "/" + current.tracks.size +
                    " · audio non leggibili: " + unreadable)
            } else if (unreadable > 0) {
                notifyUser("Analisi completata con " + unreadable + " audio non leggibili")
            }
        } finally {
            // Persist a partial safe cache even on explicit cancellation.
            try { withContext(NonCancellable) { hashCache.flush() } }
            catch (_: Exception) {
                notifyUser("Cache SHA non salvata: la prossima analisi potrebbe rallentare")
            }
            verifying = false
        }
    }

    fun scan() {
        if (busy.isNotEmpty()) return
        busy = "Lettura di tutti gli audio locali"
        analysisJob = scope.launch {
            try {
                val indexingStarted = android.os.SystemClock.elapsedRealtime()
                val found = withContext(Dispatchers.IO) { MediaLibrary.scan(context) }
                val indexingMs = android.os.SystemClock.elapsedRealtime() - indexingStarted
                // Retain unchanged MediaStore identities across scans and app restarts.
                hashCache.prepareForScan(found)
                kindWriteMutex.withLock {
                    val retained = AudioClassification.retain(manualKinds, found.map { it.stamp() })
                    if (retained != manualKinds) {
                        withContext(Dispatchers.IO) { kindStorage.save(retained) }
                        manualKinds = retained
                    }
                }
                audioFiles = found
                didScan = true
                localCopies = found.map { it.asCopy() }
                resolvedMatches = emptyMap()
                verificationComplete = false
                unreadableCount = 0
                if (playlists.any { it.tracks.isNotEmpty() }) {
                    reconcile(found, indexingMs = indexingMs)
                } else {
                    verificationComplete = true
                    hashCache.flush()
                    latestAnalysis = "Libreria: " + indexingMs + " ms · " +
                        found.size + " audio (nessuna playlist da verificare)"
                    notifyUser("Indicizzati " + found.size + " file audio")
                }
            } catch (cancel: CancellationException) {
                // Cancellation leaves entries unverified, never falsely missing.
                throw cancel
            } catch (failure: Exception) {
                notifyUser("Scansione non riuscita: " + (failure.localizedMessage ?: "errore"))
            } finally {
                busy = ""
            }
        }
    }

    // Explicit sync: local edits stay durable offline and are never auto-uploaded.
    fun runCloudSync(grant: String) {
        if (busy.isNotEmpty() || !localLoaded) return
        cloudSyncError = null
        busy = "Sincronizzazione Google Drive"
        scope.launch {
            try {
                playlistWriteMutex.withLock {
                    val result = withContext(Dispatchers.IO) {
                        cloudCoordinator.sync(grant, cloudChoices)
                    }
                    cloudState = withContext(Dispatchers.IO) { cloudJournal.load() }
                    deletedIds = withContext(Dispatchers.IO) {
                        storage.loadState().deleted.map { it.playlistId }.toSet()
                    }
                    if (result.conflicts.isNotEmpty()) {
                        cloudConflicts = result.conflicts
                        notifyUser("Conflitto Drive: scegli quale versione conservare")
                    } else {
                        playlists = result.playlists
                        if (selectedId == null || result.playlists.none { it.playlistId == selectedId })
                            selectedId = result.playlists.firstOrNull()?.playlistId
                        cloudChoices = emptyMap()
                        cloudConflicts = emptyList()
                        if (result.downloaded > 0 && audioFiles.isNotEmpty()) {
                            reconcile(audioFiles, "sincronizzazione cloud")
                        } else {
                            refreshMatches(playlists, localCopies)
                        }
                        val pending = CloudLocalSyncInspector.inspect(
                            result.playlists, cloudState.checkpoints)
                        notifyUser(when {
                            pending.pendingCount > 0 ->
                                "Drive aggiornato · altre playlist da sincronizzare: " +
                                    pending.pendingCount
                            result.uploaded == 0 && result.downloaded == 0 ->
                                "Drive · nessuna modifica"
                            else -> "Drive · " + result.uploaded + " revisioni inviate, " +
                                result.downloaded + " ricevute, " + result.deleted + " eliminate"
                        })
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                cloudChoices = emptyMap()
                cloudConflicts = emptyList()
                cloudSyncError = "Sincronizzazione non riuscita · riprova"
                notifyUser("Drive: " + (failure.localizedMessage ?: "sincronizzazione non riuscita"))
            } finally {
                // A partial upload can have advanced the durable journal.
                // Always refresh its status without assuming sync succeeded.
                cloudState = runCatching {
                    withContext(Dispatchers.IO) { cloudJournal.load() }
                }.getOrDefault(cloudState)
                busy = ""
            }
        }
    }

    val requestDriveAccess = rememberDriveAccess(
        onAuthorized = { token -> runCloudSync(token) },
        onError = { error ->
            cloudSyncError = "Drive non disponibile · riprova"
            notifyUser(error)
        },
    )
    val revokeDriveAccess = rememberDriveRevocation(
        onRevoked = {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { cloudCoordinator.disconnect() }
                    cloudState = withContext(Dispatchers.IO) { cloudJournal.load() }
                    cloudSyncError = null
                    cloudChoices = emptyMap()
                    cloudConflicts = emptyList()
                    notifyUser("Account Google Drive scollegato · playlist locali intatte")
                } catch (failure: Exception) {
                    notifyUser("Scollegamento locale non riuscito: " +
                        (failure.localizedMessage ?: "errore"))
                }
            }
        },
        onError = { error -> notifyUser(error) },
    )
    fun resolveCloud(choice: CloudResolution) {
        val conflict = cloudConflicts.firstOrNull() ?: return
        cloudChoices = cloudChoices + (conflict.playlistId to choice)
        cloudConflicts = cloudConflicts.drop(1)
        if (cloudConflicts.isEmpty()) requestDriveAccess()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scan() else notifyUser("Consenti l'accesso alla musica per leggere la libreria")
    }

    fun requestScan() {
        if (MediaLibrary.hasPermission(context)) scan()
        else permissionLauncher.launch(MediaLibrary.permission())
    }

    fun playQueue(
        queue: List<AudioFile>, index: Int,
        source: String = PlaybackQueueCommands.LIBRARY,
    ) {
        if (queue.isEmpty() || index !in queue.indices) return
        val controller = player
        if (controller == null) {
            notifyUser("Player in connessione: riprova fra un momento")
            return
        }
        val items = queue.map { file ->
            MediaItem.Builder()
                .setMediaId(UUID.randomUUID().toString())
                .setUri(Uri.parse(file.uri))
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(file.title)
                    .setArtist(file.artist)
                    .setAlbumTitle(file.album)
                    .build())
                .build()
        }
        controller.setMediaItems(items, index, 0L)
        controller.sendCustomCommand(
            PlaybackQueueCommands.command(PlaybackQueueCommands.SET_MODE),
            PlaybackQueueCommands.modeArgs(source),
        )
        controller.prepare()
        controller.play()
    }

    fun appendToQueue(file: AudioFile) {
        val controller = player ?: run {
            notifyUser("Player in connessione")
            return
        }
        val item = MediaItem.Builder()
            .setMediaId(UUID.randomUUID().toString())
            .setUri(Uri.parse(file.uri))
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(file.title)
                .setArtist(file.artist)
                .setAlbumTitle(file.album)
                .build())
            .build()
        if (controller.mediaItemCount == 0) {
            controller.setMediaItem(item)
            controller.prepare()
            controller.play()
        } else {
            controller.addMediaItem(item)
        }
        controller.sendCustomCommand(
            PlaybackQueueCommands.command(PlaybackQueueCommands.SET_MODE),
            PlaybackQueueCommands.modeArgs(PlaybackQueueCommands.CUSTOM),
        )
        notifyUser("Aggiunto alla coda: " + file.title)
    }

    fun addToPlaylist(file: AudioFile, listId: String) {
        if (busy.isNotEmpty()) return
        busy = "Verifica SHA-256 del brano"
        scope.launch {
            playlistWriteMutex.withLock {
                try {
                    val hash = hashCache.sha256(file)
                    hashCache.flush()
                    val list = playlists.firstOrNull { it.playlistId == listId }
                        ?: throw IllegalStateException("Playlist non trovata")
                    val identity = file.asCopy(hash).identity
                    localCopies = VerifiedCopies.upsert(localCopies, file.asCopy(hash))
                    // The final read/modify/write is serialized, even on repeated taps.
                    val next = PlaylistRouting.addIfAbsent(
                        list,
                        PlaylistEntry(UUID.randomUUID().toString(), identity),
                        Instant.now().toString(),
                    )
                    if (next == null) {
                        selectedId = listId
                        notifyUser("Già presente in " + list.name)
                    } else if (persist(playlists.map {
                            if (it.playlistId == listId) next else it
                        })) {
                        refreshMatches(playlists, localCopies)
                        selectedId = listId
                        notifyUser("Aggiunto a " + list.name +
                            if (cloudLinked) " · sincronizza Drive" else "")
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    notifyUser("Impossibile aggiungere: " + (failure.localizedMessage ?: "errore"))
                } finally {
                    busy = ""
                }
            }
        }
    }

    fun requestAddToPlaylist(file: AudioFile) {
        if (busy.isNotEmpty()) return
        when (PlaylistRouting.decide(playlists)) {
            PlaylistDestination.CreateFirst ->
                notifyUser("Crea prima una playlist nella scheda Playlist")
            // Even a single destination now shows the preventive verified membership.
            else -> pendingAdd = file
        }
    }

    fun setMusicClassification(file: AudioFile, music: Boolean) {
        scope.launch {
            val item = file.stamp()
            val applied = ManualAudioOverride(item, music)
            var previous: ManualAudioOverride? = null
            val saved = kindWriteMutex.withLock {
                if (audioFiles.none { it.stamp() == item }) return@withLock false
                if (AudioClassification.isMusic(file.isMusic, item, manualKinds) == music)
                    return@withLock false
                previous = manualKinds[file.uri]
                val updated = AudioClassification.set(manualKinds, item, music)
                try {
                    withContext(Dispatchers.IO) { kindStorage.save(updated) }
                    manualKinds = updated
                    true
                } catch (failure: Exception) {
                    notifyUser("Classificazione non salvata: " +
                        (failure.localizedMessage ?: "errore"))
                    false
                }
            }
            if (!saved) return@launch

            val message = if (music) "«" + file.title + "» aggiunta a Musica"
                else "«" + file.title + "» spostata in Altri audio"
            if (snackbar.showSnackbar(
                    message, actionLabel = "Annulla", withDismissAction = true,
                    duration = SnackbarDuration.Short,
                ) == SnackbarResult.ActionPerformed) {
                kindWriteMutex.withLock {
                    if (audioFiles.none { it.stamp() == item }) {
                        notifyUser("Il file è cambiato: classificazione non ripristinata")
                        return@withLock
                    }
                    val restored = AudioClassification.undoIfCurrent(
                        manualKinds, item, applied, previous,
                    ) ?: return@withLock
                    try {
                        withContext(Dispatchers.IO) { kindStorage.save(restored) }
                        manualKinds = restored
                        notifyUser("Classificazione ripristinata: " + file.title)
                    } catch (failure: Exception) {
                        notifyUser("Annullamento non salvato: " +
                            (failure.localizedMessage ?: "errore"))
                    }
                }
            }
        }
    }

    fun relink() {
        if (busy.isNotEmpty()) return
        if (selected == null) { notifyUser("Seleziona una playlist"); return }
        // Manual retry re-scans all audio, but only changed/uncached bytes are rehashed.
        requestScan()
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Importazione della playlist"
        analysisJob = scope.launch {
            var needsScan = false
            try {
                val imported = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Documento non leggibile")
                    val bytes = stream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var count: Int
                        while (input.read(buffer).also { count = it } != -1) {
                            output.write(buffer, 0, count)
                            require(output.size() <= 2_000_000) { "File troppo grande" }
                        }
                        output.toByteArray()
                    }
                    PortableJson.decode(bytes.toString(Charsets.UTF_8))
                }
                if (playlists.any { it.playlistId == imported.playlistId }) {
                    notifyUser("Playlist con lo stesso ID già presente: nessuna sovrascrittura")
                } else if (persist(playlists + imported)) {
                    selectedId = imported.playlistId
                    tab = 1
                    if (didScan) {
                        // Automatic local reconciliation, no second user action needed.
                        reconcile(audioFiles, "importazione")
                        notifyUser("Importata " + imported.name + " · riconciliazione eseguita")
                    } else {
                        needsScan = true
                        notifyUser("Importata " + imported.name + " · scansione automatica")
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                notifyUser("Importazione non riuscita: " + (failure.localizedMessage ?: "errore"))
            } finally {
                busy = ""
                if (needsScan) requestScan()
            }
        }
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val chosen = exportPending
        exportPending = null
        if (uri == null || chosen == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Esportazione della playlist"
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use {
                        it.write(PortableJson.encode(chosen).toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Non è possibile scrivere il documento")
                }
                notifyUser("Playlist esportata")
            } catch (failure: Exception) {
                notifyUser("Esportazione non riuscita: " + (failure.localizedMessage ?: "errore"))
            } finally {
                busy = ""
            }
        }
    }

    fun createPlaylist() {
        val name = proposedName.trim()
        if (name.isBlank() || name.length > 160) { notifyUser("Nome playlist non valido"); return }
        val now = Instant.now().toString()
        val list = PortablePlaylist(UUID.randomUUID().toString(), name, now, now, emptyList())
        if (persist(playlists + list)) selectedId = list.playlistId
        proposedName = ""
        createDialog = false
        tab = 1
    }

    fun deletePlaylist(id: String) {
        if (busy.isNotEmpty() || !localLoaded) return
        scope.launch {
            playlistWriteMutex.withLock {
                val victim = playlists.firstOrNull { it.playlistId == id }
                    ?: return@withLock
                try {
                    val state = withContext(Dispatchers.IO) {
                        storage.deletePlaylist(id)
                    }
                    val remaining = state.playlists
                    deletedIds = state.deleted.map { it.playlistId }.toSet()
                    playlists = remaining
                    if (selectedId == id) selectedId = remaining.firstOrNull()?.playlistId
                    refreshMatches(remaining, localCopies)
                    notifyUser("Eliminata «" + victim.name + "» · file audio intatti" +
                        if (cloudLinked) " · alla prossima sincronizzazione decidi su Drive"
                        else "")
                } catch (failure: Exception) {
                    notifyUser("Eliminazione non riuscita: " +
                        (failure.localizedMessage ?: "errore"))
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            val state = withContext(Dispatchers.IO) { storage.loadState() }
            playlists = state.playlists
            deletedIds = state.deleted.map { it.playlistId }.toSet()
            manualKinds = withContext(Dispatchers.IO) { kindStorage.load() }
            selectedId = playlists.firstOrNull()?.playlistId
            localLoaded = true
        } catch (failure: Exception) {
            notifyUser("Archivio locale non leggibile: " + (failure.localizedMessage ?: "errore"))
        }
        if (MediaLibrary.hasPermission(context)) scan()
    }

    LaunchedEffect(pendingAdd?.uri, pendingHashRetry) {
        val file = pendingAdd
        pendingHash = null
        pendingHashError = null
        if (file != null) {
            try {
                val hash = hashCache.sha256(file)
                pendingHash = hash
                hashCache.flush()
                localCopies = VerifiedCopies.upsert(localCopies, file.asCopy(hash))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                pendingHashError = failure.localizedMessage ?: "File non leggibile"
            }
        }
    }

    // Connect asynchronously. Releasing the UI controller must not release service playback.
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                    queueMode = extras.getString(PlaybackQueueCommands.MODE_KEY)
                        ?: PlaybackQueueCommands.LIBRARY
                    shuffleEnabled = queueMode == PlaybackQueueCommands.SHUFFLE
                }
            }).buildAsync()
        var disposed = false
        future.addListener({
            if (!disposed) {
                try {
                    player = future.get()
                    queueMode = player?.sessionExtras
                        ?.getString(PlaybackQueueCommands.MODE_KEY)
                        ?: PlaybackQueueCommands.LIBRARY
                    shuffleEnabled = queueMode == PlaybackQueueCommands.SHUFFLE
                } catch (failure: Exception) {
                    scope.launch {
                        snackbar.showSnackbar("Player non disponibile: " +
                            (failure.localizedMessage ?: "errore di connessione"))
                    }
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            player = null
            MediaController.releaseFuture(future)
        }
    }

    DisposableEffect(player) {
        val active = player
        if (active == null) {
            onDispose { }
        } else {
            fun updateNowPlaying() {
                // Read the same effective metadata the MediaSession publishes to Android.
                val metadata = active.mediaMetadata
                nowTitle = metadata.title?.toString().orEmpty()
                nowArtist = metadata.artist?.toString().orEmpty()
                nowAlbum = metadata.albumTitle?.toString().orEmpty()
                currentUri = active.currentMediaItem?.localConfiguration?.uri
                playing = active.isPlaying
                queueMode = active.sessionExtras.getString(PlaybackQueueCommands.MODE_KEY)
                    ?: PlaybackQueueCommands.LIBRARY
                shuffleEnabled = queueMode == PlaybackQueueCommands.SHUFFLE
                repeatOneEnabled = active.repeatMode == Player.REPEAT_MODE_ONE
                currentQueueIndex = if (active.mediaItemCount > 0)
                    active.currentMediaItemIndex else -1
                queueEntries = (0 until active.mediaItemCount).map { index ->
                    val item = active.getMediaItemAt(index)
                    MusicQueueEntry(
                        index = index,
                        mediaId = item.mediaId,
                        title = item.mediaMetadata.title?.toString()
                            ?.takeIf { it.isNotBlank() } ?: ("Brano " + (index + 1)),
                        artist = item.mediaMetadata.artist?.toString().orEmpty(),
                        uri = item.localConfiguration?.uri?.toString(),
                    )
                }
                // Reflect seek/skip and Activity reconnection immediately, rather than
                // leaving the progress bar on its prior track until the 750 ms tick.
                position = active.currentPosition.coerceAtLeast(0L)
                duration = active.duration.coerceAtLeast(0L)
            }
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    updateNowPlaying()
                }
            }
            active.addListener(listener)
            // An activity recreated while audio continues must show the existing session.
            updateNowPlaying()
            onDispose { active.removeListener(listener) }
        }
    }

    LaunchedEffect(player) {
        val active = player
        if (active != null) {
            while (true) {
                position = active.currentPosition.coerceAtLeast(0L)
                duration = active.duration.coerceAtLeast(0L)
                delay(750)
            }
        } else {
            position = 0L
            duration = 0L
        }
    }


    if (showDeleteChooser) {
        AlertDialog(
            onDismissRequest = { showDeleteChooser = false },
            title = { Text("Elimina una playlist") },
            text = {
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                    playlists.forEach { list ->
                        TextButton(
                            onClick = {
                                showDeleteChooser = false
                                deleteConfirmId = list.playlistId
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(list.name + " · " + list.tracks.size + " brani",
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDeleteChooser = false }) { Text("Annulla") }
            },
        )
    }
    deleteConfirmId?.let { id ->
        val target = playlists.firstOrNull { it.playlistId == id }
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text("Eliminare «" + (target?.name ?: "playlist") + "»?") },
            text = {
                Text("La playlist verrà eliminata dal telefono, non i file musicali. " +
                    if (cloudLinked) "La copia Drive resterà finché non decidi cosa mantenerne alla sincronizzazione."
                    else "L'operazione non elimina alcun brano dal dispositivo.")
            },
            confirmButton = {
                TextButton(
                    onClick = { deleteConfirmId = null; deletePlaylist(id) },
                    enabled = target != null && busy.isEmpty(),
                ) { Text("Elimina playlist", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmId = null }) { Text("Annulla") }
            },
        )
    }

    if (createDialog) {
        AlertDialog(
            onDismissRequest = { createDialog = false },
            title = { Text("Nuova playlist") },
            text = {
                OutlinedTextField(
                    value = proposedName,
                    onValueChange = { proposedName = it },
                    label = { Text("Nome playlist") },
                    placeholder = { Text("Es. Preferiti") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { createPlaylist() }, enabled = proposedName.trim().isNotEmpty()) {
                    Text("Crea")
                }
            },
            dismissButton = { TextButton(onClick = { createDialog = false }) { Text("Annulla") } },
            shape = RoundedCornerShape(26.dp),
        )
    }

    pendingAdd?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingAdd = null },
            title = { Text("Scegli la playlist") },
            text = {
                Column(Modifier.heightIn(max = 370.dp).verticalScroll(rememberScrollState())) {
                    Text("Aggiungi \"" + file.title + "\" a:",
                        color = Muted, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    when {
                        pendingHashError != null -> {
                            Text("Verifica SHA-256 fallita: " + pendingHashError,
                                color = MusicPalette.warning, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { pendingHashRetry++ }) { Text("Riprova verifica") }
                        }
                        pendingHash == null -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Verifica SHA-256 in corso", color = Muted)
                            }
                        }
                    }
                    playlists.forEach { list ->
                        val alreadyPresent = PlaylistRouting.containsVerifiedHash(list, pendingHash)
                        Surface(
                            onClick = {
                                pendingAdd = null
                                addToPlaylist(file, list.playlistId)
                            },
                            enabled = pendingHash != null && !alreadyPresent && busy.isEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            color = MusicPalette.elevated,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(list.name, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        when {
                                            alreadyPresent -> "Già presente"
                                            pendingHash == null -> "Verifica in corso"
                                            else -> list.tracks.size.toString() + " brani · Aggiungi"
                                        },
                                        color = if (alreadyPresent) MusicPalette.primary else Muted,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (alreadyPresent) {
                                    Icon(androidx.compose.material.icons.Icons.Rounded.CheckCircle,
                                        contentDescription = "Già presente", tint = MusicPalette.primary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pendingAdd = null }) { Text("Annulla") } },
            shape = RoundedCornerShape(26.dp),
        )
    }

    if (cloudConflicts.isNotEmpty()) {
        CloudConflictDialog(
            conflict = cloudConflicts.first(),
            onResolve = { resolveCloud(it) },
            onCancel = {
                cloudConflicts = emptyList()
                cloudChoices = emptyMap()
                cloudSyncError = "Conflitto non risolto · sincronizza per riprovare"
            },
        )
    }
    if (showCloudDisconnect) {
        AlertDialog(
            onDismissRequest = { showCloudDisconnect = false },
            title = { Text("Scollegare Google Drive?") },
            text = {
                Text("Le playlist sul telefono e le revisioni già sincronizzate su Drive " +
                    "rimangono intatte. L'autorizzazione verrà revocata.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showCloudDisconnect = false
                    revokeDriveAccess()
                }) { Text("Scollega") }
            },
            dismissButton = {
                TextButton(onClick = { showCloudDisconnect = false }) {
                    Text("Annulla")
                }
            },
        )
    }

    BackHandler(enabled = showNowPlaying && !showQueue) { showNowPlaying = false }
    if (showQueuePicker) {
        val candidatePlaylist = queuePickPlaylistId
            ?: selectedId ?: playlists.firstOrNull()?.playlistId
        val resolved = resolvedMatches[candidatePlaylist].orEmpty()
        val candidates = if (queuePickSource == 0) audioFiles else resolved.mapNotNull {
            it.localUri?.let(mediaByUri::get)
        }
        val candidatesFiltered = candidates.filter {
            AudioSearch.matches(it.title, it.artist, queuePickSearch)
        }
        AlertDialog(
            onDismissRequest = { showQueuePicker = false },
            title = { Text("Aggiungi alla coda") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row {
                        FilterChip(
                            selected = queuePickSource == 0,
                            onClick = { queuePickSource = 0 },
                            label = { Text("Libreria") },
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = queuePickSource == 1,
                            enabled = playlists.isNotEmpty(),
                            onClick = { queuePickSource = 1 },
                            label = { Text("Playlist") },
                        )
                    }
                    if (queuePickSource == 1) {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            playlists.forEach { list ->
                                FilterChip(
                                    selected = list.playlistId == candidatePlaylist,
                                    onClick = { queuePickPlaylistId = list.playlistId },
                                    label = { Text(list.name, maxLines = 1) },
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = queuePickSearch,
                        onValueChange = { queuePickSearch = it },
                        singleLine = true,
                        label = { Text("Cerca brano") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (candidatesFiltered.isEmpty()) {
                        Text("Nessun brano disponibile",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    } else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(candidatesFiltered.size) { index ->
                            val file = candidatesFiltered[index]
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .clickable { appendToQueue(file) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(file.title, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleMedium)
                                    Text(file.artist.orEmpty(), maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(Icons.Rounded.Add, contentDescription = "Inserisci nella coda")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQueuePicker = false }) { Text("Fine") }
            },
        )
    }
    if (showQueue) {
        MusicQueueSheet(
            entries = queueEntries,
            currentIndex = currentQueueIndex,
            queueMode = queueMode,
            repeatOneEnabled = repeatOneEnabled,
            cache = artworkCache,
            onDismiss = { showQueue = false },
            onAdd = { showQueuePicker = true },
            onSelect = { index ->
                val active = player
                val selectedEntry = queueEntries.getOrNull(index)
                if (active != null && selectedEntry != null &&
                    PlaybackUiLogic.canSelectQueue(index, active.mediaItemCount) &&
                    active.getMediaItemAt(index).mediaId == selectedEntry.mediaId
                ) {
                    active.seekTo(index, 0L)
                }
            },
            onMove = { from, to ->
                val active = player
                if (active != null &&
                    PlaybackQueuePlan.canMoveUpcoming(
                        from, to, active.currentMediaItemIndex, active.mediaItemCount)
                ) {
                    active.moveMediaItem(from, to)
                    active.sendCustomCommand(
                        PlaybackQueueCommands.command(PlaybackQueueCommands.SET_MODE),
                        PlaybackQueueCommands.modeArgs(PlaybackQueueCommands.CUSTOM),
                    )
                }
            },
            onRemove = { index ->
                val active = player
                if (active != null &&
                    PlaybackQueuePlan.canEditUpcoming(
                        index, active.currentMediaItemIndex, active.mediaItemCount)
                ) {
                    active.removeMediaItem(index)
                    active.sendCustomCommand(
                        PlaybackQueueCommands.command(PlaybackQueueCommands.SET_MODE),
                        PlaybackQueueCommands.modeArgs(PlaybackQueueCommands.CUSTOM),
                    )
                }
            },
        )
    }
    val musicCount = remember(audioFiles, manualKinds) {
        audioFiles.count { AudioClassification.isMusic(it.isMusic, it.stamp(), manualKinds) }
    }
    val visibleAudio = remember(audioFiles, manualKinds, onlyMusic) {
        audioFiles.filter {
            AudioClassification.isMusic(it.isMusic, it.stamp(), manualKinds) == onlyMusic
        }
    }
    val filtered = remember(visibleAudio, search) {
        visibleAudio.filter { AudioSearch.matches(it.title, it.artist, search) }
    }
    val localSyncStatus = remember(playlists, cloudState) {
        CloudLocalSyncInspector.inspect(playlists, cloudState.checkpoints)
    }
    val driveStatus = when {
        !localLoaded -> "Caricamento playlist"
        cloudConflicts.isNotEmpty() -> "Conflitto: scegli una versione"
        cloudSyncError != null -> cloudSyncError!!
        !cloudLinked -> "Playlist solo sul telefono"
        localSyncStatus.missingCount > 0 ->
            if (deletedIds.any { cloudState.checkpoints[it]?.deleted == false })
                "Eliminazione playlist da confermare su Drive"
            else "Playlist locali mancanti · verifica Drive"
        localSyncStatus.pendingCount > 0 -> localSyncStatus.pendingCount.toString() +
            " playlist da sincronizzare"
        else -> "Nessuna modifica da salvare"
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MusicPalette.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (!showNowPlaying) {
                Column(
                    Modifier.fillMaxWidth().background(MusicPalette.background)
                        .navigationBarsPadding(),
                ) {
                    if (nowTitle.isNotEmpty()) {
                        MusicMiniPlayer(
                            title = nowTitle, artist = nowArtist, uri = currentUri?.toString(),
                            cache = artworkCache, playing = playing,
                            positionMs = position, durationMs = duration,
                            onOpen = { showNowPlaying = true },
                            onSeek = { millis -> player?.seekTo(millis) },
                            onPrevious = {
                                player?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
                            },
                            onPlayPause = { player?.let { if (it.isPlaying) it.pause() else it.play() } },
                            onNext = { player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } },
                        )
                    }
                    MusicBottomNavigation(tab = tab, onTab = { tab = it })
                }
            }
        },
    ) { insets ->
        if (showNowPlaying && nowTitle.isNotEmpty()) {
            MusicNowPlaying(
                modifier = Modifier.fillMaxSize().padding(insets),
                title = nowTitle, artist = nowArtist, album = nowAlbum,
                uri = currentUri?.toString(),
                cache = artworkCache, playing = playing,
                positionMs = position, durationMs = duration,
                shuffleEnabled = shuffleEnabled, repeatOneEnabled = repeatOneEnabled,
                queueSize = queueEntries.size,
                onClose = { showQueue = false; showNowPlaying = false },
                onSeek = { millis -> player?.seekTo(millis) },
                onPrevious = {
                    player?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
                },
                onPlayPause = { player?.let { if (it.isPlaying) it.pause() else it.play() } },
                onNext = { player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } },
                onToggleShuffle = {
                    player?.sendCustomCommand(
                        PlaybackQueueCommands.command(PlaybackQueueCommands.TOGGLE_SHUFFLE),
                        Bundle.EMPTY,
                    )
                },
                onToggleRepeatOne = {
                    player?.let { active ->
                        val enabled = PlaybackUiLogic.toggleRepeatOne(
                            active.repeatMode == Player.REPEAT_MODE_ONE)
                        active.repeatMode = if (enabled)
                            Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    }
                },
                onOpenQueue = { showQueue = true },
            )
        } else {
            Column(
                Modifier.fillMaxSize().padding(insets).padding(horizontal = 18.dp),
            ) {
                if (tab == 0) MusicHeader(
                    count = visibleAudio.size,
                    total = audioFiles.size,
                    enabled = busy.isEmpty(),
                    onScan = { requestScan() },
                    onQueue = { showQueue = true },
                )
                if (busy.isNotEmpty()) MusicBusyIndicator(
                    busy, onCancel = if (verifying) ({ analysisJob?.cancel() }) else null,
                )
                if (tab == 0) {
                    MusicLibraryCategoryTabs(
                        onlyMusic = onlyMusic,
                        musicCount = musicCount,
                        otherCount = audioFiles.size - musicCount,
                        onSelect = { onlyMusic = it },
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = search, onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        placeholder = { Text("Cerca brano o artista", style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = {
                            Icon(androidx.compose.material.icons.Icons.Rounded.Search,
                                contentDescription = null)
                        },
                        trailingIcon = {
                            if (search.isNotEmpty()) {
                                IconButton(onClick = { search = "" }) {
                                    Icon(androidx.compose.material.icons.Icons.Rounded.Close,
                                        contentDescription = "Cancella ricerca")
                                }
                            }
                        },
                        shape = RoundedCornerShape(15.dp), singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (filtered.isEmpty()) {
                        MusicEmptyState(
                            title = if (search.isNotBlank()) "Nessun risultato" else "La libreria è vuota",
                            description = if (search.isNotBlank()) "Prova un altro titolo o artista."
                                else if (onlyMusic && audioFiles.isNotEmpty())
                                    "Nessun brano classificato come musica. Prova «Altri audio»."
                                else "Consenti l'accesso e aggiorna per trovare i tuoi brani.",
                            icon = androidx.compose.material.icons.Icons.Rounded.LibraryMusic,
                            buttonLabel = if (search.isNotBlank()) "Cancella ricerca" else "Scansiona dispositivo",
                            onAction = { if (search.isNotBlank()) search = "" else requestScan() },
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 18.dp),
                        ) {
                            itemsIndexed(filtered, key = { _, file -> file.uri }) { index, file ->
                                MusicTrackRow(
                                    file = file, cache = artworkCache,
                                    onPlay = { playQueue(filtered, index) },
                                    onAdd = { requestAddToPlaylist(file) },
                                    onQueue = { appendToQueue(file) },
                                    inMusic = AudioClassification.isMusic(
                                        file.isMusic, file.stamp(), manualKinds,
                                    ),
                                    onToggleMusic = {
                                        setMusicClassification(
                                            file, !AudioClassification.isMusic(
                                                file.isMusic, file.stamp(), manualKinds,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Playlist", style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = { createDialog = true },
                                    enabled = busy.isEmpty(),
                                    modifier = Modifier.heightIn(min = 48.dp)) {
                                    Icon(androidx.compose.material.icons.Icons.Rounded.Add,
                                        contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Nuova")
                                }
                                TextButton(
                                    onClick = { importer.launch(arrayOf("application/json", "text/plain")) },
                                    enabled = busy.isEmpty(),
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Icon(androidx.compose.material.icons.Icons.Rounded.FileDownload,
                                        contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Importa")
                                }
                                IconButton(
                                    onClick = { showDeleteChooser = true },
                                    enabled = busy.isEmpty() && playlists.isNotEmpty(),
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "Elimina una playlist",
                                        tint = MusicPalette.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = MusicPalette.surface,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, MusicPalette.border.copy(alpha = 0.65f),
                                ),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(
                                        start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                androidx.compose.material.icons.Icons.Rounded.Cloud,
                                                contentDescription = null,
                                                tint = MusicPalette.primary,
                                                modifier = Modifier.size(20.dp),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "Google Drive",
                                                style = MaterialTheme.typography.titleSmall,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                            )
                                        }
                                        Text(
                                            driveStatus,
                                            modifier = Modifier.padding(start = 28.dp),
                                            color = if (cloudSyncError != null ||
                                                localSyncStatus.missingCount > 0)
                                                MusicPalette.warning else MusicPalette.muted,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    TextButton(
                                        onClick = { requestDriveAccess() },
                                        enabled = busy.isEmpty() && localLoaded,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        contentPadding = PaddingValues(horizontal = 5.dp),
                                    ) {
                                        Icon(
                                            androidx.compose.material.icons.Icons.Rounded.Sync,
                                            contentDescription = null,
                                            modifier = Modifier.size(19.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (cloudLinked) "Sincronizza" else "Collega",
                                            style = MaterialTheme.typography.labelLarge)
                                    }
                                    if (cloudLinked) {
                                        Box {
                                            IconButton(
                                                onClick = { showCloudMenu = true },
                                                enabled = busy.isEmpty(),
                                            ) {
                                                Icon(
                                                    androidx.compose.material.icons.Icons.Rounded.MoreVert,
                                                    contentDescription = "Opzioni Google Drive",
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = showCloudMenu,
                                                onDismissRequest = { showCloudMenu = false },
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Scollega Drive") },
                                                    onClick = {
                                                        showCloudMenu = false
                                                        showCloudDisconnect = true
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // A single playlist needs no selector: the detail is already visible.
                        // With multiple playlists use one compact chooser and one detail panel.
                        if (PlaylistSelection.showSelector(playlists.size)) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    playlists.forEach { list ->
                                        FilterChip(
                                            selected = list.playlistId == selectedId,
                                            onClick = { selectedId = list.playlistId },
                                            label = { Text(list.name + " · " + list.tracks.size) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MusicPalette.elevated,
                                                selectedLabelColor = MusicPalette.primary,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        if (selected == null) {
                            item {
                                MusicEmptyState(
                                    title = "La tua prima playlist",
                                    description = "Crea una playlist o importa un file JSON.",
                                    icon = androidx.compose.material.icons.Icons.Rounded.PlaylistPlay,
                                    buttonLabel = "Crea playlist",
                                    onAction = { createDialog = true },
                                )
                            }
                        } else {
                            val available = matches.count { it.status == MatchStatus.EXACT }
                            val missingTitles = if (verificationComplete) selected.tracks.filter {
                                byEntryId[it.entryId]?.status == MatchStatus.MISSING
                            }.map {
                                it.identity.metadata.title ?: it.identity.filenameHint ?: "Brano senza titolo"
                            } else emptyList()
                            item {
                                MusicPlaylistSummary(
                                    name = selected.name, trackCount = selected.tracks.size,
                                    available = available, busy = busy.isNotEmpty(),
                                    analyzing = verifying,
                                    needsVerification = !verificationComplete && !verifying,
                                    unreadableCount = unreadableCount,
                                    missingTitles = missingTitles,
                                    onRelink = { relink() },
                                    onExport = {
                                        exportPending = selected
                                        exporter.launch(
                                            selected.name.replace(Regex("[^a-zA-Z0-9_-]"), "_") + ".json",
                                        )
                                    },
                                )
                            }
                            if (latestAnalysis.isNotEmpty()) item {
                                Text(latestAnalysis, color = Muted, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            if (selected.tracks.isEmpty()) {
                                item {
                                    MusicEmptyState(
                                        title = "Playlist vuota",
                                        description = "Dalla Libreria tocca + accanto a un brano per aggiungerlo qui.",
                                        icon = androidx.compose.material.icons.Icons.Rounded.MusicNote,
                                    )
                                }
                            } else {
                                items(selected.tracks, key = { it.entryId }) { entry ->
                                    val resolution = byEntryId[entry.entryId]
                                    val playable = resolution?.localUri?.let(mediaByUri::get)
                                    val title = entry.identity.metadata.title
                                        ?: entry.identity.filenameHint ?: "Brano"
                                    MusicPlaylistTrackRow(
                                        title = title, artist = entry.identity.metadata.artist,
                                        durationMs = playable?.durationMs
                                            ?: entry.identity.metadata.durationMs,
                                        status = resolution?.status, playable = playable,
                                        analyzing = verifying,
                                        verificationComplete = verificationComplete,
                                        cache = artworkCache,
                                        onPlay = {
                                            if (playable != null) {
                                                val queue = matches.mapNotNull {
                                                    it.localUri?.let(mediaByUri::get)
                                                }
                                                val index = queue.indexOfFirst { it.uri == playable.uri }
                                                if (index >= 0) playQueue(
                                                    queue, index, PlaybackQueueCommands.PLAYLIST)
                                            }
                                        },
                                        onQueue = {
                                            if (playable != null) appendToQueue(playable)
                                        },
                                        onRemove = {
                                            if (busy.isEmpty()) scope.launch {
                                                playlistWriteMutex.withLock {
                                                    val current = playlists.firstOrNull {
                                                        it.playlistId == selected.playlistId
                                                    } ?: return@withLock
                                                    val updated = current.copy(
                                                        updatedAt = Instant.now().toString(),
                                                        tracks = current.tracks.filterNot {
                                                            it.entryId == entry.entryId
                                                        },
                                                    )
                                                    if (persist(playlists.map {
                                                            if (it.playlistId == current.playlistId)
                                                                updated else it
                                                        })) refreshMatches(playlists, localCopies)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

}
