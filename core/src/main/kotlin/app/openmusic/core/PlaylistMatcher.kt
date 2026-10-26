package app.openmusic.core

import java.text.Normalizer
import java.util.Locale

/** Conservative SHA-256 identity; a metadata match never substitutes a differing hash. */
object PlaylistMatcher {
    private fun normalize(value: String?): String =
        Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    private data class Tags(val artist: String, val title: String)
    private fun tagKey(meta: TrackMetadata): Tags? {
        if (meta.source != "tags") return null
        val artist = normalize(meta.artist)
        val title = normalize(meta.title)
        return if (artist.isNotEmpty() && title.isNotEmpty()) Tags(artist, title) else null
    }

    private fun tagsMatch(original: TrackMetadata, candidate: TrackMetadata): Boolean {
        if (tagKey(original) == null || tagKey(original) != tagKey(candidate)) return false
        if (!original.album.isNullOrBlank() && !candidate.album.isNullOrBlank() &&
            normalize(original.album) != normalize(candidate.album)) return false
        val a = original.durationMs ?: return false
        val b = candidate.durationMs ?: return false
        return a >= 0 && b >= 0 && kotlin.math.abs(a - b) <= 2_000L
    }

    /** Build the SHA and tag indexes once for an entire reconciliation, not per entry. */
    private class Index(local: List<LocalCopy>) {
        private val byHash = local.filter { !it.identity.sha256.isNullOrBlank() }
            .groupBy { it.identity.sha256 }
        private val byTags = local.mapNotNull { copy ->
            tagKey(copy.identity.metadata)?.let { it to copy }
        }.groupBy({ it.first }, { it.second })

        fun resolve(playlist: PortablePlaylist): List<MatchResult> = playlist.tracks.map { entry ->
            val old = entry.identity
            val exact = old.sha256?.let { byHash[it] }.orEmpty()
            if (exact.isNotEmpty()) {
                // Identical bytes at multiple locations are interchangeable copies.
                MatchResult(entry.entryId, MatchStatus.EXACT, exact.minBy { it.uri }.uri)
            } else {
                val candidates = tagKey(old.metadata)?.let { byTags[it] }.orEmpty()
                    .filter { tagsMatch(old.metadata, it.identity.metadata) }
                val distinct = candidates.groupBy { it.identity.sha256 ?: "uri:" + it.uri }
                when {
                    distinct.size > 1 -> MatchResult(
                        entry.entryId, MatchStatus.AMBIGUOUS,
                        candidates = candidates.map { it.uri }.distinct().sorted(),
                    )
                    candidates.isEmpty() -> MatchResult(entry.entryId, MatchStatus.MISSING)
                    !old.sha256.isNullOrBlank() -> MatchResult(
                        entry.entryId, MatchStatus.REVIEW,
                        candidates = candidates.map { it.uri }.distinct().sorted(),
                    )
                    else -> MatchResult(
                        entry.entryId, MatchStatus.METADATA, candidates.minBy { it.uri }.uri,
                    )
                }
            }
        }
    }

    fun resolve(playlist: PortablePlaylist, local: List<LocalCopy>): List<MatchResult> =
        Index(local).resolve(playlist)

    fun resolveAll(
        playlists: List<PortablePlaylist>,
        local: List<LocalCopy>,
    ): Map<String, List<MatchResult>> {
        val index = Index(local)
        return playlists.associate { it.playlistId to index.resolve(it) }
    }
}
