version = 14

// DiziGom v13 build

cloudstream {
    authors = listOf("csprofesor")
    language    = "tr"
    description = "Türkçe altyazılı yabancı dizi izle, Tüm yabancı, kore, netflix dizilerin yeni ve eski sezonlarını orijinal dilinde dizigom1 alt yazılı film izleyebilir, sadece türkçe altyazılı en iyi yabancı diziler ve filmler hakkında yorum yapabilirsiniz."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=www.dizigom.love&sz=%size%"
}

// Fresh build: posters + PilayerPlay stream resolver.
// Build compatibility fix: nullable poster attributes are normalized in CI.
// Trigger rebuild after repository cache-version correction.
