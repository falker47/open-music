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
        // Explicit non-music categories win even if the scanner set conflicting flags.
        if (listOf(flags.podcast, flags.audiobook, flags.recording, flags.ringtone,
                flags.notification, flags.alarm).any { it == true }) return false
        if (flags.music == true) return true
        // A legitimate music track can have IS_MUSIC=0 on some devices. Retain
        // tagged recordings with credible album/artist metadata; no duration cutoff.
        fun tagged(value: String?): Boolean {
            val trimmed = value?.trim().orEmpty()
            return trimmed.isNotEmpty() && !trimmed.equals("<unknown>", true) &&
                !trimmed.equals("unknown", true) && !trimmed.equals("artista sconosciuto", true)
        }
        return tagged(artist) || tagged(album)
    }
}
