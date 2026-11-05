package app.openmusic.core

import org.junit.Assert.*
import org.junit.Test

class LyricsCandidateResolverTest {
    private val track = LyricsTrack("Song (Live)", "An Artist", "Live Album", 185)
    private fun candidate(
        title: String = "Song (Live)", artist: String = "An Artist",
        album: String = "Live Album", seconds: Int = 185,
    ) = LyricsCandidate(title, artist, album, seconds, false, "sample", null)

    @Test fun findsExactMetadataWithoutStrippingLiveOrFeaturingTags() {
        assertEquals(LyricsMatchKind.FOUND,
            LyricsCandidateResolver.select(track, listOf(candidate())).kind)
        assertEquals(LyricsMatchKind.NOT_FOUND,
            LyricsCandidateResolver.select(track, listOf(candidate(title = "Song"))).kind)
        assertEquals(LyricsMatchKind.NOT_FOUND,
            LyricsCandidateResolver.select(track, listOf(candidate(artist = "Other Artist"))).kind)
    }

    @Test fun rejectsDurationMismatchInsteadOfDisplayingAnotherVersion() {
        assertEquals(LyricsMatchKind.NOT_FOUND,
            LyricsCandidateResolver.select(track, listOf(candidate(seconds = 210))).kind)
    }

    @Test fun distinguishesMultipleCandidatesAndMissingMetadata() {
        assertEquals(LyricsMatchKind.AMBIGUOUS,
            LyricsCandidateResolver.select(track, listOf(candidate(), candidate())).kind)
        assertEquals(LyricsMatchKind.MISSING_METADATA,
            LyricsCandidateResolver.select(track.copy(artist = " "), listOf(candidate())).kind)
        assertEquals(LyricsMatchKind.NOT_FOUND,
            LyricsCandidateResolver.select(track, emptyList()).kind)
    }

    @Test fun albumAndDurationPreferOneClearCandidate() {
        val partial = candidate(album = "Other Album", seconds = 184)
        val chosen = candidate()
        assertEquals(chosen, LyricsCandidateResolver.select(track,
            listOf(partial, chosen)).candidate)
    }

    @Test fun lrcAndStaticTextRemainReadableWithoutTimedPlayback() {
        val static = LocalLyricsParser.parse("First line\nSecond line")
        assertEquals("First line\nSecond line", static.plainText)
        assertTrue(static.timedLines.isEmpty())

        val lrc = LocalLyricsParser.parse("[00:01.50] First\n[00:03.250] Second")
        assertEquals("First\nSecond", lrc.plainText)
        assertEquals(1500L, lrc.timedLines[0].startMs)
        assertEquals(3250L, lrc.timedLines[1].startMs)
    }

    @Test fun requiresCandidateAlbumAndDurationWhenKnownToAvoidWrongVersions() {
        assertEquals(LyricsMatchKind.NOT_FOUND,
            LyricsCandidateResolver.select(track,
                listOf(candidate(album = "Studio Album"))).kind)
        assertEquals(LyricsMatchKind.NOT_FOUND,
            LyricsCandidateResolver.select(track,
                listOf(LyricsCandidate("Song (Live)", "An Artist", null, 185,
                    false, "sample", null))).kind)
        assertEquals(LyricsMatchKind.NOT_FOUND,
            LyricsCandidateResolver.select(track,
                listOf(LyricsCandidate("Song (Live)", "An Artist", "Live Album",
                    null, false, "sample", null))).kind)
    }

    @Test fun freeApiAccessNeverImpliesDisplayOrOfflineStorageRights() {
        val freeApi = LyricsSourceRights(
            canQueryApi = true, canDisplayInApp = false, canStoreOffline = false)
        assertTrue(freeApi.allows(LyricsAction.QUERY))
        assertFalse(freeApi.allows(LyricsAction.DISPLAY))
        assertFalse(freeApi.allows(LyricsAction.STORE_OFFLINE))

        val displayOnly = freeApi.copy(canDisplayInApp = true)
        assertTrue(displayOnly.allows(LyricsAction.DISPLAY))
        assertFalse(displayOnly.allows(LyricsAction.STORE_OFFLINE))
        assertTrue(displayOnly.copy(canStoreOffline = true)
            .allows(LyricsAction.STORE_OFFLINE))
    }
}
