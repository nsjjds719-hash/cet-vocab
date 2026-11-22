"""Extract ranked headwords and the ECDICT entries needed by the Android app."""

import argparse
import csv
import json
import re
import unicodedata
from html.parser import HTMLParser
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RANK_LIMIT = 1500
VARIATION_TYPES = frozenset("pdi3rts")
EXAM_DEFINITIONS = {
    "anumeric": "adj. 缺少表示确切数目的词语的（如某种语言）",
    "crowdfunding": "n. 众筹；向许多人募集小额资金",
    "cyberloafing": "n. 工作时间用网络处理私事或娱乐",
    "ebike": "n. 电动自行车",
    "gamification": "n. 游戏化；在非游戏活动中引入游戏机制",
}


def key(word):
    normalized = unicodedata.normalize("NFKC", word).lower()
    return " ".join(normalized.translate(str.maketrans("’‘‐‑", "''--")).split())


class Rankings(HTMLParser):
    def __init__(self):
        super().__init__()
        self.rows = []
        self.pending = None
        self.parts = []

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "a" and "freqrow__link" in attributes.get("class", "").split():
            label = attributes["aria-label"]
            rank = re.search(r"第\s*(\d+)\s*名", label)
            frequency = re.search(r"真题句频\s*([\d,]+)\s*句", label)
            if not rank or not frequency:
                raise ValueError(f"Missing rank or frequency: {label}")
            self.pending = int(rank[1]), int(frequency[1].replace(",", ""))
            self.parts = []

    def handle_data(self, data):
        if self.pending is not None:
            self.parts.append(data)

    def handle_endtag(self, tag):
        if tag == "a" and self.pending is not None:
            self.rows.append((*self.pending, "".join(self.parts).strip()))
            self.pending = None


def ranked_words(path):
    parser = Rankings()
    parser.feed(path.read_text(encoding="utf-8"))
    rows = parser.rows
    if len(rows) < RANK_LIMIT or any(rank != i for i, (rank, _, _) in enumerate(rows, 1)):
        raise ValueError(f"Rank table is incomplete or unordered: {path}")
    if any(left[1] < right[1] for left, right in zip(rows, rows[1:])):
        raise ValueError(f"Frequency increased within the ranking: {path}")
    seen = set()
    ranked = []
    for _, frequency, word in rows:
        normalized = key(word)
        if normalized and normalized not in seen:
            ranked.append([word, frequency])
            seen.add(normalized)
        if len(ranked) == RANK_LIMIT:
            return ranked
    raise ValueError(f"Fewer than {RANK_LIMIT} distinct words in {path}")


def wordbook(name):
    path = ROOT / "data" / "wordbooks" / f"{name}.jsonl"
    with path.open(encoding="utf-8-sig") as source:
        return {key(json.loads(line)["headWord"]) for line in source if line.strip()}


def variations(value):
    result = {}
    for item in value.split("/"):
        label, separator, form = item.partition(":")
        if separator and label in VARIATION_TYPES and form.strip():
            result[label] = ", ".join(part.strip() for part in form.split(",") if part.strip())
    return result


def supplementary_entries(path, needed, definitions):
    results = {}
    with path.open(encoding="utf-8-sig", newline="") as source:
        for entry in csv.DictReader(source):
            word = key(entry["word"])
            if word not in needed:
                continue
            forms = variations(entry["exchange"])
            meanings = entry["translation"].replace("\\n", "\n").strip().splitlines()
            translation = "\n".join(line.strip() for line in meanings[:3] if line.strip())
            result = {}
            if forms:
                result["forms"] = forms
            if word in definitions and translation:
                result["definition"] = translation
            if result:
                results[word] = result
    for word in definitions:
        if word in EXAM_DEFINITIONS:
            results.setdefault(word, {}).setdefault("definition", EXAM_DEFINITIONS[word])
    return dict(sorted(results.items()))


def save(path, records):
    path.write_text(json.dumps(records, ensure_ascii=False, separators=(",", ":")) + "\n",
                    encoding="utf-8")


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--cet4-ranking", type=Path, required=True)
    cli.add_argument("--cet6-ranking", type=Path, required=True)
    cli.add_argument("--dictionary", type=Path, required=True)
    inputs = cli.parse_args()

    cet4 = ranked_words(inputs.cet4_ranking)
    cet6 = ranked_words(inputs.cet6_ranking)
    book4, book6 = wordbook("CET4_2"), wordbook("CET6_2")
    exam4, exam6 = {key(w) for w, _ in cet4}, {key(w) for w, _ in cet6}
    definitions = (exam4 - book4) | (exam6 - book6)
    additions = supplementary_entries(inputs.dictionary, book4 | book6 | exam4 | exam6,
                                       definitions)
    target = ROOT / "data" / "study"
    target.mkdir(parents=True, exist_ok=True)
    save(target / "frequency-cet4.json", cet4)
    save(target / "frequency-cet6.json", cet6)
    save(target / "supplement.json", additions)
    print(f"CET4: {len(cet4)} ranked words; CET6: {len(cet6)} ranked words")
    print(f"Inflections: {sum('forms' in entry for entry in additions.values())}; "
          f"exam-only words without a supplemental definition: "
          f"{sum('definition' not in additions.get(word, {}) for word in definitions)}")


if __name__ == "__main__":
    main()
