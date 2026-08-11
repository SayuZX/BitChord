package com.music.bitchord.data.innertube

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest

/**
 * Minimal Innertube (youtubei) client.
 *
 * Two client identities, for different reasons:
 *
 *  - **WEB_REMIX** against music.youtube.com for browse/search/library. It
 *    returns the full YT Music shelf layout and honours the signed-in session.
 *
 *  - **IOS** against www.youtube.com for the `player` endpoint. Google now
 *    answers ANDROID_MUSIC (and ANDROID_VR) with `LOGIN_REQUIRED`, while the
 *    iOS client still returns `OK` with unciphered `url` fields — so no
 *    signature-cipher solving is needed. Verified against the live endpoint.
 *
 * Authenticated requests are signed with Google's SAPISIDHASH scheme derived
 * from the stored cookie; no long-lived token is ever minted or stored.
 */
object Innertube {

    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
    private const val YT_BASE = "https://www.youtube.com/youtubei/v1"
    private const val MUSIC_ORIGIN = "https://music.youtube.com"

    private const val WEB_REMIX_VERSION = "1.20250101.01.00"
    private const val WEB_REMIX_CLIENT_ID = "67"
    private const val IOS_VERSION = "20.03.02"
    private val IOS_USER_AGENT = com.music.bitchord.data.Http.IOS_USER_AGENT

    private const val TAG = "BitChord"

    /** Session cookie captured by the login WebView; null = browse as guest. */
    var cookie: String? = null

