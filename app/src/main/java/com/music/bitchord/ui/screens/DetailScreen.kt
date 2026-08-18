package com.music.bitchord.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.DetailPage
import com.music.bitchord.data.model.CARD_ART_PX
import com.music.bitchord.data.model.HEADER_ART_PX
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.ArtworkBackdrop
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.SHELF_CARD_WIDTH
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.thumbnailBorder
import com.music.bitchord.ui.components.detailSkeleton
import com.music.bitchord.ui.icons.BitChordIcons
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.ui.theme.rememberArtworkPalette

private const val MAX_ARTIST_SONGS = 20
private const val SONGS_PER_COLUMN = 4

/** The artist photo, very slightly taller than it is wide. */
private const val ARTIST_PHOTO_RATIO = 0.95f

/** The sleeve on a release page, as a fraction of the page width. */
private const val SLEEVE_FRACTION = 0.80f

private val SLEEVE_SHAPE = RoundedCornerShape(12.dp)
private val PILL_SHAPE = RoundedCornerShape(12.dp)

/** The inset the header text and the action pills share. */
private val HEADER_GUTTER = PAGE_GUTTER + 14.dp

/**
 * Album / artist / playlist page. Rendered inside the main content area
 * rather than as a sheet, so the tab bar and mini player stay visible.
 *
 * The page paints itself in the artwork's own colours — a tint behind
 * everything, with the artwork blurred into the top of it, and an accent taken
 * off the sleeve for the credit line and the Play/Shuffle pair. See
 * [rememberArtworkPalette] for how those are derived and kept legible.
 */
