package app.openmusic

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.openmusic.core.DriveUiStatus
import app.openmusic.core.DriveUiTone
import app.openmusic.core.LyricsUiState
import app.openmusic.core.MatchStatus
import app.openmusic.core.AudioSearch
import app.openmusic.core.PortablePlaylist
import app.openmusic.core.PlaylistSelection
import app.openmusic.core.PlaylistEditorRowState
import app.openmusic.core.PlaylistContentEditor
import app.openmusic.core.PlaybackUiLogic
import app.openmusic.core.PlaylistUiPresentation
import app.openmusic.core.PlaylistMenuCommand
import app.openmusic.core.PlaybackQueuePlan
import app.openmusic.core.PlaylistVerification
import app.openmusic.core.VerificationDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.abs

/**
 * Visual system for the Android local-first client. No playback, playlist or cloud state
 * is owned by these components; commands are delegated to MainActivity's existing handlers.
 */
internal object MusicPalette {
    // Neutral charcoal lets album artwork lead; amber keeps the established accent.
    val background = Color(0xFF121212)
    val surface = Color(0xFF1E1D1C)
    val elevated = Color(0xFF2A2623)
    val border = Color(0xFF403832)
    val primary = Color(0xFFED8840)
    val primaryDark = Color(0xFF2D190C)
    val text = Color(0xFFF8F5F1)
    val muted = Color(0xFFC1B8B0)
    val subtle = Color(0xFF9C8E84)
    val warning = Color(0xFFFFC68C)
    val editorAlreadyPresent = Color(0xFF493020)
    val editorRemoved = Color(0xFF41272A)
    val editorAdded = Color(0xFF263C30)
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
        modifier = modifier.clip(RoundedCornerShape(13.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF45362C), Color(0xFF29231F))))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(13.dp)),
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
internal fun MusicBrandIdentity(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.open_music_wordmark_vector),
        contentDescription = "Open Music",
        modifier = modifier.height(38.dp),
        contentScale = ContentScale.Fit,
        alignment = Alignment.CenterStart,
    )
}

@Composable
internal fun MusicHeader(
    enabled: Boolean, onScan: () -> Unit, onQueue: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicBrandIdentity(Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = onQueue, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.QueueMusic, contentDescription = "Apri la coda")
        }
        IconButton(onClick = onScan, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Refresh, contentDescription = "Scansiona dispositivo",
                tint = MusicPalette.primary, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * The card visual top stays aligned with the original wordmark frame; only
 * its bottom moves up to the ordinary-letter baseline (excluding the y).
 * Button hitboxes remain 48dp and are not clipped.
 */
@Composable
internal fun MusicPlaylistHeader(
    enabled: Boolean, cloudLinked: Boolean,
    driveStatus: DriveUiStatus, showCloudMenu: Boolean,
    onDrive: () -> Unit, onShowCloudMenu: () -> Unit,
    onDismissCloudMenu: () -> Unit, onDisconnect: () -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp),
    ) {
        val rightCardWidth =
            PlaylistUiPresentation.playlistHeaderPanelWidthDp(maxWidth.value).dp
        Row(
            Modifier.fillMaxWidth()
                .height(PlaylistUiPresentation.playlistHeaderTouchHeightDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.open_music_playlist_wordmark),
                contentDescription = "Playlist",
                modifier = Modifier.weight(1f)
                    .height(PlaylistUiPresentation.playlistHeaderWordmarkHeightDp.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
            )
            Spacer(Modifier.width(8.dp))
            MusicPlaylistDriveCard(
                modifier = Modifier.width(rightCardWidth),
                enabled = enabled, cloudLinked = cloudLinked,
                driveStatus = driveStatus,
                showCloudMenu = showCloudMenu, onDrive = onDrive,
                onShowCloudMenu = onShowCloudMenu,
                onDismissCloudMenu = onDismissCloudMenu,
                onDisconnect = onDisconnect,
            )
        }
    }
}

