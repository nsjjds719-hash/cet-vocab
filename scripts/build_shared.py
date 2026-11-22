"""Assemble the offline study interface and vocabulary for both new apps."""

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
    encoded = (DATA / "study" / "catalog.json").read_bytes().rstrip()
    (output / "vocabulary.js").write_bytes(b"window.CijianVocabulary = " + encoded + b";\n")
    dictionary = (DATA / "study" / "dictionary.json").read_bytes().rstrip()
    (output / "dictionary-data.js").write_bytes(
        b"window.CijianDictionaryData = " + dictionary + b";\n")
    shutil.copy2(DATA / "study" / "ECDICT-LICENSE.txt", output / "ECDICT-LICENSE.txt")


if __name__ == "__main__":
    main()
