package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Crossfade driven by volume automation on the *single* ExoPlayer that owns the
 * queue.
 *
 * The previous two-player design overlapped tracks for real, but the second
 * player had its own copy of the queue and kept playing whenever the user
 * touched the first one mid-fade — two tracks at once. Anything that fixes that
 * ends up re-synchronising two queues, two audio-focus requests and a
 * MediaSession that keeps changing which player it points at.
 *
 * So there is one player, it is always the session player, and it is always the
 * only thing making sound. The fade is expressed as a *pure function of the
 * current playback position* ([targetGain]) that a ticker applies to
 * [ExoPlayer.volume]:
 *
 * - the last `crossfade/2` seconds of a track ramp down to silence
 * - the first `crossfade/2` seconds of the track the queue advanced to ramp up
 *
 * Because gain is recomputed from position rather than tracked in a state
 * machine, there is nothing to unwind: skip, scrub, reorder the queue or switch
 * crossfade off mid-ramp and the very next tick already computes the right
 * volume for wherever playback actually is. The transition still spans the
 * configured number of seconds — it is a butt-joint of two fades rather than an
 * overlap, so tracks no longer literally play over each other.
 */
@UnstableApi
class CrossfadeController(
    private val scope: CoroutineScope,
    private val player: ExoPlayer,
) {

    /**
     * Whether the current item was reached by the queue advancing on its own.
     * A track the user picked should start at full volume immediately —
     * fading in after a deliberate tap on "next" just reads as broken audio.
     */
    private var fadeInArmed = false

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            fadeInArmed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // Scrubbing back into the opening seconds shouldn't replay the fade-in.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) fadeInArmed = false
        }
    }

    fun start() {
        player.addListener(listener)
        scope.launch {
            while (isActive) {
                val ramping = applyGain()
                delay(if (ramping) RAMP_STEP_MS else IDLE_STEP_MS)
            }
        }
    }

    fun release() {
        player.removeListener(listener)
        player.volume = 1f
    }

    /** @return true while a ramp is in progress, so the ticker can speed up. */
    private fun applyGain(): Boolean {
        val gain = targetGain()
        if (abs(player.volume - gain) > VOLUME_EPSILON) player.volume = gain
        return gain < 1f
    }

    private fun targetGain(): Float {
        val fadeMs = AppSettings.crossfadeSeconds.value * 1000L
        if (fadeMs <= 0L) return 1f
        // Repeating one track would crossfade it into itself — just loop it.
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return 1f
        if (player.mediaItemCount == 0) return 1f
        if (player.playbackState == Player.STATE_IDLE) return 1f

        val position = player.currentPosition
        val duration = player.duration
        val hasDuration = duration != C.TIME_UNSET && duration > 0L

        // Half in, half out, so the whole transition still lasts the configured
        // number of seconds. On a track shorter than the fade the two ramps
        // would otherwise overlap into a deep dip, so clamp to half its length.
        val rampMs = if (hasDuration) minOf(fadeMs / 2, duration / 2) else fadeMs / 2
        if (rampMs <= 0L) return 1f

        var gain = 1f

        if (fadeInArmed && position < rampMs) {
            gain *= curve(position.coerceAtLeast(0L).toFloat() / rampMs)
        }

        // Nothing to fade into at the end of the queue — let the track finish.
        if (hasDuration && player.hasNextMediaItem()) {
            val remaining = duration - position
            if (remaining < rampMs) {
                gain *= curve(remaining.coerceAtLeast(0L).toFloat() / rampMs)
            }
        }

        return gain.coerceIn(0f, 1f)
    }

    /** Equal-power curve: -3dB at the midpoint, so the ramp reads as even. */
    private fun curve(progress: Float): Float = sqrt(progress.coerceIn(0f, 1f))

    private companion object {
        const val RAMP_STEP_MS = 40L
        const val IDLE_STEP_MS = 200L
        /** Below this the write is inaudible and only churns the audio track. */
        const val VOLUME_EPSILON = 0.002f
    }
}
