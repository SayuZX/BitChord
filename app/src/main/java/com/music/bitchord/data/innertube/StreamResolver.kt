package com.music.bitchord.data.innertube

import android.os.SystemClock
import android.util.Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Turns a videoId into a URL ExoPlayer can actually stream.
 *
 * Three things make or break this, and the order they are attempted in matters
 * as much as the mechanics of each:
 *
 *  1. **Which endpoint asks.** The `youtubei/v1/player` POST is one small JSON
 *     round trip. The watch page — what a full extractor scrape fetches — is
 *     several hundred kilobytes of HTML and is rate-shaped: under load Google
 *     answers its headers immediately and then feeds the body out over tens of
 *     seconds, or simply stops sending and never closes. That shaping is
 *     invisible as an error and reads to a listener as endless buffering, so
 *     the scrape is kept off the hot path entirely — see [newPipeUrl], the
 *     failsafe of last resort.
 *
 *  2. **Which client asks.** Google turns identities away without notice and
 *     without pattern: the client that works today answers `LOGIN_REQUIRED`
 *     next month. So [CLIENTS] is walked rather than trusted, the one that last
 *     worked is tried first, and one that is refused for a track is stood down
 *     for that track for a while.
 *
 *  3. **Whether the URL is real.** Every googlevideo URL carries an `n`
 *     parameter which, sent as-is, gets the response throttled to a crawl or
 *     refused with 403; it has to be transformed by running YouTube's own
 *     player JavaScript, which is what NewPipe's [YoutubeJavaScriptPlayerManager]
 *     does. That can fail quietly, and a URL can be dead on arrival for reasons
 *     no amount of care predicts — so nothing is handed to the player, or
 *     cached, until a single byte has been fetched from it. See [probe].
 */
object StreamResolver {

    private const val TAG = "BitChord"

    /**
     * Player clients in the order they are worth asking, cheapest and most
     * reliable first — an order taken from what the live endpoint actually
     * answers, not from what ought to work.
     *
     * The four at the top return plain `url` fields, so a stream is one POST
     * away with no player JavaScript involved at all. [PlayerClient.ANDROID]
     * below them hands back ciphered formats, costing a download of that
     * JavaScript and a signature to solve. See each entry in [PlayerClient].
     *
     * No web client appears here. `WEB_REMIX` was the tail of this list and
     * paid for itself in neither reliability nor speed — always ciphered,
     * usually refused, and reached only on tracks that were already failing,
     * where the one thing left worth spending is time. [newPipeUrl] is the
     * last resort instead.
     *
     * The gating that decides which of these answers is applied per network,
     * not globally — an identity refused on one connection is served on
     * another — which is the whole reason this is a list and why the order is
     * only a starting guess that [clientOrder] corrects from experience.
     */
    private val CLIENTS = listOf(
        PlayerClient.ANDROID_VR,
        PlayerClient.ANDROID_VR_LEGACY,
        PlayerClient.IOS,
        PlayerClient.IOS_RECENT,
        PlayerClient.ANDROID,
    )

