package app.openmusic

import android.content.Context
import app.openmusic.core.AudioBackupFile
import app.openmusic.core.AudioBackupPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Separate drive.file adapter. No call from playlist sync or app startup.
 * A caller must acquire an explicit audio-backup OAuth grant.
 */
internal class DriveAudioBackupGateway(
    private val accessToken: String,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    data class ChunkAck(val confirmedOffset: Long, val remoteFileId: String? = null)
    private val fileBase = "https://www.googleapis.com/drive/v3/files"
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun approvedUrl(raw: String): URL {
        val url = URL(raw)
        require(url.protocol == "https" &&
            url.host in setOf("www.googleapis.com", "upload.googleapis.com") &&
            url.port == -1 && url.userInfo == null) { "Invalid Drive endpoint" }
        return url
    }

    private fun connect(url: String, method: String): HttpURLConnection {
        require(accessToken.isNotBlank()) { "Explicit drive.file authorization required" }
        return openConnection(approvedUrl(url)).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer " + accessToken)
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun readSuccess(connection: HttpURLConnection, limit: Int = 2_500_000): String {
        val status = connection.responseCode
        if (status !in 200..299) throw httpFailure(connection, status)
        return readBody(connection.inputStream, limit)
    }

    private fun readBody(stream: InputStream, limit: Int): String {
        val out = ByteArrayOutputStream()
        stream.use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (out.size() + read > limit) throw IOException("Oversized Drive response")
                out.write(chunk, 0, read)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private class UploadRejected(message: String) : IOException(message)

    private fun httpFailure(connection: HttpURLConnection, status: Int): IOException {
        if (status == 403) {
            val reasons = try {
                connection.errorStream?.let { stream ->
                    JSONObject(readBody(stream, 16 * 1024)).optJSONObject("error")?.optJSONArray("errors")
                }
            } catch (_: JSONException) { null } catch (_: IOException) { null }
            if (reasons != null && (0 until reasons.length()).any {
                    reasons.optJSONObject(it)?.optString("reason") == "storageQuotaExceeded"
                }) return UploadRejected("Spazio Google Drive esaurito")
        }
        val message = "Drive backup HTTP " + status
        return if (status in 500..599) IOException(message) else UploadRejected(message)
    }

    /** Drive's opaque permission ID provides an account guard without email access. */
    fun accountMarker(): String {
        val connection = connect(
            "https://www.googleapis.com/drive/v3/about?fields=user(permissionId)", "GET")
        try {
            val marker = JSONObject(readSuccess(connection))
                .getJSONObject("user").getString("permissionId")
            require(marker.isNotBlank()) { "Drive user identity unavailable" }
            return marker
        } finally { connection.disconnect() }
    }

    /**
     * The folder is created by this app, so drive.file may list only our
     * own uploads. Any file lacking a valid content hash is never restored.
     */
    fun listBackedUpFiles(folderId: String): List<AudioBackupFile> {
        require(folderId.isNotBlank())
        val result = mutableListOf<AudioBackupFile>()
        var page: String? = null
        do {
            val query = "'" + folderId.replace("'", "\\'") +
                "' in parents and trashed = false"
            val args = mutableListOf(
                "q=" + encode(query),
                "pageSize=1000",
                "fields=" + encode("nextPageToken,files(id,name,size,mimeType,appProperties)")
            )
            if (page != null) args.add("pageToken=" + encode(page!!))
            val connection = connect(fileBase + "?" + args.joinToString("&"), "GET")
            val json = try { JSONObject(readSuccess(connection)) }
                finally { connection.disconnect() }
            val files = json.getJSONArray("files")
            for (i in 0 until files.length()) {
                val entry = files.getJSONObject(i)
                val sha = entry.optJSONObject("appProperties")
                    ?.optString("openMusicSha256").orEmpty()
                if (!sha.matches(Regex("^[a-f0-9]{64}$"))) continue
                val size = entry.optString("size").toLongOrNull() ?: continue
                if (size < 0) continue
                result.add(AudioBackupFile(
                    name = entry.getString("name"),
                    sha256 = sha,
                    sizeBytes = size,
                    mimeType = entry.optString("mimeType").takeIf { it.isNotBlank() }
                        ?: "application/octet-stream",
                    remoteFileId = entry.getString("id"),
                ))
                if (result.size > 50_000) throw IOException("Backup inventory too large")
            }
            page = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null)
        return result
    }

    /** Search only app-created Drive folders visible under drive.file. */
    fun findExistingFolder(previousFolderId: String?): String? {
        if (!previousFolderId.isNullOrBlank()) {
            val connection = connect(
                fileBase + "/" + encode(previousFolderId) +
                    "?fields=id,name,mimeType,trashed", "GET")
            try {
                val item = JSONObject(readSuccess(connection))
                require(item.getString("id") == previousFolderId &&
                    item.getString("name") == "Open Music Audio Backup" &&
                    item.getString("mimeType") == "application/vnd.google-apps.folder" &&
                    !item.optBoolean("trashed")) { "Saved backup folder invalid" }
                return previousFolderId
            } finally { connection.disconnect() }
        }
        val query = "name = 'Open Music Audio Backup' and " +
            "mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val candidates = mutableListOf<String>()
        var page: String? = null
        do {
            val args = mutableListOf("q=" + encode(query), "pageSize=100",
                "fields=" + encode("nextPageToken,files(id,name,mimeType,trashed)"))
            if (page != null) args.add("pageToken=" + encode(page!!))
            val connection = connect(fileBase + "?" + args.joinToString("&"), "GET")
            val response = try { JSONObject(readSuccess(connection)) }
                finally { connection.disconnect() }
            val found = response.getJSONArray("files")
            repeat(found.length()) { index ->
                val item = found.getJSONObject(index)
                if (item.optString("name") == "Open Music Audio Backup" &&
                    item.optString("mimeType") == "application/vnd.google-apps.folder" &&
                    !item.optBoolean("trashed"))
                    candidates.add(item.getString("id"))
            }
            if (candidates.size > 1)
                throw IOException("Più cartelle Open Music Audio Backup: scelta ambigua")
            page = response.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null)
        return candidates.singleOrNull()
    }

    /** Persist returned ID, discover an existing app-created folder before creating. */
    fun findOrCreateFolder(previousFolderId: String?): String {
        findExistingFolder(previousFolderId)?.let { return it }
        val connection = connect(fileBase + "?fields=id", "POST")
        try {
            val body = JSONObject()
                .put("name", "Open Music Audio Backup")
                .put("mimeType", "application/vnd.google-apps.folder")
                .toString().toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            return JSONObject(readSuccess(connection)).getString("id")
        } finally { connection.disconnect() }
    }

    fun startResumableUpload(folderId: String, file: AudioBackupFile): String {
        require(folderId.isNotBlank() && file.remoteFileId == null)
        AudioBackupPlanner.remainingBytes(file.sizeBytes, 0L)
        val body = JSONObject().put("name", file.name)
            .put("parents", org.json.JSONArray().put(folderId))
            .put("mimeType", file.mimeType)
            .put("appProperties", JSONObject()
                .put("openMusicSha256", file.sha256)
                .put("openMusicVersion", "1"))
            .toString().toByteArray(Charsets.UTF_8)
        val connection = connect(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id",
            "POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Upload-Content-Type", file.mimeType)
            connection.setRequestProperty("X-Upload-Content-Length", file.sizeBytes.toString())
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status !in 200..299) throw httpFailure(connection, status)
            val session = connection.getHeaderField("Location")
                ?: throw IOException("Drive did not return resumable session")
            approvedUrl(session)
            return session
        } finally { connection.disconnect() }
    }

    private fun parseAck(connection: HttpURLConnection, total: Long): Long {
        val range = connection.getHeaderField("Range") ?: return 0L
        val end = Regex("bytes=0-([0-9]+)").matchEntire(range)?.groupValues?.get(1)?.toLongOrNull()
        if (end == null || end !in 0 until total)
            throw UploadRejected("Drive ha confermato un intervallo non valido")
        return end + 1
    }

    private fun acknowledgement(connection: HttpURLConnection, total: Long): ChunkAck {
        val code = connection.responseCode
        if (code == 308) return ChunkAck(parseAck(connection, total))
        if (code != 200 && code != 201) throw httpFailure(connection, code)
        val id = JSONObject(readSuccess(connection)).getString("id")
        if (id.isBlank()) throw UploadRejected("ID file remoto non confermato")
        return ChunkAck(total, id)
    }

    /** Probe after interrupted/uncertain upload before retrying the same chunk. */
    fun queryConfirmedOffset(session: String, total: Long): ChunkAck {
        AudioBackupPlanner.remainingBytes(total, 0L)
        val connection = connect(session, "PUT")
        try {
            connection.setRequestProperty("Content-Range", "bytes */" + total)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(0)
            return acknowledgement(connection, total)
        } finally { connection.disconnect() }
    }

    fun uploadChunk(
        session: String, chunk: ByteArray, offset: Long, total: Long,
    ): ChunkAck {
        require(chunk.isNotEmpty() && chunk.size <= 8 * 1024 * 1024)
        AudioBackupPlanner.remainingBytes(total, offset)
        require(offset + chunk.size <= total)
        require(chunk.size % (256 * 1024) == 0 || offset + chunk.size == total)
        val connection = connect(session, "PUT")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Content-Range",
                "bytes " + offset + "-" + (offset + chunk.size - 1) + "/" + total)
            connection.setFixedLengthStreamingMode(chunk.size)
            connection.outputStream.use { it.write(chunk) }
            return acknowledgement(connection, total)
        } finally { connection.disconnect() }
    }

    suspend fun upload(
        folderId: String, file: AudioBackupFile, readChunk: (offset: Long, count: Int) -> ByteArray,
    ): String {
        val total = file.sizeBytes
        require(total > 0)
        val session = startResumableUpload(folderId, file)
        var offset = 0L
        var stalled = 0
        try {
            while (offset < total) {
                currentCoroutineContext().ensureActive()
                val count = minOf(1024L * 1024, total - offset).toInt()
                val chunk = readChunk(offset, count)
                require(chunk.size == count)
                currentCoroutineContext().ensureActive()
                val ack = try {
                    uploadChunk(session, chunk, offset, total)
                } catch (error: UploadRejected) {
                    throw error
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    // A lost response can follow a successful write. Probe before retransmission.
                    queryConfirmedOffset(session, total)
                }
                if (ack.confirmedOffset !in offset..(offset + count))
                    throw UploadRejected("Drive ha confermato un offset non valido")
                ack.remoteFileId?.let { return it }
                if (ack.confirmedOffset == offset) {
                    if (++stalled > 2) throw IOException("Drive non ha accettato i dati di upload")
                } else {
                    offset = ack.confirmedOffset
                    stalled = 0
                }
            }
            return queryConfirmedOffset(session, total).remoteFileId
                ?: throw IOException("ID file remoto non confermato")
        } catch (cancel: CancellationException) {
            runCatching { cancelUpload(session) }
            throw cancel
        }
    }

    /** Cancel session only; no remote file deletion. */
    fun cancelUpload(session: String) {
        val connection = connect(session, "DELETE")
        try {
            if (connection.responseCode !in 200..299)
                throw IOException("Cancel upload HTTP " + connection.responseCode)
        } finally { connection.disconnect() }
    }

    /** Verify size and SHA in private cache before any user-authorized SAF write. */
    fun stageVerifiedDownload(context: Context, file: AudioBackupFile): File {
        val id = file.remoteFileId ?: throw IllegalArgumentException("Missing remote file ID")
        require(file.sizeBytes >= 0 && file.sizeBytes <= context.cacheDir.usableSpace) {
            "Insufficient local storage"
        }
        val temp = File(context.cacheDir,
            "om-restore-" + UUID.randomUUID().toString() + ".part")
        val connection = connect(fileBase + "/" + encode(id) + "?alt=media", "GET")
        try {
            if (connection.responseCode !in 200..299)
                throw IOException("Audio download HTTP " + connection.responseCode)
            var count = 0L
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > file.sizeBytes) throw IOException("Oversized audio download")
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (!AudioBackupPlanner.verifyDownloaded(file, temp.inputStream()))
                throw IOException("Audio SHA-256 mismatch")
            return temp
        } catch (error: Exception) {
            temp.delete()
            throw error
        } finally { connection.disconnect() }
    }
}
