#!/usr/bin/env python3
from pathlib import Path

p = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = p.read_text(encoding="utf-8-sig")

if "KINESCOPE_SCRIPT_COUNT" in s:
    raise SystemExit("Player script probe already present")

needle = '''        val response = runCatching {'''
if needle not in s:
    raise SystemExit("signed response anchor not found")

insert = r'''        // Fetch the player JavaScript directly from the CDN. The embed HTML is only a
        // shell; playerjs.js/embed.js construct the signed media request dynamically.
        runCatching {
            val scriptUrls = Regex("<script[^>]+src=[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
                .findAll(html)
                .mapNotNull { m -> runCatching { fix(target, m.groupValues[1]) }.getOrNull() }
                .filter { it.contains("kinescope", true) || it.contains("playerjs", true) || it.contains("embed.js", true) }
                .distinct()
                .toList()

            Log.d("HintFilmIzle", "KINESCOPE_SCRIPT_COUNT=${scriptUrls.size}")
            for (scriptUrl in scriptUrls.take(8)) {
                runCatching {
                    val jsResponse = app.get(scriptUrl, referer = target, headers = headers() + mapOf(
                        "Referer" to target,
                        "Origin" to targetOrigin
                    ))
                    val js = jsResponse.text
                    Log.d("HintFilmIzle", "KINESCOPE_SCRIPT_URL=$scriptUrl")
                    Log.d("HintFilmIzle", "KINESCOPE_SCRIPT_LEN=${js.length}")

                    // Log useful endpoint fragments without dumping the whole minified file.
                    val hits = Regex("https?://[^\\\"'<>\\s]+|/api/v1/[A-Za-z0-9_./?=&${'$'}:{}-]+|[A-Za-z0-9_./-]+\\.m3u8(?:\\?[^\\\"'<>\\s]+)?", RegexOption.IGNORE_CASE)
                        .findAll(js)
                        .map { it.value }
                        .filter {
                            it.contains("api", true) || it.contains("m3u8", true) ||
                            it.contains("playlist", true) || it.contains("media", true) ||
                            it.contains("stream", true) || it.contains("embed", true)
                        }
                        .distinct()
                        .take(120)
                        .toList()
                    hits.forEach { Log.d("HintFilmIzle", "KINESCOPE_SCRIPT_HIT=$it") }
                }.onFailure {
                    Log.e("HintFilmIzle", "KINESCOPE_SCRIPT_FAILED", it)
                }
            }
        }.onFailure {
            Log.e("HintFilmIzle", "KINESCOPE_SCRIPT_PROBE_FAILED", it)
        }

'''
s = s.replace(needle, insert + needle, 1)
p.write_text(s, encoding="utf-8")
