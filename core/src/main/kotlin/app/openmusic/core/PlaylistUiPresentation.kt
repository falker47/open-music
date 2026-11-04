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

    fun miniSeekGeometry(widthDp: Float): MiniSeekGeometry {
        val left = 9f // MusicArt begins 9dp inside the mini-player
        val right = (widthDp - 5f).coerceAtLeast(left) // far edge of Next
        val thumbInset = 2f
        return MiniSeekGeometry(
            sliderStartDp = left - thumbInset,
            sliderWidthDp = right - left + 2f * thumbInset,
            visibleStartDp = left,
            visibleEndDp = right,
        )
    }
}
