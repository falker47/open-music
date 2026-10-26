package app.openmusic

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.openmusic.core.MatchStatus
import app.openmusic.core.PlaylistVerification
import app.openmusic.core.VerificationDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Visual system for the Android local-first client. No playback, playlist or cloud state
 * is owned by these components; commands are delegated to MainActivity's existing handlers.
 */
internal object MusicPalette {
    val background = Color(0xFF100E0D)
    val surface = Color(0xFF201A17)
    val elevated = Color(0xFF30241D)
    val border = Color(0xFF4A3429)
    val primary = Color(0xFFFFA552)
    val primaryDark = Color(0xFF301703)
    val text = Color(0xFFFFF6F0)
    val muted = Color(0xFFC7B8AE)
    val subtle = Color(0xFF9B8072)
    val warning = Color(0xFFFFC18A)
}

@Composable
internal fun OpenMusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = MusicPalette.background,
            onBackground = MusicPalette.text,
            surface = MusicPalette.surface,
            onSurface = MusicPalette.text,
            surfaceVariant = MusicPalette.elevated,
            onSurfaceVariant = MusicPalette.muted,
            primary = MusicPalette.primary,
            onPrimary = MusicPalette.primaryDark,
            secondary = MusicPalette.primary,
            onSecondary = MusicPalette.primaryDark,
            outline = MusicPalette.border,
            error = Color(0xFFFFB4AB),
        ),
        typography = Typography(
            headlineMedium = TextStyle(
                fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp,
            ),
            headlineSmall = TextStyle(
                fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp,
            ),
            titleLarge = TextStyle(
                fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp,
            ),
            titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp),
            bodyMedium = TextStyle(fontSize = 14.sp),
            bodySmall = TextStyle(fontSize = 12.sp),
            labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}

/**
 * Bounded artwork cache: visible rows resolve local embedded art on Dispatchers.IO.
 * Negative results are bounded too, avoiding expensive retriever work while scrolling.
 * Large-library browsing does not prefetch or scan every file's artwork.
 */
internal class LocalArtworkCache {
    private val images = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            max(1, value.width * value.height * 4)
    }
    private val missing = LinkedHashSet<String>()

    suspend fun load(context: Context, source: String, limit: Int): ImageBitmap? {
        val key = source + "@" + limit
        synchronized(this) {
            images.get(key)?.let { return it }
            if (missing.contains(key)) return null
        }
        val picture = withContext(Dispatchers.IO) { decodeEmbedded(context, source, limit) }
        synchronized(this) {
            if (picture != null) images.put(key, picture)
            else {
                missing.add(key)
                if (missing.size > 300) missing.firstOrNull()?.let(missing::remove)
            }
        }
        return picture
    }

    private fun decodeEmbedded(context: Context, source: String, limit: Int): ImageBitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(source))
            val raw = retriever.embeddedPicture ?: return null
            if (raw.size > 5_000_000) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > limit) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(raw, 0, raw.size, options)?.asImageBitmap()
        } catch (_: Exception) {
            // Artwork is optional and never blocks track playback.
            return null
        } finally {
            retriever.release()
        }
    }
}

@Composable
internal fun MusicArt(
    source: String?,
    cache: LocalArtworkCache,
    modifier: Modifier = Modifier,
    quality: Int = 256,
) {
    val context = LocalContext.current.applicationContext
    val picture by produceState<ImageBitmap?>(null, source, quality, cache) {
        value = source?.let { cache.load(context, it, quality) }
    }
    Box(
        modifier = modifier.clip(RoundedCornerShape(17.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF62402B), Color(0xFF35251E), Color(0xFF241A17))))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(17.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            Image(
                bitmap = picture!!,
                contentDescription = "Copertina del brano",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = "Copertina non disponibile",
                modifier = Modifier.fillMaxSize(0.42f), tint = MusicPalette.primary)
        }
    }
}

@Composable
internal fun MusicHeader(count: Int, isLibrary: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(MusicPalette.primary, Color(0xFFEA7930)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null,
                modifier = Modifier.size(29.dp), tint = MusicPalette.primaryDark)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text("OPEN MUSIC", color = MusicPalette.primary,
                style = MaterialTheme.typography.labelSmall, letterSpacing = 2.1.sp)
            Text(if (isLibrary) "La tua libreria" else "Le tue playlist",
                style = MaterialTheme.typography.headlineSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Surface(
            color = MusicPalette.surface, shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, MusicPalette.border),
        ) {
            Text(
                count.toString(), modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                color = MusicPalette.muted, style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
internal fun MusicBottomNavigation(tab: Int, onTab: (Int) -> Unit) {
    Surface(color = MusicPalette.background) {
        Row(
            Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NavigationDestination(
                modifier = Modifier.weight(1f), selected = tab == 0,
                title = "Libreria", icon = Icons.Rounded.LibraryMusic,
                onClick = { onTab(0) },
            )
            NavigationDestination(
                modifier = Modifier.weight(1f), selected = tab == 1,
                title = "Playlist", icon = Icons.Rounded.PlaylistPlay,
                onClick = { onTab(1) },
            )
        }
    }
}

@Composable
private fun NavigationDestination(
    modifier: Modifier, selected: Boolean, title: String, icon: ImageVector,
    onClick: () -> Unit,
) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = if (selected) MusicPalette.elevated else Color.Transparent,
            shape = RoundedCornerShape(50),
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp).size(23.dp),
                tint = if (selected) MusicPalette.primary else MusicPalette.subtle,
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(title, color = if (selected) MusicPalette.text else MusicPalette.muted,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun MusicBusyIndicator(message: String, onCancel: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
            color = MusicPalette.primary, trackColor = MusicPalette.border,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = MusicPalette.muted, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f))
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text("Annulla analisi") }
            }
        }
    }
}

