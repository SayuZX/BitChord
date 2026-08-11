package com.music.bitchord.data

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * What the audio decoder is actually being fed, for "stats for nerds".
 *
 * Every figure here is measured rather than inferred. Codec, sample rate and
 * channel count come from the `Format` the audio renderer was configured with —
 * the decoder's own view of the stream. Bitrate is the one a container usually
 * withholds, so it falls back to the bitrate of the stream the resolver
 * genuinely chose for that track. Anything the player hasn't reported stays
 * null and is left out of the display instead of being guessed at.
 */
object NerdStats {

    class Snapshot(
        val mimeType: String?,
        val bitrateKbps: Int?,
        val sampleRateHz: Int?,
        val channels: Int?,
    )

    val current = MutableStateFlow<Snapshot?>(null)

    /**
     * Bitrate in kbps of the stream picked for each videoId.
     *
     * Keyed by track rather than kept as a single "last picked": the read-ahead
     * resolves the *next* track through the same code, so one loose value would
     * end up describing the wrong song.
     */
    private val picked = ConcurrentHashMap<String, Int>()

    fun onStreamPicked(videoId: String, kbps: Int) {
        if (kbps <= 0) return
        // Enough for the queue in hand; this is a lookup, not a store.
        if (picked.size >= MAX_REMEMBERED) picked.clear()
        picked[videoId] = kbps
    }

    fun pickedBitrateKbps(videoId: String?): Int? = videoId?.let { picked[it] }

    private const val MAX_REMEMBERED = 64
}
