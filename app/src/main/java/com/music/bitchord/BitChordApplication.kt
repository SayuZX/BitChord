package com.music.bitchord

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.LastPlayed
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SearchHistory

class BitChordApplication : Application() {

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // PlaybackService shares this process, so seeding the cookie here means
        // stream resolution is authenticated from the first play onwards.
        authStore = AuthStore(this)
        Innertube.cookie = authStore.cookie
        AppSettings.init(this)
        SearchHistory.init(this)
        LastPlayed.init(this)
        // One cache directory can only be opened once per process, and
        // PlaybackService shares this one — so it's opened here, not there.
        AudioCache.init(this)
    }

    companion object {
        lateinit var authStore: AuthStore
            private set
    }
}
