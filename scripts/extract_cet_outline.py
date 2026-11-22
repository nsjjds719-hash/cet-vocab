"""Extract the headword column and ★ markers from the verified CET outline PDF."""

import argparse
import hashlib
import json
import re
import subprocess
import tempfile
import unicodedata
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

SOURCE = "https://www.neea.edu.cn/res/Home/1704/55b02330ac17274664f06d9d3db8249d.pdf"
SHA256 = "9166d3c03b7bc43abd9d9df91bd2ef8085b4419286f1e5ca100dea68f3cfd1f1"
FIRST_PAGE = 21
LAST_PAGE = 149
ROW_ORIGIN = 72.940256
ROW_SPACING = 14.167
SOURCE_FIXES = {
    "accordingto": ("according to",),
    "babyboom": ("baby boom",),
    "oughtto": ("ought to",),
    "coup(d'état)": ("coup", "coup d'état"),
    "celsius/-cius": ("Celsius",),
    "esthetic/aesthetice": ("esthetic", "aesthetic"),
    "sober/-re": ("sober",),
}


def expand(word):
    if word.lower() in SOURCE_FIXES:
        return list(SOURCE_FIXES[word.lower()])
    parts = word.split("/")
    first = re.sub(r"\d+$", "", parts[0])
    result = []
    for part in parts:
        part = re.sub(r"\d+$", "", part)
        if part.startswith("-"):
            tail = part[1:]
            part = first[:-len(tail)] + tail
        optional = re.fullmatch(r"([^()]*)\(([^()]+)\)([^()]*)", part)
        if optional:
            start, middle, end = optional.groups()
            result.extend((start + end, start + middle + end))
        else:
            result.append(part)
    return list(dict.fromkeys(result))


def head_column(cells):
    parts = []
    end = None
    for x, right, word in sorted(cells):
        if end is None and x >= 90:
            break
        if end is not None and x > end + 7:
            break
        parts.append(word)
        end = right
    return "".join(parts)


def extract(bounds):
    rows = []
    pages = ET.parse(bounds).findall(".//{*}page")
    for number, page in enumerate(pages, FIRST_PAGE):
        lines = defaultdict(list)
        for word in page.findall(".//{*}word"):
            y = float(word.get("yMin"))
            line = round((y - ROW_ORIGIN) / ROW_SPACING)
            if 0 <= line < 42 and abs(y - (ROW_ORIGIN + line * ROW_SPACING)) < 2.8:
                lines[line].append((float(word.get("xMin")), float(word.get("xMax")), word.text))
        for line, cells in sorted(lines.items()):
            raw = head_column(cells)
            if not raw:
                continue
            starred = "★" in raw
            # In this embedded font, U+FF27 renders as the dash seen in the printed wordlist.
            word = unicodedata.normalize("NFKC", raw.replace("★", "").replace("\uff27", "-")
                                        .replace("\U001001b3", "'")).strip()
            if word == "5418" and number == LAST_PAGE:
                continue
            if not re.fullmatch(r"[A-Za-z][a-zA-Z0-9éàèêëçöï'()\-/\.]*", word):
                raise ValueError(f"Unexpected headword at PDF page {number}, row {line + 1}: {raw!r}")
            forms = expand(word)
            rows.append([number, starred, word, forms])
    if len(rows) != 5377 or sum(row[1] for row in rows) != 1263:
        raise ValueError("Unexpected row or star count from the outline")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pdf", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--pdftotext", default="pdftotext")
    args = parser.parse_args()
    if hashlib.sha256(args.pdf.read_bytes()).hexdigest() != SHA256:
        raise ValueError("This extractor expects the checked 2016 edition of the official PDF")
    with tempfile.TemporaryDirectory(prefix="cijian-outline-") as temporary:
        bounds = Path(temporary) / "bounds.html"
        subprocess.run([args.pdftotext, "-enc", "UTF-8", "-bbox", "-f", str(FIRST_PAGE),
                        "-l", str(LAST_PAGE), str(args.pdf), str(bounds)], check=True)
        rows = extract(bounds)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as sink:
        sink.write('{\n  "source": ' + json.dumps(SOURCE) + ',\n  "sha256": ' + json.dumps(SHA256)
                   + ',\n  "printed_totals": [5418,2551],\n  "rows": [\n')
        for index, row in enumerate(rows):
            sink.write("    " + json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            sink.write(",\n" if index + 1 < len(rows) else "\n")
        sink.write("  ]\n}\n")
    print(f"Extracted {len(rows)} rows and {sum(row[1] for row in rows)} stars")


if __name__ == "__main__":
    main()
