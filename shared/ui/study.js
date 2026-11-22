(function (root) {
  "use strict";

  const DAY_MS = 86400000;
  const LAST_PASS = 5;

  function blankBook() {
    return { pass: 1, finished: false, entries: {}, daily: null, exam: null };
  }

  function freshState() {
    return { version: 1, book: "cet6", quota: 35, learnOnWeekends: true,
      books: { cet4: blankBook(), cet6: blankBook() } };
  }

  function normalize(value) {
    return value.normalize("NFKC").trim().toLowerCase()
      .replace(/[’‘]/g, "'").replace(/[‐‑]/g, "-").replace(/\s+/g, " ");
  }

  function answersMatch(answer, spelling, variants = []) {
    const written = normalize(answer);
    return written !== "" && [spelling, ...variants].some(variant => written === normalize(variant));
  }

  function localDay(now = new Date()) {
    return Date.UTC(now.getFullYear(), now.getMonth(), now.getDate()) / DAY_MS;
  }

  function weekend(day) {
    const weekday = ((day + 4) % 7 + 7) % 7;
    return weekday === 0 || weekday === 6;
  }

  function allowsNew(day, learnOnWeekends) {
    return learnOnWeekends || !weekend(day);
  }

  function viewedToday(entries, day) {
    return Object.values(entries).filter(entry => entry.viewed === day).length;
  }

  function newWords(catalog, entries, day, quota, learnOnWeekends) {
    if (!allowsNew(day, learnOnWeekends)) return [];
    let left = Math.max(0, quota - viewedToday(entries, day));
    const selected = catalog.filter(key => entries[key]?.viewed != null && entries[key]?.firstPass == null);
    for (const key of catalog) {
      if (selected.length >= quota || left === 0) break;
      const record = entries[key];
      if (record?.viewed == null && record?.firstPass == null) {
        selected.push(key);
        left--;
      }
    }
    return selected.slice(0, quota);
  }

  function progress(book, key) {
    if (!Object.prototype.hasOwnProperty.call(book.entries, key)) {
      Object.defineProperty(book.entries, key, { value: {}, enumerable: true, writable: true });
    }
    return book.entries[key];
  }

  function bit(pass) {
    return 1 << (pass - 1);
  }

  function mark(book, word, status, day, practiced = false) {
    const entry = progress(book, word);
    if (status === "review") {
      entry.unfamiliar = (entry.unfamiliar || 0) | bit(book.pass);
      if (practiced || entry.status !== status) entry.due = day + 1;
    } else {
      delete entry.due;
    }
    entry.status = status;
  }

  function needsReview(entries, day, includeFuture) {
    return Object.keys(entries).filter(key => entries[key].status === "review"
      && (includeFuture || entries[key].due <= day))
      .sort((a, b) => entries[a].due - entries[b].due || a.localeCompare(b, "en"));
  }

  function priority(entry, pass) {
    if (entry.status === "review") return 0;
    return pass > 1 && ((entry.unfamiliar || 0) & bit(pass - 1)) !== 0 ? 1 : 2;
  }

  function reviewedInPass(entry, pass) {
    return ((entry.reviewed || 0) & bit(pass)) !== 0;
  }

  function oldWords(entries, pass) {
    return Object.keys(entries).filter(key => entries[key].status || entries[key].viewed != null)
      .sort((a, b) => priority(entries[a], pass) - priority(entries[b], pass)
        || Number(reviewedInPass(entries[a], pass)) - Number(reviewedInPass(entries[b], pass))
        || (entries[a].due ?? 0) - (entries[b].due ?? 0)
        || a.localeCompare(b, "en"));
  }

  function firstPassCount(catalog, entries) {
    return catalog.filter(word => entries[word]?.firstPass != null).length;
  }

  function reconcile(book, catalog) {
    if (book.pass === 1 && firstPassCount(catalog, book.entries) === catalog.length) {
      book.pass = 2;
      return true;
    }
    return false;
  }

  function endPass(book) {
    if (book.pass < 2 || book.finished) return;
    if (book.pass === LAST_PASS) book.finished = true;
    else book.pass++;
  }

  function shuffle(words) {
    for (let i = words.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [words[i], words[j]] = [words[j], words[i]];
    }
    return words;
  }

  function createSession(kind, words, mode, pass) {
    return { kind, words: [...words], mode, pass, learning: kind === "new", index: 0,
      correct: 0, input: "", revealed: false, skipped: false, hint: false };
  }

  function beginTest(session, count, mode) {
    session.words = shuffle(session.words.slice(0, count));
    session.mode = mode;
    session.index = 0;
    session.learning = false;
  }

  function complete(session) {
    return !session.learning && session.index >= session.words.length;
  }

  function finishQuestion(book, session, word, status, correct, day) {
    mark(book, word, status, day, true);
    const entry = progress(book, word);
    if (session.kind === "new" && entry.firstPass == null) entry.firstPass = day;
    if (session.kind !== "exam" && session.pass > 1 && !book.finished) {
      entry.reviewed = (entry.reviewed || 0) | bit(session.pass);
    }
    session.index++;
    if (correct) session.correct++;
    Object.assign(session, { input: "", revealed: false, skipped: false, hint: false });
  }

  root.CijianRules = { freshState, normalize, answersMatch, localDay, weekend, allowsNew, viewedToday,
    newWords, progress, mark, needsReview, priority, reviewedInPass, oldWords, firstPassCount,
    reconcile, endPass, createSession, beginTest, complete, finishQuestion };
})(globalThis);
