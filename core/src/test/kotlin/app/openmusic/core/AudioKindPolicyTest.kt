package app.openmusic.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioKindPolicyTest {
    @Test fun multipleRealMusicSignalsRecognizeTaggedSongs() {
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(music = false), "Björk", "Debut"))
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true), "50 Cent", null,
            title = "21 Questions", relativePath = "Download/"))
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(), "Artist Name", null,
            title = "Instrumental", genre = "Rock", trackNumber = 2))
    }

    @Test fun flagAndMusicFolderAloneCannotPromoteRandomFiles() {
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true), null, null,
            title = "139224", displayName = "139224.mp3", relativePath = "Music/"))
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true), null, null,
            title = "127km (2) - ai", relativePath = "Music/"))
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true), "Artist", "Album"))
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(), null, null,
            embeddedArtwork = true))
    }

    @Test fun embeddedCoverAndMetadataRestoreSongsFromMessagingAudioFolder() {
        val whatsApp = "WhatsApp/Media/WhatsApp Audio/"
        val artistAndCover = AudioKindPolicy.assess(
            AudioKindFlags(music = true), "Lukas Graham", null,
            title = "7 Years", displayName = "7 Years.mp3", relativePath = whatsApp,
        )
        assertFalse(artistAndCover.music)
        assertTrue(artistAndCover.inspectEmbeddedMetadata)
        assertTrue(AudioKindPolicy.likelyMusic(
            AudioKindFlags(music = true), "Lukas Graham", null,
            title = "7 Years", displayName = "7 Years.mp3", relativePath = whatsApp,
            embeddedArtwork = true,
        ))
        assertTrue(AudioKindPolicy.likelyMusic(
            AudioKindFlags(music = true), "Sia", "This Is Acting",
            title = "Cheap Thrills", displayName = "AUD-20180630-WA0008.mp3",
            relativePath = whatsApp, embeddedArtwork = true,
        ))
    }

    @Test fun untaggedSongInMusicFolderWithArtworkCanBeRecognized() {
        val flags = AudioKindFlags(music = true)
        assertFalse(AudioKindPolicy.likelyMusic(flags, null, null,
            relativePath = "Music/Albums/"))
        assertTrue(AudioKindPolicy.assess(flags, null, null,
            relativePath = "Music/Albums/").inspectEmbeddedMetadata)
        assertTrue(AudioKindPolicy.likelyMusic(flags, null, null,
            relativePath = "Music/Albums/", embeddedArtwork = true))
    }

    @Test fun explicitNonMusicAndVoiceFoldersRemainOtherEvenWithCover() {
        val flags = listOf(
            AudioKindFlags(podcast = true), AudioKindFlags(audiobook = true),
            AudioKindFlags(recording = true), AudioKindFlags(ringtone = true),
            AudioKindFlags(notification = true), AudioKindFlags(alarm = true),
        )
        flags.forEach {
            assertFalse(AudioKindPolicy.likelyMusic(it, "Björk", "Debut",
                relativePath = "Music/", embeddedArtwork = true))
            assertFalse(AudioKindPolicy.assess(it, "Björk", "Debut",
                embeddedArtwork = false).inspectEmbeddedMetadata)
        }
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true),
            "Lukas Graham", "Album", displayName = "track.mp3",
            relativePath = "WhatsApp/Media/WhatsApp Voice Notes/",
            embeddedArtwork = true))
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true),
            null, null, displayName = "PTT-20261026-WA0012.opus",
            relativePath = "Download/"))
    }

    @Test fun titleWithVoiceIsNotARecordingAndMissingArtworkIsNotProofOfNonMusic() {
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true), "Adele", null,
            title = "Voice", displayName = "Voice.mp3", relativePath = "Download/"))
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(music = false),
            "Counting Crows", "Shrek 2", embeddedArtwork = false))
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true),
            "Unknown", "Unknown", genre = "Speech", title = "Notification"))
    }
}
