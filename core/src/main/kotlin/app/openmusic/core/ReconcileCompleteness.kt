package app.openmusic.core

/**
 * A partial inventory can finish early only when every playlist entry already has
 * a verified SHA-256 EXACT match. No MISSING or metadata-based match is final early.
 * All local audio remains available for later full scans, matching and rescans.
 */
object ReconcileCompleteness {
    fun allExact(
        playlists: List<PortablePlaylist>,
        resolved: Map<String, List<MatchResult>>,
    ): Boolean {
        if (playlists.none { it.tracks.isNotEmpty() }) return false
        return playlists.all { playlist ->
            val matches = resolved[playlist.playlistId] ?: return false
            if (matches.size != playlist.tracks.size) return false
            playlist.tracks.zip(matches).all { (entry, match) ->
                !entry.identity.sha256.isNullOrBlank() &&
                    match.entryId == entry.entryId &&
                    match.status == MatchStatus.EXACT &&
                    match.localUri != null
            }
        }
    }
}

/** When a later manual addition hashes an unscanned file, keep its verified copy. */
object VerifiedCopies {
    fun upsert(existing: List<LocalCopy>, verified: LocalCopy): List<LocalCopy> {
        val index = existing.indexOfFirst { it.uri == verified.uri }
        if (index < 0) return existing + verified
        return existing.toMutableList().also { it[index] = verified }
    }
}
