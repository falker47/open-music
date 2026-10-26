package app.openmusic

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import app.openmusic.core.AudioKindFlags
import app.openmusic.core.AudioKindPolicy
import app.openmusic.core.AudioItemStamp
import app.openmusic.core.ContentDigest
import app.openmusic.core.LocalCopy
import app.openmusic.core.TrackIdentity
import app.openmusic.core.TrackMetadata

data class AudioFile(
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val source: String,
    val isMusic: Boolean,
) {
    fun asCopy(hash: String? = null) = LocalCopy(uri, TrackIdentity(
        sha256 = hash,
        metadata = TrackMetadata(artist, title, album, durationMs, source),
        filenameHint = displayName,
    ))
}

internal fun AudioFile.stamp(): AudioItemStamp = AudioItemStamp(uri, sizeBytes, modifiedSeconds)

object MediaLibrary {
    // In-process evidence cache avoids repeatedly opening the same unchanged file
    // when the user rescans. It contains no audio, artwork bytes or cloud data.
    private data class EmbeddedEvidence(
        val artist: String?, val album: String?, val genre: String?,
        val durationMs: Long?, val hasArtwork: Boolean,
    )
    private val inspected = LinkedHashMap<AudioItemStamp, EmbeddedEvidence>()

    private fun inspectTags(context: Context, uri: Uri, stamp: AudioItemStamp): EmbeddedEvidence? {
        synchronized(inspected) { inspected[stamp]?.let { return it } }
        val retriever = MediaMetadataRetriever()
        val result = try {
            retriever.setDataSource(context, uri)
            EmbeddedEvidence(
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.takeIf { it > 0L },
                hasArtwork = retriever.embeddedPicture?.isNotEmpty() == true,
            )
        } catch (_: Exception) {
            null // Inaccessible metadata is never an error that hides the audio item.
        } finally {
            retriever.release()
        }
        if (result != null) synchronized(inspected) {
            inspected[stamp] = result
            if (inspected.size > 1200) {
                inspected.keys.firstOrNull()?.let { inspected.remove(it) }
            }
        }
        return result
    }
    fun permission(): String = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    /** Always scan *all* local audio; filtering is only a UI presentation choice. */
    fun scan(context: Context): List<AudioFile> {
        val base = listOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.TRACK,
        )
        val pathColumn = if (Build.VERSION.SDK_INT >= 29)
            listOf(MediaStore.Audio.Media.RELATIVE_PATH) else emptyList()
        val genreColumn = if (Build.VERSION.SDK_INT >= 30)
            listOf(MediaStore.Audio.Media.GENRE) else emptyList()
        val categories = buildList {
            add(MediaStore.Audio.Media.IS_MUSIC)
            add(MediaStore.Audio.Media.IS_PODCAST)
            add(MediaStore.Audio.Media.IS_RINGTONE)
            add(MediaStore.Audio.Media.IS_NOTIFICATION)
            add(MediaStore.Audio.Media.IS_ALARM)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Audio.Media.IS_AUDIOBOOK)
            if (Build.VERSION.SDK_INT >= 31) add(MediaStore.Audio.Media.IS_RECORDING)
        }
        fun read(volumeUri: Uri, columns: List<String>): List<AudioFile> {
            val audio = mutableListOf<AudioFile>()
            context.contentResolver.query(
                volumeUri,
                columns.toTypedArray(), null, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC",
            )?.use { cursor ->
                fun string(column: String): String? {
                    val i = cursor.getColumnIndex(column)
                    return if (i < 0 || cursor.isNull(i)) null else
                        cursor.getString(i)?.trim()?.takeIf { it.isNotBlank() }
                }
                fun number(column: String): Long {
                    val i = cursor.getColumnIndex(column)
                    return if (i < 0 || cursor.isNull(i)) 0L else cursor.getLong(i)
                }
                fun flag(column: String): Boolean? {
                    val i = cursor.getColumnIndex(column)
                    return if (i < 0 || cursor.isNull(i)) null else cursor.getInt(i) != 0
                }
                while (cursor.moveToNext()) {
                    val id = number(MediaStore.Audio.Media._ID)
                    val fileName = string(MediaStore.Audio.Media.DISPLAY_NAME) ?: "audio-$id"
                    val rawTitle = string(MediaStore.Audio.Media.TITLE)
                    val title = rawTitle ?: fileName.substringBeforeLast('.')
                    fun meaningfulTag(tag: String?): String? = tag?.takeUnless {
                        it.trim().lowercase() in setOf(
                            "<unknown>", "unknown", "unknown artist", "unknown album",
                            "artista sconosciuto", "album sconosciuto", "artist", "album",
                        )
                    }
                    val artist = meaningfulTag(string(MediaStore.Audio.Media.ARTIST))
                    val album = meaningfulTag(string(MediaStore.Audio.Media.ALBUM))
                    val duration = number(MediaStore.Audio.Media.DURATION).takeIf { it > 0L }
                    val genre = if (Build.VERSION.SDK_INT >= 30)
                        string(MediaStore.Audio.Media.GENRE) else null
                    val trackNumber = number(MediaStore.Audio.Media.TRACK).toInt()
                        .takeIf { it > 0 }
                    val relativePath = if (Build.VERSION.SDK_INT >= 29)
                        string(MediaStore.Audio.Media.RELATIVE_PATH) else null
                    val uri = ContentUris.withAppendedId(volumeUri, id)
                    val flags = AudioKindFlags(
                        music = flag(MediaStore.Audio.Media.IS_MUSIC),
                        podcast = flag(MediaStore.Audio.Media.IS_PODCAST),
                        audiobook = if (Build.VERSION.SDK_INT >= 29)
                            flag(MediaStore.Audio.Media.IS_AUDIOBOOK) else null,
                        recording = if (Build.VERSION.SDK_INT >= 31)
                            flag(MediaStore.Audio.Media.IS_RECORDING) else null,
                        ringtone = flag(MediaStore.Audio.Media.IS_RINGTONE),
                        notification = flag(MediaStore.Audio.Media.IS_NOTIFICATION),
                        alarm = flag(MediaStore.Audio.Media.IS_ALARM),
                    )
                    val size = number(MediaStore.Audio.Media.SIZE)
                    val modified = number(MediaStore.Audio.Media.DATE_MODIFIED)
                    val first = AudioKindPolicy.assess(
                        flags, artist, album, title, fileName, relativePath, genre, trackNumber,
                    )
                    val extra = if (first.inspectEmbeddedMetadata)
                        inspectTags(context, uri, AudioItemStamp(uri.toString(), size, modified))
                    else null
                    val effectiveArtist = artist ?: extra?.artist
                    val effectiveAlbum = album ?: extra?.album
                    val effectiveGenre = genre ?: extra?.genre
                    val music = if (extra == null) first.music else AudioKindPolicy.likelyMusic(
                        flags, effectiveArtist, effectiveAlbum, title, fileName, relativePath,
                        effectiveGenre, trackNumber, extra.hasArtwork,
                    )
                    audio.add(AudioFile(
                        uri.toString(), title, effectiveArtist, effectiveAlbum,
                        duration ?: extra?.durationMs, fileName, size, modified,
                        if (effectiveArtist != null && rawTitle != null) "tags" else "filename",
                        music,
                    ))
                }
            }
            return audio
        }
        // API 29+ exposes distinct stable volume URIs; the synthetic "external"
        // volume cannot safely be used with getVersion/getGeneration cache keys.
        val volumes = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.getExternalVolumeNames(context).map { MediaStore.Audio.Media.getContentUri(it) }
        else listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        return volumes.flatMap { volume ->
            // Older/OEM providers may not expose every classification flag.
            try { read(volume, base + pathColumn + categories + genreColumn) }
            catch (_: IllegalArgumentException) {
                try { read(volume, base + pathColumn + categories) }
                catch (_: IllegalArgumentException) {
                    try { read(volume, base + categories) }
                    catch (_: IllegalArgumentException) { read(volume, base) }
                }
            }
        }.sortedWith(compareBy({ it.title.lowercase() }, { it.artist.orEmpty().lowercase() }))
    }

    /** Invoked only from Dispatchers.IO. */
    fun sha256(context: Context, audio: AudioFile): String {
        val stream = context.contentResolver.openInputStream(Uri.parse(audio.uri))
            ?: throw IllegalStateException("Brano non accessibile")
        return ContentDigest.sha256(stream)
    }
}
