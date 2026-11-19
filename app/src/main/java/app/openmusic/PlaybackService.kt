package app.openmusic

import android.content.pm.ApplicationInfo
import android.os.Bundle
import app.openmusic.core.PlaybackQueuePlan
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.random.Random
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionCommands
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns playback independently of the Compose activity. Media3 manages the
 * media notification and exposes its session to Android and external clients.
 */
class PlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    // PlaybackService owns the only queue and its session-scoped mode.
    private var mode = PlaybackQueueCommands.LIBRARY
    private var beforeShuffleMode = PlaybackQueueCommands.LIBRARY
    private var shuffleBaseline: List<String>? = null

    private fun publishMode() {
        mediaSession?.setSessionExtras(PlaybackQueueCommands.modeArgs(mode))
    }

    private fun orderedIds(player: ExoPlayer): List<String> =
        (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    /** Move future Media3 items in-place; current playback and position are untouched. */
    private fun applyUpcomingOrder(player: ExoPlayer, order: List<String>) {
        val current = player.currentMediaItemIndex
        if (current !in 0 until player.mediaItemCount) return
        for (destination in (current + 1) until player.mediaItemCount) {
            val id = order.getOrNull(destination) ?: return
            val from = (destination until player.mediaItemCount).firstOrNull {
                player.getMediaItemAt(it).mediaId == id
            } ?: return
            if (from != destination) player.moveMediaItem(from, destination)
        }
    }

    private fun toggleShufflePlan(player: ExoPlayer) {
        if (player.currentMediaItemIndex !in 0 until player.mediaItemCount) return
        // Native shuffle is intentionally off: the Media3 list itself is the
        // effective planned order, visible to all connected controllers.
        if (player.shuffleModeEnabled) player.shuffleModeEnabled = false
        if (mode == PlaybackQueueCommands.SHUFFLE) {
            val baseline = shuffleBaseline
            if (baseline != null) {
                val order = PlaybackQueuePlan.restoreUpcoming(
                    orderedIds(player), player.currentMediaItemIndex, baseline)
                applyUpcomingOrder(player, order)
            }
            shuffleBaseline = null
            mode = beforeShuffleMode
        } else {
            beforeShuffleMode = mode
            shuffleBaseline = orderedIds(player)
            val order = PlaybackQueuePlan.shuffleUpcoming(
                orderedIds(player), player.currentMediaItemIndex, Random.Default)
            applyUpcomingOrder(player, order)
            mode = PlaybackQueueCommands.SHUFFLE
        }
        publishMode()
    }

    // QA-only instrumentation; never log track names, URIs, file paths or content.
    private fun qaTrace(message: String) {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.d("OpenMusicPlayback", "elapsedMs=${SystemClock.elapsedRealtime()} $message")
        }
    }

    override fun onCreate() {
        super.onCreate()
        val servicePlayer = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true, // Handle Android audio focus for a local music player.
            )
            setHandleAudioBecomingNoisy(true)
        }

        // The controller interaction trace separates command dispatch from
        // subsequent player buffering and playback transitions.
        val qaCallback = object : MediaSession.Callback {
            override fun onConnectAsync(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): ListenableFuture<MediaSession.ConnectionResult> {
                // Only Open Music can change queue policy, never arbitrary external controllers.
                if (controller.packageName != packageName)
                    return super.onConnectAsync(session, controller)
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .add(PlaybackQueueCommands.command(PlaybackQueueCommands.SET_MODE))
                    .add(PlaybackQueueCommands.command(PlaybackQueueCommands.TOGGLE_SHUFFLE))
                    .build()
                return Futures.immediateFuture(
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                        .setAvailableSessionCommands(commands).build())
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                if (controller.packageName == packageName) {
                    when (customCommand.customAction) {
                        PlaybackQueueCommands.SET_MODE -> {
                            val requested = args.getString(PlaybackQueueCommands.MODE_KEY)
                            if (requested in listOf(PlaybackQueueCommands.LIBRARY,
                                    PlaybackQueueCommands.PLAYLIST,
                                    PlaybackQueueCommands.CUSTOM)) {
                                mode = requested!!
                                beforeShuffleMode = mode
                                shuffleBaseline = null
                                servicePlayer.shuffleModeEnabled = false
                                publishMode()
                            }
                        }
                        PlaybackQueueCommands.TOGGLE_SHUFFLE ->
                            toggleShufflePlan(servicePlayer)
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            @androidx.annotation.OptIn(UnstableApi::class)
            override fun onPlayerInteractionFinished(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                playerCommands: Player.Commands,
            ) {
                val actions = buildList {
                    if (playerCommands.contains(Player.COMMAND_PLAY_PAUSE)) add("play_pause")
                    if (playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT) ||
                        playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) add("next")
                    if (playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS) ||
                        playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) add("previous")
                }
                if (actions.isNotEmpty()) {
                    qaTrace("transport=${actions.joinToString(",")} state=${servicePlayer.playbackState} playing=${servicePlayer.isPlaying}")
                }
            }
        }

        servicePlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val name = when (playbackState) {
                    Player.STATE_IDLE -> "idle"
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> "ready"
                    Player.STATE_ENDED -> "ended"
                    else -> "other"
                }
                qaTrace("state=$name")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                qaTrace("isPlaying=$isPlaying")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                qaTrace("track_transition reason=$reason index=${servicePlayer.currentMediaItemIndex}")
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                // Paired with track_transition timestamps: determines whether artwork
                // reaches Media3 late, not whether Android System UI paints it late.
                qaTrace("metadata_changed art_data=${mediaMetadata.artworkData?.size ?: 0} art_uri=${mediaMetadata.artworkUri != null}")
            }

            override fun onPlayerError(error: PlaybackException) {
                qaTrace("player_error code=${error.errorCode}")
            }
        })

        player = servicePlayer
        mediaSession = MediaSession.Builder(this, servicePlayer)
            .setCallback(qaCallback)
            .build()
        publishMode()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
