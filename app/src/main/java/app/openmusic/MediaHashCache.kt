package app.openmusic

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

/** In-memory, bounded by the current device scan; never saves paths or hashes in JSON v1.
 * Files are re-statted before reuse, and a rescan invalidates the entire cache.
 */
internal class MediaHashCache(private val context: Context) {
    private data class Fingerprint(val size: Long, val modified: Long, val generation: Long?)
    private data class Cached(val fingerprint: Fingerprint, val hash: String)
    private val mutex = Mutex()
    private val cached = mutableMapOf<String, Cached>()

    suspend fun clearForRescan() = mutex.withLock { cached.clear() }

    private fun fingerprint(uri: String): Fingerprint? {
        val base = listOf(MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED)
        val columns = if (Build.VERSION.SDK_INT >= 30)
            base + MediaStore.Audio.Media.GENERATION_MODIFIED else base
        fun read(projected: List<String>): Fingerprint? =
            context.contentResolver.query(Uri.parse(uri), projected.toTypedArray(), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    Fingerprint(
                        cursor.getLong(cursor.getColumnIndexOrThrow(base[0])),
                        cursor.getLong(cursor.getColumnIndexOrThrow(base[1])),
                        if (projected.size > 2) cursor.getLong(cursor.getColumnIndexOrThrow(projected[2]))
                        else null,
                    )
                }
        return try { read(columns) } catch (_: IllegalArgumentException) {
            try { read(base) } catch (_: IllegalArgumentException) { null }
        }
    }

    suspend fun sha256(file: AudioFile): String = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        mutex.withLock {
            val before = fingerprint(file.uri)
            val old = cached[file.uri]
            if (before != null && before.size == file.sizeBytes &&
                before.modified == file.modifiedSeconds && old?.fingerprint == before) {
                return@withLock old.hash
            }

            val md = MessageDigest.getInstance("SHA-256")
            val stream = context.contentResolver.openInputStream(Uri.parse(file.uri))
                ?: throw IOException("Audio non accessibile")
            stream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    job?.ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count > 0) md.update(buffer, 0, count)
                }
            }
            job?.ensureActive()
            val after = fingerprint(file.uri)
            if (before != null && after != null && before != after) {
                cached.remove(file.uri)
                throw IOException("Il file audio è cambiato durante l'analisi")
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            if (after != null && after.size == file.sizeBytes &&
                after.modified == file.modifiedSeconds) cached[file.uri] = Cached(after, hash)
            else cached.remove(file.uri)
            hash
        }
    }
}
