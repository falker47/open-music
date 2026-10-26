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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

private val Muted = Color(0xFF9DADBC)

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
    val artworkCache = remember { LocalArtworkCache() }
    // The playback engine lives in PlaybackService; this UI only holds a controller.
    var player by remember { mutableStateOf<MediaController?>(null) }
    var audioFiles by remember { mutableStateOf(emptyList<AudioFile>()) }
    var localCopies by remember { mutableStateOf(emptyList<LocalCopy>()) }
    var playlists by remember { mutableStateOf(emptyList<PortablePlaylist>()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var createDialog by remember { mutableStateOf(false) }
    var pendingAdd by remember { mutableStateOf<AudioFile?>(null) }
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
    val matches = remember(selected, localCopies) {
        selected?.let { PlaylistMatcher.resolve(it, localCopies) }.orEmpty()
    }
    val byEntryId = matches.associateBy { it.entryId }
    val mediaByUri = audioFiles.associateBy { it.uri }

    fun notifyUser(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun persist(updated: List<PortablePlaylist>) {
        try {
            storage.save(updated)
            playlists = updated
        } catch (failure: Exception) {
            notifyUser("Impossibile salvare: " + (failure.localizedMessage ?: "errore"))
        }
    }

    fun scan() {
        if (busy.isNotEmpty()) return
        scope.launch {
            busy = "Lettura della musica locale"
            try {
                val found = withContext(Dispatchers.IO) { MediaLibrary.scan(context) }
                audioFiles = found
                localCopies = found.map { it.asCopy() }
                notifyUser("Trovati " + found.size + " brani sul dispositivo")
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
        scope.launch {
            busy = "Identificazione SHA-256 del brano"
            try {
                val hash = withContext(Dispatchers.IO) { MediaLibrary.sha256(context, file) }
                val list = playlists.firstOrNull { it.playlistId == listId }
                    ?: throw IllegalStateException("Playlist non trovata")
                val identity = file.asCopy(hash).identity
                // Remember the verified local hash even if this entry already exists.
                localCopies = localCopies.map {
                    if (it.uri == file.uri) file.asCopy(hash) else it
                }
                val next = PlaylistRouting.addIfAbsent(
                    list,
                    PlaylistEntry(UUID.randomUUID().toString(), identity),
                    Instant.now().toString(),
                )
                if (next == null) {
                    selectedId = listId
                    notifyUser("Brano già presente in " + list.name)
                    return@launch
                }
                persist(playlists.map { if (it.playlistId == listId) next else it })
                selectedId = listId
                notifyUser("Aggiunto a " + list.name)
            } catch (failure: Exception) {
                notifyUser("Impossibile aggiungere: " + (failure.localizedMessage ?: "errore"))
            } finally {
                busy = ""
            }
        }
    }

    fun requestAddToPlaylist(file: AudioFile) {
        if (busy.isNotEmpty()) return
        when (val destination = PlaylistRouting.decide(playlists)) {
            PlaylistDestination.CreateFirst ->
                notifyUser("Crea prima una playlist nella scheda Playlist")
            is PlaylistDestination.Direct -> addToPlaylist(file, destination.playlistId)
            PlaylistDestination.Choose -> pendingAdd = file
        }
    }

    fun relink() {
        if (busy.isNotEmpty()) return
        if (selected == null) { notifyUser("Seleziona una playlist"); return }
        if (audioFiles.isEmpty()) { notifyUser("Scansiona prima la libreria"); return }
        scope.launch {
            busy = "Analisi dei brani locali"
            try {
                val fresh = mutableListOf<LocalCopy>()
                var unreadable = 0
                for ((index, audio) in audioFiles.withIndex()) {
                    busy = "Identificazione brani " + (index + 1) + "/" + audioFiles.size
                    val hash = withContext(Dispatchers.IO) {
                        try { MediaLibrary.sha256(context, audio) }
                        catch (_: java.io.IOException) { null }
                        catch (_: SecurityException) { null }
                    }
                    if (hash == null) unreadable++
                    fresh.add(audio.asCopy(hash))
                }
                localCopies = fresh
                val linked = PlaylistMatcher.resolve(selected, fresh)
                    .count { it.status == MatchStatus.EXACT || it.status == MatchStatus.METADATA }
                notifyUser("Ricollegati " + linked + "/" + selected.tracks.size +
                    " brani" + if (unreadable > 0) " · non leggibili: " + unreadable else "")
            } catch (failure: Exception) {
                notifyUser("Ricollegamento interrotto: " + (failure.localizedMessage ?: "errore"))
            } finally {
                busy = ""
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Importazione della playlist"
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
                } else {
                    persist(playlists + imported)
                    selectedId = imported.playlistId
                    tab = 1
                    notifyUser("Importata " + imported.name + " · premi Ricollega")
                }
            } catch (failure: Exception) {
                notifyUser("Importazione non riuscita: " + (failure.localizedMessage ?: "errore"))
            } finally {
                busy = ""
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
            selectedId = playlists.firstOrNull()?.playlistId
        } catch (failure: Exception) {
            notifyUser("Archivio locale non leggibile: " + (failure.localizedMessage ?: "errore"))
        }
        if (MediaLibrary.hasPermission(context)) scan()
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
                    playlists.forEach { list ->
                        Surface(
                            onClick = {
                                pendingAdd = null
                                addToPlaylist(file, list.playlistId)
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            color = MusicPalette.elevated,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                                Text(list.name, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(list.tracks.size.toString() + " brani",
                                    color = Muted, style = MaterialTheme.typography.bodySmall)
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
    val filtered = remember(audioFiles, search) {
        audioFiles.filter {
            it.title.contains(search, ignoreCase = true) ||
                it.artist.orEmpty().contains(search, ignoreCase = true)
        }
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
                MusicHeader(if (tab == 0) audioFiles.size else playlists.size, tab == 0)
                if (busy.isNotEmpty()) MusicBusyIndicator(busy)
                if (tab == 0) {
                    MusicSectionHeader(
                        title = "Tutti i brani",
                        subtitle = audioFiles.size.toString() + " brani sul dispositivo",
                        actionLabel = "Aggiorna",
                        actionIcon = androidx.compose.material.icons.Icons.Rounded.Refresh,
                        onAction = { if (busy.isEmpty()) requestScan() },
                    )
                    OutlinedTextField(
                        value = search, onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Cerca brano o artista") },
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
                        shape = RoundedCornerShape(18.dp), singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (filtered.isEmpty()) {
                        MusicEmptyState(
                            title = if (search.isNotBlank()) "Nessun risultato" else "La libreria è vuota",
                            description = if (search.isNotBlank()) "Prova un altro titolo o artista."
                                else "Consenti l'accesso alla musica e aggiorna per trovare i tuoi brani.",
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
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item {
                            MusicSectionHeader(
                                title = "Raccolte",
                                subtitle = "Playlist locali e portabili",
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                Button(
                                    onClick = { createDialog = true }, enabled = busy.isEmpty(),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                ) {
                                    Icon(androidx.compose.material.icons.Icons.Rounded.Add,
                                        contentDescription = null, modifier = Modifier.size(19.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("Nuova playlist", maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick = { importer.launch(arrayOf("application/json", "text/plain")) },
                                    enabled = busy.isEmpty(),
                                    contentPadding = PaddingValues(horizontal = 11.dp),
                                ) {
                                    Icon(androidx.compose.material.icons.Icons.Rounded.FileUpload,
                                        contentDescription = null, modifier = Modifier.size(19.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("Importa", maxLines = 1)
                                }
                            }
                        }
                        if (playlists.isNotEmpty()) {
                            item {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                                    contentPadding = PaddingValues(vertical = 3.dp),
                                ) {
                                    items(playlists, key = { it.playlistId }) { list ->
                                        MusicPlaylistCard(
                                            name = list.name, count = list.tracks.size,
                                            selected = list.playlistId == selectedId,
                                            onClick = { selectedId = list.playlistId },
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
                            val available = matches.count {
                                it.status == MatchStatus.EXACT || it.status == MatchStatus.METADATA
                            }
                            item {
                                MusicPlaylistHero(
                                    name = selected.name, trackCount = selected.tracks.size,
                                    available = available, busy = busy.isNotEmpty(),
                                    onRelink = { relink() },
                                    onExport = {
                                        exportPending = selected
                                        exporter.launch(
                                            selected.name.replace(Regex("[^a-zA-Z0-9_-]"), "_") + ".json",
                                        )
                                    },
                                )
                            }
                            item {
                                MusicSectionHeader(
                                    title = "Brani nella playlist",
                                    subtitle = "Tocca un brano disponibile per ascoltarlo",
                                )
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
                                        status = resolution?.status, playable = playable,
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
                                            val updated = selected.copy(
                                                updatedAt = Instant.now().toString(),
                                                tracks = selected.tracks.filterNot { it.entryId == entry.entryId },
                                            )
                                            persist(playlists.map {
                                                if (it.playlistId == selected.playlistId) updated else it
                                            })
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
