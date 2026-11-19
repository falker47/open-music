package app.openmusic.core

import java.text.Normalizer
import java.util.Locale

/** Hints affect processing ORDER only, never whether a file is considered an exact match. */
object ReconcilePriority {
    data class Hints(
        val filenames: Set<String>,
        val titles: Set<String>,
        val artistTitles: Set<String>,
    )

    private fun normalize(value: String?): String = Normalizer.normalize(
        value.orEmpty(), Normalizer.Form.NFKC,
    ).lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    private fun combined(artist: String?, title: String?): String? {
        val a = normalize(artist)
        val t = normalize(title)
        return if (a.isNotEmpty() && t.isNotEmpty()) "$a|$t" else null
    }

    fun hints(playlists: List<PortablePlaylist>): Hints {
        val tracks = playlists.flatMap { it.tracks }
        return Hints(
            tracks.mapNotNull { it.identity.filenameHint?.let(::normalize) }
                .filter(String::isNotEmpty).toSet(),
            tracks.map { normalize(it.identity.metadata.title) }.filter(String::isNotEmpty).toSet(),
            tracks.mapNotNull { combined(it.identity.metadata.artist, it.identity.metadata.title) }
                .toSet(),
        )
    }

    fun score(candidate: TrackIdentity, hints: Hints): Int {
        if (normalize(candidate.filenameHint) in hints.filenames) return 3
        val pair = combined(candidate.metadata.artist, candidate.metadata.title)
        if (pair != null && pair in hints.artistTitles) return 2
        if (normalize(candidate.metadata.title) in hints.titles) return 1
        return 0
    }
}
