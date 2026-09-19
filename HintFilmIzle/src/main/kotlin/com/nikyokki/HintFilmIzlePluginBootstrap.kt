package com.nikyokki

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HintFilmIzlePlugin : Plugin() {
    companion object {
        var pluginContext: Context? = null
    }

    override fun load(context: Context) {
        pluginContext = context
        registerMainAPI(HintFilmIzle())
    }
}
