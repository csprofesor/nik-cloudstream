#!/usr/bin/env python3
from pathlib import Path

p = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
s = p.read_text()

needle = 'val html = try {'
if needle not in s:
    raise SystemExit("HTML probe anchor not found")

old = '''val html = try {
            app.get(target, referer = parent, headers = headers() + mapOf(
                "Referer" to parent,
                "Origin" to "https://$parentDomain"
            )).text
        } catch (e: Exception) {
            logError("KINESCOPE_HTML_PROBE_FAILED=${e.javaClass.simpleName}:${e.message}")
            ""
        }
'''
if old not in s:
    raise SystemExit("Expected HTML probe block not found")

new = '''val html = try {
            app.get(target, referer = parent, headers = headers() + mapOf(
                "Referer" to parent,
                "Origin" to "https://$parentDomain"
            )).text
        } catch (e: Exception) {
            logError("KINESCOPE_HTML_PROBE_FAILED=${e.javaClass.simpleName}:${e.message}")
            ""
        }

        // The embed document itself contains the player script URLs. Fetch those natively
        // and inspect the JavaScript for the actual media/API route instead of launching WebView.
        val scriptUrls = Regex("<script[^>]+src=[\\\"']([^\\\"']+)[\\\"']", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { m -> try { fix(target, m.groupValues[1]) } catch (_: Exception) { null } }
            .filter { it.contains("kinescope", true) || it.contains("playerjs", true) || it.contains("embed.js", true) }
            .distinct()
            .toList()

        logError("KINESCOPE_SCRIPT_COUNT=${scriptUrls.size}")
        for (scriptUrl in scriptUrls.take(6)) {
            try {
                val js = app.get(scriptUrl, referer = target, headers = headers() + mapOf(
                    "Referer" to target,
                    "Origin" to "https://$host"
                )).text
                logError("KINESCOPE_SCRIPT_URL=$scriptUrl")
                logError("KINESCOPE_SCRIPT_LEN=${js.length}")

                val apiHits = Regex("https?://[^\\\\\"'\\\\\\s]+|/api/v1/[A-Za-z0-9_./?=&${'$'}:{}-]+|[A-Za-z0-9_./-]+\\.m3u8(?:\\\\?[^\\\\\"'\\\\\\s]+)?", RegexOption.IGNORE_CASE)
                    .findAll(js)
                    .map { it.value }
                    .filter { it.contains("api", true) || it.contains("m3u8", true) || it.contains("playlist", true) || it.contains("media", true) || it.contains("stream", true) }
                    .distinct()
                    .take(80)
                    .toList()
                for (hit in apiHits) logError("KINESCOPE_SCRIPT_HIT=$hit")
            } catch (e: Exception) {
                logError("KINESCOPE_SCRIPT_FAILED=${e.javaClass.simpleName}:${e.message}")
            }
        }
'''
s = s.replace(old, new, 1)
p.write_text(s)
