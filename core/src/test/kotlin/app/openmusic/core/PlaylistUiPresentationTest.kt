package app.openmusic.core

import org.junit.Assert.*
import org.junit.Test

class PlaylistUiPresentationTest {
    @Test fun completedAvailabilityShowsAvailableOverTotal() {
        assertEquals("7 / 7 brani disponibili",
            PlaylistUiPresentation.availability(available = 7, total = 7,
                analyzing = false, needsVerification = false))
        assertEquals("3 / 7 brani disponibili",
            PlaylistUiPresentation.availability(available = 3, total = 7,
                analyzing = false, needsVerification = false))
        assertEquals("0 / 0 brani disponibili",
            PlaylistUiPresentation.availability(available = 0, total = 0,
                analyzing = false, needsVerification = false))
    }

    @Test fun incompleteAnalysisNeverAppearsAsCompleted() {
        val working = PlaylistUiPresentation.availability(
            available = 3, total = 7, analyzing = true, needsVerification = false)
        val pending = PlaylistUiPresentation.availability(
            available = 3, total = 7, analyzing = false, needsVerification = true)
        assertTrue(working.contains("verifica in corso"))
        assertTrue(pending.contains("da verificare"))
        assertFalse(working.contains("brani disponibili"))
        assertFalse(pending.contains("brani disponibili"))
    }

    @Test fun fullscreenTopControlsRespectTheTargetGapAndNarrowWidths() {
        assertEquals(62.4f, PlaylistUiPresentation.topControlGapDp(340f), 0.001f)
        assertEquals(58f, PlaylistUiPresentation.topControlGapDp(260f), 0.001f)
        assertEquals(0f, PlaylistUiPresentation.topControlGapDp(144f), 0.001f)
        assertTrue(3f * 48f + 2f * PlaylistUiPresentation.topControlGapDp(260f) <= 260f)
    }

    @Test fun miniSeekTrackAndTimestampsShareRealArtworkAndNextInsets() {
        // The mini-player row and Slider share the same deliberate 14dp
        // inset; a 4dp Material3 thumb adds 2dp inside each Slider edge.
        assertEquals(14f, PlaylistUiPresentation.miniPlayerInsetDp, 0.001f)
        val normal = PlaylistUiPresentation.miniSeekGeometry(340f)
        assertEquals(14f, normal.visibleStartDp, 0.001f)
        assertEquals(326f, normal.visibleEndDp, 0.001f)
        assertEquals(12f, normal.sliderStartDp, 0.001f)
        assertEquals(316f, normal.sliderWidthDp, 0.001f)

        val narrow = PlaylistUiPresentation.miniSeekGeometry(240f)
        assertEquals(14f, narrow.visibleStartDp, 0.001f)
        assertEquals(226f, narrow.visibleEndDp, 0.001f)
        assertEquals(12f, narrow.sliderStartDp, 0.001f)
        assertEquals(216f, narrow.sliderWidthDp, 0.001f)
        assertEquals(0f, PlaylistUiPresentation.miniSeekGeometry(28f).sliderWidthDp - 4f, 0.001f)
    }

    @Test fun selectorAlwaysRendersOneRowForAnyNonEmptyPlaylistCollection() {
        assertEquals(0, PlaylistUiPresentation.selectorTabCount(0))
        assertEquals(1, PlaylistUiPresentation.selectorTabCount(1))
        assertEquals(1, PlaylistUiPresentation.selectorTabCount(2))
        assertEquals(1, PlaylistUiPresentation.selectorTabCount(25))
        assertFalse(PlaylistUiPresentation.canChooseOtherPlaylist(1))
        assertTrue(PlaylistUiPresentation.canChooseOtherPlaylist(2))
    }

    @Test fun redundantAvailabilitySummaryIsHiddenButExceptionsRemainAccessible() {
        assertFalse(PlaylistUiPresentation.needsExceptionSummary(0, 0))
        assertTrue(PlaylistUiPresentation.needsExceptionSummary(1, 0))
        assertTrue(PlaylistUiPresentation.needsExceptionSummary(0, 1))
        assertTrue(PlaylistUiPresentation.needsExceptionSummary(3, 5))
    }

    @Test fun playlistHeaderRightPanelTakesExactlyHalfOfThePaddedContentWidth() {
        for (contentWidth in listOf(284f, 324f, 376f, 564f)) {
            val card = PlaylistUiPresentation.playlistHeaderPanelWidthDp(contentWidth)
            assertEquals(contentWidth / 2f, card, 0.001f)
            assertTrue(card <= contentWidth / 2f)
            // Adding the existing 18dp right margin gives half the full screen.
            assertEquals((contentWidth + 36f) / 2f, card + 18f, 0.001f)
        }
        assertEquals(0f, PlaylistUiPresentation.playlistHeaderPanelWidthDp(-10f), 0.001f)
    }

    @Test fun playlistWordmarkIsCentredOnSameFirstControlRowAsLibraryBrand() {
        assertEquals(48f, PlaylistUiPresentation.playlistHeaderControlRowHeightDp, 0.001f)
        assertEquals(38f, PlaylistUiPresentation.playlistHeaderWordmarkHeightDp, 0.001f)
        assertEquals(5f, PlaylistUiPresentation.playlistHeaderWordmarkTopInsetDp, 0.001f)
        assertEquals(48f, PlaylistUiPresentation.playlistHeaderMinPanelHeightDp(), 0.001f)
    }

    @Test fun driveCardVisualBoundsMatchTheWordmarkEvenWhenLinked() {
        assertEquals(38f, PlaylistUiPresentation.playlistHeaderVisualHeightDp, 0.001f)
        assertEquals(48f, PlaylistUiPresentation.playlistHeaderTouchHeightDp, 0.001f)
        assertEquals(
            PlaylistUiPresentation.playlistHeaderWordmarkHeightDp,
            PlaylistUiPresentation.playlistHeaderVisualHeightDp, 0.001f)
        assertEquals(48f, PlaylistUiPresentation.playlistHeaderMinPanelHeightDp(), 0.001f)
    }

    @Test fun playlistContextMenuHasCreateImportExportRenameDeleteThenRelink() {
        assertEquals(
            listOf(
                PlaylistMenuCommand.NEW_PLAYLIST,
                PlaylistMenuCommand.IMPORT,
                PlaylistMenuCommand.EXPORT,
                PlaylistMenuCommand.RENAME,
                PlaylistMenuCommand.DELETE,
                PlaylistMenuCommand.RELINK,
            ),
            PlaylistUiPresentation.playlistMenuOrder(),
        )
    }
}