@Composable
fun DetailScreen(
    page: DetailPage,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onSectionItemClick: (ShelfItem) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val songs = (page.songs as? UiState.Success)?.data.orEmpty()
    val isArtist = page.type == BrowseType.ARTIST
    val palette = rememberArtworkPalette(page.thumbnailUrl)

    Box(modifier.fillMaxSize()) {
        ArtworkBackdrop(
            palette = palette,
            imageUrl = page.thumbnailUrl,
            modifier = Modifier.matchParentSize(),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Both artist photos and release artwork run edge-to-edge up under
            // the glass bar — the image is the top of the page, not a card on it.
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            item(key = "header") {
                if (isArtist) {
                    ArtistHeader(page = page, palette = palette)
                } else {
                    ReleaseHeader(
                        page = page,
                        palette = palette,
                        trackCount = songs.size,
                        songs = songs,
                        onPlay = { onSongClick(songs, 0) },
                        onShuffle = { onShuffle(songs) },
                    )
                }
            }

            if (songs.isNotEmpty() && isArtist) {
                item(key = "actions") {
                    ActionRow(
                        palette = palette,
                        onPlay = { onSongClick(songs, 0) },
                        onShuffle = { onShuffle(songs) },
                    )
                }
            }

            when (val state = page.songs) {
                is UiState.Loading -> detailSkeleton(isArtist)
                is UiState.Error -> item { MessageState(state.message) }
                is UiState.Success -> if (isArtist) {
                    // An artist's full song list would bury the album shelves, so
                    // it pages sideways four at a time and stops at twenty.
                    item {
                        val top = state.data.take(MAX_ARTIST_SONGS)
                        SectionHeading("Top songs", palette)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(top.chunked(SONGS_PER_COLUMN)) { column ->
                                Column(Modifier.fillParentMaxWidth(0.88f)) {
                                    column.forEach { song ->
                                        CompactSongRow(
                                            song = song,
                                            palette = palette,
                                            onClick = { onSongClick(top, top.indexOf(song)) },
                                            onLongPress = { onSongLongPress(song) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Every row on an album carries the same sleeve, which is
                    // already the largest thing on the page — Apple Music
                    // numbers those rows instead, and so does this.
                    val numbered = page.type == BrowseType.ALBUM
                    itemsIndexed(state.data) { index, song ->
                        SongRow(
                            song = if (numbered) {
                                song
                            } else {
                                song.copy(thumbnailUrl = song.thumbnailUrl ?: page.thumbnailUrl)
                            },
                            onClick = { onSongClick(state.data, index) },
                            onLongPress = { onSongLongPress(song) },
                            onSwipeToQueue = { onSongSwipe(song) },
                            rowBackground = palette.background,
                            trackNumber = (index + 1).takeIf { numbered },
                            subtitleColor = palette.onBackgroundVariant,
                        )
                        if (index < state.data.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                thickness = 0.5.dp,
                                color = palette.divider,
                            )
                        }
                    }
                }
            }

            // Albums / Singles & EPs carousels (artist pages).
            items(page.sections) { shelf ->
                Column(Modifier.padding(top = 22.dp)) {
                    SectionHeading(shelf.title, palette)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(shelf.items) { item ->
                            SectionCard(
                                item = item,
                                palette = palette,
                                onClick = { onSectionItemClick(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * An album or playlist: artwork full-bleed to the top of the screen, melting
 * into the page colour at the bottom. Title, credit, meta and the action
 * buttons all live inside the same gradient zone — no separate item below the
 * header means no gap between the cover and the song list.
 */
@Composable
private fun ReleaseHeader(
    page: DetailPage,
    palette: ArtworkPalette,
    trackCount: Int,
    songs: List<Song>,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val (credit, meta) = page.headerLines(trackCount)

    // The outer Box just needs to be as tall as its content — we don't force
    // an aspect ratio here so the action buttons can extend below the artwork.
    Box(Modifier.fillMaxWidth()) {

        // Artwork locked to a portrait aspect ratio, edge to edge.
        AsyncImage(
            model = page.thumbnailUrl.artworkAt(HEADER_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.92f)
                .background(palette.elevated),
        )

        // Top scrim so the glass back-button keeps contrast.
        Box(
            Modifier
                .fillMaxWidth()
                // 28 % of the artwork height — derived from the 0.92 ratio.
                .aspectRatio(0.92f / 0.28f)
                .background(
                    Brush.verticalGradient(
                        listOf(palette.background.copy(alpha = 0.55f), Color.Transparent),
                    ),
                ),
        )

        // Full-height gradient that carries the artwork into the page tint and
        // continues behind the text + buttons so there is never a hard edge.
        Box(
            Modifier
                .fillMaxWidth()
                // Taller than the artwork so the gradient covers the buttons too.
                .aspectRatio(0.65f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.35f to palette.background.copy(alpha = 0.55f),
                        0.62f to palette.background.copy(alpha = 0.90f),
                        1.00f to palette.background,
                    ),
                ),
        )

        // Text + action row stacked, pinned to the bottom of the Box.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineMedium,
                color = palette.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = HEADER_GUTTER),
            )
            // Artist / credit line
            if (credit.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = credit,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.accent,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = HEADER_GUTTER),
                )
            }
            // Metadata (kind • year • count)
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                    color = palette.onBackgroundVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = HEADER_GUTTER),
                )
            }

            // Action buttons — live inside the header so there is zero gap
            // between the cover zone and the first song row.
            if (songs.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = HEADER_GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleIconButton(
                        icon = BitChordIcons.Shuffle,
                        contentDescription = "Shuffle",
                        palette = palette,
                        onClick = onShuffle,
                    )
                    PlayPill(
                        palette = palette,
                        onClick = onPlay,
                        modifier = Modifier.weight(1f),
                    )
                    CircleIconButton(
                        icon = BitChordIcons.Download,
                        contentDescription = "Download",
                        palette = palette,
                        onClick = {},
                    )
                }
            }
        }
    }
}

/**
 * An artist: the photo full-bleed to the top of the screen, their name across
 * the foot of it, and the picture melting into the page's colour as it goes.
 */
@Composable
private fun ArtistHeader(page: DetailPage, palette: ArtworkPalette) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ARTIST_PHOTO_RATIO),
    ) {
        AsyncImage(
            model = page.thumbnailUrl.artworkAt(HEADER_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .background(palette.elevated),
        )
        // Shade under the glass bar. Drawn in the page's own tint rather than
        // in black, so the back arrow — which is themed, not always white —
        // keeps its contrast in light mode as well as dark.
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.3f)
                .background(
                    Brush.verticalGradient(
                        listOf(palette.background.copy(alpha = 0.55f), Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.40f to Color.Transparent,
                        0.76f to palette.background.copy(alpha = 0.78f),
                        1f to palette.background,
                    ),
                ),
        )
        Text(
            text = page.title,
            style = MaterialTheme.typography.displayLarge,
            color = palette.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = HEADER_GUTTER, vertical = 14.dp),
        )
    }
}

/** Shuffle • Play • Download — the Apple Music action row. */
@Composable
private fun ActionRow(palette: ArtworkPalette, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HEADER_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Circular Shuffle button
        CircleIconButton(
            icon = BitChordIcons.Shuffle,
            contentDescription = "Shuffle",
            palette = palette,
            onClick = onShuffle,
        )

        // Large Play pill — takes all remaining space
        PlayPill(
            palette = palette,
            onClick = onPlay,
            modifier = Modifier.weight(1f),
        )

        // Circular Download button
        CircleIconButton(
            icon = BitChordIcons.Download,
            contentDescription = "Download",
            palette = palette,
            onClick = {},
        )
    }
    Spacer(Modifier.height(22.dp))
}

