package app.openmusic.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PlaybackQueuePlanTest {
    @Test fun currentTrackIsFirstAndOnlyTheActualUpcomingTailIsShown() {
        assertEquals(listOf("C", "D", "E"),
            PlaybackQueuePlan.visible(listOf("A", "B", "C", "D", "E"), 2))
        assertEquals(emptyList<String>(), PlaybackQueuePlan.visible(listOf("A"), -1))
    }

    @Test fun shuffleKeepsPastAndCurrentAndPermutesEveryUpcomingEntryExactlyOnce() {
        val input = (0..9).toList()
        val result = PlaybackQueuePlan.shuffleUpcoming(input, 3, Random(17))
        assertEquals(input.take(4), result.take(4))
        assertEquals(input.drop(4).toSet(), result.drop(4).toSet())
        assertNotEquals(input, result)
    }

    @Test fun restoreUpcomingOrderUsesSavedSessionBaselineWithoutReplayingPast() {
        val original = listOf("a", "b", "c", "d", "e", "f")
        val shuffled = listOf("a", "b", "c", "f", "d", "e")
        assertEquals(listOf("a", "b", "c", "d", "e", "f"),
            PlaybackQueuePlan.restoreUpcoming(shuffled, 2, original))
        val advanced = listOf("a", "b", "c", "f", "e", "d")
        assertEquals(listOf("a", "b", "c", "d", "e", "f"),
            PlaybackQueuePlan.restoreUpcoming(advanced, 2, original))
    }

    @Test fun onlyUpcomingTracksCanBeMovedOrRemoved() {
        assertFalse(PlaybackQueuePlan.canEditUpcoming(1, 1, 5))
        assertTrue(PlaybackQueuePlan.canEditUpcoming(2, 1, 5))
        assertFalse(PlaybackQueuePlan.canEditUpcoming(5, 1, 5))
        assertTrue(PlaybackQueuePlan.canMoveUpcoming(3, 2, 1, 5))
        assertFalse(PlaybackQueuePlan.canMoveUpcoming(2, 1, 1, 5))
    }
}
