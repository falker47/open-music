package app.openmusic

import android.content.Context
import app.openmusic.core.*
import java.io.IOException
import java.time.Instant
import java.util.UUID

internal enum class CloudConflictChoice {
    KEEP_LOCAL, USE_REMOTE, DUPLICATE_LOCAL, KEEP_DELETED,
}
internal data class CloudResolution(
    val choice: CloudConflictChoice,
    val observedHeads: Set<String>,
    val remoteRevisionId: String? = null,
)
internal data class CloudTrackPreview(val id: String, val name: String)
internal data class CloudVersionPreview(
    val name: String,
    val trackCount: Int,
    val updatedAt: String,
    val deleted: Boolean = false,
    val tracks: List<CloudTrackPreview> = emptyList(),
) {
    companion object {
        fun from(playlist: PortablePlaylist): CloudVersionPreview =
            CloudVersionPreview(
                playlist.name, playlist.tracks.size, playlist.updatedAt,
                tracks = playlist.tracks.map { track ->
                    CloudTrackPreview(track.entryId,
                        track.identity.metadata.title ?:
                            track.identity.filenameHint ?: "Brano senza titolo")
                },
            )
    }
}
internal data class CloudConflict(
    val playlistId: String,
    val title: String,
    val reason: String,
    val remoteHeads: List<CloudRevision>,
    val hasLocal: Boolean,
    val locallyDeleted: Boolean = false,
    val localPreview: CloudVersionPreview? = null,
    val remotePreviews: Map<String, CloudVersionPreview> = emptyMap(),
)
internal data class CloudSyncOutcome(
    val playlists: List<PortablePlaylist>,
    val conflicts: List<CloudConflict> = emptyList(),
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val adopted: Int = 0,
    val deleted: Int = 0,
)

/** User-triggered, append-only Drive metadata sync. Never transfers audio or local file paths. */
internal class CloudSyncCoordinator(context: Context) {
    private val local = PlaylistStorage(context)
    private val journalStore = CloudJournalStorage(context)

    private data class Remote(val fileId: String, val revision: CloudRevision)
    private data class Work(
        val playlistId: String,
        val action: CloudSyncAction,
        val localPlaylist: PortablePlaylist?,
        val localDeletion: DeletedPlaylist?,
        val heads: List<CloudRevision>,
        val chosenRemote: Remote? = null,
        val resolution: CloudResolution? = null,
    )

    fun disconnect() = journalStore.disconnect()

