package app.openmusic

import android.Manifest
import android.content.ContentUris
import android.content.Context
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
        )
        val pathColumn = if (Build.VERSION.SDK_INT >= 29)
            listOf(MediaStore.Audio.Media.RELATIVE_PATH) else emptyList()
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
                    val artist = string(MediaStore.Audio.Media.ARTIST)?.takeUnless { it == "<unknown>" }
                    val album = string(MediaStore.Audio.Media.ALBUM)?.takeUnless { it == "<unknown>" }
                    val duration = number(MediaStore.Audio.Media.DURATION).takeIf { it > 0L }
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
                    audio.add(AudioFile(
                        uri.toString(), title, artist, album, duration, fileName,
                        number(MediaStore.Audio.Media.SIZE),
                        number(MediaStore.Audio.Media.DATE_MODIFIED),
                        if (artist != null && rawTitle != null) "tags" else "filename",
                        AudioKindPolicy.likelyMusic(
                            flags, artist, album, title, fileName,
                            if (Build.VERSION.SDK_INT >= 29)
                                string(MediaStore.Audio.Media.RELATIVE_PATH) else null,
                        ),
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
            try { read(volume, base + pathColumn + categories) }
            catch (_: IllegalArgumentException) {
                try { read(volume, base + categories) }
                catch (_: IllegalArgumentException) { read(volume, base) }
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