    /** NewPipe needs a Downloader; reuse the app's single OkHttp client. */
    private class OkHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())

            var hasUserAgent = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) {
                    hasUserAgent = true
                }
                when {
                    values.size > 1 -> {
                        builder.removeHeader(name)
                        values.forEach { builder.addHeader(name, it) }
                    }
                    values.size == 1 -> builder.header(name, values[0])
                }
            }
            if (!hasUserAgent) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; rv:120.0) Gecko/20100101 Firefox/120.0",
                )
            }

            val response = Http.client.newCall(builder.build()).execute()
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    private val init by lazy { NewPipe.init(OkHttpDownloader()) }

    /**
     * @return a directly streamable URL that has been proven to serve bytes,
     *   or throws with a reason worth showing.
     *
     * Results are held briefly — see [recent]. Resolving is the slow part of
     * starting a track, and ExoPlayer asks again for every re-open: each seek
     * outside the buffer, and each range the cache fills in.
     */
    suspend fun resolve(videoId: String): String {
        init

        recent[videoId]
            ?.takeIf { SystemClock.elapsedRealtime() - it.at < URL_TTL_MS }
            ?.let { return it.url }

        val stream = playerStream(videoId, ::pickForPlayback)
            ?: run {
                Log.w(TAG, "every player client failed for $videoId; falling back to extraction")
                newPipeStream(videoId, ::pickForQuality)
            }

        // The container carries no bitrate field, so this is the only place the
        // real figure is ever known.
        NerdStats.onStreamPicked(videoId, stream.kbps)
        remember(videoId, stream.url)
        return stream.url
    }

    /**
     * A resolved stream: the URL, and what the format behind it turned out to
     * be. Playback only ever needs the URL; a download needs the rest of it to
     * name the file and declare its type.
     */
    class Stream(val url: String, val kbps: Int, val mimeType: String) {

        val isOpus: Boolean get() = "opus" in mimeType.lowercase()

        /**
         * The container these bytes are actually in, which is not always what
         * names them.
         *
         * Nothing here transcodes or remuxes — what googlevideo sends is what
         * lands on disk — so the extension has to describe the bytes rather
         * than the codec inside them. YouTube's Opus is Opus-in-WebM, and the
         * two sources disagree about how to say so: the player endpoint calls
         * it `audio/webm; codecs="opus"` and NewPipe calls it `audio/opus`.
         * Taking the latter at face value would write a WebM file named
         * `.opus`, and an `.opus` file is expected to be Ogg — which is how a
         * perfectly good download ends up refusing to open in half the players
         * on the device.
         */
        val downloadExtension: String
            get() = when {
                isOpus || "webm" in mimeType -> "webm"
                "mp4" in mimeType || "m4a" in mimeType -> "m4a"
                else -> "webm"
            }

        /** What the media store should be told this file is. */
        val downloadMimeType: String
            get() = if (downloadExtension == "m4a") "audio/mp4" else "audio/webm"
    }

    /**
     * As [resolve], but for a file being kept rather than a stream being heard:
     * the best Opus on offer, whatever the quality ceiling says.
     *
     * Two passes, and the second one almost never runs. Opus is demanded across
     * *every* client before any of them is allowed to answer with AAC, because
     * a per-client "Opus or else the next client" walk would settle for the
     * first client that had only AAC while a later one held Opus all along.
     * Player responses are shared between the passes, so the second costs the
     * probes again but not the round trips.
     *
     * Nothing here touches [recent]. That cache exists to keep ExoPlayer's
     * re-opens off the network, and its entries are picked under the quality
     * ceiling — seeding it with an unbudgeted Opus URL would quietly hand a
     * capped connection the stream it was capped to avoid, and reading from it
     * would hand a download whatever bitrate playback happened to settle for.
     *
     * The whole thing is attempted twice, for the case where a client is turned
     * away with "Sign in to confirm you're not a bot": [playerStream] mints a
     * fresh visitor id and retries that one client, but `mintedFreshVisitor` is
     * scoped to a single walk, so a bot check late in the list burns the retry
     * and the new id benefits only the *next* resolve. The second walk is what
     * turns that into one download that works. It is worth knowing what it
     * cannot do: a client whose URL failed to probe was stood down by that
     * failure and is skipped on the way round again, so the second attempt is
     * the same walk minus its refusals, not a clean one. Bot checks it can fix;
     * refusals it cannot.
     *
     * Which is why extraction sits behind both. When every client is refusing
     * — the observed state, with the VR clients bot-checked and iOS minting
     * Opus URLs that 403 — [resolve] still gets audio, because it falls through
     * to NewPipe and re-derives the URL itself. A download reaching the same
     * wall has to do the same thing or it fails while the track it is refusing
     * to save is audibly playing.
     */
    suspend fun resolveForDownload(videoId: String): Stream {
        init

        // Whether any client offered Opus at all, as distinct from whether one
        // could be turned into a working URL. Those are different failures and
        // only one of them is a reason to accept a worse format: a track that
        // genuinely has no Opus is a fact about the track, while Opus that
        // won't probe is a bad afternoon on Google's side, and quietly saving
        // AAC because of the latter would hand back a permanently worse file
        // for a temporary reason.
        var offered = false

        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(DOWNLOAD_RETRY_MS)
            // Fresh each time. Responses are only cached once a client has
            // answered, and re-deriving a URL from a cached response produces
            // the same URL that just failed to probe — so carrying the map
            // across attempts would make every attempt after the first a
            // no-op.
            val responses = mutableMapOf<PlayerClient, JsonObject>()
            playerStream(videoId, { response -> pickOpus(response)?.also { offered = true } }, responses)
                ?.let { return it }
        }

        // Not "try again later" — every client being refused at once is a state
        // that lasts hours, and it is precisely the state [resolve] extracts its
        // way out of. Asking for Opus specifically, because this is still a
        // download: the failsafe is a different route to the bytes, not a
        // licence to take a worse format.
        Log.w(TAG, "no client minted a usable Opus URL for $videoId; extracting")
        runCatching {
            newPipeStream(videoId) { candidates ->
                candidates.filter { it.second.isOpus }
                    .maxByOrNull { it.first }?.second
                    ?.also { offered = true }
            }
        }.onSuccess { return it }
            .onFailure { Log.w(TAG, "extraction found no Opus for $videoId: ${it.message}") }

        if (offered) {
            error("Opus wasn't available for this track just now — try again")
        }

        Log.w(TAG, "nothing offered Opus for $videoId; taking the best available")
        return playerStream(videoId, ::pickBest)
            ?: newPipeStream(videoId) { candidates ->
                // Reached only when no client answered at all, so this is
                // re-deriving the formats from scratch rather than picking
                // over the ones already rejected — Opus is still worth asking
                // for here, and still worth doing without.
                val opus = candidates.filter { it.second.isOpus }
                opus.ifEmpty { candidates }.maxByOrNull { it.first }?.second
            }
    }

    /**
     * Walks [CLIENTS] until one produces a URL that actually serves audio.
     *
     * Every step is allowed to fail without taking the attempt with it: a
     * client can be refused the track, hand back formats none of which [select]
     * accepts or none of which can be unciphered, or mint a URL that turns out
     * to be dead. Only running out of clients is a failure.
     *
     * [responses] memoises the player response per client for the caller that
     * walks twice — see [resolveForDownload]. A client that is asked again
     * inside one walk is a bug, not a cost, so the default is a fresh map.
     *
     * @return the validated stream, or null to fall through to [newPipeStream].
     */
    private suspend fun playerStream(
        videoId: String,
        select: (JsonObject) -> Audio?,
        responses: MutableMap<PlayerClient, JsonObject> = mutableMapOf(),
    ): Stream? {
        // Before anything asks. Without one, the good clients refuse outright
        // and the rest hand back URLs that only *look* like they work — see
        // [Innertube.ensureVisitorData].
        Innertube.ensureVisitorData()

        var timestamp: Int? = null
        var mintedFreshVisitor = false

        for (client in clientOrder()) {
            if (isStoodDown(videoId, client)) continue
            try {
                // Only fetched once, and only if a client that needs it is
                // reached — it costs a download of YouTube's player JavaScript.
                if (client.needsSignatureTimestamp && timestamp == null) {
                    timestamp = runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }
                        .onFailure { Log.w(TAG, "no signature timestamp: ${it.message}") }
                        .getOrNull()
                        ?: continue
                }

                val response = responses[client] ?: try {
                    Innertube.player(videoId, client, timestamp)
                } catch (e: Innertube.UnplayableException) {
                    // A visitor id can be burned while the session around it is
                    // fine, and the only symptom is being called a bot. Worth
                    // one fresh id and one more try, once per resolve.
                    if (!e.looksLikeBotCheck || mintedFreshVisitor) throw e
                    mintedFreshVisitor = true
                    Log.d(TAG, "bot check from ${client.clientName}; minting a fresh visitor id")
                    Innertube.ensureVisitorData(refresh = true)
                    Innertube.player(videoId, client, timestamp)
                }
                responses[client] = response

                val format = select(response) ?: continue
                val url = streamUrl(videoId, format)
                    ?.let { patchClientVersion(it, client.clientVersion) }
                    ?: continue

                val verdict = probe(url)
                when (verdict) {
                    Probe.OK -> {
                        Log.d(TAG, "resolved $videoId via ${client.clientName} @ ${format.kbps}kbps")
                        preferred = client
                        return Stream(url, format.kbps, format.mimeType)
                    }
                    // The client itself is being refused this track; don't
                    // spend another round trip on it for a while.
                    Probe.REFUSED -> standDown(videoId, client)
                    Probe.UNREACHABLE -> Unit
                }
                // Which format was rejected, not just that one was: the same
                // client can mint a good URL for one itag and a dead one for
                // another, so without the format this line cannot tell a track
                // being refused from a codec being refused.
                Log.w(
                    TAG,
                    "${client.clientName} minted an unusable URL for $videoId: " +
                        "$verdict for ${format.mimeType} @ ${format.kbps}kbps",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${client.clientName} failed for $videoId: ${e.message}")
            }
        }
        return null
    }

    /**
     * [CLIENTS], led by whichever one last worked.
     *
     * Google's decisions apply to the whole app for as long as they last, not
     * to one track, so the client that served the previous song is overwhelmingly
     * likely to serve this one — and starting there is what keeps the common
     * case at a single round trip.
     */
    private fun clientOrder(): List<PlayerClient> {
        val first = preferred ?: return CLIENTS
        return listOf(first) + CLIENTS.filterNot { it == first }
    }

    @Volatile
    private var preferred: PlayerClient? = null

    // ---- Format selection ---------------------------------------------------

    /** One audio entry of a player response, before its URL has been unlocked. */
    private class Audio(
        val url: String?,
        val signatureCipher: String?,
        val kbps: Int,
        val mimeType: String,
    ) {
        /**
         * YouTube's Opus is always carried in WebM — there is no Opus-in-MP4
         * on this endpoint — so the codec is what the mime type names after
         * the container, and matching on the word is enough to tell it from
         * the AAC ladder sitting beside it.
         */
        val isOpus: Boolean get() = "opus" in mimeType.lowercase()
    }

    private fun audioFormats(response: JsonObject): List<Audio> =
        response["streamingData"]?.jsonObject
            ?.get("adaptiveFormats")?.jsonArray
            ?.map { it.jsonObject }
            ?.filter { it.str("mimeType")?.startsWith("audio/") == true }
            ?.map {
                Audio(
                    url = it.str("url"),
                    signatureCipher = it.str("signatureCipher") ?: it.str("cipher"),
                    kbps = ((it.str("bitrate")?.toLongOrNull() ?: 0L) / 1000).toInt(),
                    mimeType = it.str("mimeType").orEmpty(),
                )
            }
            ?.filter { it.url != null || it.signatureCipher != null }
            .orEmpty()

    /** What playback wants: the best format the connection's ceiling allows. */
    private fun pickForPlayback(response: JsonObject): Audio? =
        pickForQuality(audioFormats(response).map { it.kbps to it })

    /**
     * What a download wants: the best Opus there is, and nothing else.
     *
     * No ceiling is applied. The quality setting exists to budget a *stream* —
     * bytes spent on a track being listened to once, over and over — and a file
     * saved to the device is the opposite case: paid for once, kept, and played
     * from disk forever after. Capping it at the setting that happens to be in
     * force would bake a temporary decision about mobile data into a permanent
     * artefact.
     */
    private fun pickOpus(response: JsonObject): Audio? =
        audioFormats(response).filter { it.isOpus }.maxByOrNull { it.kbps }

    /** The fallback for the rare track that no client offers Opus for. */
    private fun pickBest(response: JsonObject): Audio? =
        audioFormats(response).maxByOrNull { it.kbps }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    /**
     * Highest stream at or under the ceiling set for the connection in use; if
     * everything is above it (e.g. Low on a track that only has 130kbps+), take
     * the cheapest available rather than failing.
     */
    private fun <T> pickForQuality(candidates: List<Pair<Int, T>>): T? {
        if (candidates.isEmpty()) return null
        val ceiling = AppSettings.effectiveAudioQuality.maxKbps
        val withinBudget = candidates.filter { it.first <= ceiling }
        return (withinBudget.maxByOrNull { it.first } ?: candidates.minByOrNull { it.first })
            ?.second
    }

    // ---- Unlocking ----------------------------------------------------------

    /** The playable URL behind a format, or null if it can't be unlocked. */
    private fun streamUrl(videoId: String, format: Audio): String? {
        val direct = format.url
        if (direct != null) return deobfuscate(videoId, direct)

        val cipher = format.signatureCipher ?: return null
        val params = cipher.split("&")
            .mapNotNull { part ->
                val i = part.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
                URLDecoder.decode(part.substring(0, i), "UTF-8") to
                    URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }
            .toMap()

        val base = params["url"] ?: return null
        val signature = params["s"] ?: return null
        // Which query parameter the solved signature belongs in; YouTube has
        // changed the name before, so it travels alongside rather than assumed.
        val into = params["sp"] ?: "signature"
        val solved = runCatching {
            YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, signature)
        }.getOrElse {
            Log.w(TAG, "signature cipher failed: ${it.message}")
            return null
        }
        val separator = if ("?" in base) "&" else "?"
        return deobfuscate(videoId, "$base$separator$into=$solved")
    }

    /**
     * Transform the `n` parameter when present. If deobfuscation itself fails
     * we still return the original URL — a throttled stream beats no stream,
     * and [probe] gets the final say on whether it plays at all.
     */
    private fun deobfuscate(videoId: String, url: String): String {
        val needsWork = url.toHttpUrlOrNull()?.queryParameter("n")?.isNotBlank() == true
        if (!needsWork) return url
        return runCatching {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
        }.getOrElse {
            Log.w(TAG, "n-param deobfuscation failed: ${it.message}")
            url
        }
    }

    /**
     * Align the URL's `cver` with the client that actually asked.
     *
     * The player response fills it in from the request, but a signature or `n`
     * transform can be solved against player JavaScript of a different vintage,
     * and googlevideo answers a version it doesn't expect with a 403.
     */
    private fun patchClientVersion(url: String, clientVersion: String): String =
        if ("cver=" in url) url.replace(Regex("cver=[^&]+"), "cver=$clientVersion") else url

    // ---- Validation ---------------------------------------------------------

    private enum class Probe {
        /** Served media bytes; safe to play and to cache. */
        OK,

        /** Answered, but refused this request — the client is the problem. */
        REFUSED,

        /** Never got an answer worth interpreting; blame nothing in particular. */
        UNREACHABLE,
    }

    /**
     * Read the end of a URL before trusting it.
     *
     * This is the whole difference between a track that fails and a track that
     * fails *visibly and instantly*. A URL that 403s is indistinguishable from
     * a good one until something reads from it; hand it to ExoPlayer and the
     * failure surfaces as a track that spins and never starts.
     *
     * *Where* it reads matters as much as that it reads at all, and the line
     * moves. This asked for the opening sixteen kilobytes until Google began
     * handing out URLs good for roughly the first megabyte and nothing beyond
     * it: they serve an opening range to anybody and refuse every offset past
     * the window — bounded range, unbounded GET, even sixteen kilobytes — so a
     * probe reading from position zero passes exactly the URLs it exists to
     * catch, and the failure lands on the first chunk instead. The bytes asked
     * for are therefore the last ones, named from `clen`: the ones a grudging
     * URL is least likely to be granted, at the same cost as the first ones.
     *
     * The headers are the ones the media fetch will really use — see
     * [PlayerClient.forStreamUrl] — so this tests the request that matters
     * rather than a more favourable version of it.
     */
    private fun probe(url: String): Probe {
        val (range, expected) = probeWindow(url)
        val builder = okhttp3.Request.Builder().url(url)
            .header("Range", "bytes=$range")
        PlayerClient.forStreamUrl(url).mediaHeaders().forEach { (name, value) ->
            builder.header(name, value)
        }
        return try {
            prober.newCall(builder.build()).execute().use { response ->
                when {
                    response.code in REFUSAL_CODES -> Probe.REFUSED
                    response.code !in 200..299 && response.code != 416 -> Probe.UNREACHABLE
                    // A refusal dressed as a success: an error page, or the
                    // consent/captcha interstitial, rather than audio.
                    response.header("Content-Type")?.startsWith("audio/") != true -> Probe.REFUSED
                    // Headers can arrive long before a body that never does —
                    // exactly the shaping this whole path exists to sidestep.
                    // Insisting on the whole range is the point: a trickle that
                    // yields its first byte and stalls is a failure too.
                    response.body?.source()?.request(expected) != true -> Probe.UNREACHABLE
                    else -> Probe.OK
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "probe failed: ${e.message}")
            Probe.UNREACHABLE
        }
    }

    /**
     * The range [probe] should ask for, and how many bytes it must yield.
     *
     * Anchored to the end of the resource, which `clen` names for nothing —
     * the same figure [contentLength] reads for read-ahead. Without one there
     * is no way to address the tail, and a URL that doesn't carry it isn't
     * being chunked for playback either (see [ChunkedDataSource][com.music.bitchord.playback.ChunkedDataSource]),
     * so the opening is both all that can be asked for and all that is at risk.
     */
    private fun probeWindow(url: String): Pair<String, Long> {
        val total = url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
        if (total == null || total <= 0L) return "0-${PROBE_BYTES - 1}" to PROBE_BYTES
        val length = minOf(PROBE_BYTES, total)
        return "${total - length}-${total - 1}" to length
    }

    private val REFUSAL_CODES = setOf(403, 404, 410)

    /**
     * The probe's own client: the app's, but on a short leash.
     *
     * [Http.client]'s 30-second read timeout is right for a stream being
     * consumed as it arrives and far too patient for a yes/no question —
     * waiting it out is indistinguishable from the stall being tested for.
     * Built from the shared client, so the connection pool and DNS are the
     * same ones the real fetch will use.
     */
    private val prober by lazy {
        Http.client.newBuilder()
            .callTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private const val PROBE_TIMEOUT_SECONDS = 6L

    /** Past what a grudging URL will serve, well under what any track holds. */
    private const val PROBE_BYTES = 16L * 1024

    // ---- Clients stood down -------------------------------------------------

    /**
     * Clients refused a given track, and until when.
     *
     * A refusal is rarely about the track alone — it usually means Google has
     * stopped answering that identity — but it is recorded per track because
     * that is the granularity it can be observed at. Keyed the same way it is
     * looked up, so a stale entry costs one retry rather than a lasting hole.
     */
    private val standDownUntil = ConcurrentHashMap<String, Long>()

    private const val STAND_DOWN_MS = 10 * 60 * 1000L

    private fun key(videoId: String, client: PlayerClient) =
        "$videoId|${client.clientName}@${client.clientVersion}"

    private fun standDown(videoId: String, client: PlayerClient) {
        standDownUntil[key(videoId, client)] = SystemClock.elapsedRealtime() + STAND_DOWN_MS
    }

    private fun isStoodDown(videoId: String, client: PlayerClient): Boolean {
        val k = key(videoId, client)
        val until = standDownUntil[k] ?: return false
        if (until > SystemClock.elapsedRealtime()) return true
        standDownUntil.remove(k)
        return false
    }

    /**
     * A URL that [probe] cleared has been refused while actually playing.
     *
     * Everything above assumes a URL that served bytes once will keep serving
     * them, and mostly that holds. When it doesn't, nothing here would ever
     * find out: [probe] runs before playback and not again, so a client that
     * goes bad mid-session stays [preferred], and [recent] keeps handing back
     * the same dead URL for the rest of its TTL. Every following track then
     * fails the same way, and the app can only be talked out of it by being
     * restarted — which is the one symptom users actually report.
     *
     * So the refusal is fed back: forget the URL, stand the client down for
     * that track, and give up the preference so the next resolve starts from
     * the top of [CLIENTS] rather than from the client that just failed.
     *
     * Called from the playback path — see
     * [ChunkedDataSource][com.music.bitchord.playback.ChunkedDataSource].
     */
    fun onPlaybackRefused(url: String, responseCode: Int) {
        if (responseCode !in REFUSAL_CODES) return
        val client = PlayerClient.forStreamUrl(url)
        // Keyed by videoId, and the fetch only knows the googlevideo URL it was
        // handed; the map is a latency cache of a few dozen entries, so finding
        // the way back costs nothing worth measuring.
        recent.entries.firstOrNull { it.value.url == url }?.key?.let { videoId ->
            recent.remove(videoId)
            standDown(videoId, client)
        }
        // Independent of that lookup on purpose: standing down the preference
        // is what breaks the loop, and it must still happen if the URL has
        // already aged out of the cache.
        if (preferred == client) {
            Log.w(TAG, "${client.clientName} refused a URL it had already served; standing it down")
            preferred = null
        }
    }

    // ---- Failsafe -----------------------------------------------------------

    /**
     * NewPipe's full extractor, kept for the case where every player client has
     * been turned away — it re-derives everything itself and is updated
     * upstream when YouTube changes, so it works when nothing else does.
     *
     * Last rather than first because of what it costs: it scrapes the watch
     * page, which is the request Google shapes hardest, and a shaped response
     * can hold this call open for the better part of a minute. Worth waiting
     * out when the alternative is silence; not worth paying for every track.
     */
    private fun newPipeStream(
        videoId: String,
        select: (List<Pair<Int, AudioStream>>) -> AudioStream?,
    ): Stream {
        val info = StreamInfo.getInfo(
            ServiceList.YouTube,
            "https://www.youtube.com/watch?v=$videoId",
        )
        val candidates = info.audioStreams
            // Progressive only — DASH/HLS entries carry a manifest, not a URL.
            .filter {
                !it.content.isNullOrBlank() &&
                    it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
            }
        val stream = select(candidates.map { it.averageBitrate to it })
            ?: error("Track unavailable: no audio streams")
        Log.d(TAG, "NewPipe picked ${stream.format?.name} @ ${stream.averageBitrate}kbps")
        return Stream(
            url = deobfuscate(videoId, stream.content),
            kbps = stream.averageBitrate,
            mimeType = stream.mime,
        )
    }

    /**
     * The container, which is all NewPipe's mime type reports.
     *
     * `MediaFormat.WEBMA_OPUS` — what YouTube's Opus arrives as — carries the
     * mime type `audio/webm`, identical to the Vorbis-in-WebM entry beside it.
     * Accurate about the bytes, and useless for telling the codec apart, which
     * is what [isOpus] is for.
     */
    private val AudioStream.mime: String get() = format?.mimeType.orEmpty()

    /**
     * Whether these bytes are Opus, asked of the format rather than its name.
     *
     * The tempting version of this is a substring match for "opus" on [mime],
     * and it silently never matches: the format that means Opus says
     * `audio/webm`. A download demanding Opus then concludes the track hasn't
     * any and fails — while playback, which asks only for the best bitrate,
     * takes the same stream and plays it as Opus.
     */
    private val AudioStream.isOpus: Boolean
        get() = format == MediaFormat.WEBMA_OPUS || format == MediaFormat.OPUS

    // ---- Cache --------------------------------------------------------------

    /**
     * How many bytes the whole track is, or null if the URL doesn't say.
     *
     * Every progressive googlevideo URL carries the figure as `clen`. It is
     * worth reading from there because the alternative is an HTTP request that
     * reaches the end of the resource: a bounded range never reveals the total,
     * so read-ahead would have no way to know when it was finished. Resolving
     * is memoised, so asking costs nothing beyond the first time.
     */
    suspend fun contentLength(videoId: String): Long? =
        resolve(videoId).toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()

    private class Resolved(val url: String, val at: Long)

    /**
     * Stream URLs already resolved, by videoId — and, since [resolve] only ever
     * stores one that has served bytes, already known good rather than merely
     * recent.
     *
     * Google issues them with several hours of validity, so the ceiling here is
     * chosen for a different reason: a URL is tied to the playback session that
     * minted it, and holding one indefinitely means a stale entry survives long
     * enough to fail a play. Twenty minutes covers a track and the seeking
     * around it while staying well inside the window where the URL is good.
     */
    private val recent = ConcurrentHashMap<String, Resolved>()

    private const val URL_TTL_MS = 20 * 60 * 1000L

    /** Enough for the queue in hand; this is a latency cache, not a store. */
    private const val MAX_REMEMBERED = 32

    /** See [resolveForDownload]: one walk to burn a stale visitor id, one to use its replacement. */
    private const val DOWNLOAD_ATTEMPTS = 2

    /** Long enough for a freshly minted visitor id to be worth anything, short enough not to be felt. */
    private const val DOWNLOAD_RETRY_MS = 500L

    private fun remember(videoId: String, url: String) {
        if (recent.size >= MAX_REMEMBERED) {
            val cutoff = SystemClock.elapsedRealtime() - URL_TTL_MS
            recent.entries.removeAll { it.value.at < cutoff }
            if (recent.size >= MAX_REMEMBERED) recent.clear()
        }
        recent[videoId] = Resolved(url, SystemClock.elapsedRealtime())
    }
}
