package app.openmusic.core

/** MediaStore flags are hints, not a guarantee about the contents of an audio file. */
data class AudioKindFlags(
    val music: Boolean? = null,
    val podcast: Boolean? = null,
    val audiobook: Boolean? = null,
    val recording: Boolean? = null,
    val ringtone: Boolean? = null,
    val notification: Boolean? = null,
    val alarm: Boolean? = null,
)

object AudioKindPolicy {
    private val excludedFolders = setOf(
        "podcast", "podcasts", "audiobook", "audiobooks", "recordings",
        "voice recorder", "voice recorders", "voice notes", "whatsapp voice notes",
        "telegram voice", "ringtones", "ringtone", "notifications", "notification",
        "alarms", "alarm", "call recordings", "call recording",
    )
    private val musicFolders = setOf("music", "musica", "songs", "albums", "downloaded music")
    private val recordedName = Regex(
        """^(ptt[-_]|voice[-_ ]?(note|message|recording)[-_]|recording[-_](\d|20\d\d)|call[-_]?record|aud[-_]\d{8}[-_])""",
        RegexOption.IGNORE_CASE,
    )

    private fun informative(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return normalized.isNotEmpty() && normalized !in setOf(
            "<unknown>", "unknown", "unknown artist", "unknown album",
            "artista sconosciuto", "album sconosciuto", "artist", "album", "untitled",
            "no artist", "no album", "null",
        )
    }

    fun likelyMusic(
        flags: AudioKindFlags,
        artist: String?,
        album: String?,
        title: String? = null,
        displayName: String? = null,
        relativePath: String? = null,
    ): Boolean {
        if (listOf(flags.podcast, flags.audiobook, flags.recording, flags.ringtone,
                flags.notification, flags.alarm).any { it == true }) return false

        val folders = relativePath.orEmpty().replace('\\', '/').lowercase()
            .split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (folders.any { it in excludedFolders }) return false
        if (recordedName.containsMatchIn(displayName.orEmpty().trim())) return false

        val artistTag = informative(artist)
        val albumTag = informative(album)
        val inMusicFolder = folders.any { it in musicFolders }
        val messagingFolder = folders.any { folder ->
            folder in setOf("whatsapp", "whatsapp audio", "telegram", "telegram audio",
                "signal", "messenger", "viber")
        }
        if (messagingFolder && !(artistTag && albumTag)) return false
        if (artistTag && albumTag) return true
        if (inMusicFolder) return true
        return flags.music == true && (artistTag || (albumTag && informative(title)))
    }
}
