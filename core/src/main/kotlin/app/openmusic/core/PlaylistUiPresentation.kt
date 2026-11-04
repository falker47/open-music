package app.openmusic.core

/** Pure Playlist/seek presentation geometry, independent of the Android SDK. */
object PlaylistUiPresentation {
    fun availability(
        available: Int, total: Int, analyzing: Boolean, needsVerification: Boolean,
    ): String = when {
        analyzing -> "$available / $total confermati · verifica in corso"
        needsVerification -> "$available / $total confermati · da verificare"
        else -> "$available / $total brani disponibili"
    }

    /** The navigation never duplicates the active tab for 2+ playlists. */
    fun selectorTabCount(playlistCount: Int): Int = if (playlistCount > 0) 1 else 0

    /** The folder action stays visible but is inactive without alternatives. */
    fun canChooseOtherPlaylist(playlistCount: Int): Boolean = playlistCount > 1

    /** The extra panel is reserved for details that cannot fit in the tab subtitle. */
    fun needsExceptionSummary(unreadableCount: Int, missingCount: Int): Boolean =
        unreadableCount > 0 || missingCount > 0

    /** Equal 48dp touch targets, free horizontal gap = 1.3x button width when possible. */
    fun topControlGapDp(availableWidthDp: Float, buttonWidthDp: Float = 48f): Float =
        (buttonWidthDp * 1.3f).coerceAtMost(
            ((availableWidthDp - 3f * buttonWidthDp) / 2f).coerceAtLeast(0f)
        )

    /** Material3's 4dp thumb places each visible track end 2dp inside Slider. */
    data class MiniSeekGeometry(
        val sliderStartDp: Float, val sliderWidthDp: Float,
        val visibleStartDp: Float, val visibleEndDp: Float,
    )

    /** Shared by the mini-player artwork, Next button, seek track and time labels. */
    const val miniPlayerInsetDp = 14f

    fun miniSeekGeometry(widthDp: Float): MiniSeekGeometry {
        val left = miniPlayerInsetDp
        val right = (widthDp - miniPlayerInsetDp).coerceAtLeast(left)
        val thumbInset = 2f
        return MiniSeekGeometry(
            sliderStartDp = left - thumbInset,
            sliderWidthDp = right - left + 2f * thumbInset,
            visibleStartDp = left,
            visibleEndDp = right,
        )
    }
}
