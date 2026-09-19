version = 1

cloudstream {
    authors = listOf("csprofesor")
    language    = "az"
    description = "SinemaTvAz - Azərbaycan dilində filmlər və seriallar"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    iconUrl = "https://www.google.com/s2/favicons?domain=sinematv.az&sz=%size%"
}