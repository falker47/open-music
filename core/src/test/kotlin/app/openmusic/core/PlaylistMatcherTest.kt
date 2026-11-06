package app.openmusic.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class PlaylistMatcherTest {
    private fun meta(index: Int, source: String = "filename") = TrackMetadata(
        artist = "Artist $index", title = "Song $index", album = "Album",
        durationMs = 200_000L, source = source,
    )
    private fun hash(index: Int) = index.toString(16).padStart(64, '0')
    private fun entry(index: Int, source: String = "filename", sha: String? = hash(index)) =
        PlaylistEntry("entry-$index", TrackIdentity(sha, meta(index, source), "old-$index.mp3"))
    private fun playlist(entries: List<PlaylistEntry>) =
        PortablePlaylist("list-1", "Test", "2026-10-24", "2026-10-24", entries)

    @Test fun fiftyToFortyDifferentPathsAndNames() {
        val entries = (1..50).map { entry(it) }
        val copied = (1..40).map { i ->
            LocalCopy("content://music/new-$i", TrackIdentity(hash(i), meta(i)))
        }
        val result = PlaylistMatcher.resolve(playlist(entries), copied)
        assertEquals(40, result.count { it.status == MatchStatus.EXACT })
        assertEquals(10, result.count { it.status == MatchStatus.MISSING })
        assertEquals(0, result.count { it.status == MatchStatus.REVIEW || it.status == MatchStatus.AMBIGUOUS })
        assertEquals(entries.map { it.entryId }, result.map { it.entryId })
    }

    @Test fun sameBytesDifferentPathsAreEquivalent() {
        val matches = PlaylistMatcher.resolve(playlist(listOf(entry(1))), listOf(
            LocalCopy("z", TrackIdentity(hash(1), meta(1))),
            LocalCopy("a", TrackIdentity(hash(1), meta(1))),
        ))
        assertEquals(MatchStatus.EXACT, matches.single().status)
        assertEquals("a", matches.single().localUri)
    }

    @Test fun differentAudioSameTagNeedsReview() {
        val matches = PlaylistMatcher.resolve(playlist(listOf(entry(1, "tags"))), listOf(
            LocalCopy("different.mp3", TrackIdentity(hash(2), meta(1, "tags"))),
        ))
        assertEquals(MatchStatus.REVIEW, matches.single().status)
        assertNull(matches.single().localUri)
    }

    @Test fun ambiguousRecordingsCannotAutoLink() {
        val matches = PlaylistMatcher.resolve(playlist(listOf(entry(1, "tags"))), listOf(
            LocalCopy("live.mp3", TrackIdentity(hash(2), meta(1, "tags"))),
            LocalCopy("studio.mp3", TrackIdentity(hash(3), meta(1, "tags"))),
        ))
        assertEquals(MatchStatus.AMBIGUOUS, matches.single().status)
        assertNull(matches.single().localUri)
        assertEquals(2, matches.single().candidates.size)
    }

    @Test fun metadataFallbackOnlyForUnhashedLegacyPlaylist() {
        val result = PlaylistMatcher.resolve(
            playlist(listOf(entry(1, "tags", sha = null))),
            listOf(LocalCopy("new.mp3", TrackIdentity(hash(7), meta(1, "tags")))),
        )
        assertEquals(MatchStatus.METADATA, result.single().status)
        assertEquals("new.mp3", result.single().localUri)
    }

    @Test fun filenamesAloneCannotProduceFalseMatches() {
        val result = PlaylistMatcher.resolve(
            playlist(listOf(entry(1))),
            listOf(LocalCopy("mismatch.mp3", TrackIdentity(hash(2), meta(1)))),
        )
        assertEquals(MatchStatus.MISSING, result.single().status)
    }

    @Test fun preservesOrderAndDuplicatePlaylistEntries() {
        val result = PlaylistMatcher.resolve(
            playlist(listOf(entry(3), entry(1), entry(3).copy(entryId = "repeated"))),
            listOf(
                LocalCopy("one", TrackIdentity(hash(1), meta(1))),
                LocalCopy("three", TrackIdentity(hash(3), meta(3))),
            ),
        )
        assertEquals(listOf("entry-3", "entry-1", "repeated"), result.map { it.entryId })
        assertEquals(listOf("three", "one", "three"), result.map { it.localUri })
    }

    @Test fun unicodeTagNormalization() {
        val original = TrackMetadata("Björk", "  Jóga ", durationMs = 201000, source = "tags")
        val candidate = TrackMetadata("BJÖRK", "JÓGA", durationMs = 200000, source = "tags")
        val result = PlaylistMatcher.resolve(
            playlist(listOf(PlaylistEntry("i", TrackIdentity(null, original)))),
            listOf(LocalCopy("song", TrackIdentity(null, candidate))),
        )
        assertEquals(MatchStatus.METADATA, result.single().status)
    }

    @Test fun sha256KnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentDigest.sha256(ByteArrayInputStream("abc".toByteArray())),
        )
    }

    @Test fun verificationDoesNotPrematurelyMarkMissing() {
        assertEquals(VerificationDisplay.ANALYZING,
            PlaylistVerification.display(MatchStatus.MISSING, true, false))
        assertEquals(VerificationDisplay.REVIEW,
            PlaylistVerification.display(MatchStatus.MISSING, false, false))
        assertEquals(VerificationDisplay.MISSING,
            PlaylistVerification.display(MatchStatus.MISSING, false, true))
        assertEquals(VerificationDisplay.AMBIGUOUS,
            PlaylistVerification.display(MatchStatus.AMBIGUOUS, false, true))
        assertEquals(VerificationDisplay.AVAILABLE,
            PlaylistVerification.display(MatchStatus.EXACT, false, false))
        assertEquals(VerificationDisplay.REVIEW,
            PlaylistVerification.display(MatchStatus.METADATA, false, true))
    }

    @Test fun importedIdentityAndOrderStayUnchangedDuringMatching() {
        val input = playlist(listOf(entry(2), entry(1), entry(2).copy(entryId = "copy")))
        val originalEntries = input.tracks.toList()
        val result = PlaylistMatcher.resolve(input, listOf(
            LocalCopy("uri:2", TrackIdentity(hash(2), meta(2))),
            LocalCopy("uri:1", TrackIdentity(hash(1), meta(1))),
        ))
        assertEquals(listOf("entry-2", "entry-1", "copy"), result.map { it.entryId })
        assertEquals(originalEntries, input.tracks)
        assertEquals(listOf(MatchStatus.EXACT, MatchStatus.EXACT, MatchStatus.EXACT),
            result.map { it.status })
    }

    @Test fun playlistContainsNoDevicePathByConstruction() {
        val entry = entry(1)
        assertEquals("old-1.mp3", entry.identity.filenameHint)
        assertTrue(PlaylistMatcher.resolve(playlist(listOf(entry)), emptyList())
            .single().status == MatchStatus.MISSING)
    }
}
