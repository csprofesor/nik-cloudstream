version = 37

cloudstream {
    authors     = listOf("gsrepo")
    language    = "tr"
    description = "Türkiye'nin hızlı hd film izleme sitesi"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=hdfilmcehennemi.nl&sz=%size%"
}