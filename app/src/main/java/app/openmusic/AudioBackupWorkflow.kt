package app.openmusic

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import app.openmusic.core.AudioBackupDisposition
import app.openmusic.core.AudioBackupFile
import app.openmusic.core.AudioBackupFilename
import app.openmusic.core.AudioBackupManifest
import app.openmusic.core.AudioBackupPlanner
import app.openmusic.core.AudioFingerprintReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Manual, user-driven backup/restore. Never starts on app launch or normal
 * playlist metadata synchronization. Only SAF-selected audio is transferred.
 */
internal class AudioBackupWorkflow(
    private val context: Context,
    private val gateway: DriveAudioBackupGateway,
) {
    data class BackupSummary(val uploaded: Int, val deduplicated: Int)
    data class RestoreSummary(val downloaded: Int, val alreadyPresent: Int)
    private val preferences = context.getSharedPreferences(
        "open-music-audio-backup-v1", Context.MODE_PRIVATE)

    private fun validateNetwork(allowMetered: Boolean) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager
        val active = manager.activeNetwork ?: throw IOException("Connessione non disponibile")
        val caps = manager.getNetworkCapabilities(active)
            ?: throw IOException("Connessione non disponibile")
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            throw IOException("Nessuna connessione internet")
        if (manager.isActiveNetworkMetered && !allowMetered)
            throw IOException("Rete a consumo: occorre il consenso esplicito")
    }

    /** Never silently rebind a previous folder to another Google account. */
    private fun folder(create: Boolean): Pair<String, String>? {
        val account = gateway.accountMarker()
        val previousAccount = preferences.getString("accountMarker", null)
        if (previousAccount != null && previousAccount != account)
            throw IOException("Account Drive diverso: backup non modificato")
        val previousFolder = preferences.getString("folderId", null)
        // A second device has no local folder ID, but must discover the existing
        // app-created folder without accidentally creating an empty backup.
        val folderId = if (create) gateway.findOrCreateFolder(previousFolder)
            else gateway.findExistingFolder(previousFolder) ?: return null
        if (!preferences.edit()
                .putString("accountMarker", account)
                .putString("folderId", folderId)
                .commit()) throw IOException("Impossibile salvare l'identità del backup")
        return folderId to account
    }

    /** Read a snapshot of remote file inventory, with no user audio download. */
    suspend fun available(): List<AudioBackupFile> = withContext(Dispatchers.IO) {
        validateNetwork(allowMetered = true) // listing only, no private media bytes
        val (folderId, account) = folder(create = false) ?: return@withContext emptyList()
        val files = gateway.listBackedUpFiles(folderId)
        AudioBackupPlanner.requireValid(
            AudioBackupManifest(1, folderId, account, files), folderId, account)
        files
    }

    private fun name(uri: Uri): String {
        val cursor = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?: throw IOException("Nome file audio non disponibile")
        cursor.use {
            if (!it.moveToFirst()) throw IOException("File audio non disponibile")
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) throw IOException("Nome file audio assente")
            return AudioBackupFilename.sanitize(it.getString(index) ?: "")
        }
    }

    private fun inspect(uri: Uri): AudioBackupFile {
        require(uri.scheme == "content") { "Selezionare file da Android SAF" }
        val mime = context.contentResolver.getType(uri).orEmpty()
        require(mime.startsWith("audio/")) { "Selezionare un file musicale" }
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Audio non leggibile")
        val fingerprint = AudioFingerprintReader.inspect(stream)
        require(fingerprint.sizeBytes > 0) { "Audio vuoto" }
        return AudioBackupFile(
            name = name(uri),
            sha256 = fingerprint.sha256,
            sizeBytes = fingerprint.sizeBytes,
            mimeType = mime,
        )
    }

    private fun readRange(uri: Uri, offset: Long, count: Int): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("File originale non disponibile")
        return input.use { stream ->
            var skipped = 0L
            while (skipped < offset) {
                val value = stream.skip(offset - skipped)
                if (value > 0) skipped += value
                else {
                    if (stream.read() < 0) throw IOException("Audio modificato durante upload")
                    skipped++
                }
            }
            val buffer = ByteArray(count)
            var done = 0
            while (done < count) {
                val n = stream.read(buffer, done, count - done)
                if (n < 0) throw IOException("Audio troncato durante upload")
                if (n > 0) done += n
            }
            buffer
        }
    }

    private suspend fun upload(uri: Uri, file: AudioBackupFile, folderId: String): String {
        val session = gateway.startResumableUpload(folderId, file)
        var offset = 0L
        var retries = 0
        try {
            while (offset < file.sizeBytes) {
                currentCoroutineContext().ensureActive()
                val size = minOf(1024L * 1024L, file.sizeBytes - offset).toInt()
                val chunk = readRange(uri, offset, size)
                val ack = try {
                    gateway.uploadChunk(session, chunk, offset, file.sizeBytes)
                } catch (error: IOException) {
                    if (++retries > 2) throw error
                    gateway.queryConfirmedOffset(session, file.sizeBytes)
                }
                if (ack.remoteFileId != null) return ack.remoteFileId
                if (ack.confirmedOffset < offset ||
                    ack.confirmedOffset > offset + chunk.size)
                    throw IOException("Drive ha confermato un offset non valido")
                if (ack.confirmedOffset == offset) {
                    if (++retries > 2)
                        throw IOException("Drive non ha accettato i dati di upload")
                    continue
                }
                offset = ack.confirmedOffset
                retries = 0
            }
            // Completion needs the remote ID, never just a locally computed length.
            val finalAck = gateway.queryConfirmedOffset(session, file.sizeBytes)
            return finalAck.remoteFileId
                ?: throw IOException("ID file remoto non confermato")
        } catch (cancel: CancellationException) {
            // Abort only the open session; never delete completed remote audio files.
            runCatching { gateway.cancelUpload(session) }
            throw cancel
        }
    }

    /** Explicitly selected files retain the original 40-file safety limit. */
    suspend fun backupSelected(
        selected: List<Uri>,
        allowMetered: Boolean,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): BackupSummary = backupFiles(selected, allowMetered, 40, onProgress)

    /** Music-library transfer is a separate, explicitly confirmed operation. */
    suspend fun backupLibrary(
        localMusic: List<Uri>,
        allowMetered: Boolean,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): BackupSummary = backupFiles(localMusic, allowMetered, 5000, onProgress)

    private suspend fun backupFiles(
        selected: List<Uri>,
        allowMetered: Boolean,
        maxFiles: Int,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): BackupSummary = withContext(Dispatchers.IO) {
        val uris = selected.distinct()
        require(uris.size in 1..maxFiles) {
            "Operazione limitata a " + maxFiles + " file audio"
        }
        validateNetwork(allowMetered)
        val (folderId, account) = folder(create = true)
            ?: throw IOException("Cartella backup non disponibile")
        val remote = gateway.listBackedUpFiles(folderId)
        val local = uris.map { uri ->
            currentCoroutineContext().ensureActive()
            inspect(uri)
        }
        val plans = AudioBackupPlanner.planUpload(
            local, AudioBackupManifest(1, folderId, account, remote))
        var added = 0
        var skipped = 0
        plans.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            when (item.disposition) {
                AudioBackupDisposition.REMOTE_EXISTS,
                AudioBackupDisposition.REUSE_LOCAL_CONTENT -> skipped++
                AudioBackupDisposition.UPLOAD,
                AudioBackupDisposition.RENAME_ON_UPLOAD -> {
                    val payload = item.file.copy(name = item.proposedRemoteName)
                    upload(uris[index], payload, folderId)
                    added++
                }
            }
            withContext(Dispatchers.Main) { onProgress(index + 1, plans.size) }
        }
        BackupSummary(added, skipped)
    }

    /**
     * The remote ID must exist in our current listed inventory. Then download
     * and verify in disposable cache before a SAF destination is created.
     */
    suspend fun stageRestore(file: AudioBackupFile): File = withContext(Dispatchers.IO) {
        validateNetwork(allowMetered = true) // user explicitly selected this file
        val (folderId, account) = folder(create = false)
            ?: throw IOException("Nessun backup Drive configurato")
        val inventory = gateway.listBackedUpFiles(folderId)
        AudioBackupPlanner.requireValid(
            AudioBackupManifest(1, folderId, account, inventory), folderId, account)
        require(inventory.any { it.remoteFileId == file.remoteFileId &&
            it.sha256 == file.sha256 && it.sizeBytes == file.sizeBytes }) {
            "Il file remoto è cambiato"
        }
        val temp = gateway.stageVerifiedDownload(context, file)
        try {
            currentCoroutineContext().ensureActive()
            temp
        } catch (cancel: CancellationException) {
            temp.delete()
            throw cancel
        }
    }

    /** Caller has already received an explicit destination Uri from SAF. */
    suspend fun restoreToDocument(
        file: AudioBackupFile, verifiedTemp: File, destination: Uri,
    ): Unit = withContext(Dispatchers.IO) {
        require(destination.scheme == "content")
        try {
            if (!AudioBackupPlanner.verifyDownloaded(file, verifiedTemp.inputStream()))
                throw IOException("Backup temporaneo non integro")
            val target = context.contentResolver.openOutputStream(destination, "w")
                ?: throw IOException("Destinazione non scrivibile")
            target.use { output -> verifiedTemp.inputStream().use { it.copyTo(output) } }
            val check = context.contentResolver.openInputStream(destination)
                ?: throw IOException("Impossibile verificare il ripristino")
            if (!AudioBackupPlanner.verifyDownloaded(file, check))
                throw IOException("Verifica SHA-256 del file ripristinato fallita")
        } finally { verifiedTemp.delete() }
    }
    /**
     * Restore all selected Drive records into a user-chosen SAF directory.
     * Existing user documents are never overwritten or deleted. Every new
     * document is verified before it can be counted as restored.
     */
    suspend fun restoreLibrary(
        selected: List<AudioBackupFile>,
        destinationTree: Uri,
        allowMetered: Boolean,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): RestoreSummary = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty() && selected.size <= 5000) {
            "Ripristino limitato a 5000 brani per operazione"
        }
        require(destinationTree.scheme == "content") { "Scegli una cartella con Android" }
        validateNetwork(allowMetered)
        val (folderId, account) = folder(create = false)
            ?: throw IOException("Nessun backup Drive disponibile")
        val remote = gateway.listBackedUpFiles(folderId)
        AudioBackupPlanner.requireValid(
            AudioBackupManifest(1, folderId, account, remote), folderId, account)
        val byId = remote.associateBy { it.remoteFileId }
        selected.forEach { file ->
            val current = byId[file.remoteFileId]
            require(current != null && current.sha256 == file.sha256 &&
                current.sizeBytes == file.sizeBytes && current.name == file.name) {
                "Il contenuto Drive è cambiato, aggiorna l'elenco"
            }
        }
        val target = DocumentFile.fromTreeUri(context, destinationTree)
            ?: throw IOException("Cartella destinazione non accessibile")
        require(target.isDirectory && target.canWrite()) {
            "Cartella selezionata non scrivibile"
        }
        val existing = target.listFiles().filter { it.isFile && !it.name.isNullOrBlank() }
            .associateBy { it.name!! }.toMutableMap()
        val takenNames = existing.keys.toMutableSet()
        val unique = selected.distinctBy { it.sha256 }
        var restored = 0
        var skipped = 0
        unique.forEachIndexed { index, file ->
            currentCoroutineContext().ensureActive()
            val sameName = existing[file.name]
            val exact = sameName != null && runCatching {
                val source = context.contentResolver.openInputStream(sameName.uri)
                    ?: return@runCatching false
                AudioBackupPlanner.verifyDownloaded(file, source)
            }.getOrDefault(false)
            if (exact) {
                skipped++
            } else {
                val name = AudioBackupPlanner.safeRestoreName(file, takenNames)
                // Stage BEFORE creating a document, so a network failure leaves
                // the user's destination untouched.
                val temporary = gateway.stageVerifiedDownload(context, file)
                var created: DocumentFile? = null
                try {
                    currentCoroutineContext().ensureActive()
                    val newDoc = target.createFile(file.mimeType, name)
                        ?: throw IOException("Impossibile creare " + name)
                    created = newDoc
                    restoreToDocument(file, temporary, newDoc.uri)
                    val actualName = newDoc.name ?: name
                    takenNames.add(actualName)
                    existing[actualName] = created
                    restored++
                } catch (failure: Exception) {
                    created?.delete() // only the new, incomplete document
                    throw failure
                } finally {
                    temporary.delete()
                }
            }
            withContext(Dispatchers.Main) { onProgress(index + 1, unique.size) }
        }
        RestoreSummary(restored, skipped)
    }

}
