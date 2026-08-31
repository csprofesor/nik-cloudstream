package com.nikyokki

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.nikyokki.extractors.FilmKovasiOkRuExtractor
import android.content.Context

@CloudstreamPlugin
class FilmKovasiPlugin: Plugin() {
    override fun load(context: Context) {
        registerExtractorAPI(FilmKovasiOkRuExtractor())
        registerMainAPI(FilmKovasi())
    }
}