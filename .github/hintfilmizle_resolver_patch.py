from pathlib import Path
import re

PATH = Path('HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt')
text = PATH.read_text(encoding='utf-8')

# The shared CloudStream WebViewResolver lives in an external dependency, so
# hintfilmizle_fix.py cannot safely patch its WebViewClient source in this repo.
# Force the generated Kinescope code to use our plugin-owned resolver instead.
updated, count = re.subn(
    r'(?<![A-Za-z0-9_.])WebViewResolver\(',
    'com.nikyokki.WebViewResolver(',
    text,
)

if count == 0:
    raise SystemExit('HintFilmIzle WebViewResolver constructor was not found')

PATH.write_text(updated, encoding='utf-8')
print(f'HintFilmIzle: switched {count} WebViewResolver constructor call(s) to plugin-owned shouldInterceptRequest resolver')
