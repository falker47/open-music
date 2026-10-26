package app.openmusic.core

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Portable, deterministic digest. It never includes device paths, MediaStore URIs or tokens. */
object CloudPlaylistDigest {
    fun of(playlist: PortablePlaylist): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun field(text: String?) {
            if (text == null) {
                md.update(byteArrayOf(0))
            } else {
                md.update(byteArrayOf(1))
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                md.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
                md.update(bytes)
            }
        }
        field(playlist.playlistId)
        field(playlist.name)
        field(playlist.createdAt)
        field(playlist.updatedAt)
        md.update(ByteBuffer.allocate(4).putInt(playlist.tracks.size).array())
        playlist.tracks.forEach { entry ->
            field(entry.entryId)
            field(entry.identity.sha256)
            field(entry.identity.filenameHint)
            field(entry.identity.metadata.artist)
            field(entry.identity.metadata.title)
            field(entry.identity.metadata.album)
            field(entry.identity.metadata.durationMs?.toString())
            field(entry.identity.metadata.source)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

data class CloudRevision(
    val playlistId: String,
    val revisionId: String,
    val parents: List<String>,
    val playlistDigest: String,
    val deleted: Boolean = false,
)

data class CloudCheckpoint(val revisionId: String, val playlistDigest: String)

enum class CloudSyncAction { NONE, UPLOAD, DOWNLOAD, ADOPT, CONFLICT }

/** Separate forks are never silently merged or discarded. */
data class CloudSyncDecision(
    val action: CloudSyncAction,
    val heads: List<CloudRevision>,
    val reason: String = "",
) {
    val head: CloudRevision? get() = heads.singleOrNull()
}

object CloudSyncPlanner {
    /** Every revision is append-only; a missing parent/duplicate ID is a conflict. */
    fun heads(revisions: List<CloudRevision>): List<CloudRevision>? {
        if (revisions.isEmpty()) return emptyList()
        val ids = revisions.map { it.revisionId }
        if (ids.size != ids.toSet().size) return null
        val byId = revisions.associateBy { it.revisionId }
        if (revisions.any { item ->
                item.revisionId.isBlank() || item.parents.distinct().size != item.parents.size ||
                    item.parents.any { parent -> parent == item.revisionId || parent !in byId } ||
                    item.parents.any { byId.getValue(it).playlistId != item.playlistId }
            }) return null
        val ancestors = revisions.flatMap { it.parents }.toSet()
        val current = revisions.filterNot { it.revisionId in ancestors }
        // A malformed cycle has no head. Missing history cannot be trusted.
        return current.takeIf { it.isNotEmpty() }
    }

    fun decide(
        playlistId: String,
        localDigest: String?,
        checkpoint: CloudCheckpoint?,
        revisions: List<CloudRevision>,
    ): CloudSyncDecision {
        if (revisions.any { it.playlistId != playlistId })
            return CloudSyncDecision(CloudSyncAction.CONFLICT, emptyList(), "Identità playlist incongruente")
        val heads = heads(revisions)
            ?: return CloudSyncDecision(CloudSyncAction.CONFLICT, emptyList(), "Cronologia remota non valida")
        if (heads.size > 1)
            return CloudSyncDecision(CloudSyncAction.CONFLICT, heads, "Modifiche concorrenti")
        val remote = heads.singleOrNull()
        // Explicit deletion/tombstone is not applied automatically.
        if (remote?.deleted == true)
            return CloudSyncDecision(CloudSyncAction.CONFLICT, heads, "Playlist cancellata sul cloud")
        if (checkpoint != null && revisions.none { it.revisionId == checkpoint.revisionId })
            return CloudSyncDecision(CloudSyncAction.CONFLICT, heads, "Revisioni cloud mancanti")
        if (checkpoint == null) {
            val action = when {
                localDigest == null && remote == null -> CloudSyncAction.NONE
                localDigest == null -> CloudSyncAction.DOWNLOAD
                remote == null -> CloudSyncAction.UPLOAD
                localDigest == remote.playlistDigest -> CloudSyncAction.ADOPT
                else -> CloudSyncAction.CONFLICT
            }
            return CloudSyncDecision(action, heads)
        }
        if (remote == null)
            return CloudSyncDecision(CloudSyncAction.CONFLICT, heads, "Cronologia remota mancante")
        if (localDigest == null)
            return CloudSyncDecision(CloudSyncAction.CONFLICT, heads, "Playlist locale assente")
        val localChanged = localDigest != checkpoint.playlistDigest
        val remoteChanged = remote.revisionId != checkpoint.revisionId
        val action = when {
            !remoteChanged && localChanged -> CloudSyncAction.UPLOAD
            remoteChanged && !localChanged -> CloudSyncAction.DOWNLOAD
            remoteChanged && localChanged -> if (localDigest == remote.playlistDigest)
                CloudSyncAction.ADOPT else CloudSyncAction.CONFLICT
            else -> CloudSyncAction.NONE
        }
        return CloudSyncDecision(action, heads)
    }
}
