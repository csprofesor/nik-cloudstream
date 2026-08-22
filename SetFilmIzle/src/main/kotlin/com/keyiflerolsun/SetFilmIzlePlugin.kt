package com.keyiflerolsun

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SetFilmIzlePlugin : Plugin() {
    override fun load(context: Context) {
        // Önce yalnızca ana sağlayıcıyı yükle. Extractor'lar ayrı ayrı
        // yüklenmediği için eklenti açılışında extractor kaynaklı çökme olmaz.
        registerMainAPI(SetFilmIzle())
    }
}
