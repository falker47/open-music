package app.openmusic

import android.content.Context
import app.openmusic.core.*
import java.io.IOException
import java.time.Instant
import java.util.UUID

internal enum class CloudConflictChoice { KEEP_LOCAL, USE_REMOTE, DUPLICATE_LOCAL }
internal data class CloudResolution(
    val choice: CloudConflictChoice,
    val observedHeads: Set<String>,
    val remoteRevisionId: String? = null,
)
internal data class CloudConflict(
    val playlistId: String,
    val title: String,
    val reason: String,
    val remoteHeads: List<CloudRevision>,
    val hasLocal: Boolean,
)
internal data class CloudSyncOutcome(
    val playlists: List<PortablePlaylist>,
    val conflicts: List<CloudConflict> = emptyList(),
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val adopted: Int = 0,
)

/** User-initiated metadata sync only: no background service and no audio transfer. */
internal class CloudSyncCoordinator(context: Context) {
    private val local = PlaylistStorage(context)
    private val journalStore = CloudJournalStorage(context)

    private data class Remote(val fileId: String, val revision: CloudRevision)
    private data class Work(
        val playlistId: String, val action: CloudSyncAction,
        val localPlaylist: PortablePlaylist?, val heads: List<CloudRevision>,
        val chosenRemote: Remote? = null,
        val resolution: CloudResolution? = null,
    )

    fun disconnect() = journalStore.disconnect()

