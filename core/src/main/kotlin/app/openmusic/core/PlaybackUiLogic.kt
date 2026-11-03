package app.openmusic.core

/** Pure guards for Android playback controls. No platform/player dependencies. */
object PlaybackUiLogic {
    /** Seek previews and committed seeks share the same clamped timestamp. */
    fun seekTargetMs(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L || !fraction.isFinite()) return 0L
        return (fraction.coerceIn(0f, 1f).toDouble() * durationMs)
            .toLong().coerceIn(0L, durationMs)
    }

    /** Repeat-one is intentionally a simple on/off switch. Shuffle is independent. */
    fun toggleRepeatOne(current: Boolean): Boolean = !current

    fun canSelectQueue(index: Int, count: Int): Boolean = index >= 0 && index < count
}
