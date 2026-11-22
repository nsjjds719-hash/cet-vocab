"""Build the offline word families from the catalog's stored relationships."""

import json
from pathlib import Path

from prepare_data import key

STUDY = Path(__file__).resolve().parents[1] / "data" / "study"
FORM_CODES = {"3", "p", "d", "i", "s", "r", "t"}


def build(catalog):
    terms = {}
    relatives = {}
    for book in catalog["books"].values():
        for spelling, senses, related, *alternate in book["entries"].values():
            word = key(spelling)
            model = terms.setdefault(word, [spelling, [], []])
            model[1].extend(sense for sense in senses if sense not in model[1])
            variants = alternate[0] if alternate else []
            model[2].extend(variant for variant in variants if variant not in model[2])
            relatives.setdefault(word, set()).update(related)

    original = set(terms)
    inflections = {}
    for base, changes in catalog["forms"].items():
        if base not in original:
            continue
        for code, spellings in changes.items():
            if code not in FORM_CODES:
                continue
            for spelling in filter(None, (part.strip() for part in spellings.split(","))):
                term = key(spelling)
                if term and term != base:
                    terms.setdefault(term, [spelling, [], []])
                    inflections.setdefault(term, set()).add((base, code))

    parent = {word: word for word in original}

    def root(word):
        while parent[word] != word:
            parent[word] = parent[parent[word]]
            word = parent[word]
        return word

    def grammatical_form(term, base):
        return any(stem == base for stem, _ in inflections.get(term, ()))

    derived = {}
    for head in sorted(relatives):
        for line in sorted(relatives[head]):
            spelling, _, meaning = line.partition("  ")
            term = key(spelling)
            if not term or term == head:
                continue
            if grammatical_form(term, head):
                continue
            senses = terms.setdefault(term, [spelling, [], []])[1]
            if meaning and meaning not in senses:
                senses.append(meaning)
            derived.setdefault(head, set()).add(term)
            if term in original and not grammatical_form(head, term):
                left, right = sorted((root(head), root(term)))
                parent[right] = left

    families = {}
    for word in sorted(original):
        family = families.setdefault(root(word), set())
        family.add(word)
        family.update(derived.get(word, ()))
    # Grammatical forms can belong to more than one family without merging homographs.
    for term, sources in inflections.items():
        for base, _ in sources:
            families[root(base)].add(term)
    return {
        "terms": dict(sorted(terms.items())),
        "families": {word: sorted(related) for word, related in sorted(families.items())
                     if len(related) > 1},
        "inflections": {word: sorted(sources) for word, sources in sorted(inflections.items())},
    }


if __name__ == "__main__":
    catalog = json.loads((STUDY / "catalog.json").read_text(encoding="utf-8-sig"))
    data = build(catalog)
    (STUDY / "dictionary.json").write_text(
        json.dumps(data, ensure_ascii=True, separators=(",", ":")) + "\n", encoding="utf-8"
    )
    print(f"{len(data['terms'])} indexed forms and headwords, {len(data['families'])} word families")
