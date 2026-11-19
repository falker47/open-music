package app.openmusic.core

/** Paths, URI, audio bytes and credentials belong to local device state, not to PortablePlaylist. */
data class TrackMetadata(
    val artist: String? = null,
    val title: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val source: String = "filename",
)
data class TrackIdentity(
    val sha256: String? = null,
    val metadata: TrackMetadata,
    val filenameHint: String? = null,
)
data class PlaylistEntry(val entryId: String, val identity: TrackIdentity)
data class PortablePlaylist(
    val playlistId: String,
    val name: String,
    val createdAt: String,
    val updatedAt: String,
    val tracks: List<PlaylistEntry>,
)
/** A device-specific copy of a song. The URI is intentionally never exported. */
data class LocalCopy(val uri: String, val identity: TrackIdentity)
enum class MatchStatus { EXACT, METADATA, REVIEW, AMBIGUOUS, MISSING }
data class MatchResult(
    val entryId: String,
    val status: MatchStatus,
    val localUri: String? = null,
    val candidates: List<String> = emptyList(),
)
