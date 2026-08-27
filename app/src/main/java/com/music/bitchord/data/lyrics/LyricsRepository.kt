package com.music.bitchord.data.lyrics

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Where the player gets its lyrics.
 *
 * Four sources, in this order:
 *
 *  1. [BetterLyrics] — Apple Music TTML, per-syllable, keyed on title/artist.
 *  2. [LyricsPlus] — the YouLy+ backend; finest timing of the lot, flakiest hosting.
 *  3. [SimpMusicLyrics] — keyed on the video id, so it can't fetch the wrong edit.
 *  4. [LrcLib] — line-synced only, but it is the one that is always up.
 *
 * The first three are asked *at the same time* and their answers taken in that
 * order. Asked one after another, a miss on each of the first two would cost
 * its own round trip before the third was even tried, and a track with no
 * lyrics anywhere would spend the best part of twenty seconds finding that
 * out. Run together, a miss costs whatever the slowest one took.
 *
 * A word-timed answer wins outright. Failing that, a line-timed one is taken
 * from the highest-priority source that had it — better a whole line lighting
 * up in sync than the right animation on lyrics that don't exist.
 */
object LyricsRepository {

    /** Parses a persisted sidecar back into the same result the player uses. */
    fun offline(
        content: String,
        format: LyricsArtifactFormat,
        source: LyricsSource = LyricsSource.LRCLIB,
    ): Result? {
        val lines = when (format) {
            LyricsArtifactFormat.TTML -> TtmlLyrics.parse(content)
            LyricsArtifactFormat.ENHANCED_LRC -> EnhancedLrc.parse(content)
            LyricsArtifactFormat.LRC -> LrcLib.parseLrc(content)
        }.takeIf { it.isNotEmpty() } ?: return null
        return Result(
            source = source,
            lines = lines,
            artifact = LyricsArtifact(source, format, content, lines),
        )
    }

    /** Lyrics, their source, and the representation that can be persisted. */
    data class Result(
        val source: LyricsSource,
        val lines: List<LyricLine>,
        val artifact: LyricsArtifact? = null,
    )

    /**
     * [sources] is the user's pick from Settings; anything not in it is not
     * contacted at all. An empty set means no lyrics, which is the same answer
     * as switching the feature off.
     */
    suspend fun lyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        sources: Set<LyricsSource> = LyricsSource.entries.toSet(),
    ): Result? = coroutineScope {
        val racing: List<Pair<LyricsSource, Deferred<LyricsArtifact?>>> = buildList {
            if (LyricsSource.BETTER_LYRICS in sources) {
                add(
                    LyricsSource.BETTER_LYRICS to
                        async(Dispatchers.IO) { BetterLyrics.artifact(title, artist, durationMs, album) },
                )
            }
            if (LyricsSource.LYRICS_PLUS in sources) {
                add(
                    LyricsSource.LYRICS_PLUS to
                        async(Dispatchers.IO) { LyricsPlus.artifact(title, artist, durationMs, album) },
                )
            }
            if (LyricsSource.SIMP_MUSIC in sources) {
                add(
                    LyricsSource.SIMP_MUSIC to
                        async(Dispatchers.IO) { SimpMusicLyrics.artifact(videoId, durationMs) },
                )
            }
        }

        try {
            var lineSynced: Result? = null
            for ((source, job) in racing) {
                val artifact = try {
                    job.await()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: continue
                if (artifact.lines.any { it.isWordSynced }) {
                    return@coroutineScope Result(source, artifact.lines, artifact)
                }
                if (lineSynced == null) {
                    lineSynced = Result(source, artifact.lines, artifact)
                }
            }
            lineSynced ?: if (LyricsSource.LRCLIB in sources) {
                LrcLib.artifact(title, artist, durationMs)?.let {
                    Result(LyricsSource.LRCLIB, it.lines, it)
                }
            } else {
                null
            }
        } finally {
            // Whoever lost the race is no longer worth waiting on, and
            // coroutineScope will not return while they are still running.
            racing.forEach { it.second.cancel() }
        }
    }
}
