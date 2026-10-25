package app.openmusic.core

/**
 * A previous tab selection is not permission to add a track to that playlist.
 * With two or more playlists the destination must be chosen for every add action.
 */
sealed interface PlaylistDestination {
    data object CreateFirst : PlaylistDestination
    data class Direct(val playlistId: String) : PlaylistDestination
    data object Choose : PlaylistDestination
}

object PlaylistRouting {
    /**
     * Block only new exact-content duplicates inside the target playlist.
     * Existing imported entries are intentionally left unchanged.
     * A null SHA-256 cannot safely establish content equality.
     */
    fun addIfAbsent(
        playlist: PortablePlaylist,
        entry: PlaylistEntry,
        updatedAt: String,
    ): PortablePlaylist? {
        val hash = entry.identity.sha256
        if (!hash.isNullOrBlank() && playlist.tracks.any {
                it.identity.sha256?.equals(hash, ignoreCase = true) == true
            }) return null
        return playlist.copy(updatedAt = updatedAt, tracks = playlist.tracks + entry)
    }

    fun decide(playlists: List<PortablePlaylist>): PlaylistDestination = when (playlists.size) {
        0 -> PlaylistDestination.CreateFirst
        1 -> PlaylistDestination.Direct(playlists.single().playlistId)
        else -> PlaylistDestination.Choose
    }
}
