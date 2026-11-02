package app.openmusic.core

/** Local-only comparison with the last acknowledged Drive snapshots.
 * It does not query Drive, assume that remote versions are current, or upload anything.
 */
data class CloudLocalSyncState(val pendingCount: Int, val missingCount: Int)

object CloudLocalSyncInspector {
    fun inspect(
        playlists: List<PortablePlaylist>,
        checkpoints: Map<String, CloudCheckpoint>,
    ): CloudLocalSyncState {
        val localIds = playlists.map { it.playlistId }.toSet()
        val pending = playlists.count { playlist ->
            checkpoints[playlist.playlistId]?.playlistDigest != CloudPlaylistDigest.of(playlist)
        }
        // A missing local playlist is NOT permission to delete its cloud snapshot.
        val missing = checkpoints.keys.count { it !in localIds }
        return CloudLocalSyncState(pending, missing)
    }
}