    /**
     * Google's per-session visitor id, lifted from the first response that
     * carries one. Stats pings are attributed to it, so it has to be the same
     * value the browse/player calls used.
     */
    @Volatile
    private var visitorData: String? = null

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        // Same OkHttp instance ExoPlayer streams through — see Http.
        engine { preconfigured = com.music.bitchord.data.Http.client }
        install(ContentNegotiation) { json(json) }
        expectSuccess = true
    }

    // ---- Public API ---------------------------------------------------------

    suspend fun browse(browseId: String, params: String? = null): JsonObject =
        postMusic("browse") {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }

    /**
     * The next page of a paged browse response — playlists and library feeds
     * come back roughly 100 rows at a time. YouTube Music takes the token as
     * query parameters rather than in the body, and answers with a bare
     * continuation envelope carrying the same row renderers.
     */
    suspend fun browseContinuation(token: String): JsonObject = postMusic(
        endpoint = "browse",
        // The web client passes the token in the body and the older query-string
        // form is still honoured; both are sent so either is enough.
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
    ) {
        put("continuation", token)
    }

    /** Signed-in profile: display name, email/handle and avatar. */
    suspend fun accountMenu(): JsonObject = postMusic("account/account_menu") {}

    /**
     * The watch queue that YouTube Music would play after [videoId] — the
     * "RDAMVM" radio mix. Used to keep AutoPlay going past the last track.
     */
    suspend fun next(videoId: String): JsonObject = postMusic("next") {
        put("videoId", videoId)
        put("playlistId", "RDAMVM$videoId")
        put("isAudioOnly", true)
    }

    suspend fun search(query: String, params: String? = null): JsonObject =
        postMusic("search") {
            put("query", query)
            params?.let { put("params", it) }
        }

    /**
     * Highest-bitrate audio-only stream for [videoId], or null when the track
     * is genuinely unplayable (region block, takedown, members-only).
     */
    suspend fun playerStreamUrl(videoId: String): String? {
        val response = postPlayer(videoId)

        val status = response["playabilityStatus"]?.jsonObject
            ?.get("status")?.jsonPrimitive?.content
        if (status != null && status != "OK") {
            val reason = response["playabilityStatus"]?.jsonObject
                ?.get("reason")?.jsonPrimitive?.content
            throw UnplayableException(reason ?: status)
        }

        return response["streamingData"]?.jsonObject
            ?.get("adaptiveFormats")?.jsonArray
            ?.map { it.jsonObject }
            ?.filter { format ->
                format["mimeType"]?.jsonPrimitive?.content?.startsWith("audio/") == true &&
                    format["url"] != null // skip ciphered entries outright
            }
            ?.maxByOrNull { it["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L }
            ?.get("url")?.jsonPrimitive?.content
    }

    class UnplayableException(reason: String) :
        IllegalStateException("Track unavailable: $reason")

    /** The stats endpoints a player response nominates for one playback. */
    data class PlaybackTracking(val playbackUrl: String, val watchtimeUrl: String?)

    /**
     * Player response fetched *with* the session cookie, purely to read back
     * `playbackTracking` — [playerStreamUrl] deliberately skips auth so it can
     * use the iOS client and dodge signature ciphering, so it never sees this
     * block. Null for guests: there's no account history to update.
     */
    suspend fun playbackTracking(videoId: String): PlaybackTracking? {
        if (cookie == null) return null
        val response = postMusic("player") {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            // Real clients always describe where playback is happening; the
            // response's tracking block is scoped to it.
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    put("referer", "$MUSIC_ORIGIN/watch?v=$videoId")
                }
            }
        }
        val tracking = response["playbackTracking"]?.jsonObject
        if (tracking == null) {
            val playability = response["playabilityStatus"]?.jsonObject
            Log.w(
                TAG,
                "player response has no playbackTracking for $videoId " +
                    "(status=${playability?.get("status")?.jsonPrimitive?.content}, " +
                    "reason=${playability?.get("reason")?.jsonPrimitive?.content})",
            )
            return null
        }
        val playbackUrl = tracking.trackingUrl("videostatsPlaybackUrl") ?: return null
        return PlaybackTracking(playbackUrl, tracking.trackingUrl("videostatsWatchtimeUrl"))
    }

    private fun JsonObject.trackingUrl(key: String): String? =
        this[key]?.jsonObject?.get("baseUrl")?.jsonPrimitive?.content

    /**
     * The "playback started" ping real YouTube Music clients send once a track
     * becomes audible. This is what creates the history entry the home feed
     * feeds off. [cpn] is the client-playback-nonce identifying this one play:
     * it must be the same value used for every [pingWatchtime] that follows.
     */
    suspend fun pingPlayback(baseUrl: String, cpn: String) =
        pingStats(baseUrl, cpn) { parameter("el", "detailpage") }

    /**
     * The follow-up ping reporting how much of the track was actually heard.
     * A history entry with no watchtime behind it reads as a skip, so it
     * carries little weight in recommendations — [seconds] is what makes the
     * play count. `st`/`et` are the watched segment's bounds, in seconds.
     */
    suspend fun pingWatchtime(baseUrl: String, cpn: String, seconds: Long) =
        pingStats(baseUrl, cpn) {
            parameter("st", "0")
            parameter("et", seconds.toString())
            parameter("state", "playing")
        }

    /** Shared shape of the s.youtube.com stats pings, including session auth. */
    private suspend fun pingStats(
        baseUrl: String,
        cpn: String,
        extras: HttpRequestBuilder.() -> Unit,
    ): Int = client.get(baseUrl) {
        parameter("ver", "2")
        parameter("c", "WEB_REMIX")
        parameter("cver", WEB_REMIX_VERSION)
        parameter("cpn", cpn)
        extras()
        header("X-Origin", MUSIC_ORIGIN)
        header("Origin", MUSIC_ORIGIN)
        header("Referer", "$MUSIC_ORIGIN/")
        visitorData?.let { header("X-Goog-Visitor-Id", it) }
        cookie?.let { c ->
            header("Cookie", c)
            sapisidFrom(c)?.let { header("Authorization", sapisidHash(it)) }
        }
    }.status.value

    /** A fresh client-playback-nonce, identifying one play of one track. */
    fun newCpn(): String = (1..16).map { CPN_ALPHABET.random() }.joinToString("")

    private const val CPN_ALPHABET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

    // ---- Request plumbing ---------------------------------------------------

    private suspend fun postMusic(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val response = client.post("$MUSIC_BASE/$endpoint") {
            contentType(ContentType.Application.Json)
            parameter("prettyPrint", "false")
            query.forEach { (key, value) -> parameter(key, value) }
            header("X-Origin", MUSIC_ORIGIN)
            header("Origin", MUSIC_ORIGIN)
            header("Referer", "$MUSIC_ORIGIN/")
            // Stats pings are only honoured for a session Google recognises as
            // a real client, so identify as one here too — the visitor id is
            // minted on the first call and reused for the rest of the session.
            header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
            header("X-YouTube-Client-Version", WEB_REMIX_VERSION)
            visitorData?.let { header("X-Goog-Visitor-Id", it) }
            cookie?.let { c ->
                header("Cookie", c)
                header("X-Goog-AuthUser", "0")
                sapisidFrom(c)?.let { header("Authorization", sapisidHash(it)) }
            }
            setBody(
                buildJsonObject {
                    putJsonObject("context") {
                        putJsonObject("client") {
                            put("clientName", "WEB_REMIX")
                            put("clientVersion", WEB_REMIX_VERSION)
                            put("hl", "en")
                            put("gl", "US")
                            visitorData?.let { put("visitorData", it) }
                        }
                        putJsonObject("user") { put("lockedSafetyMode", false) }
                        putJsonObject("request") { put("useSsl", true) }
                    }
                    bodyExtras()
                },
            )
        }.body<JsonObject>()

        if (visitorData == null) {
            visitorData = response["responseContext"]?.jsonObject
                ?.get("visitorData")?.jsonPrimitive?.content
        }
        return response
    }

    /** Deliberately unauthenticated: the iOS client is rejected when signed cookies are attached. */
    private suspend fun postPlayer(videoId: String): JsonObject =
        client.post("$YT_BASE/player") {
            contentType(ContentType.Application.Json)
            parameter("prettyPrint", "false")
            header("User-Agent", IOS_USER_AGENT)
            header("Origin", "https://www.youtube.com")
            setBody(
                buildJsonObject {
                    putJsonObject("context") {
                        putJsonObject("client") {
                            put("clientName", "IOS")
                            put("clientVersion", IOS_VERSION)
                            put("deviceMake", "Apple")
                            put("deviceModel", "iPhone16,2")
                            put("osName", "iPhone")
                            put("osVersion", "18.2.1.22C161")
                            put("hl", "en")
                            put("gl", "US")
                        }
                    }
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                },
            )
        }.body<JsonObject>()

    private fun sapisidFrom(cookieHeader: String): String? =
        cookieHeader.split("; ", ";")
            .firstOrNull { it.trim().startsWith("SAPISID=") }
            ?.substringAfter("=")

    private fun sapisidHash(sapisid: String, origin: String = MUSIC_ORIGIN): String {
        val timestamp = System.currentTimeMillis() / 1000
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$timestamp $sapisid $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timestamp}_$digest"
    }
}