@Composable
internal fun MusicSectionHeader(
    title: String, subtitle: String? = null, actionLabel: String? = null,
    actionIcon: ImageVector? = null, onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MusicPalette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                if (actionIcon != null) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                }
                Text(actionLabel)
            }
        }
    }
}

@Composable
internal fun MusicEmptyState(
    title: String, description: String, icon: ImageVector,
    buttonLabel: String? = null, onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        color = MusicPalette.surface, shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MusicPalette.border),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(23.dp)).background(MusicPalette.elevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MusicPalette.primary,
                    modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(7.dp))
            Text(description, color = MusicPalette.muted, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium)
            if (buttonLabel != null && onAction != null) {
                Spacer(Modifier.height(18.dp))
                Button(onClick = onAction) { Text(buttonLabel) }
            }
        }
    }
}

@Composable
internal fun MusicTrackRow(
    file: AudioFile, cache: LocalArtworkCache,
    onPlay: () -> Unit, onAdd: () -> Unit,
    inMusic: Boolean, onToggleMusic: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onPlay)
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MusicArt(file.uri, cache, Modifier.size(56.dp), quality = 192)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(file.title, color = MusicPalette.text,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(
                    file.artist?.takeIf { it.isNotBlank() } ?: "Artista sconosciuto",
                    color = MusicPalette.muted, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onToggleMusic, modifier = Modifier.size(48.dp)) {
            Icon(
                if (inMusic) Icons.Rounded.RemoveCircleOutline else Icons.Rounded.LibraryMusic,
                contentDescription = (if (inMusic) "Rimuovi da Musica: " else "Aggiungi a Musica: ") +
                    file.title,
                tint = if (inMusic) MusicPalette.muted else MusicPalette.primary,
                modifier = Modifier.size(23.dp),
            )
        }
        IconButton(onClick = onAdd, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Add, contentDescription = "Aggiungi " + file.title + " a una playlist",
                tint = MusicPalette.primary, modifier = Modifier.size(25.dp))
        }
    }
}

@Composable
internal fun MusicPlaylistHero(
    name: String, trackCount: Int, available: Int, busy: Boolean,
    analyzing: Boolean, needsVerification: Boolean, unreadableCount: Int,
    missingTitles: List<String>,
    onRelink: () -> Unit, onExport: () -> Unit,
) {
    var expanded by remember(name) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp), color = MusicPalette.surface,
        border = BorderStroke(1.dp, MusicPalette.border),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(58.dp).clip(RoundedCornerShape(17.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF5E341E), Color(0xFF30221B)))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlaylistPlay, contentDescription = null,
                        tint = MusicPalette.primary, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (analyzing) trackCount.toString() + " brani · " + available +
                            " SHA confermati · verifica in corso"
                        else trackCount.toString() + " brani · " + available + " disponibili",
                        color = MusicPalette.muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRelink, enabled = !busy, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 7.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Ricollega", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onExport, enabled = !busy, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 7.dp),
                ) {
                    Icon(Icons.Rounded.FileDownload, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Esporta", maxLines = 1)
                }
            }
            if (analyzing || needsVerification || unreadableCount > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        analyzing -> "Analisi in corso: verifica SHA-256 della libreria"
                        unreadableCount > 0 -> "Da verificare: " + unreadableCount + " audio non leggibili. Riprova Ricollega."
                        else -> "Da verificare · premi Ricollega per riprovare"
                    },
                    color = MusicPalette.warning, style = MaterialTheme.typography.labelMedium,
                )
            }
            if (missingTitles.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(missingTitles.size.toString() + " brani mancanti dopo la verifica",
                    color = MusicPalette.warning, style = MaterialTheme.typography.labelMedium)
                val shown = if (expanded) missingTitles else missingTitles.take(3)
                shown.forEach { title ->
                    Text("• " + title, color = MusicPalette.muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (missingTitles.size > 3) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Mostra meno" else "Mostra tutti i titoli mancanti")
                    }
                }
            }
        }
    }
}

