package app.openmusic.core

import org.junit.Assert.*
import org.junit.Test

class ReconcilePriorityTest {
    private val wanted = PortablePlaylist("p", "test", "", "", listOf(
        PlaylistEntry("e", TrackIdentity("a".repeat(64),
            TrackMetadata("Artist", "Song", "Album", 120000, "tags"), "song.mp3")),
    ))

    @Test fun matchingHintsSortEarlierButNeverGrantIdentity() {
        val hints = ReconcilePriority.hints(listOf(wanted))
        val matchingName = TrackIdentity("b".repeat(64),
            TrackMetadata("Different", "Other"), "SONG.mp3")
        val matchingTags = TrackIdentity("b".repeat(64),
            TrackMetadata("artist", "song", durationMs = 120000, source = "tags"), "other.mp3")
        val unrelated = TrackIdentity("b".repeat(64), TrackMetadata(title = "No"), "a.mp3")
        assertTrue(ReconcilePriority.score(matchingName, hints) >
            ReconcilePriority.score(matchingTags, hints))
        assertTrue(ReconcilePriority.score(matchingTags, hints) >
            ReconcilePriority.score(unrelated, hints))
        val result = PlaylistMatcher.resolve(wanted, listOf(LocalCopy("other", matchingTags)))
        assertEquals(MatchStatus.REVIEW, result.single().status)
        assertNull(result.single().localUri)
    }

    @Test fun sharedIndexHandlesManyPlaylistsWithoutCrossLinking() {
        val playlists = (1..25).map { i ->
            wanted.copy(playlistId = "p$i", tracks = listOf(
                wanted.tracks.single().copy(entryId = "e$i"),
            ))
        }
        val result = PlaylistMatcher.resolveAll(playlists, listOf(
            LocalCopy("different", TrackIdentity("b".repeat(64),
                TrackMetadata("Artist", "Song", "Album", 120000, "tags"))),
        ))
        assertEquals(25, result.size)
        result.values.forEach {
            assertEquals(MatchStatus.REVIEW, it.single().status)
            assertNull(it.single().localUri)
        }
    }
}
