package app.openmusic.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioKindPolicyTest {
    @Test fun explicitMusicAndTaggedFallbackAreVisible() {
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true), null, null))
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(music = false), "Björk", "Debut"))
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(), "Artist", "Album"))
    }

    @Test fun nonMusicalMediaAreHiddenEvenWithArtistTags() {
        listOf(
            AudioKindFlags(podcast = true), AudioKindFlags(audiobook = true),
            AudioKindFlags(recording = true), AudioKindFlags(ringtone = true),
            AudioKindFlags(notification = true), AudioKindFlags(alarm = true),
        ).forEach {
            assertFalse(AudioKindPolicy.likelyMusic(it, "Known artist", "Album"))
        }
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true, alarm = true), null, null))
    }

    @Test fun unknownAndUntaggedAreRecoverableInOtherAudioView() {
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(music = false), null, null))
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(), "<unknown>", "unknown"))
    }
}
