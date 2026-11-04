package app.openmusic.core

/**
 * Changes to a playlist are identified by entryId, never by an approximate title
 * or artist match. A verified EXACT match may be represented by multiple legacy
 * entries with the same local URI; no other entries are implicitly removed.
 */
data class PlaylistPendingChanges(
    val addedUris: Set<String>,
    val removedEntryIds: Set<String>,
    val removedUris: Set<String>,
) {
    val hasChanges: Boolean get() = addedUris.isNotEmpty() || removedEntryIds.isNotEmpty()
    val removalCount: Int get() = removedEntryIds.size
}

data class PlaylistContentEditResult(
    val playlist: PortablePlaylist,
    val added: Int,
    val removed: Int,
)

object PlaylistContentEditor {
    fun baseline(matches: List<MatchResult>, presentUris: Set<String>): Map<String, Set<String>> =
        matches.asSequence()
            .filter { it.status == MatchStatus.EXACT && it.localUri in presentUris }
            .filter { !it.localUri.isNullOrBlank() }
            .groupBy { it.localUri!! }
            .mapValues { (_, entries) -> entries.map { it.entryId }.toSet() }

    /** The UI selection survives query changes; this diff is independent of filters. */
    fun pending(
        baseline: Map<String, Set<String>>,
        selectedUris: Set<String>,
    ): PlaylistPendingChanges {
        val removedUris = baseline.keys - selectedUris
        return PlaylistPendingChanges(
            addedUris = selectedUris - baseline.keys,
            removedEntryIds = removedUris.flatMap { baseline[it].orEmpty() }.toSet(),
            removedUris = removedUris,
        )
    }

    /**
     * One immutable state transition, persisted once by the caller.
     * Preverified additions must carry SHA-256; existing missing, ambiguous,
     * unordered/legacy duplicates and entries outside removedEntryIds survive.
     */
    fun apply(
        playlist: PortablePlaylist,
        removedEntryIds: Set<String>,
        additions: List<PlaylistEntry>,
        updatedAt: String,
    ): PlaylistContentEditResult {
        val survivors = playlist.tracks.filterNot { it.entryId in removedEntryIds }
        val removedCount = playlist.tracks.size - survivors.size
        val knownHashes = survivors.mapNotNull { it.identity.sha256?.lowercase() }.toMutableSet()
        val accepted = additions.filter { entry ->
            val hash = entry.identity.sha256
            require(!hash.isNullOrBlank()) { "SHA-256 richiesto per i nuovi brani" }
            knownHashes.add(hash.lowercase())
        }
        if (removedCount == 0 && accepted.isEmpty()) {
            return PlaylistContentEditResult(playlist, 0, 0)
        }
        return PlaylistContentEditResult(
            playlist.copy(
                updatedAt = updatedAt,
                tracks = survivors + accepted,
            ),
            accepted.size, removedCount,
        )
    }
}

object PlaylistNameEditor {
    fun rename(
        playlist: PortablePlaylist,
        allPlaylists: List<PortablePlaylist>,
        proposal: String,
        updatedAt: String,
    ): PortablePlaylist {
        val name = proposal.trim()
        require(name.isNotEmpty() && name.length <= 160) {
            "Inserisci un nome tra 1 e 160 caratteri"
        }
        require(allPlaylists.none {
            it.playlistId != playlist.playlistId &&
                it.name.trim().equals(name, ignoreCase = true)
        }) { "Esiste già una playlist con questo nome" }
        return if (name == playlist.name) playlist
        else playlist.copy(name = name, updatedAt = updatedAt)
    }
}
