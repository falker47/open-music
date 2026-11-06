package app.openmusic.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistEditingTest {
    private fun entry(id: String, hash: String? = id) =
        PlaylistEntry(id, TrackIdentity(hash, TrackMetadata(title = id)))
    private fun list(id: String = "one") =
        PortablePlaylist(id, "Rock", "created", "old", emptyList())

    @Test fun onlyExactPresentUrisAreEligibleForRemoval() {
        val matches = listOf(
            MatchResult("a", MatchStatus.EXACT, "uri-a"),
            MatchResult("duplicate", MatchStatus.EXACT, "uri-a"),
            MatchResult("otherAudio", MatchStatus.EXACT, "uri-other"),
            MatchResult("ambiguous", MatchStatus.AMBIGUOUS, "uri-b"),
            MatchResult("metadata", MatchStatus.METADATA, "uri-c"),
            MatchResult("missing", MatchStatus.MISSING),
        )
        val base = PlaylistContentEditor.baseline(
            matches, setOf("uri-a", "uri-other", "uri-b", "uri-c"))
        assertEquals(setOf("a", "duplicate"), base["uri-a"])
        assertEquals(setOf("otherAudio"), base["uri-other"])
        assertFalse(base.containsKey("uri-b"))
        assertFalse(base.containsKey("uri-c"))
    }

    @Test fun changesSurviveFilteringAndTogglingBackIsNoOp() {
        val base = mapOf("uri-a" to setOf("a"), "uri-b" to setOf("b"))
        val changes = PlaylistContentEditor.pending(base, setOf("uri-b", "uri-c"))
        assertEquals(setOf("uri-c"), changes.addedUris)
        assertEquals(setOf("a"), changes.removedEntryIds)
        assertFalse(PlaylistContentEditor.pending(base, base.keys).hasChanges)
    }

    @Test fun removeOnlyExplicitIdsPreservesMissingAndLegacyDuplicates() {
        val original = list().copy(tracks = listOf(
            entry("legacy-1", "same"), entry("legacy-2", "same"),
            entry("missing"), entry("ambiguous"), entry("last")))
        val result = PlaylistContentEditor.apply(
            original, setOf("legacy-1"), emptyList(), "now")
        assertEquals(listOf("legacy-2", "missing", "ambiguous", "last"),
            result.playlist.tracks.map { it.entryId })
        assertEquals(1, result.removed)
        assertEquals(0, result.added)
        assertEquals("now", result.playlist.updatedAt)
        assertEquals(5, original.tracks.size)
    }

    @Test fun batchOrderAndDuplicatePreventionAndIdempotency() {
        val original = list().copy(tracks = listOf(entry("old", "A")))
        val result = PlaylistContentEditor.apply(original, emptySet(), listOf(
            entry("duplicate", "a"), entry("new-1", "B"),
            entry("repeated", "b"), entry("new-2", "C")), "now")
        assertEquals(2, result.added)
        assertEquals(listOf("old", "new-1", "new-2"),
            result.playlist.tracks.map { it.entryId })
        assertEquals("now", result.playlist.updatedAt)
        assertEquals(original, PlaylistContentEditor.apply(
            original, emptySet(), emptyList(), "now").playlist)
        assertEquals(original, PlaylistContentEditor.apply(
            original, emptySet(), listOf(entry("new", "a")), "now").playlist)
    }

    @Test fun unrelatedPlaylistRemainsUnchanged() {
        val one = list("first").copy(tracks = listOf(entry("one")))
        val other = list("second").copy(tracks = listOf(entry("other")))
        val updated = listOf(one, other).map {
            if (it.playlistId == one.playlistId)
                PlaylistContentEditor.apply(it, setOf("one"), emptyList(), "now").playlist
            else it
        }
        assertEquals(other, updated[1])
    }

    @Test fun editorRowStateFollowsInitialMembershipAndLiveSelection() {
        assertEquals(PlaylistEditorRowState.ALREADY_IN_PLAYLIST,
            PlaylistContentEditor.rowState(initiallyPresent = true, selected = true))
        assertEquals(PlaylistEditorRowState.REMOVED,
            PlaylistContentEditor.rowState(initiallyPresent = true, selected = false))
        assertEquals(PlaylistEditorRowState.ADDED,
            PlaylistContentEditor.rowState(initiallyPresent = false, selected = true))
        assertEquals(PlaylistEditorRowState.UNCHANGED,
            PlaylistContentEditor.rowState(initiallyPresent = false, selected = false))
    }

    @Test fun editorRowStateReturnsToBaselineWhenToggleIsUndone() {
        val base = mapOf("uri-a" to setOf("a"))
        val selectedAfterRemoval = emptySet<String>()
        assertEquals(PlaylistEditorRowState.REMOVED,
            PlaylistContentEditor.rowState(
                "uri-a" in base, "uri-a" in selectedAfterRemoval))
        assertEquals(1,
            PlaylistContentEditor.pending(base, selectedAfterRemoval).removalCount)
        val restored = selectedAfterRemoval + "uri-a"
        assertEquals(PlaylistEditorRowState.ALREADY_IN_PLAYLIST,
            PlaylistContentEditor.rowState("uri-a" in base, "uri-a" in restored))
        assertFalse(PlaylistContentEditor.pending(base, restored).hasChanges)
    }

    @Test fun renamePreservesIdentityAndUpdatedAtOnlyChangesOnRealEdit() {
        val original = list().copy(tracks = listOf(entry("a"), entry("b")))
        val renamed = PlaylistNameEditor.rename(original, listOf(original),
            " Nuovo nome ", "now")
        assertEquals("Nuovo nome", renamed.name)
        assertEquals(original.playlistId, renamed.playlistId)
        assertEquals(original.createdAt, renamed.createdAt)
        assertEquals(original.tracks, renamed.tracks)
        assertEquals("now", renamed.updatedAt)
        assertEquals(original, PlaylistNameEditor.rename(original, listOf(original),
            " Rock ", "now"))
    }

    @Test fun renameRejectsBlankLongAndCaseInsensitiveCollision() {
        val one = list("first")
        val other = list("second").copy(name = "Jazz")
        for (invalid in listOf(" ", "x".repeat(161), "jAzZ")) {
            var rejected = false
            try {
                PlaylistNameEditor.rename(one, listOf(one, other), invalid, "now")
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
        }
    }
}
