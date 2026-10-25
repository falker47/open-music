package app.openmusic

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.openmusic.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

private val Background = Color(0xFF0E141A)
private val Surface = Color(0xFF19242C)
private val Accent = Color(0xFF8BE0CB)
private val Muted = Color(0xFFB4C4C8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Background, surface = Surface,
                primary = Accent, secondary = Accent,
                onBackground = Color.White, onSurface = Color.White,
            )) {
                OpenMusicApp()
            }
        }
    }
}

@Composable
private fun OpenMusicApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val storage = remember { PlaylistStorage(context.applicationContext) }
    val player = remember { ExoPlayer.Builder(context).build() }
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
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.play()
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
                val next = list.copy(
                    updatedAt = Instant.now().toString(),
                    tracks = list.tracks + PlaylistEntry(UUID.randomUUID().toString(), identity),
                )
                persist(playlists.map { if (it.playlistId == listId) next else it })
                selectedId = listId
                localCopies = localCopies.map {
                    if (it.uri == file.uri) file.asCopy(hash) else it
                }
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

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                nowTitle = player.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
                nowArtist = player.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty()
                playing = player.isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(750)
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
                    label = { Text("Nome") },
                    singleLine = true,
                )
            },
            confirmButton = { TextButton(onClick = { createPlaylist() }) { Text("Crea") } },
            dismissButton = {
                TextButton(onClick = { createDialog = false }) { Text("Annulla") }
            },
        )
    }

    pendingAdd?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingAdd = null },
            title = { Text("Scegli la playlist") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("Aggiungi «" + file.title + "» a:", color = Muted,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    playlists.forEach { list ->
                        TextButton(
                            onClick = {
                                pendingAdd = null
                                addToPlaylist(file, list.playlistId)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(list.tracks.size.toString() + " brani", color = Muted,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingAdd = null }) { Text("Annulla") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Background,
        bottomBar = {
            if (nowTitle.isNotEmpty()) {
                PlayerBar(
                    title = nowTitle, artist = nowArtist, playing = playing,
                    progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onSeek = { fraction ->
                        if (duration > 0) player.seekTo((fraction * duration).toLong())
                    },
                    onBack = { if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() },
                    onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                    onNext = { if (player.hasNextMediaItem()) player.seekToNextMediaItem() },
                )
            }
        },
    ) { safeArea ->
        Column(Modifier.fillMaxSize().padding(safeArea).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(12.dp))
            Text("Open Music", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("La tua musica, senza vincoli", color = Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            TabRow(selectedTabIndex = tab, containerColor = Background) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("Libreria · " + audioFiles.size) })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("Playlist · " + playlists.size) })
            }
            Spacer(Modifier.height(12.dp))
            if (busy.isNotEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(busy, color = Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
            }

            if (tab == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Brani locali", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { requestScan() }, enabled = busy.isEmpty()) { Text("Aggiorna") }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Cerca brano o artista") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                val filtered = audioFiles.filter {
                    it.title.contains(search, ignoreCase = true) ||
                        it.artist.orEmpty().contains(search, ignoreCase = true)
                }
                if (filtered.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 44.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Nessun brano da mostrare", fontWeight = FontWeight.Medium)
                        Text("Tocca Aggiorna e consenti l'accesso alla musica.",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { requestScan() }) { Text("Scansiona dispositivo") }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.uri }) { file ->
                            AudioRow(
                                file = file,
                                onPlay = { playQueue(filtered, filtered.indexOf(file)) },
                                onAdd = { requestAddToPlaylist(file) },
                            )
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Le mie playlist", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain")) },
                        enabled = busy.isEmpty()) { Text("Importa") }
                    TextButton(onClick = { createDialog = true }, enabled = busy.isEmpty()) { Text("+ Nuova") }
                }
                if (playlists.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        playlists.forEach { list ->
                            FilterChip(
                                selected = selectedId == list.playlistId,
                                onClick = { selectedId = list.playlistId },
                                label = { Text(list.name, maxLines = 1) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (selected == null) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 44.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Le playlist vivono indipendentemente dai file.")
                        Text("Crea o importa una playlist per iniziare.",
                            color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(selected.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        OutlinedButton(
                            onClick = {
                                exportPending = selected
                                exporter.launch(selected.name.replace(Regex("[^a-zA-Z0-9_-]"), "_") + ".json")
                            },
                            enabled = busy.isEmpty(),
                        ) { Text("Esporta") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(selected.tracks.size.toString() + " brani",
                            color = Muted, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { relink() }, enabled = busy.isEmpty()) {
                            Text("Ricollega")
                        }
                    }
                    val exact = matches.count {
                        it.status == MatchStatus.EXACT || it.status == MatchStatus.METADATA
                    }
                    Text(exact.toString() + " disponibili · " +
                        (selected.tracks.size - exact) + " da verificare/mancanti",
                        color = Muted, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(selected.tracks, key = { it.entryId }) { entry ->
                            val resolution = byEntryId[entry.entryId]
                            val playable = resolution?.localUri?.let(mediaByUri::get)
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier.weight(1f).clickable(enabled = playable != null) {
                                        val queue = matches.mapNotNull { it.localUri?.let(mediaByUri::get) }
                                        val index = queue.indexOfFirst { it.uri == playable?.uri }
                                        playQueue(queue, index)
                                    }.padding(vertical = 8.dp),
                                ) {
                                    Text(entry.identity.metadata.title ?: entry.identity.filenameHint ?: "Brano",
                                        fontWeight = FontWeight.Medium, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    Text(entry.identity.metadata.artist.orEmpty() + " · " +
                                        (resolution?.status?.name?.lowercase() ?: "missing"),
                                        color = if (playable != null) Accent else Muted,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1)
                                }
                                TextButton(onClick = {
                                    val next = selected.copy(
                                        updatedAt = Instant.now().toString(),
                                        tracks = selected.tracks.filterNot { it.entryId == entry.entryId },
                                    )
                                    persist(playlists.map {
                                        if (it.playlistId == selected.playlistId) next else it
                                    })
                                }) { Text("Rimuovi", style = MaterialTheme.typography.labelSmall) }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioRow(file: AudioFile, onPlay: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).clickable(onClick = onPlay).padding(vertical = 10.dp),
        ) {
            Text(file.title, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(file.artist ?: "Artista sconosciuto", color = Muted,
                style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        TextButton(onClick = onAdd) { Text("+ Playlist", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun PlayerBar(
    title: String, artist: String, playing: Boolean, progress: Float,
    onSeek: (Float) -> Unit, onBack: () -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit,
) {
    SurfaceBox {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(artist, color = Muted, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1)
                }
                TextButton(onClick = onBack) { Text("◀◀") }
                TextButton(onClick = onPlayPause) { Text(if (playing) "Pausa" else "Play") }
                TextButton(onClick = onNext) { Text("▶▶") }
            }
            Slider(value = progress, onValueChange = onSeek,
                modifier = Modifier.fillMaxWidth().height(20.dp))
        }
    }
}

@Composable
private fun SurfaceBox(content: @Composable () -> Unit) {
    androidx.compose.material3.Surface(
        color = Surface, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        content = content,
    )
}
