package app.openmusic.core

/** Explicitly ordered contextual actions; UI dispatch must follow this single list. */
enum class PlaylistMenuCommand {
    NEW_PLAYLIST, IMPORT, EXPORT, RENAME, DELETE, RELINK
}

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

    /** Match the Library header wordmark: the first row is 48dp high. */
    const val playlistHeaderControlRowHeightDp = 48f
    const val playlistHeaderWordmarkHeightDp = 38f
    const val playlistHeaderWordmarkTopInsetDp =
        (playlistHeaderControlRowHeightDp - playlistHeaderWordmarkHeightDp) / 2f

    /** The body already has symmetrical 18dp outside margins on both sides. */
    fun playlistHeaderPanelWidthDp(contentWidthDp: Float): Float =
        contentWidthDp.coerceAtLeast(0f) / 2f

    /**
     * The drawable has a 356-unit viewport, with the glyph group translated -182.
     * The normal glyph baseline (e.g. the 's') is y=475; the 'y' descends to 516.
     * Fix the card's TOP to the existing 5dp wordmark inset, and shorten only
     * its bottom to the ordinary baseline. The full 48dp hitboxes do not shrink.
     */
    const val playlistHeaderVisualTopInsetDp = playlistHeaderWordmarkTopInsetDp
    /** 15% extra visual height extends DOWN from the existing top, never upwards. */
    const val playlistHeaderVisualHeightDp =
        playlistHeaderWordmarkHeightDp * (475f - 182f) / 356f * 1.15f
    const val playlistHeaderTouchHeightDp = playlistHeaderControlRowHeightDp

    /** Touch controls center on the visual card rather than on its larger hitbox. */
    const val playlistHeaderRowCenterOffsetDp =
        playlistHeaderVisualTopInsetDp + playlistHeaderVisualHeightDp / 2f -
            playlistHeaderTouchHeightDp / 2f
    const val playlistHeaderLeftInsetDp = 12f
    const val playlistHeaderButtonSizeDp = 48f
    const val playlistHeaderSyncGlyphSizeDp = 21f
    const val playlistHeaderMenuGlyphSizeDp = 24f
    /** Keep the menu's position fixed and reserve its 48dp slot while disconnected. */
    fun playlistHeaderSyncButtonCenterXDp(cardWidthDp: Float): Float =
        cardWidthDp - playlistHeaderButtonSizeDp * 1.5f
    fun playlistHeaderMenuButtonCenterXDp(cardWidthDp: Float): Float =
        cardWidthDp - playlistHeaderButtonSizeDp / 2f
    /** Preserve the earlier request for roughly half the free gap between glyphs. */
    const val playlistHeaderSyncGlyphOffsetDp =
        (playlistHeaderButtonSizeDp -
            (playlistHeaderSyncGlyphSizeDp + playlistHeaderMenuGlyphSizeDp) / 2f) / 2f
    fun playlistHeaderMinPanelHeightDp(): Float = playlistHeaderTouchHeightDp

    /** Offline create/import are contextual playlist actions, not Drive actions. */
    fun playlistMenuOrder(): List<PlaylistMenuCommand> = listOf(
        PlaylistMenuCommand.NEW_PLAYLIST,
        PlaylistMenuCommand.IMPORT,
        PlaylistMenuCommand.EXPORT,
        PlaylistMenuCommand.RENAME,
        PlaylistMenuCommand.DELETE,
        PlaylistMenuCommand.RELINK,
    )

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
