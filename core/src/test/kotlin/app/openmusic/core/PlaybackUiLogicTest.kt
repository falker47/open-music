package app.openmusic.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackUiLogicTest {
    @Test fun seekPreviewFollowsDraggingFractionAndClampsToDuration() {
        assertEquals(120_000L, PlaybackUiLogic.seekTargetMs(0.5f, 240_000L))
        assertEquals(0L, PlaybackUiLogic.seekTargetMs(-0.2f, 240_000L))
        assertEquals(240_000L, PlaybackUiLogic.seekTargetMs(1.4f, 240_000L))
    }

    @Test fun invalidDurationAndNonFinitePointerNeverProduceUnusableSeekTargets() {
        assertEquals(0L, PlaybackUiLogic.seekTargetMs(0.4f, -1L))
        assertEquals(0L, PlaybackUiLogic.seekTargetMs(0.4f, 0L))
        assertEquals(0L, PlaybackUiLogic.seekTargetMs(Float.NaN, 240_000L))
        assertEquals(0L, PlaybackUiLogic.seekTargetMs(Float.POSITIVE_INFINITY, 240_000L))
    }

    @Test fun repeatOneTogglesWithoutAffectingShuffleState() {
        assertTrue(PlaybackUiLogic.toggleRepeatOne(false))
        assertFalse(PlaybackUiLogic.toggleRepeatOne(true))
    }

    @Test fun queueSelectionChecksIndexAgainstCurrentQueueLength() {
        assertTrue(PlaybackUiLogic.canSelectQueue(0, 1))
        assertTrue(PlaybackUiLogic.canSelectQueue(2, 3))
        assertFalse(PlaybackUiLogic.canSelectQueue(-1, 3))
        assertFalse(PlaybackUiLogic.canSelectQueue(3, 3))
        assertFalse(PlaybackUiLogic.canSelectQueue(0, 0))
    }
}
