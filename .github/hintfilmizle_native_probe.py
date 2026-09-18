from pathlib import Path
path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = path.read_text(encoding="utf-8-sig")
markers = [
    '''        val response = runCatching {''',
    '''        val response = runCatching {'''.replace("        ", "    "),
]
marker = next((m for m in markers if m in s), None)
insert = '''        // Probe the real CDN embed HTML natively before falling back to the signed API.
        runCatching {
            val htmlResponse = app.get(target, referer = parent, headers = headers() + mapOf(
                "Referer" to parent,
                "Origin" to "https://$parentDomain"
            ))
            Log.d("HintFilmIzle", "KINESCOPE_HTML_CODE=${htmlResponse.code}")
            Log.d("HintFilmIzle", "KINESCOPE_HTML_LEN=${htmlResponse.text.length}")
            val html = htmlResponse.text.replace("\\\\u0026", "&").replace("\\\\/", "/").replace("&amp;", "&")
            val candidates = buildList {
                Regex("""https?://[^\\\"'<>\\s]+\\.m3u8(?:\\?[^\\\"'<>\\s]*)?""", RegexOption.IGNORE_CASE)
                    .findAll(html).forEach { add(it.value) }
                Regex("""[\\\"'](?:src|url|file|playlist)[\\\"']?\\s*[:=]\\s*[\\\"']([^\\\"']+\\.m3u8[^\\\"']*)[\\\"']""", RegexOption.IGNORE_CASE)
                    .findAll(html).forEach { add(it.groupValues[1]) }
            }.mapNotNull { fix(it, target) }.distinct()
            val manifest = candidates.firstOrNull()
            if (!manifest.isNullOrBlank()) {
                Log.d("HintFilmIzle", "KINESCOPE_HTML_MANIFEST=$manifest")
                callback(newExtractorLink(source = name, name = "HintFilmİzle Kinescope", url = manifest, type = ExtractorLinkType.M3U8) {
                    referer = target
                    this.headers = mapOf("Referer" to target, "Origin" to "https://$parentDomain", "User-Agent" to ua)
                    quality = getQualityFromName(manifest)
                })
                return@runCatching true
            }
            Log.d("HintFilmIzle", "KINESCOPE_HTML_MANIFEST_NOT_FOUND")
        }.onFailure {
            Log.e("HintFilmIzle", "KINESCOPE_HTML_PROBE_FAILED", it)
        }

'''
if "KINESCOPE_HTML_MANIFEST_NOT_FOUND" in s:
    print("HintFilmIzle native probe patch skipped: already present")
    raise SystemExit(0)
if marker is None:
    raise SystemExit("signed response marker not found")
s = s.replace(marker, insert + marker, 1)
path.write_text(s, encoding="utf-8")
