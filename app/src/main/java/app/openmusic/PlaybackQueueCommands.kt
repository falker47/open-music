package app.openmusic

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Protocol between the UI controller and the existing Media3 service. */
internal object PlaybackQueueCommands {
    const val SET_MODE = "app.openmusic.queue.set_mode"
    const val TOGGLE_SHUFFLE = "app.openmusic.queue.toggle_shuffle"
    const val MODE_KEY = "app.openmusic.queue.mode"
    const val LIBRARY = "library"
    const val PLAYLIST = "playlist"
    const val SHUFFLE = "shuffle"
    const val CUSTOM = "custom"

    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)
    fun modeArgs(mode: String) = Bundle().apply { putString(MODE_KEY, mode) }
}
