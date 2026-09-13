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
        // directly, then inspect the minified fetchPlaylist implementation.
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

                    // The player is heavily minified/obfuscated. Extract the exact
                    // fetchPlaylist body and its string literals in manageable chunks.
                    val fetchStart = Regex("function\\s+fetchPlaylist\\s*\\(").find(js)?.range?.first
                    if (fetchStart != null) {
                        val tail = js.substring(fetchStart)
                        val bodyEnd = Regex("function\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\(").find(tail, 1)?.range?.first
                        val fetchSource = tail.substring(0, (bodyEnd ?: minOf(tail.length, 30000)).coerceAtMost(30000))
                        Log.d("HintFilmIzle", "KINESCOPE_FETCHPLAYLIST_LEN=${fetchSource.length}")
                        fetchSource.chunked(1400).take(24).forEachIndexed { index, chunk ->
                            Log.d("HintFilmIzle", "KINESCOPE_FETCHPLAYLIST[$index]=$chunk")
                        }
                        Regex("['\"]([^'\"]{1,300})['\"]")
                            .findAll(fetchSource)
                            .map { it.groupValues[1] }
                            .filter {
                                it.contains("api", true) || it.contains("playlist", true) ||
                                it.contains("m3u8", true) || it.contains("media", true) ||
                                it.contains("embed", true) || it.contains("stream", true) ||
                                it.contains("http", true) || it.contains("domain", true) ||
                                it.contains("iframe", true) || it.contains("sig", true) ||
                                it.contains("nonce", true) || it.contains("meta", true)
                            }
                            .distinct()
                            .take(200)
                            .forEach { Log.d("HintFilmIzle", "KINESCOPE_FETCH_STRING=$it") }
                    } else {
                        Log.d("HintFilmIzle", "KINESCOPE_FETCHPLAYLIST_NOT_FOUND")
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
