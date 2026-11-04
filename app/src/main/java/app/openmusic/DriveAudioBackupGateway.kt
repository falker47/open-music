package app.openmusic

import android.content.Context
import app.openmusic.core.AudioBackupFile
import app.openmusic.core.AudioBackupPlanner
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Separate drive.file adapter. No call from playlist sync or app startup.
 * A caller must acquire an explicit audio-backup OAuth grant.
 */
internal class DriveAudioBackupGateway(private val accessToken: String) {
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
        return (approvedUrl(url).openConnection() as HttpURLConnection).apply {
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
        if (status !in 200..299) throw IOException("Drive backup HTTP " + status)
        val out = ByteArrayOutputStream()
        connection.inputStream.use { input ->
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

    /** Persist returned ID: never identify a folder by its name alone. */
    fun findOrCreateFolder(previousFolderId: String?): String {
        if (!previousFolderId.isNullOrBlank()) {
            val connection = connect(
                fileBase + "/" + encode(previousFolderId) +
                    "?fields=id,name,mimeType,trashed", "GET")
            try {
                val item = JSONObject(readSuccess(connection))
                require(item.getString("id") == previousFolderId &&
                    item.getString("mimeType") == "application/vnd.google-apps.folder" &&
                    !item.optBoolean("trashed")) { "Saved backup folder invalid" }
                return previousFolderId
            } finally { connection.disconnect() }
        }
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
            if (connection.responseCode !in 200..299)
                throw IOException("Upload initiation HTTP " + connection.responseCode)
            val session = connection.getHeaderField("Location")
                ?: throw IOException("Drive did not return resumable session")
            approvedUrl(session)
            return session
        } finally { connection.disconnect() }
    }

    private fun parseAck(connection: HttpURLConnection, total: Long): Long {
        val end = connection.getHeaderField("Range")
            ?.substringAfterLast('-')?.toLongOrNull()
        val confirmed = end?.plus(1) ?: 0L
        AudioBackupPlanner.remainingBytes(total, confirmed)
        return confirmed
    }

    /** Probe after interrupted/uncertain upload before retrying the same chunk. */
    fun queryConfirmedOffset(session: String, total: Long): ChunkAck {
        AudioBackupPlanner.remainingBytes(total, 0L)
        val connection = connect(session, "PUT")
        try {
            connection.setRequestProperty("Content-Range", "bytes */" + total)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(0)
            val code = connection.responseCode
            if (code == 308) return ChunkAck(parseAck(connection, total))
            if (code in 200..299)
                return ChunkAck(total, JSONObject(readSuccess(connection)).getString("id"))
            throw IOException("Upload session HTTP " + code)
        } finally { connection.disconnect() }
    }

    fun uploadChunk(
        session: String, chunk: ByteArray, offset: Long, total: Long,
    ): ChunkAck {
        require(chunk.isNotEmpty() && chunk.size <= 8 * 1024 * 1024)
        AudioBackupPlanner.remainingBytes(total, offset)
        require(offset + chunk.size <= total)
        require(offset % (256 * 1024) == 0L)
        require(chunk.size % (256 * 1024) == 0 || offset + chunk.size == total)
        val connection = connect(session, "PUT")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Content-Range",
                "bytes " + offset + "-" + (offset + chunk.size - 1) + "/" + total)
            connection.setFixedLengthStreamingMode(chunk.size)
            connection.outputStream.use { it.write(chunk) }
            val code = connection.responseCode
            if (code == 308) return ChunkAck(parseAck(connection, total))
            if (code in 200..299)
                return ChunkAck(total, JSONObject(readSuccess(connection)).getString("id"))
            throw IOException("Audio upload HTTP " + code)
        } finally { connection.disconnect() }
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
