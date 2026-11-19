package app.openmusic

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.AtomicFile
import app.openmusic.core.DigestFingerprint
import app.openmusic.core.HashReusePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal data class DigestResult(val hash: String, val reused: Boolean)

/**
 * Private SHA-256 cache, deliberately separate from portable JSON v1.
 * A cache hit requires a specific MediaStore volume/version plus size, modified
 * timestamp and generation. Unsupported/uncertain providers are always rehashed.
 */
internal class MediaHashCache(private val context: Context) {
    private data class Cached(val fingerprint: DigestFingerprint, val hash: String)
    private val storage = AtomicFile(File(context.filesDir, "open-music-media-sha256.json"))
    private val mutex = Mutex()
    // Only cache-map and AtomicFile operations need the global mutex. Hashing
    // bytes while holding it accidentally serialized the entire 795-file scan.
    // Two readers bound I/O pressure on mobile storage.
    private val readGate = Semaphore(2)
    // Stripes prevent a manual add racing a library scan for the same URI.
    private val uriLocks = Array(64) { Mutex() }
    private val cached = mutableMapOf<String, Cached>()
    private var loaded = false
    private var dirty = 0

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        if (!storage.baseFile.exists()) return
        // Corrupt/outdated cache is never an authority: recompute content, don't fail playlists.
        try {
            val root = JSONObject(storage.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (root.optInt("schema") != 1) return
            val items = root.getJSONArray("items")
            repeat(items.length()) { i ->
                val j = items.getJSONObject(i)
                val uri = j.getString("uri")
                val hash = j.getString("sha256")
                if (!uri.startsWith("content://media/") || !Regex("[a-f0-9]{64}").matches(hash)) return@repeat
                val fingerprint = DigestFingerprint(
                    uri, j.getString("volume"), j.getLong("size"), j.getLong("modified"),
                    if (j.isNull("generation")) null else j.getLong("generation"),
                    if (j.isNull("mediaStoreVersion")) null else j.getString("mediaStoreVersion"),
                )
                if (HashReusePolicy.reusable(fingerprint, fingerprint))
                    cached[uri] = Cached(fingerprint, hash)
            }
        } catch (_: Exception) {
            cached.clear()
            dirty++
        }
    }

    private fun saveLocked() {
        if (dirty == 0) return
        val items = JSONArray()
        cached.values.forEach { (fingerprint, hash) ->
            items.put(JSONObject()
                .put("uri", fingerprint.uri)
                .put("volume", fingerprint.volume)
                .put("size", fingerprint.sizeBytes)
                .put("modified", fingerprint.modifiedSeconds)
                .put("generation", fingerprint.generationModified)
                .put("mediaStoreVersion", fingerprint.mediaStoreVersion)
                .put("sha256", hash))
        }
        val bytes = JSONObject().put("schema", 1).put("items", items)
            .toString().toByteArray(Charsets.UTF_8)
        val stream = storage.startWrite()
        try {
            stream.write(bytes)
            storage.finishWrite(stream)
            dirty = 0
        } catch (error: Exception) {
            storage.failWrite(stream)
            throw error
        }
    }

    suspend fun prepareForScan(files: List<AudioFile>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val present = files.mapTo(HashSet(files.size)) { it.uri }
            val before = cached.size
            cached.keys.retainAll(present)
            if (cached.size != before) dirty++
        }
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked(); saveLocked() }
    }

    /** Null means the metadata cannot be trusted for reuse, not that the audio is missing. */
    private fun fingerprint(uri: String): DigestFingerprint? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val parsed = Uri.parse(uri)
            val volume = MediaStore.getVolumeName(parsed)
            // The combined "external" volume is synthetic. getVersion only accepts real volumes.
            if (volume !in MediaStore.getExternalVolumeNames(context)) return null
            val version = MediaStore.getVersion(context, volume)
                ?.takeIf { it.isNotBlank() } ?: return null
            val columns = arrayOf(
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.GENERATION_MODIFIED,
            )
            context.contentResolver.query(parsed, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                fun number(column: String): Long? {
                    val index = cursor.getColumnIndex(column)
                    return if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
                }
                val size = number(columns[0]) ?: return@use null
                val modified = number(columns[1]) ?: return@use null
                DigestFingerprint(uri, volume, size, modified, number(columns[2]), version)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun sha256(file: AudioFile): String = digest(file).hash

    suspend fun digest(file: AudioFile): DigestResult = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        readGate.withPermit {
            val lock = uriLocks[(file.uri.hashCode() and Int.MAX_VALUE) % uriLocks.size]
            lock.withLock {
                // Recheck the item at the moment of use; unchanged URI alone never
                // validates a stale cached hash. The provider version/generation
                // safeguards are deliberately unchanged.
                val before = fingerprint(file.uri)
                val old = mutex.withLock {
                    loadLocked()
                    cached[file.uri]
                }
                if (old != null && before != null &&
                    before.sizeBytes == file.sizeBytes &&
                    before.modifiedSeconds == file.modifiedSeconds &&
                    HashReusePolicy.reusable(old.fingerprint, before)
                ) {
                    DigestResult(old.hash, true)
                } else {
                    // Drop any stale digest even when opening or reading the file
                    // subsequently fails, preventing a stale on-disk cache entry.
                    mutex.withLock {
                        if (cached.remove(file.uri) != null) dirty++
                    }
                    val md = MessageDigest.getInstance("SHA-256")
                    val stream = context.contentResolver.openInputStream(Uri.parse(file.uri))
                        ?: throw IOException("Audio non accessibile")
                    stream.use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            job?.ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (count > 0) md.update(buffer, 0, count)
                        }
                    }
                    job?.ensureActive()
                    val after = fingerprint(file.uri)
                    if (before != null && before != after)
                        throw IOException("Il file audio è cambiato durante l'analisi")
                    val digest = md.digest()
                    val hex = "0123456789abcdef"
                    val hash = buildString(digest.size * 2) {
                        for (byte in digest) {
                            val value = byte.toInt() and 0xff
                            append(hex[value ushr 4])
                            append(hex[value and 15])
                        }
                    }
                    if (after != null && after.sizeBytes == file.sizeBytes &&
                        after.modifiedSeconds == file.modifiedSeconds &&
                        HashReusePolicy.reusable(after, after)
                    ) {
                        mutex.withLock {
                            cached[file.uri] = Cached(after, hash)
                            dirty++
                            if (dirty >= 32) saveLocked()
                        }
                    }
                    DigestResult(hash, false)
                }
            }
        }
    }
}
