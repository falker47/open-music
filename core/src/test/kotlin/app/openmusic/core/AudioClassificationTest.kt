package app.openmusic.core

import org.junit.Assert.*
import org.junit.Test

class AudioClassificationTest {
    private val music = AudioItemStamp("content://media/external_primary/audio/media/1", 128, 123)
    private val other = AudioItemStamp("content://media/external_primary/audio/media/2", 321, 123)

    @Test fun complementaryMusicAndOtherViewsNeverOverlap() {
        val entries = listOf(music to true, other to false)
        val overrides = emptyMap<String, ManualAudioOverride>()
        val inMusic = entries.filter { AudioClassification.isMusic(it.second, it.first, overrides) }
        val elsewhere = entries.filterNot { AudioClassification.isMusic(it.second, it.first, overrides) }
        assertEquals(1, inMusic.size)
        assertEquals(1, elsewhere.size)
        assertEquals(entries.size, inMusic.size + elsewhere.size)
        assertTrue(inMusic.map { it.first }.intersect(elsewhere.map { it.first }.toSet()).isEmpty())
    }

    @Test fun manualPromotionDemotionResetAndRestart() {
        val promoted = AudioClassification.set(emptyMap(), other, true)
        val removed = AudioClassification.set(promoted, music, false)
        val reloaded = removed.toMap() // persisted values survive a reconstructed state
        assertTrue(AudioClassification.isMusic(false, other, reloaded))
        assertFalse(AudioClassification.isMusic(true, music, reloaded))
        assertFalse(AudioClassification.isMusic(false, other, AudioClassification.reset(reloaded, other.uri)))
        assertTrue(AudioClassification.isMusic(true, music, AudioClassification.reset(reloaded, music.uri)))
    }

    @Test fun rescanPrunesDeletedModifiedAndRecycledMediaIds() {
        val map = AudioClassification.set(
            AudioClassification.set(emptyMap(), music, false), other, true,
        )
        assertEquals(map, AudioClassification.retain(map, listOf(music, other)))
        assertEquals(mapOf(music.uri to map.getValue(music.uri)),
            AudioClassification.retain(map, listOf(music)))
        assertTrue(AudioClassification.retain(map, listOf(
            music.copy(modifiedSeconds = 999), other.copy(sizeBytes = 333),
        )).isEmpty())
    }

    @Test fun stricterTagFallbackButFlaggedMusicRemainsAvailable() {
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(music = false), "Artist", null))
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(music = false), "Artist", "Album"))
        assertTrue(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true), null, null))
        assertFalse(AudioKindPolicy.likelyMusic(AudioKindFlags(music = true, podcast = true),
            "Artist", "Album"))
    }

    @Test fun searchIsIndependentOfCategoryAndPlaylistSelectionIsCompact() {
        assertTrue(AudioSearch.matches("The Great Song", "An Artist", "great"))
        assertTrue(AudioSearch.matches("The Great Song", "An Artist", "artist"))
        assertFalse(AudioSearch.matches("The Great Song", "An Artist", "nothing"))
        assertFalse(PlaylistSelection.showSelector(0))
        assertFalse(PlaylistSelection.showSelector(1))
        assertTrue(PlaylistSelection.showSelector(2))
        assertTrue(PlaylistSelection.showSelector(80))
    }
}
