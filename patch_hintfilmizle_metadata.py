from pathlib import Path
import re

path = Path('HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt')
s = path.read_text(encoding='utf-8')

pattern = re.compile(
    r'''    private fun findNumber\(text: String, vararg labels: String\): String\? =
        Regex\(".*?"\s*, RegexOption\.IGNORE_CASE\)\s*\.find\(text\)\?\.groupValues\?\.getOrNull\(1\)
''', re.S)
replacement = '''    private fun findNumber(text: String, vararg labels: String): String? =
        Regex("(?:${labels.joinToString(\"|\") { Regex.escape(it) }})(?:\\\\s+[A-Za-zÇĞİÖŞÜçğıöşü]+){0,3}\\\\s*[:\\\\-]?\\\\s*([0-9]+(?:[.,][0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)

    private fun sectionText(body: String, start: String, end: String): String? =
        Regex("${Regex.escape(start)}\\\\s+(.*?)\\\\s+${Regex.escape(end)}", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun extractGenreTags(document: org.jsoup.nodes.Document, body: String): List<String> {
        val domTags = document.select(".genres a, .genre a, .genreList a, .categories a, .post-categories a, a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length < 80 && it.contains("Film", true) }
            .distinct()
        if (domTags.isNotEmpty()) return domTags

        val match = Regex(
            "Türü\\\\s*:\\\\s*((?:[A-ZÇĞİÖŞÜ][^,]+?Filmleri(?:\\\\s*,\\\\s*)?)+)",
            RegexOption.IGNORE_CASE
        ).find(body) ?: return emptyList()

        return match.groupValues[1]
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractActors(document: org.jsoup.nodes.Document, body: String): List<Actor> {
        val direct = document.select(".actors a, .cast a, .oyuncular a, .cast-list a, .actor-list a")
            .mapNotNull { it.text().trim().takeIf(String::isNotBlank)?.let(::Actor) }
            .distinctBy { it.name }
        if (direct.isNotEmpty()) return direct

        val heading = document.getElementsContainingOwnText("ÖNE ÇIKAN OYUNCULAR").firstOrNull()
        val container = heading?.parents()?.plus(heading)?.firstOrNull { element ->
            val text = element.text()
            val links = element.select("a")
            text.contains("ÖNE ÇIKAN OYUNCULAR", true) &&
                text.contains("YÖNETMEN", true) &&
                links.size in 1..30
        }
        return container?.select("a")
            ?.mapNotNull { it.text().trim().takeIf(String::isNotBlank)?.let(::Actor) }
            ?.distinctBy { it.name }
            .orEmpty()
    }
'''
s, n = pattern.subn(replacement, s, count=1)
if n != 1:
    raise SystemExit('findNumber function not found')

meta_pattern = re.compile(
    r'''        val body = document\.text\(\).*?        val recommendations = extractResults\(document\)\n''',
    re.S,
)
new_meta = '''        val body = document.text().replace(Regex("\\\\s+"), " ").trim()

        val overview = sectionText(body, "GENEL BAKIŞ", "HATA BİLDİR")
        val description = firstText(
            document,
            ".description", ".film-description", ".movie-description", ".serieDescription",
            ".plot", ".summary", ".synopsis", ".film-summary", ".movie-summary", ".entry-content p", ".entry-content > p"
        ) ?: overview?.let {
            it.replaceFirst(Regex("^Türü\\\\s*:\\\\s*(?:[A-ZÇĞİÖŞÜ][^,]+?Filmleri(?:\\\\s*,\\\\s*)?)+\\\\s+", RegexOption.IGNORE_CASE), "")
                .substringBefore("Bu Film özeti")
                .trim()
                .takeIf { text -> text.isNotBlank() }
        }

        val year = Regex("\\\\b(19|20)\\\\d{2}\\\\b").find(body)?.value?.toIntOrNull()
        val rating = findNumber(body, "IMDb", "IMDB")
        val tags = extractGenreTags(document, body)
        val actors = extractActors(document, body)
        val recommendations = extractResults(document)
'''
s, n = meta_pattern.subn(new_meta, s, count=1)
if n != 1:
    raise SystemExit('metadata block not found')

path.write_text(s, encoding='utf-8')
