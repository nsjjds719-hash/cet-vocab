"""Combine the existing books, checked CET outline and supplemental definitions."""

import argparse
import csv
import json
import re
import unicodedata
from pathlib import Path

from prepare_data import key, variations

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
ORIGINAL_SIZE = {"cet4": 3739, "cet6": 2078}
POS = re.compile(r"^(n|v|vt|vi|adj|a|ad|adv|art|aux|prep|pron|conj|int|interj|num|det|abbr|pl|past)\b")
CHINESE = re.compile(r"[\u3400-\u9fff]")
DEFINITION_FIXES = {
    "according to": ["prep. 根据；按照；据……所说"],
    "air-conditioning": ["n. 空气调节；空调系统"],
    "am": ["v. be 的第一人称单数现在式"],
    "app": ["n. 应用程序"],
    "cell-phone": ["n. 手机；移动电话"],
    "cyberspace": ["n. 网络空间"],
    "deregulate": ["vt. 解除或放宽对某行业的管制"],
    "download": ["v. 下载", "n. 下载；下载的文件"],
    "internet": ["n. 互联网；因特网"],
    "laptop": ["n. 笔记本电脑"],
    "o'clock": ["adv. ……点钟（用于钟点后）"],
    "online": ["adj. 在线的；联网的", "adv. 在线"],
    "ought to": ["aux. 应该；应当"],
    "upload": ["v. 上传", "n. 上传；上传的文件"],
}


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def comparable(value):
    return re.sub(r"[^a-z0-9]", "", unicodedata.normalize("NFKD", key(value)))


def outline_groups(rows):
    groups = []
    lookup = {}
    for _, starred, _, forms in rows:
        links = sorted({lookup[comparable(form)] for form in forms if comparable(form) in lookup})
        if links:
            index = links[0]
            group = groups[index]
            for duplicate in links[1:]:
                older = groups[duplicate]
                group["forms"].extend(older["forms"])
                group["base"] |= older["base"]
                group["star"] |= older["star"]
                groups[duplicate] = None
        else:
            index = len(groups)
            group = {"forms": [], "base": False, "star": False}
            groups.append(group)
        group["forms"] = list(dict.fromkeys((*group["forms"], *forms)))
        group["base"] |= not starred
        group["star"] |= starred
        for form in group["forms"]:
            lookup[comparable(form)] = index
    return [group for group in groups if group is not None]


def original(level):
    path = DATA / "wordbooks" / f"{level.upper()}_2.jsonl"
    entries = {}
    with path.open(encoding="utf-8-sig") as source:
        for line in source:
            if not line.strip():
                continue
            row = json.loads(line)
            word = row["headWord"].strip()
            details = row["content"]["word"]["content"]
            senses = []
            for item in details["trans"]:
                meaning = item.get("tranCn", "").strip()
                if meaning:
                    senses.append("  ".join(part for part in (item.get("pos", "").strip(), meaning)
                                            if part))
            relatives = []
            for group in (details.get("relWord") or {}).get("rels") or []:
                for member in group.get("words", []):
                    form = member["hwd"].strip()
                    if form and key(form) != key(word):
                        relatives.append("  ".join(part for part in
                            (form, group.get("pos", ""), member.get("tran", "").strip()) if part))
            normalized = key(word)
            if normalized in entries or not senses:
                raise ValueError(f"Invalid entry in {path.name}: {word}")
            entries[normalized] = [word, list(dict.fromkeys(senses)), list(dict.fromkeys(relatives))]
    if len(entries) != ORIGINAL_SIZE[level]:
        raise ValueError(f"Incomplete original book: {path.name}")
    return entries


def load_dictionary(path, needed):
    exact = {}
    approximations = {}
    with path.open(encoding="utf-8-sig", newline="") as source:
        for row in csv.DictReader(source):
            normalized = key(row["word"])
            soft = comparable(normalized)
            if soft not in needed or not normalized:
                continue
            senses = [line.strip() for line in row["translation"].replace("\\n", "\n").splitlines()
                      if CHINESE.search(line)]
            parts = [item.split(":", 1)[0] for item in row["pos"].split("/") if item]
            if senses and not any(POS.match(sense) for sense in senses) and parts:
                senses[0] = f"{parts[0]}. {senses[0]}"
            exact[normalized] = (senses, variations(row["exchange"]))
            approximations.setdefault(soft, []).append(normalized)
    return exact, approximations


def dictionary_term(forms, dictionary, approx):
    for form in forms:
        candidate = key(form)
        if candidate in dictionary and dictionary[candidate][0]:
            return dictionary[candidate]
    for form in forms:
        for candidate in approx.get(comparable(form), []):
            if dictionary[candidate][0]:
                return dictionary[candidate]
    return [], {}


def base_match(forms, entries, index):
    for form in forms:
        normalized = key(form)
        if normalized in entries:
            return normalized
    for form in forms:
        matches = index.get(comparable(form), [])
        if len(matches) == 1:
            return matches[0]
    return None


def compatibility_map(entries):
    index = {}
    for word in entries:
        index.setdefault(comparable(word), []).append(word)
    return index