    fun sync(
        accessToken: String,
        resolutions: Map<String, CloudResolution> = emptyMap(),
    ): CloudSyncOutcome {
        val api = DriveAppDataGateway(accessToken)
        val state = local.loadState()
        val initial = state.playlists
        val localById = initial.associateBy { it.playlistId }
        require(localById.size == initial.size) { "ID playlist locali duplicati" }
        val deletedById = state.deleted.associateBy { it.playlistId }
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

        val remote = files.filter { it.name != CloudSnapshotCodec.BINDING_NAME }.map { file ->
            val rev = try { CloudSnapshotCodec.parse(file) }
            catch (failure: Exception) { throw IOException("Documento cloud non valido", failure) }
            Remote(file.id, rev)
        }
        val grouped = remote.groupBy { it.revision.playlistId }
        val ids = (localById.keys + deletedById.keys + grouped.keys +
            journal.checkpoints.keys).toSortedSet()
        val works = mutableListOf<Work>()
        val conflicts = mutableListOf<CloudConflict>()

        for (id in ids) {
            val own = localById[id]
            val deletion = deletedById[id]
            val history = grouped[id].orEmpty()
            val revisions = history.map { it.revision }
            val decision = CloudSyncPlanner.decide(
                id, own?.let(CloudPlaylistDigest::of), journal.checkpoints[id], revisions,
                locallyDeleted = deletion != null,
            )
            if (decision.action != CloudSyncAction.CONFLICT) {
                require(resolutions[id] == null) { "La revisione cloud è cambiata: riprova" }
                works.add(Work(id, decision.action, own, deletion, decision.heads,
                    decision.head?.let { head -> history.firstOrNull {
                        it.revision.revisionId == head.revisionId
                    } }))
                continue
            }
            val resolution = resolutions[id]
            if (resolution == null) {
                val previews = decision.heads.associate { head ->
                    val chosen = history.single { it.revision.revisionId == head.revisionId }
                    head.revisionId to if (head.deleted) CloudVersionPreview(
                        deletion?.name ?: own?.name ?: "Playlist", 0, "", deleted = true)
                    else {
                        val snapshot = CloudSnapshotCodec.decode(
                            api.download(chosen.fileId), head)
                        CloudVersionPreview.from(snapshot)
                    }
                }
                conflicts.add(CloudConflict(
                    id, own?.name ?: deletion?.name ?: previews.values.firstOrNull()?.name
                        ?: "Playlist $id",
                    decision.reason.ifEmpty { "Versioni locali e cloud differenti" },
                    decision.heads, own != null, deletion != null,
                    own?.let(CloudVersionPreview::from),
                    previews,
                ))
                continue
            }
            require(resolution.observedHeads == decision.heads.map { it.revisionId }.toSet()) {
                "Nuove modifiche nel cloud: rileggi il conflitto"
            }
            require(decision.heads.isNotEmpty()) {
                "Cronologia cloud incompleta: richiede verifica manuale"
            }
            val chosen = when (resolution.choice) {
                CloudConflictChoice.KEEP_LOCAL -> {
                    require(own != null) { "Versione locale non disponibile" }
                    null
                }
                CloudConflictChoice.KEEP_DELETED -> {
                    require(deletion != null) { "Eliminazione locale non disponibile" }
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
            works.add(Work(id, CloudSyncAction.CONFLICT, own, deletion,
                decision.heads, chosen, resolution))
        }
        if (conflicts.isNotEmpty()) return CloudSyncOutcome(initial, conflicts)
        require(resolutions.keys.all { key -> works.any { it.playlistId == key } }) {
            "Risoluzione non più applicabile"
        }

        // Pre-validate every incoming snapshot before any write.
        val incoming = mutableMapOf<String, PortablePlaylist>()
        works.forEach { task ->
            val useCloud = task.action == CloudSyncAction.DOWNLOAD ||
                task.resolution?.choice == CloudConflictChoice.USE_REMOTE ||
                task.resolution?.choice == CloudConflictChoice.DUPLICATE_LOCAL
            if (useCloud) {
                val chosen = task.chosenRemote ?: throw IOException("Versione cloud assente")
                if (!chosen.revision.deleted) incoming[task.playlistId] =
                    CloudSnapshotCodec.decode(api.download(chosen.fileId), chosen.revision)
            }
        }

        var uploaded = 0
        var downloaded = 0
        var adopted = 0
        var deletionCount = 0
        val checkpoints = journal.checkpoints.toMutableMap()
        val pendingMergeAcks = mutableMapOf<String, CloudCheckpoint>()

        // Append-only revisions. A returned success never removes any Drive file.
        works.forEach { task ->
            val choice = task.resolution?.choice
            val keepLocal = choice == CloudConflictChoice.KEEP_LOCAL
            val keepDeleted = choice == CloudConflictChoice.KEEP_DELETED
            val mergeRemote = task.heads.size > 1 && task.resolution != null &&
                !keepLocal && !keepDeleted
            if (task.action == CloudSyncAction.UPLOAD || keepLocal || keepDeleted ||
                mergeRemote) {
                val playlist = if (task.action == CloudSyncAction.UPLOAD || keepLocal)
                    task.localPlaylist ?: throw IOException("Playlist locale assente")
                else if (keepDeleted) null else incoming[task.playlistId]
                val digest = playlist?.let(CloudPlaylistDigest::of)
                    ?: task.heads.firstOrNull()?.playlistDigest
                    ?: throw IOException("Revisione da eliminare assente")
                val revision = CloudRevision(
                    task.playlistId, UUID.randomUUID().toString(),
                    task.heads.map { it.revisionId }, digest, deleted = keepDeleted ||
                        (mergeRemote && task.chosenRemote?.revision?.deleted == true),
                )
                val body = if (revision.deleted)
                    CloudSnapshotCodec.encodeDeleted(revision)
                else CloudSnapshotCodec.encode(
                    revision, playlist ?: throw IOException("Playlist assente"))
                api.upload(CloudSnapshotCodec.name(revision.revisionId),
                    CloudSnapshotCodec.metadata(revision).toString(), body)
                uploaded++
                val ack = CloudCheckpoint(revision.revisionId, revision.playlistDigest)
                if (mergeRemote) {
                    // Commit the local replacement atomically before acknowledging merge.
                    pendingMergeAcks[task.playlistId] = ack
                } else {
                    checkpoints[task.playlistId] = ack
                    journalStore.save(CloudJournal(journal.binding, checkpoints))
                }
            }
        }

        val merged = initial.toMutableList()
        val deletions = state.deleted.associateBy { it.playlistId }.toMutableMap()
        var saveLocal = false
        works.forEach { task ->
            val head = task.chosenRemote?.revision
            val chosenDelete = head?.deleted == true && (
                task.resolution?.choice == CloudConflictChoice.USE_REMOTE ||
                task.resolution?.choice == CloudConflictChoice.DUPLICATE_LOCAL)
            if (task.playlistId in incoming || chosenDelete) {
                if (task.resolution?.choice == CloudConflictChoice.DUPLICATE_LOCAL) {
                    val original = task.localPlaylist ?: throw IOException("Copia locale assente")
                    merged.add(original.copy(playlistId = UUID.randomUUID().toString(),
                        name = (original.name.take(135) + " (copia locale)").take(160),
                        updatedAt = Instant.now().toString()))
                }
                merged.removeAll { it.playlistId == task.playlistId }
                if (chosenDelete) {
                    deletions[task.playlistId] = DeletedPlaylist(task.playlistId,
                        task.localPlaylist?.name ?: task.localDeletion?.name ?: "Playlist",
                        Instant.now().toString())
                    deletionCount++
                } else {
                    merged.add(incoming.getValue(task.playlistId))
                    deletions.remove(task.playlistId)
                }
                downloaded++
                saveLocal = true
                if (!checkpoints.containsKey(task.playlistId) || task.heads.size <= 1) {
                    val chosen = head ?: error("Versione cloud assente")
                    checkpoints[task.playlistId] =
                        CloudCheckpoint(chosen.revisionId, chosen.playlistDigest)
                }
            } else if (task.action == CloudSyncAction.ADOPT) {
                val chosen = head ?: error("Revisione cloud assente")
                checkpoints[task.playlistId] =
                    CloudCheckpoint(chosen.revisionId, chosen.playlistDigest)
                adopted++
            } else if (task.action == CloudSyncAction.ADOPT_TOMBSTONE) {
                val chosen = head ?: error("Tombstone cloud assente")
                if (task.playlistId !in deletions) {
                    deletions[task.playlistId] = DeletedPlaylist(
                        task.playlistId, "Playlist eliminata", Instant.now().toString())
                    saveLocal = true
                }
                checkpoints[task.playlistId] =
                    CloudCheckpoint(chosen.revisionId, chosen.playlistDigest)
                adopted++
            }
        }
        if (saveLocal) local.saveState(PlaylistLocalState(merged, deletions.values.toList()))
        checkpoints.putAll(pendingMergeAcks)
        journalStore.save(CloudJournal(journal.binding, checkpoints))
        return CloudSyncOutcome(merged, uploaded = uploaded,
            downloaded = downloaded, adopted = adopted, deleted = deletionCount)
    }
}
