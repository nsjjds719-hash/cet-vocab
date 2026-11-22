"""Assemble the offline study interface and vocabulary for the Windows app."""

import argparse
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=Path)
    output = parser.parse_args().out
    output.mkdir(parents=True, exist_ok=True)
    for asset in (ROOT / "shared" / "ui").iterdir():
        if asset.is_file():
            shutil.copy2(asset, output / asset.name)
    # 可选数字字体：只带网页用的 woff2 和许可证，ttf 只给 Android。
    fonts = output / "fonts"
    fonts.mkdir(exist_ok=True)
    for asset in (ROOT / "shared" / "ui" / "fonts").iterdir():
        if asset.suffix in (".woff2", ".txt"):
            shutil.copy2(asset, fonts / asset.name)
    encoded = (DATA / "study" / "catalog.json").read_bytes().rstrip()
    (output / "vocabulary.js").write_bytes(b"window.CijianVocabulary = " + encoded + b";\n")
    dictionary = (DATA / "study" / "dictionary.json").read_bytes().rstrip()
    (output / "dictionary-data.js").write_bytes(
        b"window.CijianDictionaryData = " + dictionary + b";\n")
    shutil.copy2(DATA / "study" / "ECDICT-LICENSE.txt", output / "ECDICT-LICENSE.txt")


if __name__ == "__main__":
    main()
