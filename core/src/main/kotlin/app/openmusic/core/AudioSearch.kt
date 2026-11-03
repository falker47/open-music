package app.openmusic.core

/** A query applies within either complementary category, never to the SHA inventory. */
object AudioSearch {
    fun matches(title: String, artist: String?, query: String): Boolean {
        val trimmed = query.trim()
        return title.contains(trimmed, ignoreCase = true) ||
            artist.orEmpty().contains(trimmed, ignoreCase = true)
    }
}

enum class PlaylistNavLayout { NONE, SINGLE, DOUBLE, PICKER }

object PlaylistSelection {
    fun showSelector(count: Int): Boolean = count > 1

    fun layout(count: Int): PlaylistNavLayout = when {
        count <= 0 -> PlaylistNavLayout.NONE
        count == 1 -> PlaylistNavLayout.SINGLE
        count == 2 -> PlaylistNavLayout.DOUBLE
        else -> PlaylistNavLayout.PICKER
    }

    /** Keep a saved selection only while its playlist still exists. */
    fun resolveId(playlists: List<PortablePlaylist>, requestedId: String?): String? =
        playlists.firstOrNull { it.playlistId == requestedId }?.playlistId
            ?: playlists.firstOrNull()?.playlistId
}
