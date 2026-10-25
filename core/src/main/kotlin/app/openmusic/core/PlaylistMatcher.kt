package app.openmusic.core

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative cross-device identity resolution.
 * A changed SHA-256 cannot be silently substituted based only on tags.
 */
object PlaylistMatcher {
    private fun normalize(value: String?): String =
        Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    private fun tagsMatch(original: TrackMetadata, candidate: TrackMetadata): Boolean {
        if (original.source != "tags" || candidate.source != "tags") return false
        if (normalize(original.artist).isEmpty() || normalize(original.title).isEmpty()) return false
        if (normalize(original.artist) != normalize(candidate.artist) ||
            normalize(original.title) != normalize(candidate.title)) return false
        if (!original.album.isNullOrBlank() && !candidate.album.isNullOrBlank() &&
            normalize(original.album) != normalize(candidate.album)) return false
        val a = original.durationMs ?: return false
        val b = candidate.durationMs ?: return false
        return a >= 0 && b >= 0 && kotlin.math.abs(a - b) <= 2_000L
    }

    fun resolve(playlist: PortablePlaylist, local: List<LocalCopy>): List<MatchResult> {
        val byHash = local.filter { !it.identity.sha256.isNullOrBlank() }
            .groupBy { it.identity.sha256 }
        return playlist.tracks.map { entry ->
            val old = entry.identity
            val exact = old.sha256?.let { byHash[it] }.orEmpty()
            if (exact.isNotEmpty()) {
                // Duplicate files with identical bytes are equivalent copies.
                MatchResult(entry.entryId, MatchStatus.EXACT, exact.minBy { it.uri }.uri)
            } else {
                val candidates = local.filter { tagsMatch(old.metadata, it.identity.metadata) }
                // An un-hashed copy cannot safely be treated as an identical duplicate.
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
}
