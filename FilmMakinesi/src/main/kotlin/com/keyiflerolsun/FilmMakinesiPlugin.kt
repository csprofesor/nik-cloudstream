package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class FilmMakinesiPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmMakinesi())
        registerExtractorAPI(FilmMakinesiWebViewExtractor(context))
        registerExtractorAPI(CloseLoadTo())
        registerExtractorAPI(CloseLoadFilm())
        registerExtractorAPI(CloseLoadDe())
        registerExtractorAPI(CloseLoadTv())
        registerExtractorAPI(CloseLoadSh())
        registerExtractorAPI(RapidTo())
        registerExtractorAPI(RapidFilm())
        registerExtractorAPI(RapidDe())
        registerExtractorAPI(RapidTv())
        registerExtractorAPI(RapidSh())
    }
}