def make_book(level, groups, originals, supplement, dictionary, approx):
    baseline = originals[level]
    alternate = originals["cet4" if level == "cet6" else "cet6"]
    entries = {word: entry[:] for word, entry in baseline.items()}
    baseline_index = compatibility_map(baseline)
    other_index = compatibility_map(alternate)
    ranking = []
    last_frequency = float("inf")
    for word, frequency in read_json(DATA / "study" / f"frequency-{level}.json"):
        if frequency > last_frequency:
            raise ValueError(f"Invalid frequency order: {level}")
        last_frequency = frequency
        candidate = key(word)
        ranking.append(candidate)
        if candidate not in entries:
            definition = supplement.get(candidate, {}).get("definition")
            if not definition:
                raise ValueError(f"Missing definition for ranked word {word}")
            entries[candidate] = [word, definition.splitlines(), []]
    if len(ranking) != 1500 or len(set(ranking)) != 1500:
        raise ValueError(f"Invalid ranking: {level}")
    entry_index = compatibility_map(entries)
    core = list(baseline)
    core_keys = set(core)
    outline_keys = []
    forms_by_word = {}
    for group in groups:
        if level == "cet4" and not group["base"]:
            continue
        forms = group["forms"]
        primary = base_match(forms, baseline, baseline_index) or base_match(forms, entries, entry_index)
        source_key = base_match(forms, alternate, other_index)
        source_word = alternate[source_key] if source_key else None
        translation, inflections = dictionary_term(forms, dictionary, approx)
        if not primary:
            spelling = source_word[0] if source_word else forms[0]
            primary = key(spelling)
            meanings = source_word[1] if source_word else translation[:6]
            if not meanings or not any(CHINESE.search(meaning) for meaning in meanings):
                raise ValueError(f"No Chinese definition: {level} {forms}")
            entries[primary] = [spelling, meanings, source_word[2] if source_word else []]
            entry_index.setdefault(comparable(primary), []).append(primary)
        if primary not in core_keys:
            spelling, meanings, roots = entries[primary][:3]
            meanings = DEFINITION_FIXES.get(primary, meanings)
            if not any(POS.match(meaning) for meaning in meanings) or not any(
                    CHINESE.search(meaning) for meaning in meanings):
                raise ValueError(f"No usable Chinese definition with a part of speech: {level} {spelling}")
            entries[primary] = [spelling, meanings, roots]
            core.append(primary)
            core_keys.add(primary)
        outline_keys.append(primary)
        if inflections and primary not in forms_by_word:
            forms_by_word[primary] = inflections
        answers = [*forms, *(unaccented(form) for form in forms)]
        aliases = list(dict.fromkeys(form for form in answers if key(form) != primary))
        if aliases:
            word = entries[primary]
            if len(word) == 3:
                entries[primary] = [*word, aliases]
            else:
                entries[primary] = [*word[:3], list(dict.fromkeys([*word[3], *aliases]))]
    # Preserve aliases on existing duplicate spellings too, without changing their keys.
    for group in groups:
        variants = group["forms"]
        for variant in variants:
            candidate = key(variant)
            if candidate not in entries or len(entries[candidate]) != 3:
                continue
            aliases = [alias for alias in variants if key(alias) != candidate]
            if aliases:
                entries[candidate] = [*entries[candidate], aliases]
    if len(set(outline_keys)) != len(outline_keys):
        raise ValueError(f"Distinct outline groups collapsed in {level}")
    return {"core": core, "entries": entries, "ranking": ranking, "outline": outline_keys}, forms_by_word


def unaccented(form):
    return "".join(char for char in unicodedata.normalize("NFKD", form)
                   if not unicodedata.combining(char))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dictionary", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=DATA / "study" / "catalog.json")
    args = parser.parse_args()
    outline = read_json(DATA / "wordbooks" / "cet-outline-2016.json")
    rows = outline["rows"]
    groups = outline_groups(rows)
    supplement = read_json(DATA / "study" / "supplement.json")
    originals = {level: original(level) for level in ORIGINAL_SIZE}
    needed = {comparable(form) for group in groups for form in group["forms"]}
    dictionary, approx = load_dictionary(args.dictionary, needed)
    all_forms = {word: entry["forms"] for word, entry in supplement.items() if "forms" in entry}
    books = {}
    for level, original_count in ORIGINAL_SIZE.items():
        book, forms = make_book(level, groups, originals, supplement, dictionary, approx)
        books[level] = book
        for word, inflections in forms.items():
            all_forms.setdefault(word, inflections)
        print(f"{level}: {len(book['core'])} study entries, "
              f"{len(book['core']) - original_count} added, {len(book['outline'])} outline groups")
    metadata = {
        "source": outline["source"],
        "sha256": outline["sha256"],
        "printedTotals": outline["printed_totals"],
        "rows": len(rows),
        "starredRows": sum(row[1] for row in rows),
        "headwordGroups": len(groups),
    }
    bundle = {"outline": metadata, "books": books, "forms": all_forms}
    args.output.write_text(json.dumps(bundle, ensure_ascii=True, separators=(",", ":")) + "\n",
                           encoding="utf-8")


if __name__ == "__main__":
    main()