/** Only cloud status and cloud controls belong in the Drive component. */
@Composable
private fun MusicPlaylistDriveCard(
    modifier: Modifier, enabled: Boolean, cloudLinked: Boolean,
    driveStatus: DriveUiStatus, showCloudMenu: Boolean,
    onDrive: () -> Unit, onShowCloudMenu: () -> Unit,
    onDismissCloudMenu: () -> Unit, onDisconnect: () -> Unit,
) {
    var showStatusDetails by remember { mutableStateOf(false) }
    val enlargedFont = LocalDensity.current.fontScale > 1.35f
    BoxWithConstraints(
        modifier.height(PlaylistUiPresentation.playlistHeaderTouchHeightDp.dp),
    ) {
        val shortDriveLabel = maxWidth < 200.dp
        // This Surface is visual only. Interactive buttons overlay it with
        // the platform's full 48dp touch target, including top/bottom overflow.
        Surface(
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = PlaylistUiPresentation.playlistHeaderVisualTopInsetDp.dp)
                .fillMaxWidth()
                .height(PlaylistUiPresentation.playlistHeaderVisualHeightDp.dp),
            shape = RoundedCornerShape(12.dp),
            color = MusicPalette.surface,
            border = BorderStroke(1.dp, MusicPalette.border.copy(alpha = 0.7f)),
        ) {
            Spacer(Modifier.fillMaxSize())
        }
        Row(
            Modifier.fillMaxSize().padding(start = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showStatusDetails = true }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Google Drive: ${driveStatus.label}. Mostra stato completo"
                    },
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (!enlargedFont) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Cloud, contentDescription = null,
                            tint = MusicPalette.muted, modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            if (shortDriveLabel) "Drive" else "Google Drive",
                            color = MusicPalette.text,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    driveStatus.label,
                    color = when (driveStatus.tone) {
                        DriveUiTone.SUCCESS -> Color(0xFF80D5A1)
                        DriveUiTone.ATTENTION -> Color(0xFFFFBE72)
                        DriveUiTone.ERROR -> Color(0xFFFF8989)
                        DriveUiTone.NEUTRAL -> MusicPalette.muted
                    },
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MusicDriveSyncButton(enabled, cloudLinked, onDrive)
            if (cloudLinked) {
                MusicDriveMenuButton(
                    enabled, showCloudMenu, onShowCloudMenu,
                    onDismissCloudMenu, onDisconnect,
                )
            }
        }
    }
    if (showStatusDetails) {
        AlertDialog(
            onDismissRequest = { showStatusDetails = false },
            title = { Text("Stato Google Drive") },
            text = { Text(driveStatus.label) },
            confirmButton = {
                TextButton(onClick = { showStatusDetails = false }) {
                    Text("Chiudi")
                }
            },
            containerColor = MusicPalette.surface,
        )
    }
}

@Composable
private fun MusicDriveSyncButton(
    enabled: Boolean, linked: Boolean, onDrive: () -> Unit,
) {
    IconButton(onClick = onDrive, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(
            Icons.Rounded.Sync,
            contentDescription = if (linked) "Sincronizza Google Drive"
                else "Collega Google Drive",
            tint = MusicPalette.primary,
            modifier = Modifier.size(21.dp)
                .offset(x = PlaylistUiPresentation.playlistHeaderSyncGlyphOffsetDp.dp),
        )
    }
}

@Composable
private fun MusicDriveMenuButton(
    enabled: Boolean, showCloudMenu: Boolean, onShowCloudMenu: () -> Unit,
    onDismissCloudMenu: () -> Unit, onDisconnect: () -> Unit,
) {
    Box {
        IconButton(
            onClick = onShowCloudMenu, enabled = enabled,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Opzioni Google Drive")
        }
        DropdownMenu(expanded = showCloudMenu, onDismissRequest = onDismissCloudMenu) {
            DropdownMenuItem(text = { Text("Scollega Drive") }, onClick = onDisconnect)
        }
    }
}

@Composable
internal fun MusicBottomNavigation(tab: Int, onTab: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MusicPalette.background)) {
        HorizontalDivider(color = MusicPalette.border.copy(alpha = 0.55f), thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().height(62.dp).padding(horizontal = 28.dp),
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
        modifier.fillMaxHeight().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = if (selected) MusicPalette.elevated else Color.Transparent,
            shape = RoundedCornerShape(50),
        ) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp).size(22.dp),
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
internal fun MusicEmptyState(
    title: String, description: String, icon: ImageVector,
    buttonLabel: String? = null, onAction: (() -> Unit)? = null,
    secondaryButtonLabel: String? = null, onSecondaryAction: (() -> Unit)? = null,
    onCardClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            .then(if (onCardClick != null) Modifier.clickable(onClick = onCardClick) else Modifier),
        color = MusicPalette.surface, shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MusicPalette.border),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(58.dp).clip(RoundedCornerShape(17.dp)).background(MusicPalette.elevated),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MusicPalette.primary,
                    modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(description, color = MusicPalette.muted, textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium)
            if (buttonLabel != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                if (secondaryButtonLabel != null && onSecondaryAction != null) {
                    // Two full-width actions fit even narrow devices and large fonts.
                    Column(Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAction,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(buttonLabel)
                        }
                        OutlinedButton(onClick = onSecondaryAction,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(secondaryButtonLabel)
                        }
                    }
                } else {
                    Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(buttonLabel)
                    }
                }
            }
        }
    }
}

/** Exactly two equal-width, labelled destinations for local audio classification. */
@Composable
internal fun MusicLibraryCategoryTabs(
    onlyMusic: Boolean,
    musicCount: Int,
    otherCount: Int,
    onSelect: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MusicPalette.surface).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(true, false).forEach { isMusic ->
            val selected = onlyMusic == isMusic
            Surface(
                onClick = { onSelect(isMusic) },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(11.dp),
                color = if (selected) MusicPalette.elevated else Color.Transparent,
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isMusic) Icons.Rounded.MusicNote else Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = if (selected) MusicPalette.primary else MusicPalette.muted,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isMusic) "Musica ($musicCount)" else "Altri audio ($otherCount)",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MusicPalette.text else MusicPalette.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}


