package app.openmusic.core

import org.junit.Assert.*
import org.junit.Test

class CloudSyncPlannerTest {
    private fun playlist(name: String = "Rock", tracks: List<PlaylistEntry> = listOf(
        PlaylistEntry("entry-1", TrackIdentity("a".repeat(64),
            TrackMetadata("Artist", "Song", "Album", 123_000L, "tags"), "song.mp3")),
    )) = PortablePlaylist("playlist-1", name, "2026-01-01", "2026-01-02", tracks)
    private val root = CloudRevision("playlist-1", "rev-a", emptyList(), "hash-a")
    private val checkpoint = CloudCheckpoint("rev-a", "hash-a")
    private fun decide(local: String?, saved: CloudCheckpoint? = checkpoint,
        history: List<CloudRevision> = listOf(root)) =
        CloudSyncPlanner.decide("playlist-1", local, saved, history)

    @Test fun deterministicHashCapturesIdentityOrderMissingAndDuplicates() {
        val original = playlist()
        val hash = CloudPlaylistDigest.of(original)
        assertEquals(hash, CloudPlaylistDigest.of(original.copy()))
        assertEquals(64, hash.length)
        assertNotEquals(hash, CloudPlaylistDigest.of(original.copy(name = "Pop")))
        assertNotEquals(hash, CloudPlaylistDigest.of(original.copy(
            tracks = original.tracks.reversed() + original.tracks)))
        assertNotEquals(hash, CloudPlaylistDigest.of(original.copy(
            tracks = listOf(original.tracks.first().copy(identity =
                original.tracks.first().identity.copy(sha256 = null))))))
        assertNotEquals(hash, CloudPlaylistDigest.of(original.copy(
            tracks = original.tracks.map { it.copy(entryId = "other-entry") })))
    }

    @Test fun firstDeviceUploadsAndSecondDownloadsWithoutEditingLocalFirst() {
        assertEquals(CloudSyncAction.UPLOAD, decide("hash-a", null, emptyList()).action)
        assertEquals(CloudSyncAction.DOWNLOAD, decide(null, null).action)
        assertEquals(CloudSyncAction.ADOPT, decide("hash-a", null).action)
        assertEquals(CloudSyncAction.CONFLICT, decide("other", null).action)
    }

    @Test fun unchangedAndOneSidedEditsAreSafe() {
        assertEquals(CloudSyncAction.NONE, decide("hash-a").action)
        assertEquals(CloudSyncAction.UPLOAD, decide("locally edited").action)
        val updated = root.copy(revisionId = "rev-b", parents = listOf("rev-a"),
            playlistDigest = "hash-b")
        assertEquals(CloudSyncAction.DOWNLOAD,
            decide("hash-a", checkpoint, listOf(root, updated)).action)
    }

    @Test fun concurrentEditsRequireExplicitUserDecision() {
        val updated = root.copy(revisionId = "rev-b", parents = listOf("rev-a"),
            playlistDigest = "hash-b")
        assertEquals(CloudSyncAction.CONFLICT,
            decide("local-new", checkpoint, listOf(root, updated)).action)
        assertEquals(CloudSyncAction.ADOPT,
            decide("hash-b", checkpoint, listOf(root, updated)).action)
    }

    @Test fun branchedRemoteHistoryIsNeverLastWriterWins() {
        val deviceA = root.copy(revisionId = "rev-b", parents = listOf("rev-a"))
        val deviceB = root.copy(revisionId = "rev-c", parents = listOf("rev-a"))
        val fork = decide("hash-a", checkpoint, listOf(root, deviceA, deviceB))
        assertEquals(CloudSyncAction.CONFLICT, fork.action)
        assertEquals(setOf("rev-b", "rev-c"), fork.heads.map { it.revisionId }.toSet())
        val resolved = root.copy(revisionId = "rev-d", parents = listOf("rev-b", "rev-c"))
        assertEquals(listOf(resolved),
            CloudSyncPlanner.heads(listOf(root, deviceA, deviceB, resolved)))
    }

    @Test fun deletedMissingOrCorruptHistoryCannotSilentlyOverwrite() {
        assertEquals(CloudSyncAction.CONFLICT, decide(null).action)
        assertEquals(CloudSyncAction.CONFLICT, decide("hash-a", checkpoint, emptyList()).action)
        assertEquals(CloudSyncAction.CONFLICT, decide("hash-a", checkpoint,
            listOf(root.copy(deleted = true))).action)
        assertEquals(CloudSyncAction.CONFLICT, decide("hash-a", checkpoint,
            listOf(root.copy(parents = listOf("not-present")))).action)
        assertNull(CloudSyncPlanner.heads(listOf(root, root)))
        assertEquals(CloudSyncAction.CONFLICT, decide("hash-a", checkpoint,
            listOf(root.copy(playlistId = "other"))).action)
    }
}