    fun sync(
        accessToken: String,
        resolutions: Map<String, CloudResolution> = emptyMap(),
    ): CloudSyncOutcome {
        val api = DriveAppDataGateway(accessToken)
        val initial = local.load()
        val localById = initial.associateBy { it.playlistId }
        require(localById.size == initial.size) { "ID playlist locali duplicati" }
        var journal = journalStore.load()
        var files = api.listFiles()

        val bindingFiles = files.filter { it.name == CloudSnapshotCodec.BINDING_NAME }
        if (bindingFiles.size > 1)
            throw IOException("Più identità cloud: sincronizzazione bloccata")
        val remoteBinding = if (bindingFiles.size == 1)
            CloudSnapshotCodec.decodeBinding(api.download(bindingFiles.single().id))
        else null
        if (journal.binding != null && remoteBinding != journal.binding)
            throw IOException("Account Google Drive diverso o dati cloud mancanti. Scollega prima l'account.")
        if (remoteBinding == null) {
            if (files.isNotEmpty())
                throw IOException("Dati cloud senza identità: sincronizzazione bloccata")
            val id = UUID.randomUUID().toString()
            api.upload(CloudSnapshotCodec.BINDING_NAME, "Open Music sync binding",
                CloudSnapshotCodec.binding(id))
            files = api.listFiles()
            val identity = files.filter { it.name == CloudSnapshotCodec.BINDING_NAME }
            if (identity.size != 1 ||
                CloudSnapshotCodec.decodeBinding(api.download(identity.single().id)) != id)
                throw IOException("Impossibile confermare identità cloud")
            journal = CloudJournal(id, journal.checkpoints)
        } else {
            journal = CloudJournal(remoteBinding, journal.checkpoints)
        }
        journalStore.save(journal)

        // All remote records contain revision descriptors, but no audio or device paths.
        val remote = files.filter { it.name != CloudSnapshotCodec.BINDING_NAME }.map { file ->
            val rev = try { CloudSnapshotCodec.parse(file) }
            catch (failure: Exception) { throw IOException("Documento cloud non valido", failure) }
            Remote(file.id, rev)
        }
        val grouped = remote.groupBy { it.revision.playlistId }
        val ids = (localById.keys + grouped.keys + journal.checkpoints.keys).toSortedSet()
        val works = mutableListOf<Work>()
        val conflicts = mutableListOf<CloudConflict>()

        for (id in ids) {
            val own = localById[id]
            val history = grouped[id].orEmpty()
            val revisions = history.map { it.revision }
            val decision = CloudSyncPlanner.decide(
                id, own?.let(CloudPlaylistDigest::of), journal.checkpoints[id], revisions,
            )
            if (decision.action != CloudSyncAction.CONFLICT) {
                require(resolutions[id] == null) { "La revisione cloud è cambiata: riprova" }
                works.add(Work(id, decision.action, own, decision.heads,
                    decision.head?.let { head -> history.firstOrNull {
                        it.revision.revisionId == head.revisionId
                    } }))
                continue
            }
            val resolution = resolutions[id]
            if (resolution == null) {
                conflicts.add(CloudConflict(id, own?.name ?: "Playlist $id",
                    decision.reason.ifEmpty { "Versioni locali e cloud differenti" },
                    decision.heads, own != null))
                continue
            }
            require(resolution.observedHeads == decision.heads.map { it.revisionId }.toSet()) {
                "Nuove modifiche nel cloud: rileggi il conflitto"
            }
            require(decision.heads.isNotEmpty()) {
                "La cronologia cloud è incompleta e richiede una verifica manuale"
            }
            val choice = when (resolution.choice) {
                CloudConflictChoice.KEEP_LOCAL -> {
                    require(own != null) { "Versione locale non disponibile" }
                    null
                }
                CloudConflictChoice.USE_REMOTE, CloudConflictChoice.DUPLICATE_LOCAL -> {
                    require(resolution.choice != CloudConflictChoice.DUPLICATE_LOCAL || own != null) {
                        "Versione locale da duplicare non disponibile"
                    }
                    val selected = resolution.remoteRevisionId
                    require(!selected.isNullOrBlank() &&
                        decision.heads.any { it.revisionId == selected }) {
                        "Seleziona una versione cloud"
                    }
                    history.single { it.revision.revisionId == selected }
                }
            }
            works.add(Work(id, CloudSyncAction.CONFLICT, own, decision.heads, choice, resolution))
        }
        if (conflicts.isNotEmpty()) return CloudSyncOutcome(initial, conflicts)
        require(resolutions.keys.all { key -> works.any { it.playlistId == key } }) {
            "Risoluzione non più applicabile"
        }

        // Validate ALL incoming snapshots before any playlist write or remote revision upload.
        val incoming = mutableMapOf<String, PortablePlaylist>()
        works.forEach { task ->
            val useCloud = task.action == CloudSyncAction.DOWNLOAD ||
                task.resolution?.choice == CloudConflictChoice.USE_REMOTE ||
                task.resolution?.choice == CloudConflictChoice.DUPLICATE_LOCAL
            if (useCloud) {
                val chosen = task.chosenRemote ?: throw IOException("Versione cloud assente")
                incoming[task.playlistId] = CloudSnapshotCodec.decode(
                    api.download(chosen.fileId), chosen.revision)
            }
        }

        var uploaded = 0
        var downloaded = 0
        var adopted = 0
        val checkpoints = journal.checkpoints.toMutableMap()
        // A remote conflict merge is not acknowledged until its chosen
        // playlist has been durably saved. Otherwise, a crash after upload
        // but before local.save() makes the stale local copy look like a
        // new edit and silently re-uploads it on the next manual sync.
        val pendingMergeAcks = mutableMapOf<String, CloudCheckpoint>()

        // Append-only uploads. Ambiguous request outcomes converge by digest on retry.
        works.forEach { task ->
            val keepLocal = task.resolution?.choice == CloudConflictChoice.KEEP_LOCAL
            val mergeRemote = task.heads.size > 1 && task.resolution != null &&
                task.resolution.choice != CloudConflictChoice.KEEP_LOCAL
            if (task.action == CloudSyncAction.UPLOAD || keepLocal || mergeRemote) {
                val playlist = if (keepLocal || task.action == CloudSyncAction.UPLOAD)
                    task.localPlaylist ?: throw IOException("Playlist locale assente")
                else incoming.getValue(task.playlistId)
                val revision = CloudRevision(
                    task.playlistId, UUID.randomUUID().toString(),
                    task.heads.map { it.revisionId },
                    CloudPlaylistDigest.of(playlist),
                )
                api.upload(CloudSnapshotCodec.name(revision.revisionId),
                    CloudSnapshotCodec.metadata(revision).toString(),
                    CloudSnapshotCodec.encode(revision, playlist))
                uploaded++
                val ack = CloudCheckpoint(revision.revisionId, revision.playlistDigest)
                if (mergeRemote) {
                    // Remote snapshot exists, but local replacement is still
                    // pending. A retry with the old checkpoint downloads the
                    // merged head instead of uploading stale local state.
                    pendingMergeAcks[task.playlistId] = ack
                } else {
                    checkpoints[task.playlistId] = ack
                    journalStore.save(CloudJournal(journal.binding, checkpoints))
                }
            }
        }

        val merged = initial.toMutableList()
        works.forEach { task ->
            if (task.playlistId in incoming) {
                val cloudCopy = incoming.getValue(task.playlistId)
                if (task.resolution?.choice == CloudConflictChoice.DUPLICATE_LOCAL) {
                    val original = task.localPlaylist ?: throw IOException("Copia locale assente")
                    merged.add(original.copy(
                        playlistId = UUID.randomUUID().toString(),
                        name = (original.name.take(135) + " (copia locale)").take(160),
                        updatedAt = Instant.now().toString(),
                    ))
                }
                merged.removeAll { it.playlistId == task.playlistId }
                merged.add(cloudCopy)
                downloaded++
                // Multi-head fork is resolved by the newly uploaded revision above.
                if (!checkpoints.containsKey(task.playlistId) ||
                    task.heads.size <= 1) {
                    val head = task.chosenRemote?.revision ?: error("Versione cloud assente")
                    checkpoints[task.playlistId] =
                        CloudCheckpoint(head.revisionId, head.playlistDigest)
                }
            } else if (task.action == CloudSyncAction.ADOPT) {
                val head = task.chosenRemote?.revision ?: error("Revisione cloud assente")
                checkpoints[task.playlistId] =
                    CloudCheckpoint(head.revisionId, head.playlistDigest)
                adopted++
            }
        }
        if (downloaded > 0) local.save(merged)
        // Only after the atomic local write may the new remote merge head
        // become the acknowledged baseline for subsequent local edits.
        checkpoints.putAll(pendingMergeAcks)
        journalStore.save(CloudJournal(journal.binding, checkpoints))
        return CloudSyncOutcome(merged, uploaded = uploaded,
            downloaded = downloaded, adopted = adopted)
    }
}
