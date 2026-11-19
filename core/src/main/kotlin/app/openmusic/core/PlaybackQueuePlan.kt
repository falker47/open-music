package app.openmusic.core

import kotlin.random.Random

/**
 * Session queue contract. Media3's physical item order is authoritative;
 * this planner only supplies deterministic, testable transformations.
 */
object PlaybackQueuePlan {
    fun <T> visible(items: List<T>, currentIndex: Int): List<T> =
        if (currentIndex !in items.indices) emptyList() else items.drop(currentIndex)

    /** Preserve played tracks/current item; shuffle only the actual upcoming tail. */
    fun <T> shuffleUpcoming(items: List<T>, currentIndex: Int, random: Random): List<T> =
        if (currentIndex !in items.indices) items
        else items.take(currentIndex + 1) + items.drop(currentIndex + 1).shuffled(random)

    /** Restore original order only for unplayed items, never requeue played history. */
    fun <T> restoreUpcoming(items: List<T>, currentIndex: Int, baseline: List<T>): List<T> {
        if (currentIndex !in items.indices) return items
        val original = baseline.withIndex().associate { it.value to it.index }
        return items.take(currentIndex + 1) + items.drop(currentIndex + 1)
            .sortedBy { original[it] ?: Int.MAX_VALUE }
    }

    fun canEditUpcoming(index: Int, currentIndex: Int, count: Int): Boolean =
        currentIndex >= 0 && index > currentIndex && index < count

    fun canMoveUpcoming(from: Int, to: Int, currentIndex: Int, count: Int): Boolean =
        from != to && canEditUpcoming(from, currentIndex, count) &&
            canEditUpcoming(to, currentIndex, count)

    /** One threshold crossing is exactly one future-track move; no current-item skips. */
    fun dragTarget(
        from: Int, totalDragPx: Float, thresholdPx: Float,
        currentIndex: Int, count: Int,
    ): Int? {
        if (!totalDragPx.isFinite() || !thresholdPx.isFinite() ||
            thresholdPx <= 0f || kotlin.math.abs(totalDragPx) < thresholdPx) return null
        val to = from + if (totalDragPx > 0f) 1 else -1
        return to.takeIf { canMoveUpcoming(from, it, currentIndex, count) }
    }
}
