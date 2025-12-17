package com.kreastream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

@CloudstreamPlugin
class YouTubePlugin : Plugin() {

    override fun load(context: Context) {

        // Init downloader
        NewPipeDownloader.init(OkHttpClient.Builder())

        // Init NewPipe (MANDATORY for 0.28)
        NewPipe.init(
            NewPipeDownloader.getInstance(),
            Localization.fromLocale(context.resources.configuration.locales[0])
        )

        // Register providers
        registerMainAPI(YouTubeProvider())
        registerMainAPI(YouTubeChannelProvider("en"))
        registerMainAPI(YouTubePlaylistsProvider("en"))
    }
}
