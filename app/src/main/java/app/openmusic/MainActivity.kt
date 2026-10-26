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
    var selectedId by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var createDialog by remember { mutableStateOf(false) }
    var pendingAdd by remember { mutableStateOf<AudioFile?>(null) }
    var pendingHash by remember { mutableStateOf<String?>(null) }
    var pendingHashError by remember { mutableStateOf<String?>(null) }
    var pendingHashRetry by remember { mutableIntStateOf(0) }
    var proposedName by remember { mutableStateOf("") }
    var exportPending by remember { mutableStateOf<PortablePlaylist?>(null) }
    var nowTitle by remember { mutableStateOf("") }
    var nowArtist by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
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

    suspend fun reconcile(found: List<AudioFile>, reason: String = "scansione") {
        verifying = true
        verificationComplete = false
        unreadableCount = 0
        resolvedMatches = emptyMap()
        val fresh = ArrayList<LocalCopy>(found.size)
        val hints = ReconcilePriority.hints(playlists)
        // Metadata only schedules the first candidates. Every file is SHA-checked
        // before a missing result can be considered final.
        val ordered = found.sortedByDescending {
            ReconcilePriority.score(it.asCopy().identity, hints)
        }
        var unreadable = 0
        var reused = 0
        var recomputed = 0
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            for ((index, audio) in ordered.withIndex()) {
                val digest = try {
                    hashCache.digest(audio)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    unreadable++
                    null
                }
                if (digest?.reused == true) reused++
                else if (digest != null) recomputed++
                fresh.add(audio.asCopy(digest?.hash))
                // Indexed matching runs off the main thread. Publish verified
                // matches progressively; never publish provisional MISSING.
                if ((index + 1) % 24 == 0 || index == ordered.lastIndex) {
                    localCopies = fresh.toList()
                    refreshMatches(playlists, localCopies)
                    busy = "Verifica SHA-256 · " + (index + 1) + "/" + ordered.size +
                        " · cache " + reused
                }
            }
            localCopies = fresh.toList()
            refreshMatches(playlists, localCopies)
            unreadableCount = unreadable
            verificationComplete = unreadable == 0
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            latestAnalysis = reason + ": " + elapsed + " ms · " +
                recomputed + " hash ricalcolati · " + reused + " dalla cache / " + found.size
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
            // Save a partial warm cache even when the user cancels halfway.
            try { withContext(NonCancellable) { hashCache.flush() } }
            catch (_: Exception) { notifyUser("Cache SHA non salvata: la prossima analisi potrebbe rallentare") }
            verifying = false
        }
    }

    fun scan() {
        if (busy.isNotEmpty()) return
        busy = "Lettura di tutti gli audio locali"
        analysisJob = scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { MediaLibrary.scan(context) }
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
                    reconcile(found)
                } else {
                    verificationComplete = true
                    hashCache.flush()
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scan() else notifyUser("Consenti l'accesso alla musica per leggere la libreria")
    }

    fun requestScan() {
        if (MediaLibrary.hasPermission(context)) scan()
        else permissionLauncher.launch(MediaLibrary.permission())
    }

    fun playQueue(queue: List<AudioFile>, index: Int) {
        if (queue.isEmpty() || index !in queue.indices) return
        val controller = player
        if (controller == null) {
            notifyUser("Player in connessione: riprova fra un momento")
            return
        }
        val items = queue.map { file ->
            MediaItem.Builder()
                .setUri(Uri.parse(file.uri))
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(file.title)
                    .setArtist(file.artist)
                    .setAlbumTitle(file.album)
                    .build())
                .build()
        }
        controller.setMediaItems(items, index, 0L)
        controller.prepare()
        controller.play()
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
                    localCopies = localCopies.map {
                        if (it.uri == file.uri) file.asCopy(hash) else it
                    }
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
                        notifyUser("Aggiunto a " + list.name)
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
                notifyUser("Playlist esportata: contiene solo metadati")
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
        persist(playlists + list)
        selectedId = list.playlistId
        proposedName = ""
        createDialog = false
        tab = 1
    }

    LaunchedEffect(Unit) {
        try {
            playlists = withContext(Dispatchers.IO) { storage.load() }
            manualKinds = withContext(Dispatchers.IO) { kindStorage.load() }
            selectedId = playlists.firstOrNull()?.playlistId
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
                localCopies = localCopies.map {
                    if (it.uri == file.uri) file.asCopy(hash) else it
                }
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
        val future = MediaController.Builder(context, token).buildAsync()
        var disposed = false
        future.addListener({
            if (!disposed) {
                try {
                    player = future.get()
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
                currentUri = active.currentMediaItem?.localConfiguration?.uri
                playing = active.isPlaying
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

    BackHandler(enabled = showNowPlaying) { showNowPlaying = false }
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
                title = nowTitle, artist = nowArtist, uri = currentUri?.toString(),
                cache = artworkCache, playing = playing,
                positionMs = position, durationMs = duration,
                onClose = { showNowPlaying = false },
                onSeek = { millis -> player?.seekTo(millis) },
                onPrevious = {
                    player?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
                },
                onPlayPause = { player?.let { if (it.isPlaying) it.pause() else it.play() } },
                onNext = { player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } },
            )
        } else {
            Column(
                Modifier.fillMaxSize().padding(insets).padding(horizontal = 18.dp),
            ) {
                if (tab == 0) MusicHeader(visibleAudio.size, isLibrary = true)
                if (busy.isNotEmpty()) MusicBusyIndicator(
                    busy, onCancel = if (verifying) ({ analysisJob?.cancel() }) else null,
                )
                if (tab == 0) {
                    MusicSectionHeader(
                        title = "Brani locali",
                        subtitle = visibleAudio.size.toString() + " visibili su " +
                            audioFiles.size + " file audio",
                        actionLabel = "Aggiorna",
                        actionIcon = androidx.compose.material.icons.Icons.Rounded.Refresh,
                        onAction = { if (busy.isEmpty()) requestScan() },
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = onlyMusic, onClick = { onlyMusic = true },
                            label = { Text("Musica (" + musicCount + ")") },
                        )
                        FilterChip(
                            selected = !onlyMusic, onClick = { onlyMusic = false },
                            label = { Text("Altri audio (" + (audioFiles.size - musicCount) + ")") },
                        )
                    }
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
                        shape = RoundedCornerShape(14.dp), singleLine = true,
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
                                        )
                                    }
                                }
                            }
                        }
                        if (selected == null) {
                            item {
                                MusicEmptyState(
                                    title = "La tua prima playlist",
                                    description = "Crea una raccolta oppure importa un file JSON v1. I brani restano sul tuo dispositivo.",
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
                                                if (index >= 0) playQueue(queue, index)
                                            }
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
