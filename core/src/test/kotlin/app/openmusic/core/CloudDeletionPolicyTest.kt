package app.openmusic.core

import org.junit.Assert.*
import org.junit.Test

class CloudDeletionPolicyTest {
    private val base = CloudRevision("playlist-1", "rev-1", emptyList(), "a".repeat(64))
    private val checkpoint = CloudCheckpoint(base.revisionId, base.playlistDigest)

    @Test fun deletingLocallyNeverSilentlyReimportsTheCloudCopy() {
        val decision = CloudSyncPlanner.decide("playlist-1", null, checkpoint,
            listOf(base), locallyDeleted = true)
        assertEquals(CloudSyncAction.CONFLICT, decision.action)
    }

    @Test fun deletionOnCloudNeverSilentlyDeletesAStillPresentLocalPlaylist() {
        val remoteDelete = base.copy(revisionId = "rev-2", parents = listOf("rev-1"),
            deleted = true)
        val decision = CloudSyncPlanner.decide("playlist-1", base.playlistDigest,
            checkpoint, listOf(base, remoteDelete))
        assertEquals(CloudSyncAction.CONFLICT, decision.action)
    }

    @Test fun anAlreadyDeletedCloudPlaylistIsNotResurrectedOnFreshInstall() {
        val remoteDelete = base.copy(revisionId = "rev-2", parents = listOf("rev-1"),
            deleted = true)
        val decision = CloudSyncPlanner.decide("playlist-1", null, null,
            listOf(base, remoteDelete))
        assertEquals(CloudSyncAction.ADOPT_TOMBSTONE, decision.action)
    }

    @Test fun acknowledgedTombstoneRemainsStableOnRepeatedSync() {
        val remoteDelete = base.copy(revisionId = "rev-2", parents = listOf("rev-1"),
            deleted = true)
        val decision = CloudSyncPlanner.decide("playlist-1", null,
            CloudCheckpoint(remoteDelete.revisionId, remoteDelete.playlistDigest, deleted = true),
            listOf(base, remoteDelete), locallyDeleted = true)
        assertEquals(CloudSyncAction.ADOPT_TOMBSTONE, decision.action)
    }
}
