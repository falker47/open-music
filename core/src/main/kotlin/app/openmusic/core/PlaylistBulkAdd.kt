package app.openmusic.core

/**
 * A single transaction for direct multi-select playlist additions.
 * Media hashes are computed before calling this pure function.
 * Imported duplicates are never rewritten or removed.
 */
data class PlaylistBulkResult(val playlist: PortablePlaylist, val inserted: Int)

object PlaylistBulkAdd {
    fun toggle(selected: Set<String>, uri: String): Set<String> =
        if (uri in selected) selected - uri else selected + uri

    fun add(
        playlist: PortablePlaylist,
        candidates: List<PlaylistEntry>,
        updatedAt: String,
    ): PlaylistBulkResult {
        val knownHashes = playlist.tracks.mapNotNull { it.identity.sha256 }
            .map { it.lowercase() }.toMutableSet()
        val accepted = candidates.filter { entry ->
            val hash = entry.identity.sha256?.lowercase()
            hash == null || knownHashes.add(hash)
        }
        if (accepted.isEmpty()) return PlaylistBulkResult(playlist, 0)
        return PlaylistBulkResult(
            playlist.copy(updatedAt = updatedAt, tracks = playlist.tracks + accepted),
            accepted.size,
        )
    }
}
