package com.music.bitchord.data.sources

/**
 * The kinds of source this build knows how to talk to.
 *
 * This is the catalogue the sources screen offers under "Available" — a fixed
 * list compiled into the app, not something fetched at runtime. Adding a source
 * means adding a [MusicSource] implementation and an entry here, which is the
 * point: every protocol the app speaks is one someone can read in this repo,
 * and a source can't teach the app a new way to behave after it ships.
 *
 * What varies per *instance* — which server, whose account — is [SourceConfig].
 */
enum class SourceKind(
    val label: String,
    val detail: String,
    /** The chips under the name on the sources screen. */
    val labels: List<String>,
    /** Whether an instance needs a URL and credentials before it can do anything. */
    val needsServer: Boolean,
    /** Whether this kind can serve bit-exact audio when asked. */
    val canServeLossless: Boolean,
    /** More than one instance is meaningful — two Navidrome servers, but only one device library. */
    val allowsMultiple: Boolean,
) {
    SUBSONIC(
        label = "Subsonic server",
        detail = "Navidrome, Airsonic, Gonic, Ampache, or Jellyfin's Subsonic endpoint. " +
            "Streams your own library at whatever the files hold.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Your library"),
        needsServer = true,
        canServeLossless = true,
        allowsMultiple = true,
    ),

    DEVICE(
        label = "On this device",
        detail = "Music already on the phone, including anything downloaded in BitChord.",
        labels = listOf("FLAC", "Lossless", "Offline"),
        needsServer = false,
        canServeLossless = true,
        allowsMultiple = false,
    ),

    /**
     * The source the app was built on, listed here so it can be *ordered* rather
     * than merely tolerated. Someone with a full Navidrome library wants it
     * tried first and YouTube kept as the long tail; someone without one wants
     * the opposite. Neither is expressible while one of the two is hardcoded as
     * "the" source, which it was until this screen existed.
     *
     * It cannot be removed — see [SourceRegistry]. Nothing else in the app can
     * supply a home feed, a radio station or a related-tracks queue.
     */
    YOUTUBE(
        label = "YouTube Music",
        detail = "The full catalogue, at Opus up to about 171 kbps. Lossy — there is no " +
            "lossless rendition to ask for.",
        labels = listOf("Lossy", "Full catalogue", "Radio"),
        needsServer = false,
        canServeLossless = false,
        allowsMultiple = false,
    ),
}
