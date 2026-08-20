package com.music.bitchord.ui.player

import android.graphics.Matrix
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.CanvasArtwork

/**
 * The looping video that plays over a track's cover art, sized to fill and
 * clipped by whatever laid it out.
 *
 * A second, deliberately unassuming ExoPlayer: silent, with its audio track
 * switched off entirely so a clip's soundtrack is never even fetched, and no
 * audio attributes — taking focus here would duck the music this is decorating.
 * It follows the transport, so pausing the track stops the sleeve moving too.
 *
 * Nothing is drawn until the first frame arrives, and the fade in from there
 * means a failed or slow clip simply leaves the still art showing rather than
 * flashing a black square over it. [CanvasArtwork.fallbackUrl] gets one try if
 * the first rendition won't decode.
 */
@OptIn(UnstableApi::class)
@Composable
fun CanvasArtworkPlayer(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var url by remember(canvas) { mutableStateOf(canvas.url) }
    var rendered by remember(canvas) { mutableStateOf(false) }
    // Aspect of the clip itself. Zero until the decoder reports it, which is
    // also the signal that there is nothing sensible to crop to yet.
    var clipAspect by remember(canvas) { mutableFloatStateOf(0f) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }

    val player = remember {
        ExoPlayer.Builder(context)
            // Shares the app's one OkHttp client, as everything that fetches
            // over the network here does.
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(OkHttpDataSource.Factory(Http.client)),
            )
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                rendered = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val width = videoSize.width * videoSize.pixelWidthHeightRatio
                if (width > 0f && videoSize.height > 0) {
                    clipAspect = width / videoSize.height
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // One retry, at the other rendition. If that is the one that
                // just failed there is nowhere left to go: leave the still
                // art up rather than looping through a broken URL.
                val alternate = canvas.fallbackUrl
                if (alternate != null && alternate != url) {
                    url = alternate
                } else {
                    rendered = false
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(url) {
        rendered = false
        clipAspect = 0f
        val item = MediaItem.Builder().setUri(url)
        mimeTypeOf(url)?.let { item.setMimeType(it) }
        player.setMediaItem(item.build())
        player.prepare()
    }

    LaunchedEffect(isPlaying) { player.playWhenReady = isPlaying }

    val alpha by animateFloatAsState(
        targetValue = if (rendered) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "canvasAlpha",
    )

    AndroidView(
        factory = { viewContext ->
            TextureView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                // Blend rather than punch a hole: the still sleeve stays
                // visible underneath for the length of the fade.
                isOpaque = false
                this.alpha = 0f
                player.setVideoTextureView(this)
            }
        },
        update = { view ->
            // Set on the view itself. A Compose alpha layer over a TextureView
            // is not reliably composited, and this is the same fade either way.
            view.alpha = alpha
            view.centerCrop(bounds, clipAspect)
        },
        modifier = modifier.onSizeChanged { bounds = it },
    )
}

/**
 * A TextureView stretches its content to whatever bounds it was given, which
 * turns a 9:16 clip in a square sleeve into a smeared one. Undo that with a
 * transform: scale the axis that came up short until the clip covers the view
 * at its true aspect, and let the overflow fall outside the clip.
 */
private fun TextureView.centerCrop(bounds: IntSize, clipAspect: Float) {
    if (bounds.width == 0 || bounds.height == 0 || clipAspect <= 0f) return
    val viewAspect = bounds.width.toFloat() / bounds.height
    val pivotX = bounds.width / 2f
    val pivotY = bounds.height / 2f
    val matrix = Matrix().apply {
        if (clipAspect > viewAspect) {
            setScale(clipAspect / viewAspect, 1f, pivotX, pivotY)
        } else {
            setScale(1f, viewAspect / clipAspect, pivotX, pivotY)
        }
    }
    setTransform(matrix)
}

/**
 * Apple serves HLS, Tidal and the community index serve MP4. Naming the type
 * saves ExoPlayer a sniff, and an unrecognised URL is left for it to work out.
 */
private fun mimeTypeOf(url: String): String? {
    val path = url.substringBefore('?').lowercase()
    return when {
        path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
        else -> null
    }
}
