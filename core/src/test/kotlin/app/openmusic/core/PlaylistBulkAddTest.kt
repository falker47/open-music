package app.openmusic.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistBulkAddTest {
    private fun list(id: String = "rock") =
        PortablePlaylist(id, "Rock", "created", "old", emptyList())

    private fun entry(id: String, hash: String? = id) =
        PlaylistEntry(id, TrackIdentity(hash, TrackMetadata(title = id)))

    @Test fun selectionTogglesWithoutChangingOtherSelections() {
        val one = PlaylistBulkAdd.toggle(emptySet(), "uri-1")
        val two = PlaylistBulkAdd.toggle(one, "uri-2")
        assertEquals(setOf("uri-1", "uri-2"), two)
        assertEquals(setOf("uri-2"), PlaylistBulkAdd.toggle(two, "uri-1"))
        assertTrue(PlaylistBulkAdd.toggle(one, "uri-1").isEmpty())
    }

    @Test fun cancellingMultiSelectLeavesPlaylistUnchanged() {
        val original = list()
        val selected = PlaylistBulkAdd.toggle(emptySet(), "uri-1")
        assertEquals(setOf("uri-1"), selected)
        assertEquals(emptyList<PlaylistEntry>(), original.tracks)
        assertEquals("old", original.updatedAt)
        assertEquals(original, PlaylistBulkAdd.add(original, emptyList(), "new").playlist)
    }

    @Test fun batchAppendsInSelectionOrderWithOneTimestamp() {
        val original = list().copy(tracks = listOf(entry("old")))
        val result = PlaylistBulkAdd.add(original, listOf(entry("a"), entry("b")), "new")
        assertEquals(2, result.inserted)
        assertEquals(listOf("old", "a", "b"), result.playlist.tracks.map { it.entryId })
        assertEquals("new", result.playlist.updatedAt)
        assertEquals("old", original.updatedAt)
    }

    @Test fun batchSkipsVerifiedDuplicatesIncludingRepeatedCandidates() {
        val original = list().copy(tracks = listOf(entry("before", "abcd")))
        val result = PlaylistBulkAdd.add(
            original,
            listOf(entry("dupe-existing", "ABCD"), entry("new", "xyz"),
                entry("dupe-batch", "XYZ")), "now",
        )
        assertEquals(1, result.inserted)
        assertEquals(listOf("before", "new"), result.playlist.tracks.map { it.entryId })
    }

    @Test fun alreadyPresentBatchIsAnIdempotentNoOp() {
        val original = list().copy(tracks = listOf(entry("before", "abcd")))
        val result = PlaylistBulkAdd.add(original, listOf(entry("duplicate", "aBcD")), "now")
        assertEquals(0, result.inserted)
        assertEquals(original, result.playlist)
    }

    @Test fun importedLegacyDuplicatesRemainAndOtherPlaylistsAreUntouched() {
        val legacy = list().copy(tracks = listOf(entry("one", "same"), entry("two", "same")))
        val other = list("other")
        val added = PlaylistBulkAdd.add(legacy, listOf(entry("three", "different")), "now")
        assertEquals(listOf("one", "two", "three"), added.playlist.tracks.map { it.entryId })
        assertTrue(other.tracks.isEmpty())
    }

    @Test fun navigationModesAndValidSelectionBehaveForManyPlaylists() {
        assertEquals(PlaylistNavLayout.NONE, PlaylistSelection.layout(0))
        assertEquals(PlaylistNavLayout.SINGLE, PlaylistSelection.layout(1))
        assertEquals(PlaylistNavLayout.DOUBLE, PlaylistSelection.layout(2))
        assertEquals(PlaylistNavLayout.PICKER, PlaylistSelection.layout(3))
        assertEquals(PlaylistNavLayout.PICKER, PlaylistSelection.layout(20))
        val lists = listOf(list("first"), list("second"), list("third"))
        assertEquals("second", PlaylistSelection.resolveId(lists, "second"))
        assertEquals("first", PlaylistSelection.resolveId(lists, "deleted"))
        assertEquals("first", PlaylistSelection.resolveId(lists, null))
        assertEquals(null, PlaylistSelection.resolveId(emptyList(), "deleted"))
    }
}
