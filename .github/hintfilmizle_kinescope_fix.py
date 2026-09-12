from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")

# Keep the Kinescope decoder available for a future API fallback, but make the
# primary resolver direct: current Kinescope documentation exposes a public
# master.m3u8 URL for video IDs, so there is no reason to start a WebView just
# to wait for the player to request HLS.
decoder_start = s.find("    private fun decodeKinescopeApi(body: String): String?")
decoder_end = s.find("    private suspend fun kinescope(", decoder_start)
if decoder_start < 0 or decoder_end < 0:
    raise SystemExit("Kinescope decoder function not found")

decoder = r'''    private fun decodeKinescopeApi(body: String): String? {
        val encoded = Regex(""""p"\s*:\s*"([^"]+)"""")
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
            """https?://[^"'\s<>]+\.kinescopecdn\.net/hls/[^"'\s<>]+/index\.m3u8(?:\?[^"'\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).find(decoded)?.value
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
    }

'''
s = s[:decoder_start] + decoder + s[decoder_end:]

# The fallback request is nullable; do not assign it back to a non-null NiceResponse variable.
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

# Replace the complete WebView-based Kinescope resolver with direct HLS links.
kine_start = s.find("    private suspend fun kinescope(")
loadlinks_start = s.find("    override suspend fun loadLinks(", kine_start)
if kine_start < 0 or loadlinks_start < 0:
    raise SystemExit("Kinescope function boundaries not found")

kine = r'''    private suspend fun kinescope(kine: String, parent: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val id = Regex("/embed/([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)
            .find(kine)?.groupValues?.getOrNull(1)
            ?: return false

        // Kinescope's public HLS endpoint. The embed URL may use a CDN host and
        // publisher prefix, but the video ID itself is the value after /embed/.
        val direct = "https://kinescope.io/$id/master.m3u8"
        Log.d("HintFilmIzle", "KINESCOPE_DIRECT=" + direct)

        callback(
            newExtractorLink(
                source = name,
                name = "HintFilmİzle Kinescope",
                url = direct,
                type = ExtractorLinkType.M3U8
            ) {
                referer = parent
                headers = mapOf(
                    "Referer" to parent,
                    "Origin" to mainUrl,
                    "User-Agent" to ua
                )
                quality = getQualityFromName(direct)
            }
        )
        true
    }.getOrElse {
        Log.e("HintFilmIzle", "KINESCOPE_FAILED", it)
        false
    }

'''
s = s[:kine_start] + kine + s[loadlinks_start:]

path.write_text(s, encoding="utf-8")
print("HintFilmIzle Kinescope direct resolver patch applied")
