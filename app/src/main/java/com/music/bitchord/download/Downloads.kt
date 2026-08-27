package com.music.bitchord.download

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.lyrics.LyricsArtifact
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.lyrics.LyricsSerializer
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.OutputStream

/** Where a track is between "not on this device" and "on it". */
sealed interface DownloadState {

    /** Accepted, waiting for the one in front of it. */
    data object Queued : DownloadState

    /** [fraction] is 0f until the length is known, which is the first thing asked for. */
    data class Running(val fraction: Float) : DownloadState

    data class Failed(val reason: String) : DownloadState
}

/** The lifecycle state of a track's lyrics file. */
enum class LyricsDownloadState {
    NOT_REQUESTED,
    DOWNLOADING,
    SAVED,
    FAILED,
    UNAVAILABLE,
}

/** A queued download item: either a full audio track or lyrics enrichment only. */
data class PendingDownload(
    val song: Song,
    val lyricsOnly: Boolean = false,
    val from: String? = null,
)

/**
 * The download queue, and the record of what came out of it.
 *
 * Downloads both the audio stream and the synchronized lyrics. The lyric lookup
 * is initiated concurrently with the audio transfer, eliminating idle latency
 * while ensuring both the audio file and its matching sidecar are ready together.
 */
object Downloads {

    private const val TAG = "BitChord"
    private const val KEY_SAVED = "downloaded_tracks"
    private const val KEY_SAVED_METADATA = "downloaded_tracks_metadata"
    private const val KEY_SAVED_COLLECTIONS = "downloaded_collections"

    private const val LOSSLESS_LOOKUP_MS = 20_000L
    private const val LYRICS_LOOKUP_MS = 15_000L

    internal const val WIFI_ONLY_REFUSAL = "Downloads are set to Wi-Fi only"

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val metadataSerializer = MapSerializer(String.serializer(), SavedSongMetadata.serializer())
    private val collectionSerializer = MapSerializer(String.serializer(), SavedCollection.serializer())

    private val _active = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val active: StateFlow<Map<String, DownloadState>> = _active.asStateFlow()

    private val _saved = MutableStateFlow<Map<String, String>>(emptyMap())
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

    private val _savedMetadata = MutableStateFlow<Map<String, SavedSongMetadata>>(emptyMap())
    val savedMetadata: StateFlow<Map<String, SavedSongMetadata>> = _savedMetadata.asStateFlow()

    private val _lyricsActive = MutableStateFlow<Set<String>>(emptySet())
    val lyricsActive: StateFlow<Set<String>> = _lyricsActive.asStateFlow()

    private val _collections = MutableStateFlow<Map<String, SavedCollection>>(emptyMap())
    val collections: StateFlow<Map<String, SavedCollection>> = _collections.asStateFlow()

    /** Waiting, in the order asked for. Guarded by [lock]. */
    private val pending = LinkedHashMap<String, PendingDownload>()

    private val lock = Any()

    @Volatile
    private var runningId: String? = null

