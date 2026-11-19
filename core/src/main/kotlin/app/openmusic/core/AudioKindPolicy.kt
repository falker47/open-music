package app.openmusic.core

/** MediaStore categories are hints. This classifier never hides files from SHA matching. */
data class AudioKindFlags(
    val music: Boolean? = null,
    val podcast: Boolean? = null,
    val audiobook: Boolean? = null,
    val recording: Boolean? = null,
    val ringtone: Boolean? = null,
    val notification: Boolean? = null,
    val alarm: Boolean? = null,
)

/** A score is a heuristic for local presentation, not a claim about audio contents. */
data class AudioKindAssessment(
    val music: Boolean,
    val score: Int,
    val inspectEmbeddedMetadata: Boolean,
)

object AudioKindPolicy {
    private val nonMusicFolders = setOf(
        "podcast", "podcasts", "audiobook", "audiobooks",
        "recordings", "voice recorder", "voice recorders", "voice notes",
        "whatsapp voice notes", "telegram voice", "ringtones", "ringtone",
        "notifications", "notification", "alarms", "alarm",
        "call recordings", "call recording",
    )
    private val musicFolders = setOf("music", "musica", "songs", "albums", "downloaded music")
    private val messagingFolders = setOf(
        "whatsapp", "whatsapp audio", "telegram", "telegram audio",
        "signal", "messenger", "viber",
    )
    // A filename alone is weak negative evidence; tagged songs can still score as music.
    private val voiceFilename = Regex(
        """^(ptt[-_]|voice[-_ ]?(note|message|recording)[-_]|recording[-_](\d|20\d\d)|call[-_]?record|aud[-_]\d{8}[-_])""",
        RegexOption.IGNORE_CASE,
    )
    private val anonymousFilename = Regex("""^(\d{4,}|[a-f0-9]{16,})$""", RegexOption.IGNORE_CASE)
    private val missingTags = setOf(
        "<unknown>", "unknown", "unknown artist", "unknown album",
        "artista sconosciuto", "album sconosciuto", "artist", "album",
        "untitled", "no artist", "no album", "null",
    )
    private val spokenGenres = setOf(
        "podcast", "podcasts", "speech", "spoken word", "audiobook",
        "voice memo", "voice recording",
    )

    private fun informative(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return normalized.isNotEmpty() && normalized !in missingTags
    }

    fun assess(
        flags: AudioKindFlags,
        artist: String?,
        album: String?,
        title: String? = null,
        displayName: String? = null,
        relativePath: String? = null,
        genre: String? = null,
        trackNumber: Int? = null,
        embeddedArtwork: Boolean = false,
    ): AudioKindAssessment {
        val folders = relativePath.orEmpty().replace('\\', '/').lowercase()
            .split('/').map { it.trim() }.filter { it.isNotEmpty() }

        // Explicit categories and voice-only folders are higher-confidence negatives.
        // All still remain accessible under "Altri audio" and can be manually overridden.
        if (listOf(flags.podcast, flags.audiobook, flags.recording, flags.ringtone,
                flags.notification, flags.alarm).any { it == true } ||
            folders.any { it in nonMusicFolders }
        ) return AudioKindAssessment(false, -100, false)

        val artistTag = informative(artist)
        val albumTag = informative(album)
        val genreTag = informative(genre) &&
            genre.orEmpty().trim().lowercase() !in spokenGenres
        val inMusicFolder = folders.any { it in musicFolders }
        val inMessagingFolder = folders.any { it in messagingFolders }
        val filename = displayName.orEmpty().trim()
        val stem = filename.substringBeforeLast('.')
        val hasTrackNumber = (trackNumber ?: 0) > 0

        var score = 0
        if (artistTag) score += 3
        if (albumTag) score += 2
        if (genreTag) score += 1
        if (genre.orEmpty().trim().lowercase() in spokenGenres) score -= 3
        if (hasTrackNumber) score += 1
        if (flags.music == true) score += 1
        if (inMusicFolder) score += 1
        if (embeddedArtwork) score += 3
        if (inMessagingFolder) score -= if (artistTag && albumTag) 1 else 2
        if (voiceFilename.containsMatchIn(filename)) score -= 3
        if (anonymousFilename.matches(stem)) score -= 2

        // A single file path, Android music flag, embedded cover or duration never
        // suffices alone. Embedded media metadata is inspected only for borderline
        // candidates; explicit flags, obvious voice folders and clear tracks skip it.
        val music = score >= 4
        val inspect = !music && !embeddedArtwork && score >= -1 &&
            (artistTag || albumTag || inMusicFolder || hasTrackNumber ||
                flags.music == true)
        return AudioKindAssessment(music, score, inspect)
    }

    fun likelyMusic(
        flags: AudioKindFlags,
        artist: String?,
        album: String?,
        title: String? = null,
        displayName: String? = null,
        relativePath: String? = null,
        genre: String? = null,
        trackNumber: Int? = null,
        embeddedArtwork: Boolean = false,
    ): Boolean = assess(
        flags, artist, album, title, displayName, relativePath, genre,
        trackNumber, embeddedArtwork,
    ).music
}
