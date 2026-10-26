package app.openmusic

import android.content.pm.ApplicationInfo
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
