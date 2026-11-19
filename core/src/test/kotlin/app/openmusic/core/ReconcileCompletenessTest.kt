package app.openmusic.core

import org.junit.Assert.*
import org.junit.Test

class ReconcileCompletenessTest {
    private fun entry(
        id: String,
        hash: String? = id.sumOf { it.code }.toString(16).padStart(64, '0'),
    ) =
        PlaylistEntry(id, TrackIdentity(hash,
            TrackMetadata("Artist", "Song $id", "Album", 180_000, "tags")))
    private fun list(id: String, vararg entries: PlaylistEntry) =
        PortablePlaylist(id, id, "2026-01-01", "2026-01-01", entries.toList())
    private fun exact(id: String, uri: String = "content://media/external/audio/media/$id") =
        MatchResult(id, MatchStatus.EXACT, uri)

    @Test fun allVerifiedExactMayFinishBeforeFullInventory() {
        val rock = list("rock", entry("one"), entry("two"))
        val pop = list("pop", entry("three"))
        val empty = list("empty")
        // These three verified local copies are sufficient even when the
        // original MediaStore scan contains hundreds of other audio files.
        val matches = mapOf("rock" to listOf(exact("one"), exact("two")),
            "pop" to listOf(exact("three")), "empty" to emptyList())
        assertTrue(ReconcileCompleteness.allExact(listOf(rock, pop, empty), matches))
    }

    @Test fun unknownMissingAmbiguousAndMetadataLinksNeverFinishEarly() {
        val required = list("rock", entry("one"), entry("two"))
        val statuses = listOf(MatchStatus.MISSING, MatchStatus.AMBIGUOUS,
            MatchStatus.METADATA, MatchStatus.REVIEW)
        statuses.forEach { status ->
            val result = mapOf("rock" to listOf(exact("one"),
                MatchResult("two", status, if (status == MatchStatus.METADATA)
                    "content://media/external/audio/media/2" else null)))
            assertFalse(status.toString(),
                ReconcileCompleteness.allExact(listOf(required), result))
        }
        assertFalse(ReconcileCompleteness.allExact(listOf(required),
            mapOf("rock" to listOf(exact("one")))))
        assertFalse(ReconcileCompleteness.allExact(listOf(required),
            mapOf("rock" to listOf(exact("one"), exact("wrong")))))
        assertFalse(ReconcileCompleteness.allExact(listOf(required), emptyMap()))
    }

    @Test fun hashlessOrEmptyPlaylistsCannotTriggerEarlyStop() {
        assertFalse(ReconcileCompleteness.allExact(emptyList(), emptyMap()))
        assertFalse(ReconcileCompleteness.allExact(
            listOf(list("empty")), mapOf("empty" to emptyList())))
        val hashless = list("legacy", entry("x", null))
        assertFalse(ReconcileCompleteness.allExact(
            listOf(hashless), mapOf("legacy" to listOf(exact("x")))))
    }

    @Test fun duplicateImportedEntriesStillRequireTheirOwnExactResult() {
        val same = entry("first", "a".repeat(64))
        val duplicate = entry("second", "a".repeat(64))
        val playlist = list("imported", same, duplicate)
        assertTrue(ReconcileCompleteness.allExact(listOf(playlist),
            mapOf("imported" to listOf(exact("first"), exact("second")))))
        assertFalse(ReconcileCompleteness.allExact(listOf(playlist),
            mapOf("imported" to listOf(exact("first")))))
    }

    @Test fun laterManualHashInsertsIntoPartialInventoryWithoutDuplicates() {
        val original = LocalCopy("content://media/external/audio/media/1",
            TrackIdentity(null, TrackMetadata(title = "Old")))
        val verified = original.copy(identity = original.identity.copy(sha256 = "a".repeat(64)))
        val other = LocalCopy("content://media/external/audio/media/2",
            TrackIdentity("b".repeat(64), TrackMetadata(title = "New")))
        assertEquals(listOf(verified), VerifiedCopies.upsert(listOf(original), verified))
        assertEquals(listOf(verified, other), VerifiedCopies.upsert(listOf(verified), other))
        assertEquals(listOf(verified, other), VerifiedCopies.upsert(
            listOf(original, other), verified))
    }
}
