package app.openmusic

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
) {
    fun asCopy(hash: String? = null) = LocalCopy(uri, TrackIdentity(
        sha256 = hash,
        metadata = TrackMetadata(artist, title, album, durationMs, source),
        filenameHint = displayName,
    ))
}

object MediaLibrary {
    fun permission(): String = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    /** Reads Android's local MediaStore audio index, not pCloud/Drive remote libraries. */
    fun scan(context: Context): List<AudioFile> {
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val audio = mutableListOf<AudioFile>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            columns, null, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC",
        )?.use { cursor ->
            val indices = columns.map(cursor::getColumnIndexOrThrow)
            fun string(i: Int): String? = if (cursor.isNull(indices[i])) null else
                cursor.getString(indices[i])?.trim()?.takeIf { it.isNotBlank() }
            fun number(i: Int): Long = if (cursor.isNull(indices[i])) 0L else
                cursor.getLong(indices[i])
            while (cursor.moveToNext()) {
                val id = number(0)
                val fileName = string(5) ?: "audio-$id"
                val title = string(1) ?: fileName.substringBeforeLast('.')
                val artist = string(2)?.takeUnless { it == "<unknown>" }
                val album = string(3)?.takeUnless { it == "<unknown>" }
                val duration = number(4).takeIf { it > 0L }
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                audio.add(AudioFile(
                    uri.toString(), title, artist, album, duration, fileName,
                    number(6), number(7), if (artist != null && string(1) != null) "tags" else "filename",
                ))
            }
        }
        return audio.sortedWith(compareBy({ it.title.lowercase() }, { it.artist.orEmpty().lowercase() }))
    }

    /** Full-file hashing is deliberate and only runs in a worker coroutine, never on the UI thread. */
    fun sha256(context: Context, audio: AudioFile): String {
        val stream = context.contentResolver.openInputStream(Uri.parse(audio.uri))
            ?: throw IllegalStateException("Brano non accessibile")
        return ContentDigest.sha256(stream)
    }
}