/** A single active playlist header, with every contextual action in fixed order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicPlaylistNavigation(
    playlists: List<PortablePlaylist>, selectedId: String?, busy: Boolean,
    availableCount: Int, analyzing: Boolean, needsVerification: Boolean,
    onSelect: (String) -> Unit, onAddTracks: () -> Unit,
    onCreate: () -> Unit, onImport: () -> Unit,
    onRelink: () -> Unit, onExport: () -> Unit,
    onRename: () -> Unit, onDelete: () -> Unit,
) {
    if (PlaylistUiPresentation.selectorTabCount(playlists.size) == 0) return
    val currentId = PlaylistSelection.resolveId(playlists, selectedId)
    val current = playlists.firstOrNull { it.playlistId == currentId } ?: return
    val canChoose = PlaylistUiPresentation.canChooseOtherPlaylist(playlists.size)
    val availableText = PlaylistUiPresentation.availability(
        available = availableCount, total = current.tracks.size,
        analyzing = analyzing, needsVerification = needsVerification,
    )
    var showChooser by remember { mutableStateOf(false) }
    var showActions by remember(currentId) { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MusicPalette.surface,
        border = BorderStroke(1.dp, MusicPalette.border.copy(alpha = 0.7f)),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    current.name,
                    color = MusicPalette.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    availableText,
                    color = MusicPalette.subtle,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onAddTracks, enabled = !busy,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Rounded.Add, contentDescription = "Modifica brani della playlist",
                    tint = MusicPalette.primary, modifier = Modifier.size(24.dp),
                )
            }
            IconButton(
                onClick = { showChooser = true },
                enabled = canChoose,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Rounded.FolderOpen,
                    contentDescription = if (canChoose) "Scegli un'altra playlist"
                        else "Nessun'altra playlist disponibile",
                    tint = if (canChoose) MusicPalette.muted else MusicPalette.subtle,
                    modifier = Modifier.size(23.dp),
                )
            }
            Box {
                IconButton(
                    onClick = { showActions = true }, enabled = !busy,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "Opzioni della playlist",
                    )
                }
                DropdownMenu(
                    expanded = showActions,
                    onDismissRequest = { showActions = false },
                ) {
                    PlaylistUiPresentation.playlistMenuOrder().forEach { command ->
                        val actionName = when (command) {
                            PlaylistMenuCommand.NEW_PLAYLIST -> "Nuova playlist"
                            PlaylistMenuCommand.IMPORT -> "Importa"
                            PlaylistMenuCommand.EXPORT -> "Esporta"
                            PlaylistMenuCommand.RENAME -> "Rinomina"
                            PlaylistMenuCommand.DELETE -> "Elimina"
                            PlaylistMenuCommand.RELINK -> "Trova file"
                        }
                        val actionIcon = when (command) {
                            PlaylistMenuCommand.NEW_PLAYLIST -> Icons.Rounded.Add
                            PlaylistMenuCommand.IMPORT -> Icons.Rounded.FileDownload
                            PlaylistMenuCommand.EXPORT -> Icons.Rounded.FileUpload
                            PlaylistMenuCommand.RENAME -> Icons.Rounded.Edit
                            PlaylistMenuCommand.DELETE -> Icons.Rounded.DeleteOutline
                            PlaylistMenuCommand.RELINK -> Icons.Rounded.Link
                        }
                        DropdownMenuItem(
                            text = { Text(actionName) },
                            onClick = {
                                showActions = false
                                when (command) {
                                    PlaylistMenuCommand.NEW_PLAYLIST -> onCreate()
                                    PlaylistMenuCommand.IMPORT -> onImport()
                                    PlaylistMenuCommand.EXPORT -> onExport()
                                    PlaylistMenuCommand.RENAME -> onRename()
                                    PlaylistMenuCommand.DELETE -> onDelete()
                                    PlaylistMenuCommand.RELINK -> onRelink()
                                }
                            },
                            leadingIcon = {
                                Icon(actionIcon, contentDescription = null)
                            },
                        )
                    }
                }
            }
        }
    }
    if (showChooser) {
        ModalBottomSheet(
            onDismissRequest = { showChooser = false; query = "" },
            containerColor = MusicPalette.surface,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp),
            ) {
                Text("Scegli la playlist", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                if (playlists.size >= 10) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true, placeholder = { Text("Cerca playlist") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                val matches = playlists.filter { it.name.contains(query, ignoreCase = true) }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(matches, key = { it.playlistId }) { playlist ->
                        Surface(
                            onClick = {
                                onSelect(playlist.playlistId)
                                showChooser = false
                                query = ""
                            },
                            color = if (playlist.playlistId == currentId)
                                MusicPalette.elevated else Color.Transparent,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    playlist.name, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (playlist.playlistId == currentId) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = "Selezionata",
                                        tint = MusicPalette.primary,
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

/** Minimal, searchable, multi-select view. Selection is managed by the caller across queries. */
@Composable
internal fun MusicPlaylistAddPicker(
    playlistName: String, candidates: List<AudioFile>, selectedUris: Set<String>,
    baselineUris: Set<String>, additions: Int, removals: Int,
    query: String, saving: Boolean, onQuery: (String) -> Unit,
    onToggle: (String) -> Unit, onCancel: () -> Unit, onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val results = remember(candidates, query) {
        candidates.filter { AudioSearch.matches(it.title, it.artist, query) }
    }
    Column(
        modifier.fillMaxSize().background(MusicPalette.background)
            .padding(horizontal = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel, enabled = !saving) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Annulla aggiunta brani")
            }
            Text("Modifica " + playlistName,
                modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Cerca titolo o artista") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancella ricerca")
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (additions + removals == 0) "Seleziona o deseleziona i brani"
            else "+" + additions + " aggiunte · -" + removals + " rimozioni",
            color = MusicPalette.muted, style = MaterialTheme.typography.labelMedium)
        Text("Le voci mancanti o non verificate restano nella playlist.",
            color = MusicPalette.subtle, style = MaterialTheme.typography.bodySmall)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (results.isEmpty()) item {
                Text(if (query.isNotBlank()) "Nessun risultato" else "Nessun brano musicale indicizzato",
                    modifier = Modifier.padding(16.dp), color = MusicPalette.muted)
            }
            items(results, key = { it.uri }) { file ->
                val checked = file.uri in selectedUris
                val rowState = PlaylistContentEditor.rowState(
                    initiallyPresent = file.uri in baselineUris,
                    selected = checked,
                )
                val rowColor = when (rowState) {
                    PlaylistEditorRowState.ALREADY_IN_PLAYLIST -> MusicPalette.editorAlreadyPresent
                    PlaylistEditorRowState.REMOVED -> MusicPalette.editorRemoved
                    PlaylistEditorRowState.ADDED -> MusicPalette.editorAdded
                    PlaylistEditorRowState.UNCHANGED -> MusicPalette.surface
                }
                val rowLabel = when (rowState) {
                    PlaylistEditorRowState.ALREADY_IN_PLAYLIST -> "Già in playlist"
                    PlaylistEditorRowState.REMOVED -> "Rimossa"
                    PlaylistEditorRowState.ADDED -> "Aggiunta"
                    PlaylistEditorRowState.UNCHANGED -> null
                }
                Surface(
                    onClick = { if (!saving) onToggle(file.uri) },
                    color = rowColor,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 58.dp)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(file.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(file.artist?.takeIf { it.isNotBlank() } ?: "Artista sconosciuto",
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MusicPalette.muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (rowLabel != null) {
                            Text(rowLabel, style = MaterialTheme.typography.labelSmall,
                                color = when (rowState) {
                                    PlaylistEditorRowState.REMOVED -> Color(0xFFFFC1BF)
                                    PlaylistEditorRowState.ADDED -> Color(0xFFB6E1C0)
                                    else -> MusicPalette.primary
                                }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(4.dp))
                        }
                        Icon(if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = if (checked) "Selezionato" else "Non selezionato",
                            tint = if (checked) MusicPalette.primary else MusicPalette.subtle)
                    }
                }
            }
        }
        Button(
            onClick = onConfirm, enabled = (additions + removals) > 0 && !saving,
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).heightIn(min = 48.dp),
        ) { Text(if (saving) "Salvataggio" else "Salva modifiche") }
    }
}