/**
 * The prominent, pill-shaped Play button that anchors the action row.
 * White-ish solid fill with the accent colour, like Apple Music's Play button.
 */
@Composable
private fun PlayPill(
    palette: ArtworkPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(50.dp)
            .clip(PILL_SHAPE)
            // Solid white-ish fill — stands out from the translucent page tint
            .background(palette.onBackground.copy(alpha = 0.92f))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = BitChordIcons.Play,
            contentDescription = null,
            tint = palette.background,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Play",
            style = MaterialTheme.typography.titleMedium,
            color = palette.background,
        )
    }
}

/**
 * Small circular icon-only button — used for Shuffle and Download flanking the
 * Play pill. Translucent glassy fill, accent-coloured icon.
 */
@Composable
private fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    palette: ArtworkPalette,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(palette.onBackground.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = palette.onBackground,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Track count and running time, the way a release page signs off. */
@Composable
private fun ReleaseFooter(songs: List<Song>, palette: ArtworkPalette) {
    Text(
        text = songs.playtimeSummary(),
        style = MaterialTheme.typography.labelMedium,
        color = palette.onBackgroundVariant,
        modifier = Modifier.padding(start = HEADER_GUTTER, end = HEADER_GUTTER, top = 18.dp),
    )
}

@Composable
private fun SectionHeading(title: String, palette: ArtworkPalette) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = palette.onBackground,
        modifier = Modifier.padding(
            start = PAGE_GUTTER, end = PAGE_GUTTER, top = 10.dp, bottom = 8.dp,
        ),
    )
}

/** Compact row used inside the artist song grid; no swipe, to keep the
 *  horizontal pager's gestures unambiguous. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactSongRow(
    song: Song,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = song.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(7.dp))
                .thumbnailBorder(RoundedCornerShape(7.dp))
                .background(palette.elevated),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onLongPress),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = "More",
                tint = palette.onBackgroundVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(item: ShelfItem, palette: ArtworkPalette, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(SHELF_CARD_WIDTH)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .width(SHELF_CARD_WIDTH)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .thumbnailBorder(RoundedCornerShape(10.dp))
                .background(palette.elevated),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Splits the one subtitle a browse row hands over — "Album • Travis Scott •
 * 2023", or sometimes just "Travis Scott" — into the credit line and the
 * metadata line the header shows separately.
 *
 * Everything is optional, because every caller supplies a different amount of
 * it: the player knows an album's artist but not its year, search knows both,
 * and a home card frequently knows neither.
 */
private fun DetailPage.headerLines(trackCount: Int): Pair<String, String> {
    val parts = subtitle.split("•", "·").map { it.trim() }.filter { it.isNotEmpty() }
    val year = parts.lastOrNull { it.length == 4 && it.all(Char::isDigit) }
    val kind = parts.firstOrNull { it.lowercase() in KIND_WORDS }
    val credit = parts.filter { it != year && it != kind }.joinToString(", ")
    val meta = listOfNotNull(
        kind ?: type.label,
        year,
        trackCount.takeIf { it > 0 }?.let { "$it ${if (it == 1) "song" else "songs"}" },
    ).joinToString(" • ").uppercase()
    return credit to meta
}

/** Subtitle words that name what a page *is* rather than who made it. */
private val KIND_WORDS = setOf(
    "album", "single", "ep", "playlist", "artist", "podcast", "episode", "song", "video",
)

private val BrowseType.label: String?
    get() = when (this) {
        BrowseType.ALBUM -> "Album"
        BrowseType.PLAYLIST -> "Playlist"
        BrowseType.ARTIST -> "Artist"
        BrowseType.OTHER -> null
    }

/** "12 songs, 41 minutes" — omitting the time when the rows carry no durations. */
private fun List<Song>.playtimeSummary(): String {
    val count = "$size ${if (size == 1) "song" else "songs"}"
    val minutes = sumOf { it.durationText.toSeconds() } / 60
    return when {
        minutes <= 0 -> count
        minutes < 60 -> "$count, $minutes minutes"
        else -> {
            val hours = minutes / 60
            val rest = minutes % 60
            val hourLabel = "$hours ${if (hours == 1) "hour" else "hours"}"
            if (rest == 0) "$count, $hourLabel" else "$count, $hourLabel $rest minutes"
        }
    }
}

/** "3:45" or "1:02:33" as seconds; 0 for anything that isn't a duration. */
private fun String?.toSeconds(): Int {
    val parts = this?.split(":")?.map { it.trim().toIntOrNull() ?: return 0 } ?: return 0
    return when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> 0
    }
}
