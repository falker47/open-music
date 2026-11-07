package app.openmusic.core

import java.io.InputStream
import java.security.MessageDigest

/** Versioned, path-free description of user-selected audio content on visible Drive storage. */
data class AudioBackupFile(
    val name: String,
    val sha256: String,
    val sizeBytes: Long,
    val mimeType: String,
    val remoteFileId: String? = null,
)

data class AudioBackupManifest(
    val version: Int,
    val folderId: String,
    val accountMarker: String,
    val files: List<AudioBackupFile>,
)

enum class AudioBackupDisposition { UPLOAD, REMOTE_EXISTS, REUSE_LOCAL_CONTENT, RENAME_ON_UPLOAD }

data class AudioBackupDecision(
    val file: AudioBackupFile,
    val disposition: AudioBackupDisposition,
    val proposedRemoteName: String,
    val existingRemoteFileId: String? = null,
)

/** Planning and integrity checks are pure; no remote or local file can be deleted here. */
object AudioBackupPlanner {
    const val VERSION = 1
    private val sha = Regex("^[a-f0-9]{64}$")

    fun requireValid(
        manifest: AudioBackupManifest,
        expectedFolderId: String,
        expectedAccountMarker: String,
    ) {
        require(manifest.version == VERSION) { "Unsupported backup manifest version" }
        require(expectedFolderId.isNotBlank() && manifest.folderId == expectedFolderId) {
            "Backup folder mismatch"
        }
        require(expectedAccountMarker.isNotBlank() &&
            manifest.accountMarker == expectedAccountMarker) { "Drive account mismatch" }
        val ids = HashSet<String>()
        manifest.files.forEach { file ->
            checkFile(file)
            require(!file.remoteFileId.isNullOrBlank()) { "Remote file ID missing" }
            require(ids.add(file.remoteFileId)) { "Duplicate remote file ID" }
        }
    }

    private fun checkFile(file: AudioBackupFile) {
        require(file.name.isNotBlank() && file.name.length <= 240 &&
            '/' !in file.name && '\\' !in file.name) { "Unsafe audio filename" }
        require(sha.matches(file.sha256)) { "Invalid SHA-256" }
        require(file.sizeBytes >= 0) { "Negative audio size" }
        require(file.mimeType.isNotBlank() && file.mimeType.length <= 120) {
            "Invalid MIME type"
        }
    }

    fun planUpload(
        local: List<AudioBackupFile>,
        remote: AudioBackupManifest,
    ): List<AudioBackupDecision> {
        requireValid(remote, remote.folderId, remote.accountMarker)
        val byHash = remote.files.associateBy { it.sha256 }
        val names = remote.files.map { it.name }.toHashSet()
        val newHashes = HashSet<String>()
        return local.map { file ->
            checkFile(file)
            val existing = byHash[file.sha256]
            when {
                existing != null && existing.sizeBytes == file.sizeBytes ->
                    AudioBackupDecision(file, AudioBackupDisposition.REMOTE_EXISTS,
                        existing.name, existing.remoteFileId)
                !newHashes.add(file.sha256) ->
                    AudioBackupDecision(file, AudioBackupDisposition.REUSE_LOCAL_CONTENT,
                        file.name)
                file.name in names -> {
                    val suffix = " (" + file.sha256.take(12) + ")"
                    val baseName = file.name.take(240 - suffix.length)
                    var candidate = baseName + suffix
                    var sequence = 1
                    while (!names.add(candidate)) {
                        val extra = "-" + sequence++
                        candidate = file.name.take(240 - suffix.length - extra.length) +
                            suffix + extra
                    }
                    AudioBackupDecision(file, AudioBackupDisposition.RENAME_ON_UPLOAD,
                        candidate)
                }
                else -> {
                    names.add(file.name)
                    AudioBackupDecision(file, AudioBackupDisposition.UPLOAD, file.name)
                }
            }
        }
    }

    /**
     * Never overwrite a document with a matching filename: keep playable
     * audio extensions and insert a stable content hint before the extension.
     */
    fun safeRestoreName(file: AudioBackupFile, existingNames: Set<String>): String {
        checkFile(file)
        if (file.name !in existingNames) return file.name
        val dot = file.name.lastIndexOf('.')
        val extension = if (dot > 0 && file.name.length - dot <= 9)
            file.name.substring(dot) else ""
        val stem = file.name.dropLast(extension.length)
        val suffix = " (" + file.sha256.take(12) + ")"
        var attempt = 0
        while (attempt < 100_000) {
            val collision = if (attempt == 0) "" else "-" + attempt
            val prefixLength = 240 - suffix.length - collision.length - extension.length
            require(prefixLength > 0) { "Audio filename cannot be restored safely" }
            val candidate = stem.take(prefixLength) + suffix + collision + extension
            if (candidate !in existingNames) return candidate
            attempt++
        }
        throw IllegalArgumentException("Too many filename collisions")
    }

    /** A retry may start only at a known committed offset from the remote session. */
    fun remainingBytes(totalSize: Long, confirmedOffset: Long): Long {
        require(totalSize >= 0 && confirmedOffset in 0..totalSize) { "Invalid upload offset" }
        return totalSize - confirmedOffset
    }

    /** The caller must stage bytes and promote only after this verification succeeds. */
    fun verifyDownloaded(file: AudioBackupFile, input: InputStream): Boolean {
        checkFile(file)
        return input.use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var count = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                count += read
                if (count > file.sizeBytes) return@use false
                digest.update(buffer, 0, read)
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            count == file.sizeBytes && sha256 == file.sha256
        }
    }
}
