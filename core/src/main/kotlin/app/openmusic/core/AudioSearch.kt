package app.openmusic.core

/** A query applies within either complementary category, never to the SHA inventory. */
object AudioSearch {
    fun matches(title: String, artist: String?, query: String): Boolean {
        val trimmed = query.trim()
        return title.contains(trimmed, ignoreCase = true) ||
            artist.orEmpty().contains(trimmed, ignoreCase = true)
    }
}

object PlaylistSelection {
    fun showSelector(count: Int): Boolean = count > 1
}
