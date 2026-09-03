from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzle.kt")
text = path.read_text(encoding="utf-8")

old_intercept = r'''                interceptUrl = Regex("""https?://[^"'\s<>]*kinescopecdn\.net/hls/[^"'\s<>]+\.m3u8(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE),'''
new_intercept = r'''                interceptUrl = Regex("""https?://(?:kinescope\.io/api/v1/embed/[^"'\s<>]+|[^"'\s<>]*kinescopecdn\.net/hls/[^"'\s<>]+\.m3u8(?:\?[^"'\s<>]*)?)""", RegexOption.IGNORE_CASE),'''

if old_intercept not in text:
    raise SystemExit("HintFilmIzle Kinescope intercept satırı bulunamadı")

text = text.replace(old_intercept, new_intercept, 1)

marker = '''            val manifestCandidates = buildList {'''
insertion = '''            // Android WebView can reject the dynamic Kinescope CDN certificate.
            // The player requests its signed embed API on kinescope.io first.
            // Capture that request, fetch it natively, decrypt the payload and
            // extract the signed HLS variant without loading the CDN in WebView.
            val apiRequestUrl = sequenceOf(resolverLink?.url?.toString())
                .plus(allUrls.asSequence())
                .filterNotNull()
                .firstOrNull { it.contains("kinescope.io/api/v1/embed/", true) }

            val apiManifestUrl = apiRequestUrl?.let { apiUrl ->
                runCatching {
                    val response = app.get(
                        apiUrl,
                        referer = iframeUrl,
                        headers = mapOf(
                            "Accept" to "application/json,text/plain,*/*",
                            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
                        )
                    )
                    val decoded = decodeKinescopeManifestResponse(response.text)
                    if (decoded.isNullOrBlank()) {
                        null
                    } else {
                        val direct = Regex(
                            """https?://[^"'\\s<>]+?\\.m3u8(?:\\?[^"'\\s<>]*)?""",
                            RegexOption.IGNORE_CASE
                        ).find(decoded)?.value

                        direct ?: extractKinescopeVariant(decoded, apiUrl)
                    }
                }.onFailure {
                    Log.e("HintFilmIzle", "KINESCOPE_API_RESOLVE_FAILED", it)
                }.getOrNull()
            }

            Log.d("HintFilmIzle", "KINESCOPE_API_URL=" + apiRequestUrl.orEmpty())
            Log.d("HintFilmIzle", "KINESCOPE_API_MANIFEST=" + apiManifestUrl.orEmpty())

'''

if marker not in text:
    raise SystemExit("HintFilmIzle manifestCandidates noktası bulunamadı")

if "KINESCOPE_API_MANIFEST=" not in text:
    text = text.replace(marker, insertion + marker, 1)

path.write_text(text, encoding="utf-8")
print("HintFilmIzle Kinescope API patch applied")
