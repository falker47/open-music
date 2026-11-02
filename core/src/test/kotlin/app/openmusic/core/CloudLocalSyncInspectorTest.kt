package app.openmusic.core

import org.junit.Assert.*
import org.junit.Test

class CloudLocalSyncInspectorTest {
    private fun entry(id: String, hash: Char) = PlaylistEntry(
        id,
        TrackIdentity(hash.toString().repeat(64),
            TrackMetadata("Artist", "Track $id"), "$id.mp3"),
    )
    private fun playlist(vararg tracks: PlaylistEntry) = PortablePlaylist(
        "playlist-1", "Rock", "2026-01-01", "2026-01-02", tracks.toList(),
    )

    @Test fun localEditRemainsPendingUntilNewRevisionIsAcknowledged() {
        val first = playlist(entry("one", 'a'))
        val updated = first.copy(
            updatedAt = "2026-01-03",
            tracks = first.tracks + entry("two", 'b'),
        )
        val firstDigest = CloudPlaylistDigest.of(first)
        val updatedDigest = CloudPlaylistDigest.of(updated)
        val checkpoint = CloudCheckpoint("rev-a", firstDigest)
        val root = CloudRevision("playlist-1", "rev-a", emptyList(), firstDigest)

        assertEquals(1, CloudLocalSyncInspector.inspect(listOf(updated),
            mapOf("playlist-1" to checkpoint)).pendingCount)
        assertEquals(CloudSyncAction.UPLOAD, CloudSyncPlanner.decide(
            "playlist-1", updatedDigest, checkpoint, listOf(root)).action)

        val uploaded = CloudRevision("playlist-1", "rev-b", listOf("rev-a"), updatedDigest)
        val saved = CloudCheckpoint(uploaded.revisionId, uploaded.playlistDigest)
        assertEquals(0, CloudLocalSyncInspector.inspect(listOf(updated),
            mapOf("playlist-1" to saved)).pendingCount)
        // A fresh installation has no local copy or checkpoint; it must download rev-b.
        val restore = CloudSyncPlanner.decide(
            "playlist-1", null, null, listOf(root, uploaded))
        assertEquals(CloudSyncAction.DOWNLOAD, restore.action)
        assertEquals(updatedDigest, restore.head?.playlistDigest)
        assertEquals(listOf("one", "two"), updated.tracks.map { it.entryId })
    }

    @Test fun importsAndNewPlaylistsArePendingEvenWithoutCheckpoints() {
        val withDuplicates = playlist(entry("one", 'a'), entry("two", 'a'))
        val status = CloudLocalSyncInspector.inspect(listOf(withDuplicates), emptyMap())
        assertEquals(1, status.pendingCount)
        assertEquals(0, status.missingCount)
        // Identical SHA-256 does not remove imported entries from the portable digest.
        assertNotEquals(CloudPlaylistDigest.of(playlist(entry("one", 'a'))),
            CloudPlaylistDigest.of(withDuplicates))
    }

    @Test fun concurrentRemoteEditKeepsLocalPendingAndRequiresConflictResolution() {
        val first = playlist(entry("one", 'a'))
        val local = first.copy(tracks = first.tracks + entry("two", 'b'))
        val checkpoint = CloudCheckpoint("rev-a", CloudPlaylistDigest.of(first))
        val root = CloudRevision("playlist-1", "rev-a", emptyList(), checkpoint.playlistDigest)
        val remote = CloudRevision("playlist-1", "rev-b", listOf("rev-a"), "c".repeat(64))

        assertEquals(1, CloudLocalSyncInspector.inspect(listOf(local),
            mapOf("playlist-1" to checkpoint)).pendingCount)
        assertEquals(CloudSyncAction.CONFLICT, CloudSyncPlanner.decide(
            "playlist-1", CloudPlaylistDigest.of(local), checkpoint,
            listOf(root, remote)).action)
        val fork = CloudRevision("playlist-1", "rev-c", listOf("rev-a"), "d".repeat(64))
        assertEquals(CloudSyncAction.CONFLICT, CloudSyncPlanner.decide(
            "playlist-1", checkpoint.playlistDigest, checkpoint,
            listOf(root, remote, fork)).action)
    }

    @Test fun missingLocalSnapshotIsWarningNotAutomaticCloudDeletion() {
        val checkpoint = CloudCheckpoint("rev-a", "a".repeat(64))
        val state = CloudLocalSyncInspector.inspect(
            emptyList(), mapOf("playlist-1" to checkpoint))
        assertEquals(0, state.pendingCount)
        assertEquals(1, state.missingCount)
    }
}
