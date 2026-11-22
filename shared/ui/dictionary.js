(function (root) {
  "use strict";

  const hanText = /\p{Script=Han}/u;
  const hanPhrases = /\p{Script=Han}+/gu;

  function searchKey(value) {
    return root.CijianRules.normalize(value).normalize("NFD").replace(/\p{Mark}+/gu, "");
  }

  function create(data) {
    const entries = new Map();
    const families = new Map();
    for (const [key, [spelling, senses, variants]] of Object.entries(data.terms)) {
      const normalized = searchKey(spelling);
      const aliases = new Set([normalized, ...variants.map(searchKey)]);
      const phrases = new Set(senses.flatMap(meaning => meaning.match(hanPhrases) ?? []));
      entries.set(key, { key, spelling, normalized, senses, aliases, phrases, variants });
    }
    for (const members of Object.values(data.families)) {
      for (const member of members) {
        if (!families.has(member)) families.set(member, []);
        families.get(member).push(members);
      }
    }
    return { entries, families, inflections: data.inflections };
  }

  function score(entry, query, chinese) {
    let best = Infinity;
    if (chinese) {
      for (const phrase of entry.phrases) {
        if (phrase === query) return 0;
        if (phrase.startsWith(query)) best = 1;
        else if (phrase.includes(query)) best = Math.min(best, 2);
      }
      return best;
    }
    if (entry.normalized === query) return 0;
    if (entry.aliases.has(query)) return 1;
    for (const alias of entry.aliases) {
      if (alias.startsWith(query)) return 3;
      if (alias.includes(query)) best = 4;
    }
    return best;
  }

  function search(index, input) {
    const normalized = searchKey(input);
    const chinese = hanText.test(normalized);
    const query = chinese ? normalized.replace(/\s+/g, "") : normalized;
    if (!query) return [];
    const scores = new Map();
    const exact = [];
    for (const entry of index.entries.values()) {
      const rank = score(entry, query, chinese);
      if (Number.isFinite(rank)) scores.set(entry, rank);
      if (!chinese && rank <= 1) exact.push(entry);
    }
    for (const entry of exact) {
      for (const family of families(index, entry)) {
        for (const member of family) scores.set(member, Math.min(2, scores.get(member) ?? Infinity));
      }
    }
    const matches = [...scores].map(([entry, rank]) => ({ entry, rank }));
    matches.sort((a, b) => a.rank - b.rank
      || a.entry.normalized.length - b.entry.normalized.length
      || (a.entry.normalized < b.entry.normalized ? -1 : a.entry.normalized > b.entry.normalized ? 1 : 0));
    return matches.map(match => match.entry);
  }

  function families(index, entry) {
    return (index.families.get(entry.key) ?? []).map(group => group.map(key => index.entries.get(key)));
  }

  const labels = { 3: "第三人称单数", p: "过去式", d: "过去分词", i: "现在分词",
    s: "复数", r: "比较级", t: "最高级" };

  function sources(index, entry) {
    return (index.inflections[entry.key] ?? []).map(([key, code]) => {
      const base = index.entries.get(key);
      return { base, label: base.spelling + " 的" + labels[code] };
    });
  }

  function meanings(index, entry) {
    if (entry.senses.length) return entry.senses;
    const grammatical = sources(index, entry);
    if (grammatical.length) {
      return [...new Set(grammatical.flatMap(source => [source.label, ...source.base.senses]))];
    }
    const other = families(index, entry).flat().find(member => member.senses.length);
    return other ? ["同源词  " + other.spelling, ...other.senses] : [];
  }

  function reviewWord(index, key) {
    const entry = index.entries.get(key);
    return entry ? [entry.spelling, meanings(index, entry), [], entry.variants] : undefined;
  }

  function suggestedTerm(query, results) {
    if (results.length === 1) return results[0];
    const normalized = searchKey(query);
    if (hanText.test(normalized)) return null;
    const exact = results.filter(entry => entry.aliases.has(normalized));
    return exact.length === 1 ? exact[0] : null;
  }

  root.CijianDictionary = { create, search, families, sources, meanings, reviewWord, suggestedTerm };
})(globalThis);
