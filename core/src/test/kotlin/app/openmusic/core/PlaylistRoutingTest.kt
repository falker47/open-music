package app.openmusic.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistRoutingTest {
    private fun playlist(id: String) =
        PortablePlaylist(id, id, "2026-10-25", "2026-10-25", emptyList())

    @Test fun noneRequiresPlaylistCreation() {
        assertEquals(PlaylistDestination.CreateFirst, PlaylistRouting.decide(emptyList()))
    }

    @Test fun onePlaylistCanBeSelectedWithoutAChooser() {
        assertEquals(PlaylistDestination.Direct("first"),
            PlaylistRouting.decide(listOf(playlist("first"))))
    }

    @Test fun twoPlaylistsMustPromptRatherThanUseLastSelected() {
        val playlists = listOf(playlist("first"), playlist("last-selected"))
        assertEquals(PlaylistDestination.Choose, PlaylistRouting.decide(playlists))
        assertEquals(PlaylistDestination.Choose, PlaylistRouting.decide(playlists.reversed()))
    }

    @Test fun manyPlaylistsRequireExplicitDestination() {
        val playlists = (1..100).map { playlist("playlist-$it") }
        assertEquals(PlaylistDestination.Choose, PlaylistRouting.decide(playlists))
    }
}
