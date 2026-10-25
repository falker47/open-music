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
    fun decide(playlists: List<PortablePlaylist>): PlaylistDestination = when (playlists.size) {
        0 -> PlaylistDestination.CreateFirst
        1 -> PlaylistDestination.Direct(playlists.single().playlistId)
        else -> PlaylistDestination.Choose
    }
}
