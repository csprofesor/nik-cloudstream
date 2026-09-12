from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")

# Keep the Kinescope API decoder: the site's embed API returns an encrypted
# `p` field which decodes to the real, signed CDN HLS manifest.
decoder_start = s.find("    private fun decodeKinescopeApi(body: String): String?")
decoder_end = s.find("    private suspend fun kinescope(", decoder_start)
if decoder_start < 0 or decoder_end < 0:
    raise SystemExit("Kinescope decoder function not found")

decoder = r'''    private fun decodeKinescopeApi(body: String): String? {
        val encoded = Regex("""\"p\"\s*:\s*\"([^\"]+)\"""")
            .find(body)?.groupValues?.getOrNull(1) ?: return null
        val encrypted = runCatching {
            android.util.Base64.decode(encoded.reversed(), android.util.Base64.DEFAULT)
        }.getOrNull() ?: return null
        val key = "RySdvcyu5iTUxn97vn4HwoniwgxaCynA".toByteArray(Charsets.UTF_8)
        val plain = ByteArray(encrypted.size) { i ->
            (encrypted[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        val decoded = runCatching { String(plain, Charsets.UTF_8) }.getOrNull() ?: return null
        return Regex(
            """https?://[^\"'\s<>]+\.kinescopecdn\.net/hls/[^\"'\s<>]+/index\.m3u8(?:\?[^\"'\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).find(decoded)?.value
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
    }

'''
s = s[:decoder_start] + decoder + s[decoder_end:]

old_main = '''        var response = runCatching { app.get(url, referer = "$mainUrl/", headers = headers()) }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        var doc = response.document
        var r = results(doc, slug)
        if (slug != null && r.isEmpty()) {
            val fallbackUrl = if (page <= 1) "$mainUrl/film?order=DESC&orderby=date" else "$mainUrl/film/page/$page/?order=DESC&orderby=date"
            response = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()
            if (response != null) { doc = response.document; r = results(doc, slug) }
        }
'''
new_main = '''        val response = runCatching { app.get(url, referer = "$mainUrl/", headers = headers()) }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        var doc = response.document
        var r = results(doc, slug)
        if (slug != null && r.isEmpty()) {
            val fallbackUrl = if (page <= 1) "$mainUrl/film?order=DESC&orderby=date" else "$mainUrl/film/page/$page/?order=DESC&orderby=date"
            val fallbackResponse = runCatching { app.get(fallbackUrl, referer = "$mainUrl/", headers = headers()) }.getOrNull()
            if (fallbackResponse != null) { doc = fallbackResponse.document; r = results(doc, slug) }
        }
'''
if old_main in s:
    s = s.replace(old_main, new_main, 1)

kine_start = s.find("    private suspend fun kinescope(")
loadlinks_start = s.find("    override suspend fun loadLinks(", kine_start)
if kine_start < 0 or loadlinks_start < 0:
    raise SystemExit("Kinescope function boundaries not found")

kine = r'''    private suspend fun kinescope(kine: String, parent: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val id = Regex("/embed/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(kine)?.groupValues?.getOrNull(1)
            ?: return false

        val parsed = runCatching { URI(kine) }.getOrNull() ?: return false
        val playerHost = parsed.host ?: return false
        val query = parsed.rawQuery.orEmpty()
        val lang = Regex("(?:^|&)lang=([^&]+)").find(query)?.groupValues?.getOrNull(1) ?: "en"
        val voiceover = Regex("(?:^|&)voiceover=([^&]+)").find(query)?.groupValues?.getOrNull(1)
        val nc = Regex("(?:^|&)nc=([^&]+)").find(query)?.groupValues?.getOrNull(1)
            ?: (System.currentTimeMillis() / 1000L).toString()

        // IMPORTANT: the iframe itself may be served from a river-*.kinescopecdn.net
        // host, but /api/v1/embed/{id} is the Kinescope API endpoint, not a CDN path.
        val apiBase = "https://kinescope.io/api/v1/embed/$id"
        Log.d("HintFilmIzle", "KINESCOPE_API_START=$apiBase")

        val embedBody = runCatching {
            app.get(kine, referer = parent, headers = headers()).text
        }.getOrNull().orEmpty()

        val normalizedEmbed = embedBody
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        // Some revisions expose the already-signed API or HLS URL in bootstrap JSON.
        val directHls = Regex(
            """https?://[^\"'\s<>]+\.kinescopecdn\.net/hls/[^\"'\s<>]+/index\.m3u8(?:\?[^\"'\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).find(normalizedEmbed)?.value
        if (directHls != null) {
            Log.d("HintFilmIzle", "KINESCOPE_EMBED_HLS=$directHls")
            callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = directHls, type = ExtractorLinkType.M3U8) {
                referer = kine
                headers = mapOf("Referer" to kine, "Origin" to "https://$playerHost", "User-Agent" to ua)
                quality = getQualityFromName(directHls)
            })
            return true
        }

        val signedApi = Regex(
            "https?://[^\\\"'\\s<>]+/api/v1/embed/" + Regex.escape(id) + "\\?[^\\\"'<>\\s]+",
            RegexOption.IGNORE_CASE
        ).find(normalizedEmbed)?.value
            ?.replace("\\u0026", "&")
            ?.replace("&amp;", "&")

        val params = buildList {
            add("lang=${URLEncoder.encode(lang, "UTF-8")}")
            add("domain=${URLEncoder.encode(URI(parent).host ?: "hintfilmizle.com", "UTF-8")}")
            add("iframe_url=${URLEncoder.encode(kine, "UTF-8")}")
            voiceover?.let { add("voiceover=${URLEncoder.encode(it, "UTF-8")}") }
            add("nc=${URLEncoder.encode(nc, "UTF-8")}")
        }.joinToString("&")

        val candidates = listOfNotNull(
            signedApi,
            "$apiBase?$params"
        ).distinct()

        var stream: String? = null
        for (apiUrl in candidates) {
            Log.d("HintFilmIzle", "KINESCOPE_API_TRY=$apiUrl")
            val body = runCatching {
                app.get(
                    apiUrl,
                    referer = kine,
                    headers = headers() + mapOf(
                        "Origin" to "https://$playerHost",
                        "Accept" to "application/json,text/plain,*/*"
                    )
                ).text
            }.getOrNull() ?: continue
            stream = decodeKinescopeApi(body)
            if (stream != null) {
                Log.d("HintFilmIzle", "KINESCOPE_API_OK=$apiUrl")
                Log.d("HintFilmIzle", "KINESCOPE_HLS=$stream")
                break
            }
        }

        val final = stream ?: run {
            Log.e("HintFilmIzle", "KINESCOPE_API_NO_STREAM id=$id candidates=${candidates.size}")
            return false
        }

        callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = final, type = ExtractorLinkType.M3U8) {
            referer = kine
            headers = mapOf(
                "Referer" to kine,
                "Origin" to "https://$playerHost",
                "User-Agent" to ua
            )
            quality = getQualityFromName(final)
        })
        true
    }.getOrElse {
        Log.e("HintFilmIzle", "KINESCOPE_FAILED", it)
        false
    }

'''
s = s[:kine_start] + kine + s[loadlinks_start:]

# The generated source no longer needs WebViewResolver.
s = s.replace('import com.lagradost.cloudstream3.network.WebViewResolver\n', '')
s = s.replace('// Kinescope WebView resolver: only the real HLS manifest terminates the resolver.\n// Analytics/ad requests are blocked in-page without using interceptUrl.\n', '// Kinescope resolver: use the embed API to obtain the real signed CDN HLS manifest.\n')

path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope API resolver patch applied")
