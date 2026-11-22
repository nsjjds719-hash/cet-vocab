"""Extract the digit-only numeral fonts used by the home-page figure styles.

The subsets (U+002F-0039: "/" and 0-9) were produced from Google Fonts during the
2026-11-21 redesign and embedded in a preview page. This script decodes them, writes
WOFF2 files for shared/ui and TTF files for Android, and checks the glyph coverage.

    python scripts/extract_numerals.py .claude/previews/hero-fonts.html

Requires: pip install fonttools brotli
"""

import argparse
import base64
import io
import re
from pathlib import Path

from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "shared" / "ui" / "fonts"
FAMILIES = {
    "Bodoni Moda": "bodoni",
    "Cormorant Garamond": "cormorant",
    "Abril Fatface": "abril",
    "Cinzel Decorative": "cinzel",
    "Italiana": "italiana",
    "Fraunces": "fraunces",
}
NEEDED = set(range(0x2F, 0x3A))


def rename(font, family):
    """A subset is a Modified Version under the OFL, so it must not carry the
    Reserved Font Name (Cinzel, Italiana). Give every subset our own family name."""
    table = font["name"]
    style = table.getDebugName(2) or "Regular"
    postscript = (family + "-" + style).replace(" ", "")
    for record in list(table.names):
        if record.nameID in (16, 17, 21, 22):
            table.removeNames(nameID=record.nameID)
    for record in table.names:
        value = {1: family, 3: postscript, 4: family + " " + style, 6: postscript}.get(record.nameID)
        if value is not None:
            record.string = value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("preview", type=Path)
    page = parser.parse_args().preview.read_text(encoding="utf-8")
    OUT.mkdir(parents=True, exist_ok=True)
    found = {}
    for block in re.findall(r"@font-face\s*\{(.*?)\}", page, re.S):
        family = re.search(r"font-family:\s*'([^']+)'", block).group(1)
        if family not in FAMILIES:
            continue
        data = base64.b64decode(re.search(r"base64,([A-Za-z0-9+/=]+)", block).group(1))
        font = TTFont(io.BytesIO(data))
        missing = NEEDED - set(font.getBestCmap())
        if missing:
            raise SystemExit(f"{family} lacks {sorted(hex(c) for c in missing)}")
        name = FAMILIES[family]
        rename(font, "Cijian Numeral " + name.capitalize())
        font.save(OUT / f"{name}.woff2")
        font.flavor = None
        font.save(OUT / f"{name}.ttf")
        found[family] = name
    if set(found) != set(FAMILIES):
        raise SystemExit(f"missing families: {sorted(set(FAMILIES) - set(found))}")
    for family, name in found.items():
        print(f"{family} -> fonts/{name}.woff2, fonts/{name}.ttf")


if __name__ == "__main__":
    main()
