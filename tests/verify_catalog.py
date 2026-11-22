"""Verify outline coverage, preserved identities, and identical packaged data."""

import hashlib
import json
import sys
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from prepare_catalog import CHINESE, POS, comparable, key, outline_groups, read_json


def verify_book(level, book, rows):
    expected_groups = [group for group in outline_groups(rows)
                       if level == "cet6" or group["base"]]
    assert len(expected_groups) == len(book["outline"])
    entries = book["entries"]
    core = book["core"]
    core_keys = set(core)
    assert len(core) == len(core_keys)
    for representative, group in zip(book["outline"], expected_groups):
        assert representative in core_keys
        entry = entries[representative]
        answers = {comparable(form) for form in [entry[0], *(entry[3] if len(entry) > 3 else [])]}
        assert answers.intersection(comparable(form) for form in group["forms"])
    original_file = ROOT / "data" / "wordbooks" / f"{level.upper()}_2.jsonl"
    with original_file.open(encoding="utf-8-sig") as source:
        original = [key(json.loads(line)["headWord"]) for line in source if line.strip()]
    assert core[:len(original)] == original
    expected_ranking = read_json(ROOT / "data" / "study" / f"frequency-{level}.json")
    assert book["ranking"] == [key(word) for word, _ in expected_ranking]
    for entry in (entries[word] for word in core[len(original):]):
        assert any(CHINESE.search(sense) and POS.match(sense) for sense in entry[1]), entry
    print(f"{level}: {len(core)} study keys, {len(expected_groups)} verified outline groups, "
          f"{len(original)} original keys preserved, 1500 ranked words unchanged")


def verify_package(path, member, expected, javascript_prefix=None):
    with ZipFile(path) as archive:
        assert archive.testzip() is None, path
        actual = archive.read(member).strip()
        if javascript_prefix:
            assert actual.startswith(javascript_prefix) and actual.endswith(b";")
            actual = actual[len(javascript_prefix):-1]
        assert actual == expected, path
    print(f"{path.name}: identical {member.rsplit('/', 1)[-1]}")


if __name__ == "__main__":
    data = (ROOT / "data" / "study" / "catalog.json").read_bytes().strip()
    bundle = json.loads(data)
    source = read_json(ROOT / "data" / "wordbooks" / "cet-outline-2016.json")
    for level, book in bundle["books"].items():
        verify_book(level, book, source["rows"])
    if len(sys.argv) > 1:
        version = sys.argv[1]
        files = ROOT / "build"
        android = files / f"cijian-{version}.apk"
        desktop = files / f"Cijian-Windows-x64-{version}.zip"
        windows_assets = f"cijian-windows-x64-{version}/web/"
        js_catalog = b"window.CijianVocabulary = "
        verify_package(android, "assets/catalog.json", data)
        verify_package(desktop, windows_assets + "vocabulary.js", data, js_catalog)
        dictionary = (ROOT / "data" / "study" / "dictionary.json").read_bytes().strip()
        js_dictionary = b"window.CijianDictionaryData = "
        verify_package(android, "assets/dictionary.json", dictionary)
        verify_package(desktop, windows_assets + "dictionary-data.js", dictionary, js_dictionary)
    print("SHA-256:", hashlib.sha256(data).hexdigest())
