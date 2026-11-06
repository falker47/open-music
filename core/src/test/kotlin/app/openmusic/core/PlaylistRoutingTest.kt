package app.openmusic.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistRoutingTest {
    private fun playlist(id: String) =
        PortablePlaylist(id, id, "2026-10-25", "2026-10-25", emptyList())

    @Test fun noneRequiresPlaylistCreation() {
        assertEquals(PlaylistDestination.CreateFirst, PlaylistRouting.decide(emptyList()))
    }

    @Test fun onePlaylistCanBeSelectedWithoutAChooser() {
        assertEquals(PlaylistDestination.Direct("first"),
            PlaylistRouting.decide(listOf(playlist("first"))))
    }

    @Test fun twoPlaylistsMustPromptRatherThanUseLastSelected() {
        val playlists = listOf(playlist("first"), playlist("last-selected"))
        assertEquals(PlaylistDestination.Choose, PlaylistRouting.decide(playlists))
        assertEquals(PlaylistDestination.Choose, PlaylistRouting.decide(playlists.reversed()))
    }

    @Test fun manyPlaylistsRequireExplicitDestination() {
        val playlists = (1..100).map { playlist("playlist-$it") }
        assertEquals(PlaylistDestination.Choose, PlaylistRouting.decide(playlists))
    }

    private fun entry(id: String, hash: String?, title: String = "Same song") =
        PlaylistEntry(id, TrackIdentity(hash, TrackMetadata(title = title)))

    @Test fun sameRecordingCannotBeAddedTwiceToOnePlaylist() {
        val original = playlist("one")
        val first = entry("first", "a".repeat(64))
        val once = requireNotNull(PlaylistRouting.addIfAbsent(original, first, "updated-1"))
        val duplicate = entry("different-entry-id", "A".repeat(64), "Renamed track")
        assertNull(PlaylistRouting.addIfAbsent(once, duplicate, "updated-2"))
        assertEquals(listOf(first), once.tracks)
        assertEquals("updated-1", once.updatedAt)
    }

    @Test fun sameRecordingIsAllowedInDifferentPlaylists() {
        val recording = entry("song", "a".repeat(64))
        val first = requireNotNull(PlaylistRouting.addIfAbsent(playlist("one"), recording, "t1"))
        val second = requireNotNull(PlaylistRouting.addIfAbsent(playlist("two"), recording, "t2"))
        assertEquals(1, first.tracks.size)
        assertEquals(1, second.tracks.size)
    }

    @Test fun sameMetadataButDifferentHashIsNotConsideredDuplicate() {
        val first = entry("one", "a".repeat(64))
        val second = entry("two", "b".repeat(64))
        val once = requireNotNull(PlaylistRouting.addIfAbsent(playlist("one"), first, "t1"))
        val twice = requireNotNull(PlaylistRouting.addIfAbsent(once, second, "t2"))
        assertEquals(listOf(first, second), twice.tracks)
    }

    @Test fun importedLegacyDuplicatesArePreservedWhenAddingOtherTracks() {
        val first = entry("old-1", "a".repeat(64))
        val second = entry("old-2", "a".repeat(64))
        val imported = playlist("legacy").copy(tracks = listOf(first, second))
        assertNull(PlaylistRouting.addIfAbsent(imported, entry("new-duplicate", "a".repeat(64)), "t1"))
        val changed = requireNotNull(PlaylistRouting.addIfAbsent(
            imported, entry("new-song", "b".repeat(64)), "t2"))
        assertEquals(listOf(first, second), changed.tracks.take(2))
        assertEquals(3, changed.tracks.size)
        assertEquals(2, imported.tracks.size)
    }

    @Test fun chooserShowsDuplicatesOnlyInVerifiedDestinations() {
        val hash = "a".repeat(64)
        val first = playlist("rock").copy(tracks = listOf(entry("one", hash)))
        val second = playlist("other")
        assertEquals(true, PlaylistRouting.containsVerifiedHash(first, hash.uppercase()))
        assertEquals(false, PlaylistRouting.containsVerifiedHash(second, hash))
        assertEquals(false, PlaylistRouting.containsVerifiedHash(first, null))
        assertEquals(false, PlaylistRouting.containsVerifiedHash(first, "b".repeat(64)))
    }

    @Test fun missingHashCannotSafelyProveTwoTracksAreIdentical() {
        val first = entry("one", null)
        val second = entry("two", null)
        val once = requireNotNull(PlaylistRouting.addIfAbsent(playlist("one"), first, "t1"))
        val twice = requireNotNull(PlaylistRouting.addIfAbsent(once, second, "t2"))
        assertEquals(2, twice.tracks.size)
    }
}
