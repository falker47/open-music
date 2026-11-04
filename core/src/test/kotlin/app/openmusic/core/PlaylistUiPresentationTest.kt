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

    @Test fun miniSeekVisibleTrackSharesArtworkAndNextButtonEdges() {
        val normal = PlaylistUiPresentation.miniSeekGeometry(340f)
        assertEquals(9f, normal.visibleStartDp, 0.001f)
        assertEquals(335f, normal.visibleEndDp, 0.001f)
        assertEquals(7f, normal.sliderStartDp, 0.001f)
        assertEquals(330f, normal.sliderWidthDp, 0.001f)
        val narrow = PlaylistUiPresentation.miniSeekGeometry(240f)
        assertEquals(9f, narrow.visibleStartDp, 0.001f)
        assertEquals(235f, narrow.visibleEndDp, 0.001f)
    }
}
