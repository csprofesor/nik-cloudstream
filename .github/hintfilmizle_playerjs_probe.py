#!/usr/bin/env python3
from pathlib import Path

p = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = p.read_text(encoding="utf-8-sig")

if "KINESCOPE_SCRIPT_COUNT" in s:
    raise SystemExit("Player script probe already present")

needle = '''        val response = runCatching {'''
if needle not in s:
    raise SystemExit("signed response anchor not found")

insert = r'''        // The embed document is only a shell. Fetch the known Kinescope player assets
        // directly, then also inspect any script URLs exposed by the shell.
        runCatching {
            val targetOrigin = "https://${URI(target).host}"
            val shell = app.get(target, referer = parent, headers = headers() + mapOf(
                "Referer" to parent,
                "Origin" to targetOrigin
            )).text

            val candidates = mutableListOf(
                "$targetOrigin/player/3/playerjs.js?v=22.2.4",
                "$targetOrigin/embed.js?v=1.5.42"
            )

            Regex("<script[^>]+src=[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
                .findAll(shell)
                .mapNotNull { m -> runCatching { fix(target, m.groupValues[1]) }.getOrNull() }
                .filter { it.contains("playerjs", true) || it.contains("embed.js", true) }
                .forEach { candidates += it }

            val scriptUrls = candidates.distinct()
            Log.d("HintFilmIzle", "KINESCOPE_SCRIPT_COUNT=${scriptUrls.size}")

            for (scriptUrl in scriptUrls.take(8)) {
                runCatching {
                    val jsResponse = app.get(scriptUrl, referer = target, headers = headers() + mapOf(
                        "Referer" to target,
                        "Origin" to targetOrigin,
                        "Accept" to "*/*"
                    ))
                    val js = jsResponse.text
                    Log.d("HintFilmIzle", "KINESCOPE_SCRIPT_URL=$scriptUrl")
                    Log.d("HintFilmIzle", "KINESCOPE_SCRIPT_CODE=${jsResponse.code}")
                    Log.d("HintFilmIzle", "KINESCOPE_SCRIPT_LEN=${js.length}")

                    val hits = Regex(
                        "https?://[^\\\"'<>\\s]+|/api/v1/[A-Za-z0-9_./?=&${'$'}:{}-]+|[A-Za-z0-9_./-]+\\.m3u8(?:\\?[^\\\"'<>\\s]+)?",
                        RegexOption.IGNORE_CASE
                    )
                        .findAll(js)
                        .map { it.value }
                        .filter {
                            it.contains("api", true) || it.contains("m3u8", true) ||
                            it.contains("playlist", true) || it.contains("media", true) ||
                            it.contains("stream", true) || it.contains("embed", true) ||
                            it.contains("manifest", true)
                        }
                        .distinct()
                        .take(200)
                        .toList()
                    hits.forEach { Log.d("HintFilmIzle", "KINESCOPE_SCRIPT_HIT=$it") }

                    // Also log the small windows around media-related tokens. This is
                    // useful when the current minified player constructs URLs indirectly.
                    val tokenRegex = Regex("(?i)(playlist|manifest|m3u8|media|stream|api/v1|master\\.m3u8)")
                    tokenRegex.findAll(js).take(80).forEach { match ->
                        val from = (match.range.first - 180).coerceAtLeast(0)
                        val to = (match.range.last + 280).coerceAtMost(js.length)
                        Log.d("HintFilmIzle", "KINESCOPE_SCRIPT_CONTEXT=${js.substring(from, to)}")
                    }
                }.onFailure {
                    Log.e("HintFilmIzle", "KINESCOPE_SCRIPT_FAILED=$scriptUrl", it)
                }
            }
        }.onFailure {
            Log.e("HintFilmIzle", "KINESCOPE_SCRIPT_PROBE_FAILED", it)
        }

'''
s = s.replace(needle, insert + needle, 1)
p.write_text(s, encoding="utf-8")