@Composable
internal fun MusicTrackRow(
    file: AudioFile, cache: LocalArtworkCache,
    onPlay: () -> Unit, onAdd: () -> Unit, onQueue: () -> Unit,
    inMusic: Boolean, onToggleMusic: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 70.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onPlay)
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MusicArt(file.uri, cache, Modifier.size(50.dp), quality = 192)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(file.title, color = MusicPalette.text,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        file.artist?.takeIf { it.isNotBlank() } ?: "Artista sconosciuto",
                        modifier = Modifier.weight(1f), color = MusicPalette.muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    file.durationMs?.takeIf { it > 0L }?.let { duration ->
                        Spacer(Modifier.width(8.dp))
                        Text(formatMusicTime(duration), color = MusicPalette.muted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        // Keep the metadata width and all three 40dp touch regions unchanged.
        // Only the glyphs shift right: the final playlist action remains fixed.
        Spacer(Modifier.width(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onToggleMusic,
                modifier = Modifier.size(40.dp).semantics {
                    contentDescription = if (inMusic)
                        "Sposta " + file.title + " in Altri audio"
                    else "Sposta " + file.title + " in Musica"
                },
            ) {
                // The arrow almost touches the destination icon.
                Row(
                    modifier = Modifier.offset(x = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy((-6).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (inMusic) {
                        Icon(Icons.Rounded.ArrowForward, contentDescription = null,
                            tint = MusicPalette.muted, modifier = Modifier.size(16.dp))
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null,
                            tint = MusicPalette.muted, modifier = Modifier.size(19.dp))
                    } else {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null,
                            tint = MusicPalette.primary, modifier = Modifier.size(19.dp))
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null,
                            tint = MusicPalette.primary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            IconButton(onClick = onQueue, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.QueueMusic,
                    contentDescription = "Aggiungi " + file.title + " alla coda",
                    tint = MusicPalette.muted, modifier = Modifier.size(21.dp).offset(x = 4.dp))
            }
            IconButton(onClick = onAdd, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Add,
                    contentDescription = "Aggiungi " + file.title + " a una playlist",
                    tint = MusicPalette.primary,
                    modifier = Modifier.size(25.dp))
            }
        }
    }
}

@Composable
internal fun MusicPlaylistSummary(
    trackCount: Int, analyzing: Boolean, unreadableCount: Int,
    missingTitles: List<String>,
) {
    var showMissing by remember(trackCount) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp), color = MusicPalette.surface,
        border = BorderStroke(1.dp, MusicPalette.border.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
            if (unreadableCount > 0 && !analyzing) {
                Text("$unreadableCount audio non leggibili · usa Trova file",
                    style = MaterialTheme.typography.labelSmall, color = MusicPalette.warning)
            }
            if (missingTitles.isNotEmpty()) {
                TextButton(onClick = { showMissing = !showMissing },
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(missingTitles.size.toString() + " mancanti · " +
                        (if (showMissing) "Nascondi" else "Mostra dettagli"))
                }
                if (showMissing) {
                    missingTitles.take(12).forEach { missing ->
                        Text("• " + missing, color = MusicPalette.muted,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    if (missingTitles.size > 12) {
                        Text("E altri " + (missingTitles.size - 12) + " non trovati",
                            style = MaterialTheme.typography.bodySmall,
                            color = MusicPalette.muted)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MusicPlaylistTrackRow(
    title: String, artist: String?, durationMs: Long?, status: MatchStatus?,
    analyzing: Boolean, verificationComplete: Boolean,
    playable: AudioFile?, cache: LocalArtworkCache,
    onPlay: () -> Unit, onRemove: () -> Unit, onQueue: () -> Unit,
) {
    val accessible = playable != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 70.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                .clickable(enabled = accessible, onClick = onPlay).padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MusicArt(playable?.uri, cache, Modifier.size(50.dp), quality = 192)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (accessible) MusicPalette.text else MusicPalette.muted)
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        artist?.takeIf { it.isNotBlank() } ?: "Artista sconosciuto",
                        modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MusicPalette.muted,
                    )
                    durationMs?.takeIf { it > 0L }?.let { duration ->
                        Spacer(Modifier.width(8.dp))
                        Text(formatMusicTime(duration), color = MusicPalette.muted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                // Keep exceptional match states visible without repeating "Disponibile".
                when (PlaylistVerification.display(status, analyzing, verificationComplete)) {
                    VerificationDisplay.AVAILABLE -> Unit
                    VerificationDisplay.ANALYZING -> Text("Analisi in corso",
                        color = MusicPalette.muted, style = MaterialTheme.typography.labelSmall)
                    VerificationDisplay.REVIEW -> Text("Da verificare",
                        color = MusicPalette.warning, style = MaterialTheme.typography.labelSmall)
                    VerificationDisplay.AMBIGUOUS -> Text("Ambiguo",
                        color = MusicPalette.warning, style = MaterialTheme.typography.labelSmall)
                    VerificationDisplay.MISSING -> Text("Mancante",
                        color = MusicPalette.warning, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (accessible) {
            IconButton(onClick = onQueue, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Rounded.QueueMusic,
                    contentDescription = "Aggiungi " + title + " alla coda",
                    tint = MusicPalette.muted, modifier = Modifier.size(21.dp))
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
    alignTrackToEdges: Boolean = false,
) {
    var dragging by remember(trackKey) { mutableStateOf<Float?>(null) }
    val fraction = if (durationMs > 0L)
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    // Mini-player tooltip overlays artwork/text only while dragging; no permanent lane.
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(if (compact) 32.dp else 82.dp)
            .zIndex(if (compact) 1f else 0f),
    ) {
        val miniGeometry = if (compact) PlaylistUiPresentation.miniSeekGeometry(maxWidth.value)
            else null
        val preview = dragging
        if (preview != null && durationMs > 0L) {
            val bubbleWidth = 64.dp
            val thumbCenter = when {
                miniGeometry != null -> (
                    miniGeometry.visibleStartDp +
                        (miniGeometry.visibleEndDp - miniGeometry.visibleStartDp) * preview
                    ).dp
                alignTrackToEdges -> maxWidth * preview
                else -> 16.dp + (maxWidth - 32.dp).coerceAtLeast(0.dp) * preview
            }
            val bubbleX = (thumbCenter - bubbleWidth / 2)
                .coerceIn(0.dp, (maxWidth - bubbleWidth).coerceAtLeast(0.dp))
            Surface(
                modifier = Modifier.align(Alignment.TopStart).zIndex(2f)
                    .offset(x = bubbleX, y = if (compact) (-37).dp else 0.dp)
                    .width(bubbleWidth),
                shape = RoundedCornerShape(10.dp),
                color = MusicPalette.elevated,
                border = BorderStroke(1.dp, MusicPalette.primary),
            ) {
                Text(formatMusicTime(PlaybackUiLogic.seekTargetMs(preview, durationMs)),
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MusicPalette.text,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center)
            }
        }
        // The Slider's 4dp thumb leaves 2dp between its bounds and visible track.
        // Compact seeks compensate those offsets to meet artwork and Next edges.
        // No requiredWidth/offset overflow. The mini-player owns a real inset
        // from the Surface edges, matching its artwork and Next button.
        val sliderModifier = if (miniGeometry != null) {
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = miniGeometry.sliderStartDp.dp)
                .height(32.dp)
        } else {
            Modifier.align(Alignment.BottomCenter)
                .requiredWidth(if (alignTrackToEdges) maxWidth + 4.dp else maxWidth)
                .height(50.dp)
        }
        Slider(
            value = dragging ?: fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek(PlaybackUiLogic.seekTargetMs(it, durationMs)) }
                dragging = null
            },
            enabled = durationMs > 0L,
            modifier = sliderModifier,
            colors = SliderDefaults.colors(
                thumbColor = MusicPalette.primary,
                activeTrackColor = MusicPalette.primary,
                inactiveTrackColor = MusicPalette.border,
                disabledThumbColor = MusicPalette.subtle,
            ),
        )
    }
}

@Composable
internal fun MusicMiniPlayer(
    title: String, artist: String, uri: String?, cache: LocalArtworkCache,
    playing: Boolean, positionMs: Long, durationMs: Long,
    onOpen: () -> Unit, onSeek: (Long) -> Unit, onPrevious: () -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(17.dp),
        color = MusicPalette.elevated, tonalElevation = 4.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 68.dp)
                    .padding(horizontal = PlaylistUiPresentation.miniPlayerInsetDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen)
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MusicArt(uri, cache, Modifier.size(46.dp), quality = 192)
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
            // Persistent position / duration labels, sharing seek/artwork bounds.
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = PlaylistUiPresentation.miniPlayerInsetDp.dp)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatMusicTime(positionMs), color = MusicPalette.muted,
                    style = MaterialTheme.typography.labelSmall)
                Text(if (durationMs > 0L) formatMusicTime(durationMs) else "--:--",
                    color = MusicPalette.muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun MusicNowPlaying(
    modifier: Modifier,
    title: String, artist: String, album: String, uri: String?,
    cache: LocalArtworkCache, playing: Boolean, positionMs: Long, durationMs: Long,
    shuffleEnabled: Boolean, repeatOneEnabled: Boolean, queueSize: Int,
    onClose: () -> Unit, onSeek: (Long) -> Unit, onPrevious: () -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit,
    onToggleShuffle: () -> Unit, onToggleRepeatOne: () -> Unit, onOpenQueue: () -> Unit,
    lyrics: LyricsUiState, onImportLyrics: () -> Unit,
) {
    BoxWithConstraints(modifier.background(MusicPalette.background)) {
        val controlWidth: Dp = (maxWidth - 36.dp).coerceAtMost(340.dp)
        // Give the square artwork the full available horizontal presence.
        // Short screens scroll the whole player instead of shrinking the artwork.
        val artSize: Dp = (maxWidth - 36.dp).coerceAtMost(356.dp)
        val skipButtonSize = 62.dp
        val playButtonSize = 74.dp
        val topButtonSize = 48.dp
        val topGap = PlaylistUiPresentation.topControlGapDp(controlWidth.value).dp
        val topRowWidth = topButtonSize * 3 + topGap * 2
        // Keep a 30dp gap beside Play, without clipping narrow screens.
        val skipCenter = (controlWidth / 2 - (skipButtonSize + playButtonSize) / 2 - 30.dp)
            .coerceAtLeast(skipButtonSize / 2)
        // Bottom-aligning unequal buttons would put their icon centers on different lines.
        val skipVerticalOffset = (skipButtonSize - playButtonSize) / 2
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 6.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(48.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Chiudi In riproduzione",
                        tint = MusicPalette.text, modifier = Modifier.size(28.dp))
                }
                Text("IN RIPRODUZIONE", modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp,
                    color = MusicPalette.muted)
            }
            // Keep the artwork, metadata, timeline and controls as one
            // centered group. If height is constrained, the whole group scrolls.
            Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MusicArt(
                        source = uri, cache = cache,
                        modifier = Modifier.width(artSize).aspectRatio(1f)
                            .shadow(12.dp, RoundedCornerShape(18.dp)),
                        quality = 720,
                    )
                    Spacer(Modifier.height(16.dp))
                    // Text, seek track and timestamps share the artwork edges.
                    Column(Modifier.width(artSize)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(artist.ifBlank { "Artista sconosciuto" },
                            color = MusicPalette.text, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (album.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(album, color = MusicPalette.muted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(5.dp))
                        MusicPlaybackSeek(
                            title + "|" + uri, positionMs, durationMs, onSeek,
                            alignTrackToEdges = true,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(formatMusicTime(positionMs), color = MusicPalette.muted,
                                style = MaterialTheme.typography.labelMedium)
                            Text(if (durationMs > 0L) formatMusicTime(durationMs) else "--:--",
                                color = MusicPalette.muted,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    // Top tier: Repeat / Queue / Shuffle. Lower tier: previous /
                    // Play / next. The outer controls rise to frame a shallow arc.
                    Box(
                        Modifier.width(controlWidth).height(142.dp),
                    ) {
                        // Centered equal 48dp targets; gap = 1.3x width if room allows.
                        Row(
                            Modifier.align(Alignment.TopCenter).width(topRowWidth)
                                .height(topButtonSize),
                            horizontalArrangement = Arrangement.spacedBy(topGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onToggleRepeatOne,
                                modifier = Modifier.size(topButtonSize)) {
                                Icon(
                                    if (repeatOneEnabled) Icons.Rounded.RepeatOne
                                    else Icons.Rounded.Repeat,
                                    contentDescription = if (repeatOneEnabled)
                                        "Disattiva ripetizione brano" else "Ripeti questa canzone",
                                    tint = if (repeatOneEnabled) MusicPalette.primary
                                        else MusicPalette.muted,
                                    modifier = Modifier.size(27.dp))
                            }
                            IconButton(onClick = onOpenQueue, enabled = queueSize > 0,
                                modifier = Modifier.size(topButtonSize)) {
                                Icon(Icons.Rounded.QueueMusic,
                                    contentDescription = "Apri la coda di riproduzione",
                                    tint = MusicPalette.primary, modifier = Modifier.size(30.dp))
                            }
                            IconButton(onClick = onToggleShuffle,
                                modifier = Modifier.size(topButtonSize)) {
                                Icon(Icons.Rounded.Shuffle,
                                    contentDescription = if (shuffleEnabled)
                                        "Disattiva riproduzione casuale"
                                        else "Attiva riproduzione casuale",
                                    tint = if (shuffleEnabled) MusicPalette.primary
                                        else MusicPalette.muted,
                                    modifier = Modifier.size(27.dp))
                            }
                        }
                        IconButton(
                            onClick = onPrevious,
                            modifier = Modifier.align(Alignment.BottomStart)
                                .offset(x = skipCenter - skipButtonSize / 2, y = skipVerticalOffset)
                                .size(skipButtonSize),
                        ) {
                            Icon(Icons.Rounded.SkipPrevious,
                                contentDescription = "Brano precedente",
                                tint = MusicPalette.text, modifier = Modifier.size(52.dp))
                        }
                        FilledIconButton(
                            onClick = onPlayPause,
                            modifier = Modifier.align(Alignment.BottomCenter).size(playButtonSize),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MusicPalette.primary,
                                contentColor = MusicPalette.primaryDark),
                        ) {
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playing) "Pausa" else "Riproduci",
                                modifier = Modifier.size(41.dp))
                        }
                        IconButton(
                            onClick = onNext,
                            modifier = Modifier.align(Alignment.BottomStart)
                                .offset(x = controlWidth - skipCenter - skipButtonSize / 2,
                                    y = skipVerticalOffset)
                                .size(skipButtonSize),
                        ) {
                            Icon(Icons.Rounded.SkipNext,
                                contentDescription = "Brano successivo",
                                tint = MusicPalette.text, modifier = Modifier.size(52.dp))
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    MusicLyricsPanel(
                        state = lyrics, onImportLyrics = onImportLyrics,
                        trackIdentity = uri.orEmpty(),
                        modifier = Modifier.width(controlWidth),
                    )
                }
            }
        }
    }
}


/** User-supplied local texts; no network provider is enabled without display rights. */
@Composable
private fun MusicLyricsPanel(
    state: LyricsUiState, onImportLyrics: () -> Unit,
    trackIdentity: String, modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(trackIdentity) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MusicPalette.surface,
        border = BorderStroke(1.dp, MusicPalette.border.copy(alpha = 0.7f)),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Testo", color = MusicPalette.text,
                    style = MaterialTheme.typography.labelLarge)
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "Chiudi testo" else "Apri testo",
                    tint = MusicPalette.primary, modifier = Modifier.size(22.dp)
                        .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                )
            }
            AnimatedVisibility(
                visible = expanded, enter = expandVertically(), exit = shrinkVertically(),
            ) {
                Column(
                    Modifier.fillMaxWidth().background(MusicPalette.background)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state is LyricsUiState.Available) {
                        Box(
                            Modifier.fillMaxWidth().heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                state.plainText, color = MusicPalette.muted,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 23.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        val message = when (state) {
                            LyricsUiState.Loading -> "Caricamento testo…"
                            LyricsUiState.Instrumental -> "Brano strumentale"
                            LyricsUiState.NotFound -> "Nessun testo associato a questo brano"
                            LyricsUiState.Offline -> "Connessione assente"
                            LyricsUiState.ProviderError -> "Servizio testi non disponibile"
                            LyricsUiState.Ambiguous -> "Più testi possibili: selezione necessaria"
                            is LyricsUiState.Available -> ""
                        }
                        Text(
                            message, color = MusicPalette.muted,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onImportLyrics, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(
                            if (state is LyricsUiState.Available)
                                "Sostituisci testo locale" else "Carica testo locale",
                        )
                    }
                }
            }
        }
    }
}

/** The physical Media3 timeline is the playback plan; entries never leave the device. */
internal data class MusicQueueEntry(
    val index: Int,
    val mediaId: String,
    val title: String,
    val artist: String,
    val uri: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicQueueSheet(
    entries: List<MusicQueueEntry>, currentIndex: Int, queueMode: String,
    repeatOneEnabled: Boolean, cache: LocalArtworkCache,
    onSelect: (Int) -> Unit, onDismiss: () -> Unit,
    onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    val upcoming = PlaybackQueuePlan.visible(entries, currentIndex)
    val dragThresholdPx = with(LocalDensity.current) { 40.dp.toPx() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val latestMove by rememberUpdatedState(onMove)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MusicPalette.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Coda", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when (queueMode) {
                            PlaybackQueueCommands.SHUFFLE -> "Casuale · ordine effettivo"
                            PlaybackQueueCommands.PLAYLIST -> "Dalla playlist"
                            PlaybackQueueCommands.CUSTOM -> "Personalizzata"
                            else -> "Dalla libreria"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MusicPalette.muted,
                    )
                }
                TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = null,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Aggiungi")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Chiudi coda")
                }
            }
            if (repeatOneEnabled && upcoming.isNotEmpty()) {
                Text("Ripeti brano attivo · i prossimi restano in attesa",
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MusicPalette.muted)
            }
            if (upcoming.size > 1) {
                Text("Tieni premuto un brano per trascinarlo",
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MusicPalette.muted)
            }
            if (upcoming.isEmpty()) {
                Text("Coda vuota · aggiungi brani dalla libreria o dalle playlist",
                    modifier = Modifier.padding(vertical = 22.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MusicPalette.muted)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    userScrollEnabled = draggingId == null,
                ) {
                    items(upcoming, key = { it.mediaId }) { item ->
                        val position = item.index - currentIndex
                        val current = item.index == currentIndex
                        val canUp = PlaybackQueuePlan.canMoveUpcoming(
                            item.index, item.index - 1, currentIndex, entries.size)
                        val canDown = PlaybackQueuePlan.canMoveUpcoming(
                            item.index, item.index + 1, currentIndex, entries.size)
                        // Stable media ID keeps the gesture alive while items move.
                        // Hold anywhere on a future song; each ~40dp drag
                        // crosses one item. Media3 receives the same moves.
                        val drag = if (current) Modifier else Modifier.pointerInput(
                            item.mediaId, currentIndex,
                        ) {
                            var index = item.index
                            var travelled = 0f
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingId = item.mediaId
                                    dragOffsetY = 0f
                                },
                                onDragEnd = {
                                    draggingId = null
                                    dragOffsetY = 0f
                                    travelled = 0f
                                },
                                onDragCancel = {
                                    draggingId = null
                                    dragOffsetY = 0f
                                    travelled = 0f
                                },
                                onDrag = { change, movement ->
                                    change.consume()
                                    travelled += movement.y
                                    while (abs(travelled) >= dragThresholdPx) {
                                        val to = PlaybackQueuePlan.dragTarget(
                                            index, travelled, dragThresholdPx,
                                            currentIndex, entries.size,
                                        )
                                        if (to == null) {
                                            travelled = 0f
                                            break
                                        }
                                        latestMove(index, to)
                                        index = to
                                        travelled -= if (travelled > 0f)
                                            dragThresholdPx else -dragThresholdPx
                                    }
                                    dragOffsetY = travelled
                                },
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .zIndex(if (draggingId == item.mediaId) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (draggingId == item.mediaId)
                                        dragOffsetY else 0f
                                }
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (current || draggingId == item.mediaId)
                                    MusicPalette.elevated else Color.Transparent)
                                .then(drag)
                                .clickable { onSelect(item.index) }
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MusicArt(item.uri, cache, Modifier.size(44.dp), quality = 128)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.title, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (current) MusicPalette.primary
                                        else MusicPalette.text,
                                )
                                Text(
                                    item.artist.ifBlank { "Artista sconosciuto" },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MusicPalette.muted,
                                )
                                if (position == 1) Text("Prossimo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MusicPalette.primary)
                            }
                            if (current) {
                                Icon(Icons.Rounded.PlayArrow,
                                    contentDescription = "Brano corrente",
                                    tint = MusicPalette.primary)
                            } else {
                                // Accessible alternatives to drag-and-drop. These also make
                                // ordering possible with a screen reader or keyboard.
                                IconButton(
                                    onClick = { onMove(item.index, item.index - 1) },
                                    enabled = canUp,
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Rounded.ArrowUpward,
                                        contentDescription = "Sposta sopra " + item.title,
                                        modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { onMove(item.index, item.index + 1) },
                                    enabled = canDown,
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Rounded.ArrowDownward,
                                        contentDescription = "Sposta sotto " + item.title,
                                        modifier = Modifier.size(18.dp))
                                }
                                IconButton(
                                    onClick = { onRemove(item.index) },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline,
                                        contentDescription = "Rimuovi dalla coda " + item.title,
                                        modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMusicTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000L
    return if (seconds >= 3600L) "%d:%02d:%02d".format(
        seconds / 3600L, (seconds / 60L) % 60L, seconds % 60L,
    ) else "%d:%02d".format(seconds / 60L, seconds % 60L)
}
