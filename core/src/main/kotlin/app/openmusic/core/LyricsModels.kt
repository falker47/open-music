package app.openmusic.core

import java.util.Locale
import kotlin.math.abs

data class LyricsTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Int? = null,
)
data class LyricsCandidate(
    val title: String, val artist: String, val album: String?,
    val durationSeconds: Int?, val instrumental: Boolean,
    val plainLyrics: String?, val syncedLyrics: String?,
)
enum class LyricsMatchKind { FOUND, NOT_FOUND, MISSING_METADATA, AMBIGUOUS }
data class LyricsMatch(val kind: LyricsMatchKind, val candidate: LyricsCandidate? = null)

/** A search adapter may supply candidates, but no unlicensed provider is enabled here. */
interface LyricsProvider {
    suspend fun find(track: LyricsTrack): List<LyricsCandidate>
}

object LyricsCandidateResolver {
    private fun normalized(value: String?): String =
        value.orEmpty().trim().replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)

    fun select(track: LyricsTrack, candidates: List<LyricsCandidate>): LyricsMatch {
        val title = normalized(track.title)
        val artist = normalized(track.artist)
        if (title.isBlank() || artist.isBlank()) return LyricsMatch(LyricsMatchKind.MISSING_METADATA)
        val matching = candidates.filter { item ->
            normalized(item.title) == title && normalized(item.artist) == artist &&
                (normalized(track.album).isBlank() ||
                    normalized(item.album) == normalized(track.album)) &&
                (track.durationSeconds == null ||
                    (item.durationSeconds != null &&
                        abs(track.durationSeconds - item.durationSeconds) <= 2))
        }
        if (matching.isEmpty()) return LyricsMatch(LyricsMatchKind.NOT_FOUND)
        val ranked = matching.map { candidate ->
            val score = (if (normalized(track.album).isNotBlank() &&
                    normalized(track.album) == normalized(candidate.album)) 2 else 0) +
                (if (track.durationSeconds != null &&
                    track.durationSeconds == candidate.durationSeconds) 2 else 0)
            candidate to score
        }.sortedByDescending { it.second }
        if (ranked.size > 1 && ranked[0].second == ranked[1].second)
            return LyricsMatch(LyricsMatchKind.AMBIGUOUS)
        return LyricsMatch(LyricsMatchKind.FOUND, ranked.first().first)
    }
}


/**
 * A free HTTP response does not license protected lyrics.
 * Remote providers must explicitly establish each right before wiring a player.
 */
data class LyricsSourceRights(
    val canQueryApi: Boolean,
    val canDisplayInApp: Boolean,
    val canStoreOffline: Boolean,
) {
    fun allows(action: LyricsAction): Boolean = when (action) {
        LyricsAction.QUERY -> canQueryApi
        LyricsAction.DISPLAY -> canDisplayInApp
        LyricsAction.STORE_OFFLINE -> canDisplayInApp && canStoreOffline
    }
}

enum class LyricsAction { QUERY, DISPLAY, STORE_OFFLINE }

data class TimedLyricLine(val startMs: Long, val text: String)
data class ParsedLyrics(val plainText: String, val timedLines: List<TimedLyricLine>)

object LocalLyricsParser {
    private val stamp = Regex("""^\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]\s*(.*)$""")

    /** Static text works without timestamps; local LRC is rendered without timing markup. */
    fun parse(raw: String): ParsedLyrics {
        val clean = raw.removePrefix("\uFEFF").trim()
        val timed = mutableListOf<TimedLyricLine>()
        val plain = mutableListOf<String>()
        for (line in clean.lines()) {
            val match = stamp.matchEntire(line.trim())
            if (match == null) {
                if (!line.trim().startsWith("[") || line.trim().contains("] ") )
                    plain.add(line)
                continue
            }
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            if (seconds >= 60) continue
            val fractional = match.groupValues[3].padEnd(3, '0').take(3)
            val ms = minutes * 60_000L + seconds * 1000L +
                (fractional.toLongOrNull() ?: 0L)
            val body = match.groupValues[4]
            timed.add(TimedLyricLine(ms, body))
            plain.add(body)
        }
        return ParsedLyrics(plain.joinToString("\n").trim(), timed)
    }
}

sealed interface LyricsUiState {
    data object Loading : LyricsUiState
    data class Available(val plainText: String, val hasTiming: Boolean) : LyricsUiState
    data object Instrumental : LyricsUiState
    data object NotFound : LyricsUiState
    data object Offline : LyricsUiState
    data object ProviderError : LyricsUiState
    data object Ambiguous : LyricsUiState
}
