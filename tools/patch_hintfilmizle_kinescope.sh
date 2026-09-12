#!/usr/bin/env python3
from pathlib import Path

path = Path("HintFilmIzle/src/main/kotlin/com/nikyokki/HintFilmIzlePlugin.kt")
text = path.read_text(encoding="utf-8")

required = (
    "kinescopeApiRegex",
    "decodeKinescopeManifestResponse",
    "KINESCOPE_API_MANIFEST=",
)

if all(marker in text for marker in required):
    print("HintFilmIzle Kinescope API patch already present")
else:
    raise SystemExit(
        "HintFilmIzle Kinescope API patch markers not found in HintFilmIzlePlugin.kt"
    )
