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
    fun likelyMusic(flags: AudioKindFlags, artist: String?, album: String?): Boolean {
        // Explicit non-music categories win over IS_MUSIC and any misleading tags.
        if (listOf(flags.podcast, flags.audiobook, flags.recording, flags.ringtone,
                flags.notification, flags.alarm).any { it == true }) return false
        if (flags.music == true) return true
        // IS_MUSIC=0/unknown is common for legitimate tracks. Require BOTH credible
        // tags before overriding it, not the extremely permissive artist OR album.
        // Untagged singles remain discoverable under Altri audio; no duration cutoff.
        fun tagged(value: String?): Boolean {
            val trimmed = value?.trim().orEmpty()
            return trimmed.isNotEmpty() && !trimmed.equals("<unknown>", true) &&
                !trimmed.equals("unknown", true) &&
                !trimmed.equals("artista sconosciuto", true)
        }
        return tagged(artist) && tagged(album)
    }
}
