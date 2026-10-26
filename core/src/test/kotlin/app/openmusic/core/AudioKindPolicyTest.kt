package app.openmusic.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioKindPolicyTest {
    @Test fun musicWithTagsOrMusicDirectoryCanBeIncluded() {
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true), "Björk", null))
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(music = false), "Björk", "Debut"))
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(), null, null,
            relativePath = "Music/"))
    }

    @Test fun categoriesRejectNonMusicEvenWhenTagsSayOtherwise() {
        listOf(AudioKindFlags(podcast = true), AudioKindFlags(audiobook = true),
            AudioKindFlags(recording = true), AudioKindFlags(ringtone = true),
            AudioKindFlags(notification = true), AudioKindFlags(alarm = true)).forEach {
            assertFalse(AudioKindPolicy.likelyMusic(it, "Björk", "Debut",
                relativePath = "Music/"))
        }
    }

    @Test fun androidMusicFlagAloneDoesNotPromoteUnknownSounds() {
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true), null, null,
            displayName = "audio_01.ogg", relativePath = "Download/"))
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true), "Artist", "Album"))
    }

    @Test fun knownVoiceNotesAndNonMusicFoldersTakePrecedence() {
        val flags = AudioKindFlags(music = true)
        assertFalse(AudioKindPolicy.likelyMusic(flags, "Björk", "Debut",
            displayName = "PTT-20261026-WA0012.opus", relativePath = "Download/"))
        assertFalse(AudioKindPolicy.likelyMusic(flags, "Björk", "Debut",
            displayName = "track.mp3", relativePath = "WhatsApp/Media/WhatsApp Voice Notes/"))
        assertFalse(AudioKindPolicy.likelyMusic(flags, "Björk", "Debut",
            displayName = "tone.mp3", relativePath = "Ringtones/"))
    }

    @Test fun forwardedSongWithRealTagsAndSongWithVoiceInNameRemainDiscoverable() {
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true), "Björk", "Debut",
            displayName = "song.mp3", relativePath = "WhatsApp/Media/WhatsApp Audio/"))
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true), "Adele", null,
            displayName = "Voice.mp3", relativePath = "Download/"))
    }
}