@Composable
internal fun MusicPlaylistTrackRow(
    title: String, artist: String?, status: MatchStatus?,
    analyzing: Boolean, verificationComplete: Boolean,
    playable: AudioFile?, cache: LocalArtworkCache,
    onPlay: () -> Unit, onRemove: () -> Unit,
) {
    val accessible = playable != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .clickable(enabled = accessible, onClick = onPlay).padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MusicArt(playable?.uri, cache, Modifier.size(52.dp), quality = 192)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (accessible) MusicPalette.text else MusicPalette.muted)
                Spacer(Modifier.height(3.dp))
                Text(
                    artist?.takeIf { it.isNotBlank() } ?: "Artista sconosciuto",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MusicPalette.muted,
                )
                Text(
                    when (PlaylistVerification.display(status, analyzing, verificationComplete)) {
                        VerificationDisplay.ANALYZING -> "Analisi in corso"
                        VerificationDisplay.AVAILABLE -> "Disponibile"
                        VerificationDisplay.REVIEW -> "Da verificare"
                        VerificationDisplay.AMBIGUOUS -> "Ambiguo"
                        VerificationDisplay.MISSING -> "Mancante"
                    },
                    color = if (status == MatchStatus.EXACT)
                        MusicPalette.primary else MusicPalette.warning,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Rimuovi " + title + " dalla playlist",
                tint = MusicPalette.muted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun MusicPlaybackSeek(
    trackKey: String, positionMs: Long, durationMs: Long,
    onSeek: (Long) -> Unit, compact: Boolean = false,
) {
    var dragging by remember(trackKey) { mutableStateOf<Float?>(null) }
    val fraction = if (durationMs > 0L)
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    Slider(
        value = dragging ?: fraction,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { if (durationMs > 0L) onSeek((it * durationMs).toLong()) }
            dragging = null
        },
        enabled = durationMs > 0L,
        modifier = Modifier.fillMaxWidth().height(if (compact) 32.dp else 46.dp),
        colors = SliderDefaults.colors(
            thumbColor = MusicPalette.primary,
            activeTrackColor = MusicPalette.primary,
            inactiveTrackColor = MusicPalette.border,
            disabledThumbColor = MusicPalette.subtle,
        ),
    )
}

@Composable
internal fun MusicMiniPlayer(
    title: String, artist: String, uri: String?, cache: LocalArtworkCache,
    playing: Boolean, positionMs: Long, durationMs: Long,
    onOpen: () -> Unit, onSeek: (Long) -> Unit, onPrevious: () -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(22.dp),
        color = MusicPalette.elevated, tonalElevation = 4.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 9.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen)
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MusicArt(uri, cache, Modifier.size(48.dp), quality = 192)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(artist.ifBlank { "Artista sconosciuto" },
                            color = MusicPalette.muted, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onPrevious, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Brano precedente",
                        tint = MusicPalette.text, modifier = Modifier.size(25.dp))
                }
                IconButton(onClick = onPlayPause, modifier = Modifier.size(44.dp)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pausa" else "Riproduci",
                        tint = MusicPalette.primary, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = onNext, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Brano successivo",
                        tint = MusicPalette.text, modifier = Modifier.size(25.dp))
                }
            }
            MusicPlaybackSeek(title + "|" + uri, positionMs, durationMs, onSeek, compact = true)
        }
    }
}

@Composable
internal fun MusicNowPlaying(
    modifier: Modifier, title: String, artist: String, uri: String?,
    cache: LocalArtworkCache, playing: Boolean, positionMs: Long, durationMs: Long,
    onClose: () -> Unit, onSeek: (Long) -> Unit, onPrevious: () -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit,
) {
    BoxWithConstraints(
        modifier.background(
            Brush.verticalGradient(
                listOf(Color(0xFF382417), MusicPalette.background, MusicPalette.background),
            ),
        ),
    ) {
        val artSize: Dp = (maxWidth - 48.dp).coerceAtMost(maxHeight * 0.49f)
            .coerceAtMost(410.dp).coerceAtLeast(180.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Chiudi In riproduzione",
                        tint = MusicPalette.text, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("IN RIPRODUZIONE", style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.5.sp, color = MusicPalette.muted)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(48.dp))
            }
            Spacer(Modifier.height(20.dp))
            MusicArt(
                source = uri, cache = cache,
                modifier = Modifier.size(artSize).shadow(22.dp, RoundedCornerShape(24.dp)),
                quality = 720,
            )
            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(artist.ifBlank { "Artista sconosciuto" },
                    color = MusicPalette.muted, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(22.dp))
            MusicPlaybackSeek(title + "|" + uri, positionMs, durationMs, onSeek)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMusicTime(positionMs), color = MusicPalette.muted,
                    style = MaterialTheme.typography.labelMedium)
                Text(if (durationMs > 0L) formatMusicTime(durationMs) else "--:--",
                    color = MusicPalette.muted, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(62.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Brano precedente",
                        tint = MusicPalette.text, modifier = Modifier.size(38.dp))
                }
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(78.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MusicPalette.primary, contentColor = MusicPalette.primaryDark,
                    ),
                ) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pausa" else "Riproduci",
                        modifier = Modifier.size(43.dp))
                }
                IconButton(onClick = onNext, modifier = Modifier.size(62.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Brano successivo",
                        tint = MusicPalette.text, modifier = Modifier.size(38.dp))
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

private fun formatMusicTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000L
    return if (seconds >= 3600L) "%d:%02d:%02d".format(
        seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L,
    ) else "%d:%02d".format(seconds / 60L, seconds % 60L)
}