    @Volatile
    private var runningJob: Job? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bitchord_settings", Context.MODE_PRIVATE)
        _saved.value = runCatching {
            json.decodeFromString(serializer, prefs.getString(KEY_SAVED, null) ?: "{}")
        }.getOrDefault(emptyMap())
        _savedMetadata.value = runCatching {
            json.decodeFromString(metadataSerializer, prefs.getString(KEY_SAVED_METADATA, null) ?: "{}")
        }.getOrDefault(emptyMap())
        _collections.value = runCatching {
            json.decodeFromString(collectionSerializer, prefs.getString(KEY_SAVED_COLLECTIONS, null) ?: "{}")
        }.getOrDefault(emptyMap())
    }

    // ---- Asking -------------------------------------------------------------

    fun enqueue(context: Context, song: Song, from: String? = null) {
        val id = song.videoId
        if (!AppSettings.downloadsAllowedNow) {
            val inFlight = _active.value[id]
            if (inFlight !is DownloadState.Queued && inFlight !is DownloadState.Running) {
                DownloadSession.queued(song, from)
                fail(id, WIFI_ONLY_REFUSAL)
            }
            return
        }
        synchronized(lock) {
            if (id in pending || id == runningId) return
            pending[id] = PendingDownload(song, lyricsOnly = false, from = from)
        }
        _active.value = _active.value + (id to DownloadState.Queued)
        DownloadSession.queued(song, from)
        startService(context, id)
    }

    fun enqueueLyrics(context: Context, song: Song) {
        val id = song.videoId
        if (!AppSettings.downloadsAllowedNow) {
            fail(id, WIFI_ONLY_REFUSAL)
            return
        }
        synchronized(lock) {
            if (id in pending || id == runningId) return
            pending[id] = PendingDownload(song, lyricsOnly = true)
        }
        _lyricsActive.value = _lyricsActive.value + id
        startService(context, id)
    }

    private fun startService(context: Context, id: String) {
        val app = context.applicationContext
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        }.onFailure {
            Log.w(TAG, "could not start the download service: ${it.message}")
            synchronized(lock) { pending.remove(id) }
            _lyricsActive.value = _lyricsActive.value - id
            fail(id, "Downloads can't start right now")
        }
    }

    fun cancel(videoId: String) {
        val job = synchronized(lock) {
            pending.remove(videoId)
            if (videoId != runningId) return@synchronized null
            runningId = null
            runningJob.also { runningJob = null }
        }
        job?.cancel()
        clear(videoId)
        _lyricsActive.value = _lyricsActive.value - videoId
        DownloadSession.forget(videoId)
    }

    // ---- The record ---------------------------------------------------------

    suspend fun savedUri(context: Context, videoId: String): Uri? = withContext(Dispatchers.IO) {
        val recorded = _saved.value[videoId] ?: return@withContext null
        val uri = recorded.toUri()
        if (DownloadStore.exists(context, uri)) return@withContext uri
        Log.d(TAG, "$videoId was downloaded but the file is gone; forgetting it")
        forget(videoId)
        null
    }

    suspend fun delete(context: Context, videoId: String): Boolean = withContext(Dispatchers.IO) {
        val uri = _saved.value[videoId]?.toUri() ?: return@withContext false
        val deleted = DownloadStore.delete(context, uri)
        _savedMetadata.value[videoId]?.lyricsUri?.toUri()?.let { sidecarUri ->
            LyricsSidecarStore.delete(context, sidecarUri)
        }
        forget(videoId)
        deleted
    }

    private fun forget(videoId: String) {
        record(_saved.value - videoId, _savedMetadata.value - videoId)
    }

    // ---- Releases -----------------------------------------------------------

    fun rememberCollection(target: DownloadTarget, songs: List<Song>) {
        if (songs.isEmpty()) return
        val existing = _collections.value[target.id]
        val ids = songs.map { it.videoId }.distinct()
        val record = SavedCollection(
            id = target.id,
            title = target.title,
            subtitle = target.subtitle,
            thumbnailUrl = target.thumbnailUrl ?: existing?.thumbnailUrl,
            playlist = target.playlist,
            videoIds = (ids + (existing?.videoIds ?: emptyList())).distinct(),
        )
        recordCollections(_collections.value + (target.id to record))
    }

    fun forgetCollection(id: String) {
        if (id !in _collections.value) return
        recordCollections(_collections.value - id)
    }

    /**
     * Delete every file downloaded for release [id] and drop the record of it.
     *
     * The counterpart to [forgetCollection]: that one is for a record whose
     * files are already gone, this one is what actually takes them off the
     * device — the "delete download" a whole album or playlist card offers,
     * where a single track only ever offers [delete].
     */
    suspend fun deleteCollection(context: Context, id: String): Boolean {
        val record = _collections.value[id] ?: return false
        var any = false
        record.videoIds.forEach { videoId -> if (delete(context, videoId)) any = true }
        forgetCollection(id)
        return any
    }

    const val PLAYLIST_PREFIX = "local:playlist:"

    fun pageIdFor(id: String): String = PLAYLIST_PREFIX + id

    fun recordIdOf(browseId: String): String? =
        browseId.removePrefix(PLAYLIST_PREFIX).takeIf { it != browseId && it.isNotEmpty() }

    fun savedPlaylists(onDisk: Map<String, String> = _saved.value): List<SavedCollection> {
        if (_collections.value.isEmpty()) return emptyList()
        return _collections.value.values
            .filter { record -> record.playlist && record.videoIds.any { it in onDisk } }
            .sortedBy { it.title.lowercase() }
    }

    fun collectionsAmong(songs: List<Song>): List<DownloadedCollection> {
        if (songs.isEmpty() || _collections.value.isEmpty()) return emptyList()
        val byId = songs.associateBy { it.videoId }
        val byUri = songs.mapNotNull { song -> song.localUri?.let { it to song } }.toMap()
        val uris = _saved.value
        return _collections.value.values
            .mapNotNull { record ->
                val tracks = record.videoIds
                    .mapNotNull { id -> byId[id] ?: uris[id]?.let(byUri::get) }
                    .distinctBy { it.localUri ?: it.videoId }
                if (tracks.isEmpty()) {
                    null
                } else {
                    DownloadedCollection(
                        id = record.id,
                        title = record.title,
                        subtitle = record.subtitle,
                        thumbnailUrl = record.thumbnailUrl ?: tracks.firstNotNullOfOrNull { it.thumbnailUrl },
                        playlist = record.playlist,
                        songs = tracks,
                    )
                }
            }
            .sortedBy { it.title.lowercase() }
    }

    private fun recordCollections(map: Map<String, SavedCollection>) {
        _collections.value = map
        if (::prefs.isInitialized) {
            prefs.edit()
                .putString(KEY_SAVED_COLLECTIONS, json.encodeToString(collectionSerializer, map))
                .apply()
        }
    }

    private fun remember(
        asked: Song,
        fetched: Song,
        uri: Uri,
        lyrics: LyricsResult? = null,
    ) {
        val ids = setOf(asked.videoId, fetched.videoId)
        val newSaved = _saved.value + ids.associateWith { uri.toString() }
        val album = fetched.albumName?.takeIf { it.isNotBlank() }
            ?: asked.albumName?.takeIf { it.isNotBlank() }
        val prevAsked = _savedMetadata.value[asked.videoId]
        val prevFetched = _savedMetadata.value[fetched.videoId]

        val metaAsked = SavedSongMetadata(
            videoId = asked.videoId,
            title = asked.title,
            artist = asked.artist,
            thumbnailUrl = asked.thumbnailUrl,
            durationText = asked.durationText,
            albumName = album,
            uri = uri.toString(),
            lyricsUri = lyrics?.uri ?: prevAsked?.lyricsUri,
            lyricsSource = lyrics?.source ?: prevAsked?.lyricsSource,
            lyricsFormat = lyrics?.format ?: prevAsked?.lyricsFormat,
            lyricsState = lyrics?.state ?: prevAsked?.lyricsState ?: LyricsDownloadState.NOT_REQUESTED,
        )
        val metaFetched = SavedSongMetadata(
            videoId = fetched.videoId,
            title = fetched.title,
            artist = fetched.artist,
            thumbnailUrl = fetched.thumbnailUrl,
            durationText = fetched.durationText,
            albumName = album,
            uri = uri.toString(),
            lyricsUri = lyrics?.uri ?: prevFetched?.lyricsUri,
            lyricsSource = lyrics?.source ?: prevFetched?.lyricsSource,
            lyricsFormat = lyrics?.format ?: prevFetched?.lyricsFormat,
            lyricsState = lyrics?.state ?: prevFetched?.lyricsState ?: LyricsDownloadState.NOT_REQUESTED,
        )
        val newMeta = _savedMetadata.value + mapOf(
            asked.videoId to metaAsked,
            fetched.videoId to metaFetched,
        )
        record(newSaved, newMeta)
    }

    private fun updateLyricsMetadata(videoId: String, lyrics: LyricsResult) {
        val existing = _savedMetadata.value[videoId] ?: return
        val updated = existing.copy(
            lyricsUri = lyrics.uri,
            lyricsSource = lyrics.source,
            lyricsFormat = lyrics.format,
            lyricsState = lyrics.state,
        )
        record(_saved.value, _savedMetadata.value + (videoId to updated))
    }

    private fun updateLyricsState(videoId: String, state: LyricsDownloadState) {
        val existing = _savedMetadata.value[videoId] ?: return
        val updated = existing.copy(lyricsState = state)
        record(_saved.value, _savedMetadata.value + (videoId to updated))
    }

    private fun record(savedMap: Map<String, String>, metaMap: Map<String, SavedSongMetadata>) {
        _saved.value = savedMap
        _savedMetadata.value = metaMap
        if (::prefs.isInitialized) {
            prefs.edit()
                .putString(KEY_SAVED, json.encodeToString(serializer, savedMap))
                .putString(KEY_SAVED_METADATA, json.encodeToString(metadataSerializer, metaMap))
                .apply()
        }
    }

    /** Returns all downloaded songs whose files still exist on disk. */
    suspend fun getDownloadedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val metaMap = _savedMetadata.value
        val result = mutableListOf<Song>()
        val seenUris = mutableSetOf<String>()

        for ((videoId, meta) in metaMap) {
            val uri = meta.uri.toUri()
            if (DownloadStore.exists(context, uri)) {
                if (seenUris.add(meta.uri)) {
                    result.add(
                        Song(
                            videoId = meta.videoId,
                            title = meta.title,
                            artist = meta.artist,
                            thumbnailUrl = meta.thumbnailUrl,
                            durationText = meta.durationText,
                            albumName = meta.albumName,
                            localUri = meta.uri,
                            localLyricsUri = meta.lyricsUri,
                            localLyricsSource = meta.lyricsSource,
                            localLyricsFormat = meta.lyricsFormat,
                        ),
                    )
                }
            } else {
                forget(videoId)
            }
        }
        result
    }

    fun savedLyricsUri(videoId: String): String? = _savedMetadata.value[videoId]?.lyricsUri

    fun savedLyricsSource(videoId: String): String? = _savedMetadata.value[videoId]?.lyricsSource

    fun savedLyricsFormat(videoId: String): String? = _savedMetadata.value[videoId]?.lyricsFormat

    fun savedLyricsState(videoId: String): LyricsDownloadState =
        _savedMetadata.value[videoId]?.lyricsState ?: LyricsDownloadState.NOT_REQUESTED

    fun hasLyrics(videoId: String): Boolean = savedLyricsUri(videoId) != null

    // ---- Driven by DownloadService -----------------------------------------

    internal fun takeNext(): PendingDownload? = synchronized(lock) {
        val entry = pending.entries.firstOrNull() ?: return null
        pending.remove(entry.key)
        runningId = entry.key
        entry.value
    }

    internal fun onRunning(videoId: String, job: Job) {
        val cancelled = synchronized(lock) {
            if (runningId != videoId) return@synchronized true
            runningJob = job
            false
        }
        if (cancelled) job.cancel()
    }

    internal fun onIdle() {
        synchronized(lock) {
            runningId = null
            runningJob = null
        }
    }

    suspend fun run(context: Context, task: PendingDownload) = withContext(Dispatchers.IO) {
        val (song, lyricsOnly) = task
        val id = song.videoId

        if (lyricsOnly) {
            runLyricsOnly(context, song)
            return@withContext
        }

        var pendingDest: DownloadStore.Pending? = null
        var lyricsDeferred: Deferred<LyricsArtifact?>? = null

        try {
            val track = runCatching { YtMusicRepository.resolveAudio(song) }.getOrDefault(song)
            DownloadSession.retitle(id, track)

            val quality = AppSettings.downloadQuality.value
            val route = routeFor(track, quality)
            Log.d(TAG, "downloading $id as .${route.extension} (${route.describe}, ${quality.label})")

            // Concurrently lookup lyrics while audio streams down the wire
            if (MediaTagger.carriesTags(route.extension)) {
                lyricsDeferred = async { fetchLyrics(song, track) }
            }

            val name = DownloadStore.fileNameFor(track, route.extension)
            val alreadyThere = DownloadStore.existing(context, name)
            if (alreadyThere != null) {
                Log.d(TAG, "$name is already in Music; adopting it")
                val lyricsArtifact = lyricsDeferred?.await()
                val lyricsResult = saveAndEmbedLyrics(context, song, track, alreadyThere, name, route.extension, lyricsArtifact)
                remember(song, track, alreadyThere, lyricsResult)
                DownloadSession.done(id)
                clear(id)
                return@withContext
            }

            val destination = DownloadStore.begin(context, name, route.mimeType)
            pendingDest = destination
            destination.openStream().use { sink ->
                route.write(sink) { written, total ->
                    val fraction = written.toFloat() / total
                    _active.value = _active.value + (id to DownloadState.Running(fraction))
                    DownloadSession.running(id, fraction)
                }
            }

            val lyricsArtifact = lyricsDeferred?.await()
            val savedUri = destination.commit()
            pendingDest = null

            val lyricsResult = saveAndEmbedLyrics(context, song, track, savedUri, name, route.extension, lyricsArtifact)
            remember(song, track, savedUri, lyricsResult)
            DownloadSession.done(id)
            clear(id)
            Log.d(TAG, "saved $name")
        } catch (e: CancellationException) {
            pendingDest?.abort()
            clear(id)
            throw e
        } catch (e: Exception) {
            pendingDest?.abort()
            Log.w(TAG, "download failed for $id: ${e.message}", e)
            fail(id, e.friendly())
        } finally {
            lyricsDeferred?.cancel()
        }
    }

    private suspend fun runLyricsOnly(context: Context, song: Song) = withContext(Dispatchers.IO) {
        val id = song.videoId
        _lyricsActive.value = _lyricsActive.value + id
        try {
            val audioUri = savedUri(context, id)
            if (audioUri == null) {
                updateLyricsState(id, LyricsDownloadState.FAILED)
                return@withContext
            }

            val track = runCatching { YtMusicRepository.resolveAudio(song) }.getOrDefault(song)
            val artifact = fetchLyrics(song, track)

            if (artifact == null) {
                updateLyricsState(id, LyricsDownloadState.UNAVAILABLE)
                return@withContext
            }

            val displayName = DownloadStore.displayName(context, audioUri)
                ?: DownloadStore.fileNameFor(song, "m4a")
            val extension = displayName.substringAfterLast('.', "m4a")

            val lyricsResult = saveAndEmbedLyrics(context, song, track, audioUri, displayName, extension, artifact)
            if (lyricsResult.state == LyricsDownloadState.SAVED) {
                updateLyricsMetadata(id, lyricsResult)
            } else {
                updateLyricsState(id, lyricsResult.state)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "lyrics-only download failed for $id: ${e.message}", e)
            updateLyricsState(id, LyricsDownloadState.FAILED)
        } finally {
            _lyricsActive.value = _lyricsActive.value - id
        }
    }

    private suspend fun fetchLyrics(asked: Song, track: Song): LyricsArtifact? {
        val sources = if (AppSettings.syncedLyrics.value) {
            AppSettings.lyricsSources.value
        } else {
            emptySet()
        }
        if (sources.isEmpty()) return null

        val durationMs = track.durationMillis().takeIf { it > 0L }
            ?: TrackMatcher.secondsOf(track.durationText ?: asked.durationText)?.times(1000L)
            ?: 0L
        if (durationMs <= 0L) {
            Log.d(TAG, "no duration for ${track.videoId}; skipping lyrics")
            return null
        }

        val result = withTimeoutOrNull(LYRICS_LOOKUP_MS) {
            try {
                LyricsRepository.lyrics(
                    videoId = asked.videoId,
                    title = track.title,
                    artist = track.artist,
                    durationMs = durationMs,
                    album = track.albumName,
                    sources = sources,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "lyrics lookup failed for ${track.videoId}: ${e.message}")
                null
            }
        } ?: return null

        val artifact = result.artifact ?: return null
        if (artifact.lines.none { it.text.isNotBlank() }) return null
        if (artifact.content.length > LyricsSerializer.MAX_LYRICS_CHARS) {
            Log.w(TAG, "lyrics for ${track.videoId} exceed ${LyricsSerializer.MAX_LYRICS_CHARS} chars; skipping")
            return null
        }
        return artifact
    }

    private fun saveAndEmbedLyrics(
        context: Context,
        asked: Song,
        track: Song,
        audioUri: Uri,
        audioName: String,
        audioExtension: String,
        artifact: LyricsArtifact?,
    ): LyricsResult {
        if (artifact == null) return LyricsResult(LyricsDownloadState.UNAVAILABLE)

        var sidecarUri: Uri? = null
        val sidecarName = LyricsSidecarStore.fileNameFor(audioName, artifact)
        runCatching {
            sidecarUri = LyricsSidecarStore.write(context, sidecarName, artifact)
        }.onFailure {
            Log.w(TAG, "could not write lyrics sidecar $sidecarName: ${it.message}")
        }

        runCatching {
            val plain = LyricsSerializer.plainText(artifact.lines)
            MediaTagger.embed(context, audioUri, track, audioExtension, plain)
        }.onFailure {
            Log.w(TAG, "could not embed plain lyrics tag in $audioName: ${it.message}")
        }

        return if (sidecarUri != null) {
            LyricsResult(
                state = LyricsDownloadState.SAVED,
                uri = sidecarUri.toString(),
                source = artifact.source.name,
                format = artifact.format.name,
            )
        } else {
            LyricsResult(LyricsDownloadState.FAILED)
        }
    }

    private class LyricsResult(
        val state: LyricsDownloadState,
        val uri: String? = null,
        val source: String? = null,
        val format: String? = null,
    )

    // ---- Routing ------------------------------------------------------------

    private class Route(
        val extension: String,
        val mimeType: String,
        val describe: String,
        val write: suspend (OutputStream, (written: Long, total: Long) -> Unit) -> Unit,
    )

    private suspend fun routeFor(track: Song, quality: DownloadQuality): Route {
        lossless(track, quality)?.let { (stream, storable) ->
            return Route(
                extension = storable.extension,
                mimeType = storable.mimeType,
                describe = stream.format.summary,
                write = { sink, onProgress ->
                    Downloader.fetchDirect(stream.url, stream.headers, sink, onProgress)
                },
            )
        }
        val stream = StreamResolver.resolveForDownload(track.videoId, quality.maxKbps)
        return Route(
            extension = stream.downloadExtension,
            mimeType = stream.downloadMimeType,
            describe = "${stream.kbps}kbps ${stream.mimeType}",
            write = { sink, onProgress ->
                Downloader.fetch(track.videoId, stream, quality.maxKbps, sink, onProgress)
            },
        )
    }

    private suspend fun lossless(
        track: Song,
        quality: DownloadQuality,
    ): Pair<SourceStream, DownloadStore.Storable>? {
        val stream = withTimeoutOrNull(LOSSLESS_LOOKUP_MS) {
            try {
                SourceResolver.forDownload(
                    TrackMatcher.targetOf(track),
                    SourceResolver.requestForDownload(quality),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "lossless lookup failed for ${track.videoId}: ${e.message}")
                null
            }
        } ?: return null

        val storable = DownloadStore.storable(stream.format.codec)
        if (storable == null) {
            Log.d(TAG, "nothing to file a '${stream.format.codec}' as; taking YouTube for ${track.videoId}")
            return null
        }
        return stream to storable
    }

    private fun clear(videoId: String) {
        _active.value = _active.value - videoId
    }

    private fun fail(videoId: String, reason: String) {
        _active.value = _active.value + (videoId to DownloadState.Failed(reason))
        DownloadSession.failed(videoId, reason)
    }

    private fun Exception.friendly(): String = when {
        (this is IllegalStateException || this is IllegalArgumentException) &&
            !message.isNullOrBlank() -> message!!
        else -> "Download failed — check your connection"
    }

    fun dismissFailure(videoId: String) {
        if (_active.value[videoId] is DownloadState.Failed) clear(videoId)
    }
}

@kotlinx.serialization.Serializable
data class SavedSongMetadata(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val albumName: String? = null,
    val uri: String,
    val lyricsUri: String? = null,
    val lyricsSource: String? = null,
    val lyricsFormat: String? = null,
    val lyricsState: LyricsDownloadState = LyricsDownloadState.NOT_REQUESTED,
)

data class DownloadTarget(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val playlist: Boolean = false,
)

@kotlinx.serialization.Serializable
data class SavedCollection(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val playlist: Boolean = false,
    val videoIds: List<String> = emptyList(),
)

data class DownloadedCollection(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val playlist: Boolean,
    val songs: List<Song>,
)
