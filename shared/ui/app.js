(function () {
  "use strict";

  const rules = window.CijianRules;
  const vocab = window.CijianVocabulary;
  const app = document.getElementById("app");
  const dialog = document.getElementById("dialog");
  const notice = document.getElementById("notice");
  const labels = { cet4: "英语四级", cet6: "英语六级" };
  const names = { new: "学习新词", due: "到期复习", weekend: "周末复习",
    pass: "周目复习", exam: "考前重点复习" };
  const formLabels = { 3: "第三人称单数", p: "过去式", d: "过去分词", i: "现在分词",
    s: "复数", r: "比较级", t: "最高级" };
  const formatDay = new Intl.DateTimeFormat("zh-CN", { month: "long", day: "numeric" });
  const storageKey = "cijian.study.v1";
  const host = window.chrome?.webview ?? window.webkit?.messageHandlers?.cijian;
  const waiting = new Map();
  const pageSize = 50;
  const dictionaryPageSize = 8;
  let requestId = 0;
  let state;
  let route = "home";
  let list = null;
  let saveChain = Promise.resolve();
  let shownDay;
  let audioRun = 0;
  let dictionaryIndex;
  let dictionaryQuery = "";
  let dictionaryMatches = [];
  let dictionaryOffset = 0;
  let dictionarySelection = null;

  function escape(value) {
    return String(value).replace(/[&<>"']/g, character =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
  }

  function button(text, action, type = "", value = "", disabled = false) {
    return `<button type="button" class="${type}" data-action="${action}"
      data-value="${escape(value)}" ${disabled ? "disabled" : ""}>${escape(text)}</button>`;
  }

  function title(label, action = "", description = "") {
    return `<header class="stack"><div class="row between"><h1>${escape(label)}</h1>${action}</div>
      ${description ? `<p class="muted small">${escape(description)}</p>` : ""}</header>`;
  }

  function accept(reply) {
    const callback = waiting.get(reply.id);
    if (!callback) return;
    waiting.delete(reply.id);
    if (reply.error) callback.reject(new Error(reply.error));
    else callback.resolve(reply.value);
  }

  window.cijianResponse = accept;
  window.chrome?.webview?.addEventListener("message", event => accept(event.data));

  function native(operation, value) {
    return new Promise((resolve, reject) => {
      const id = ++requestId;
      const timeout = setTimeout(() => {
        waiting.delete(id);
        reject(new Error("系统操作超时，请重试。"));
      }, 12000);
      waiting.set(id, { resolve: result => { clearTimeout(timeout); resolve(result); },
        reject: error => { clearTimeout(timeout); reject(error); } });
      try {
        host.postMessage({ id, operation, value: value ?? null });
      } catch (error) {
        clearTimeout(timeout);
        waiting.delete(id);
        reject(error);
      }
    });
  }

  function showNotice(text) {
    notice.hidden = !text;
    notice.textContent = text;
  }

  function persist() {
    if (!state) return;
    const json = JSON.stringify(state);
    if (!host) {
      try { localStorage.setItem(storageKey, json); }
      catch { showNotice("学习记录保存失败，请检查本机存储。"); }
      return;
    }
    saveChain = saveChain.catch(() => {}).then(() => native("save", JSON.parse(json)))
      .then(() => showNotice(""), () => showNotice("学习记录保存失败，请重试或检查本机存储。"));
  }

  function stopAudio() {
    audioRun++;
    if (host) native("stop").catch(() => {});
    else window.speechSynthesis?.cancel();
  }

  function browserVoice(word) {
    return new Promise((resolve, reject) => {
      if (!window.speechSynthesis) {
        reject(new Error("系统英语语音暂不可用。"));
        return;
      }
      const speech = new SpeechSynthesisUtterance(word);
      const voices = window.speechSynthesis.getVoices().filter(voice => /^en[-_]/i.test(voice.lang));
      speech.voice = voices.find(voice => voice.localService && voice.lang === "en-US")
        ?? voices.find(voice => voice.localService) ?? voices[0] ?? null;
      speech.lang = speech.voice?.lang ?? "en-US";
      speech.onend = resolve;
      speech.onerror = () => reject(new Error("英语语音暂不可用，请检查系统语音设置。"));
      window.speechSynthesis.speak(speech);
    });
  }

  async function play(word, control) {
    stopAudio();
    const token = ++audioRun;
    const feedback = control.parentElement.querySelector(".message");
    feedback.textContent = "正在播放…";
    try {
      if (host) await native("speak", word);
      else await browserVoice(word);
      if (token === audioRun) feedback.textContent = "播放完毕，可重听。";
    } catch (error) {
      if (token === audioRun) feedback.textContent = error.message;
    }
  }

  function audio(word, highlighted = false) {
    return `<div class="audio">${button("▶  播放发音", "speak", highlighted ? "gold" : "", word)}
      <span class="small message" role="status"></span></div>`;
  }

  function setPage(page, html) {
    route = page;
    shownDay = rules.localDay();
    app.innerHTML = html;
    window.scrollTo(0, 0);
  }

  function levelButtons() {
    return `<div class="pair">${Object.keys(labels).map(key =>
      button(labels[key], "book", key === state.book ? "primary" : "", key)).join("")}</div>`;
  }

  function selection() {
    return [state.books[state.book], vocab.books[state.book]];
  }

  function home() {
    const [book, data] = selection();
    if (rules.reconcile(book, data.core)) persist();
    const day = rules.localDay();
    const due = rules.needsReview(book.entries, day, false);
    const pending = rules.needsReview(book.entries, day, true);
    const mastered = Object.values(book.entries).filter(entry => entry.status === "mastered").length;
    const weekend = rules.weekend(day);
    const firstPassCompleted = rules.firstPassCount(data.core, book.entries);
    const hasNew = firstPassCompleted < data.core.length;
    const canLearn = hasNew && rules.allowsNew(day, state.learnOnWeekends);
    const studied = rules.viewedToday(book.entries, day);
    const description = formatDay.format(new Date()) + (weekend ? (canLearn ? " · 周末学习日" : " · 周末复习日") : " · 学习日");
    let meter;
    if (book.pass === 1) {
      meter = `<p class="muted">词书首轮 ${firstPassCompleted} / ${data.core.length}</p>
        <progress class="bar" value="${firstPassCompleted}" max="${data.core.length}" aria-label="首轮完成进度"></progress>`;
    } else {
      const count = Object.values(book.entries).filter(entry => rules.reviewedInPass(entry, book.pass)).length;
      meter = `<p class="muted">本周目已复习 ${count} 个词</p>`;
      if (hasNew) meter += `<p class="muted small">待补学 ${data.core.length - firstPassCompleted} 个词</p>`;
    }
    let controls = "";
    if (book.daily && rules.complete(book.daily)) {
      book.daily = null;
      persist();
    }
    if (book.daily) {
      controls = `<div class="pair">${button("继续" + names[book.daily.kind], "resume", "primary", "daily")}
        ${button("结束这组", "end-session", "quiet")}</div>`;
    } else {
      let reviewAvailable = false;
      if (weekend) {
        reviewAvailable = rules.oldWords(book.entries, book.pass).length > 0;
        if (reviewAvailable) controls += button("自选旧词复习", "picker", "primary", "weekend");
      } else if (due.length) {
        reviewAvailable = true;
        controls += button("复习到期词", "picker", "primary", "due");
      }
      if (canLearn) {
        const newItems = rules.newWords(data.core, book.entries, day, state.quota, state.learnOnWeekends);
        controls += newItems.length ? button(book.pass === 1 ? "开始学新词" : "补学新词",
          "new", reviewAvailable ? "" : "primary")
          : '<p class="muted small">今天的新词任务已完成。</p>';
      }
      if (book.pass > 1 && !book.finished) {
        if (!weekend) controls += button("自选本周目复习", "picker", reviewAvailable ? "" : "primary", "pass");
        controls += button("结束本周目", "end-pass", "quiet");
      }
      if (!controls) controls = '<p class="muted small">今天暂无旧词可复习。</p>';
    }
    const preferences = hasNew ? `<label class="control row between">周末学新词
      <input id="weekends" type="checkbox" role="switch" ${state.learnOnWeekends ? "checked" : ""}></label>` : "";
    setPage("home", `<div class="stack">${title("词间", button("考前复习", "exam"), description)}
      ${dictionaryField()}${state.dictionaryHistory?.length ? button("搜索记录", "dictionary-history", "quiet") : ""}
      ${levelButtons()}<div class="dashboard">
        <section class="card hero stack"><p class="tag">${state.book === "cet4" ? "CET-4" : "CET-6"}</p>
          <h2>${book.finished ? "已完成五周目" : "第 " + book.pass + " 周目"}</h2>${meter}</section>
        <section class="card stack"><div class="row between"><h2>今日任务</h2>
          ${hasNew ? button("设置新词量", "quota", "quiet") : ""}</div>
          <p class="muted small">${canLearn ? `今日新词 ${studied} / ${state.quota}　·　` : ""}
            到期复习 ${due.length} 个</p><div class="actions stack">${controls}</div>${preferences}</section>
      </div><div class="pair">${button("已学会 " + mastered, "records", "", "mastered")}
        ${button("待复习 " + pending.length, "records", "", "review")}</div></div>`);
  }

  function dictionaryField(query = "") {
    return `<form class="row dictionary-search" role="search">
      <input name="query" type="search" placeholder="搜索英文单词或中文释义"
        aria-label="搜索英文单词或中文释义" enterkeyhint="search"
        value="${escape(query)}" required>
      <button class="primary" type="submit">搜索</button></form>`;
  }

  function lookup() {
    dictionaryIndex ??= window.CijianDictionary.create(window.CijianDictionaryData);
    return dictionaryIndex;
  }

  function wordEntry(book, key) {
    return book.entries[key] ?? window.CijianDictionary.reviewWord(lookup(), key);
  }

  function searchDictionary(query) {
    state.dictionaryHistory = [query, ...(state.dictionaryHistory ?? []).filter(
      previous => !rules.answersMatch(previous, query))].slice(0, 20);
    dictionaryQuery = query;
    dictionarySelection = null;
    dictionaryMatches = window.CijianDictionary.search(lookup(), query);
    persist();
    showDictionary();
  }

  function showDictionaryHistory() {
    dictionaryQuery = "";
    dictionarySelection = null;
    dictionaryMatches = [];
    showDictionary();
  }

  function showDictionary(offset = 0) {
    dictionaryOffset = offset;
    const end = Math.min(offset + dictionaryPageSize, dictionaryMatches.length);
    const rows = dictionaryMatches.slice(offset, end).map(entry => `<article class="card stack">
      <h2 lang="en">${button(entry.spelling, "dictionary-detail", "", entry.key)}</h2>
      <p class="terms">${escape(window.CijianDictionary.meanings(lookup(), entry).slice(0, 2).join("\n"))}</p>
    </article>`).join("");
    const paging = dictionaryMatches.length > dictionaryPageSize ? `<div class="row between">
      ${button("上一页", "dictionary-page", "", offset - dictionaryPageSize, !offset)}
      <span class="muted small">${offset + 1}–${end} / ${dictionaryMatches.length}</span>
      ${button("下一页", "dictionary-page", "", end, end >= dictionaryMatches.length)}</div>` : "";
    let results;
    if (!dictionaryQuery) {
      const history = state.dictionaryHistory ?? [];
      results = history.length ? `<section class="stack"><h2>最近搜索</h2><div class="actions stack">
        ${history.map(query => button(query, "dictionary-repeat", "", query)).join("")}</div></section>`
        : '<p class="muted">暂无搜索记录</p>';
    } else {
      results = rows ? `<p class="muted small">共 ${dictionaryMatches.length} 条结果</p>${rows}${paging}`
        : '<p class="muted">无结果</p>';
    }
    setPage("dictionary", `<div class="narrow stack">${title("词典", button("返回首页", "dictionary-exit", "quiet"))}
      ${dictionaryField(dictionaryQuery)}${results}</div>`);
  }

  function dictionaryDetail(key) {
    const index = lookup();
    const entry = index.entries.get(key);
    dictionarySelection = entry;
    const changes = vocab.forms[key];
    const formLines = changes ? Object.entries(formLabels).filter(([code]) => changes[code])
      .map(([code, label]) => label + "  " + changes[code]) : [];
    const source = window.CijianDictionary.sources(index, entry).map(item =>
      button(item.label, "dictionary-detail", "", item.base.key)).join("");
    const families = window.CijianDictionary.families(index, entry).map(family =>
      `<section class="card dictionary-family">${family.filter(other => other !== entry).map(other =>
        button(other.spelling, "dictionary-detail", "quiet", other.key)).join("")}</section>`).join("");
    setPage("dictionary-detail", `<div class="narrow stack">${title("词条详情",
      button("返回搜索结果", "dictionary-results", "quiet"))}
      ${button("结束查词", "dictionary-exit", "quiet")}
      <article class="card stack"><h2 lang="en" class="learn-title">${escape(entry.spelling)}</h2>
        <p class="terms">${escape(window.CijianDictionary.meanings(index, entry).join("\n"))}</p>
        ${entry.variants.length ? `<p class="muted small">其他写法　${escape(entry.variants.join(" / "))}</p>` : ""}
      </article>
      ${source ? `<section class="dictionary-family">${source}</section>` : ""}
      ${formLines.length ? `<section class="card stack"><h2>词形变化</h2>
        <p class="terms">${escape(formLines.join("\n"))}</p></section>` : ""}
      ${families ? `<section class="stack"><h2>同源词与词形</h2>${families}</section>` : ""}</div>`);
  }

  function markDictionaryWord(entry) {
    rules.mark(selection()[0], entry.key, "review", rules.localDay());
    persist();
    home();
  }

  function exitDictionary() {
    if (!dictionaryMatches.length) { home(); return; }
    const chosen = dictionarySelection
      ?? window.CijianDictionary.suggestedTerm(dictionaryQuery, dictionaryMatches);
    if (chosen) {
      openDialog("加入待复习？", `<p>把 ${escape(chosen.spelling)} 加入${labels[state.book]}的待复习吗？</p>
        <div class="pair">${button("不加入", "dictionary-no")}${button("加入复习", "dictionary-yes", "primary")}</div>`,
        action => {
          dialog.close();
          if (action === "dictionary-yes") markDictionaryWord(chosen);
          else home();
        });
      return;
    }
    const visible = dictionaryMatches.slice(dictionaryOffset, dictionaryOffset + dictionaryPageSize);
    openDialog("选一个词加入待复习？", `<div class="actions stack">${visible.map((item, i) =>
      button("加入 " + item.spelling, "choose-" + i)).join("")}${button("不加入", "dictionary-no", "quiet")}</div>`,
      action => {
        dialog.close();
        if (action === "dictionary-no") home();
        else markDictionaryWord(visible[Number(action.substring("choose-".length))]);
      });
  }

  function listPage(page, kind, source, heading, extra = "") {
    list = { kind, source, query: "", offset: 0, picked: new Set() };
    setPage(page, `<div class="narrow stack">${title(heading, button("返回首页", "home", "quiet"))}
      ${extra}<input id="search" type="search" aria-label="搜索英文单词" placeholder="搜索英文单词">
      ${page === "picker" ? `<div class="pair">${button("按数量选词", "amount")}
        ${button("清空选择", "clear", "quiet")}</div>` : ""}
      <div id="word-list"></div>${page === "picker"
        ? '<div id="picker-actions" class="actions"></div>' : ""}</div>`);
    showRows();
  }

  function showRows() {
    const [book, data] = selection();
    const matching = list.source.filter(key => key.includes(list.query));
    list.offset = Math.max(0, Math.min(list.offset, Math.floor((matching.length - 1) / pageSize) * pageSize));
    const rows = matching.slice(list.offset, list.offset + pageSize).map(key => {
      const word = escape(wordEntry(data, key)[0]);
      const label = book.entries[key]?.status;
      if (route === "picker") {
        const entry = book.entries[key];
        const importance = rules.priority(entry, book.finished ? 1 : book.pass);
        const info = importance === 0 ? "待复习" : importance === 1 ? "上一周目不熟"
          : label === "mastered" ? "已学会" : "已看过";
        return `<div class="row between word-row"><label class="control"><input data-word="${escape(key)}"
          type="checkbox" ${list.picked.has(key) ? "checked" : ""}><strong lang="en">${word}</strong></label>
          <small class="${importance === 0 ? "warning" : "muted"}">${info}</small></div>`;
      }
      const action = route === "exam" ? (list.kind === "rank" ? "review" : null)
        : (list.kind === "review" ? "mastered" : "review");
      const mark = action ? button(label === action ? "已加入" : action === "review" ? "不熟" : "已学会",
        "mark-list", "quiet", key, label === action) : "";
      return `<div class="row between word-row"><strong lang="en">${word}</strong>${mark}</div>`;
    }).join("");
    const pagination = matching.length > pageSize ? `<div class="row between pager">
      ${button("上一页", "page", "", "-1", !list.offset)}
      <span class="small muted">${list.offset + 1}–${Math.min(list.offset + pageSize, matching.length)} / ${matching.length}</span>
      ${button("下一页", "page", "", "1", list.offset + pageSize >= matching.length)}</div>` : "";
    document.getElementById("word-list").innerHTML = rows
      ? rows + pagination : '<p class="muted">没有符合条件的单词。</p>';
    showSelected();
  }

  function showSelected() {
    if (route === "picker") {
      document.getElementById("picker-actions").innerHTML = button(
        list.picked.size ? "开始复习 · " + list.picked.size + " 个词" : "开始复习",
        "start-picked", "primary", "", !list.picked.size);
    }
  }

  function exam(kind = "rank") {
    const [book, data] = selection();
    if (book.exam && rules.complete(book.exam)) {
      book.exam = null;
      persist();
    }
    const source = kind === "rank" ? data.ranking : rules.needsReview(book.entries, rules.localDay(), true);
    const option = kind === "review" && (book.exam || source.length)
      ? button(book.exam ? "继续重点复习" : "开始重点复习", book.exam ? "resume" : "exam-start", "primary", "exam") : "";
    listPage("exam", kind, source, "考前复习",
      `${levelButtons()}<div class="pair">${button("考试高频词", "exam", kind === "rank" ? "primary" : "", "rank")}
        ${button("不会的单词", "exam", kind === "review" ? "primary" : "", "review")}</div>${option}`);
  }

  function records(kind) {
    const [book] = selection();
    const words = kind === "review" ? rules.needsReview(book.entries, rules.localDay(), true)
      : Object.keys(book.entries).filter(key => book.entries[key].status === "mastered").sort();
    listPage("records", kind, words, kind === "review" ? "待复习" : "已学会");
  }

  function picker(kind) {
    const [book] = selection();
    const words = kind === "due" ? rules.needsReview(book.entries, rules.localDay(), false)
      : rules.oldWords(book.entries, book.finished ? 1 : book.pass);
    listPage("picker", kind, words, names[kind], '<p class="muted small">勾选单词，或按列表顺序选取指定数量。</p>');
  }

  function askMode(callback) {
    openDialog("选择默写方式", `<div class="pair">${button("看中文默写", "dialog-cn", "primary")}
      ${button("听音默写", "dialog-listen")}</div>`, action => {
      dialog.close();
      callback(action === "dialog-cn" ? "chinese" : "listen");
    });
  }

  function openDialog(heading, contents, callback) {
    dialog.innerHTML = `<div class="stack"><div class="row between"><h2>${escape(heading)}</h2>
      ${button("取消", "dialog-close", "quiet")}</div>${contents}</div>`;
    dialog.onclick = event => {
      const target = event.target.closest("button[data-action]");
      if (!target) return;
      if (target.dataset.action === "dialog-close") dialog.close();
      else callback(target.dataset.action);
    };
    dialog.showModal();
  }

  function quantity(heading, max, current, callback) {
    openDialog(heading, `<input id="quantity" type="number" inputmode="numeric" min="1" max="${max}"
      aria-label="词数" value="${current}" placeholder="1–${max}">
      ${button("确定", "dialog-submit", "primary") }`, () => {
      const field = dialog.querySelector("input");
      const count = Number(field.value);
      if (!Number.isInteger(count) || count < 1 || count > max) {
        field.setCustomValidity("请输入 1–" + max + " 的整数。");
        field.reportValidity();
        return;
      }
      dialog.close();
      callback(count);
    });
    dialog.querySelector("input").oninput = event => event.target.setCustomValidity("");
  }

  function confirmAction(heading, description, action) {
    openDialog(heading, `<p>${escape(description)}</p>${button("确认", "dialog-confirm", "primary")}`,
      () => { dialog.close(); action(); });
  }

  function start(kind, words, mode) {
    if (!words.length) return;
    const [book] = selection();
    const slot = kind === "exam" ? "exam" : "daily";
    book[slot] = rules.createSession(kind, words, mode, book.pass);
    persist();
    sessionPage(slot);
  }

  function currentSlot() {
    return route === "exam-session" ? "exam" : "daily";
  }

  function sessionPage(slot) {
    stopAudio();
    const [book, data] = selection();
    const active = book[slot];
    if (!active) { home(); return; }
    const page = slot === "exam" ? "exam-session" : "daily-session";
    if (rules.complete(active)) {
      if (rules.reconcile(book, data.core)) persist();
      setPage(page, `<div class="narrow stack">${title("本组已完成")}
        <section class="card hero stack"><p>${names[active.kind]}</p>
        <h2>${active.correct} / ${active.words.length}</h2><p class="muted">拼写正确</p></section>
        <div class="actions">${button("完成", "leave", "primary")}</div></div>`);
      return;
    }
    const key = active.words[active.index];
    const word = wordEntry(data, key);
    const day = rules.localDay();
    let content;
    if (active.learning) {
      const viewed = book.entries[key]?.viewed != null;
      if (!viewed && (!rules.allowsNew(day, state.learnOnWeekends)
          || rules.viewedToday(book.entries, day) >= state.quota)) {
        if (active.index === 0) {
          book[slot] = null;
          persist();
          home();
          return;
        }
        setPage(page, `<div class="narrow stack">${title("接着默写这一组", button("稍后继续", "leave", "quiet"))}
          <p>已经学过 ${active.index} 个词。</p>${button("选择默写方式", "learn-test", "primary", active.index)}</div>`);
        return;
      }
      if (!viewed) {
        rules.progress(book, key).viewed = day;
        persist();
      }
      content = studyCard(active, word, key, book.entries[key]);
    } else {
      content = active.revealed ? answerCard(active, word) : questionCard(active, word);
    }
    const mode = active.learning ? "先学" : active.mode === "listen" ? "听音默写" : "看中文默写";
    setPage(page, `<div class="narrow stack">${title(names[active.kind], button("稍后继续", "leave", "quiet"),
      mode + "　·　" + (active.index + 1) + " / " + active.words.length)}${content}</div>`);
  }

  function studyCard(active, word, key, entry) {
    const forms = vocab.forms[key];
    const variations = forms ? Object.entries(formLabels).filter(([code]) => forms[code])
      .map(([code, name]) => name + "  " + forms[code]) : [];
    const spellings = variations.length ? `<section class="stack"><h3>语法变形</h3>
      <p class="terms">${escape(variations.join("\n"))}</p></section>` : "";
    const roots = word[2].length ? `<section class="stack"><h3>同根词</h3>
      <p class="terms">${escape(word[2].join("\n"))}</p></section>` : "";
    const status = entry?.status;
    const last = active.index + 1 === active.words.length;
    return `<section class="card hero stack"><h2 class="learn-title" lang="en">${escape(word[0])}</h2>
      <p class="terms muted">${escape(word[1].join("\n"))}</p>${variantNote(word)}${audio(word[0], true)}</section>
      ${spellings || roots ? `<div class="card stack">${spellings}${roots}</div>` : ""}
      <div class="pair">${button(status === "mastered" ? "✓ 已学会" : "已学会", "learn-mark",
        status === "mastered" ? "primary" : "", "mastered")}
        ${button(status === "review" ? "✓ 待复习" : "加入复习", "learn-mark",
          status === "review" ? "primary" : "", "review")}</div>
      <div class="actions">${last ? button("选择默写方式", "learn-test", "primary", active.words.length)
        : button("下一个单词", "learn-next", "primary")}</div>`;
  }

  function clue(word) {
    const variants = [word[0], ...(word[3] ?? [])].sort((a, b) => b.length - a.length);
    const quoted = variants.map(answer => answer.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
    return word[1].slice(0, 3).join("\n").replace(new RegExp(
      "(^|[^a-z])(?:" + quoted.join("|") + ")(?=[^a-z]|$)", "gi"), "$1____");
  }

  function variantNote(word) {
    return word[3]?.length
      ? `<p class="muted small">其他写法　${escape(word[3].join(" / "))}</p>`
      : "";
  }

  function questionCard(active, word) {
    const listening = active.mode === "listen";
    const instruction = listening ? "听发音写出英文" : "根据释义写出英文";
    const meaning = `<p id="clue" class="terms" ${listening && !active.hint ? "hidden" : ""}>
      ${escape(clue(word))}</p>`;
    return `<section class="card stack"><h2>${instruction}</h2>${listening
      ? audio(word[0]) + button("中文提示", "hint", "quiet") : ""}${meaning}</section>
      <input id="spelling" aria-label="输入英文拼写" placeholder="输入英文拼写" value="${escape(active.input)}"
        autocomplete="off" spellcheck="false" autocapitalize="none" enterkeyhint="done">
      <div class="pair">${button("跳过并看答案", "skip")}${button("提交", "submit", "primary",
        "", !rules.normalize(active.input))}</div>`;
  }

  function answerCard(active, word) {
    const correct = !active.skipped && rules.answersMatch(active.input, word[0], word[3]);
    return `<section class="card stack ${correct ? "success" : "retry"}">
      <p class="small ${correct ? "" : "warning"}">${correct ? "拼写正确" : active.skipped ? "已跳过" : "再记一次"}</p>
      <h2 class="learn-title" lang="en">${escape(word[0])}</h2>
      ${variantNote(word)}
      ${!correct && !active.skipped ? `<p class="warning">你的拼写　${escape(active.input)}</p>` : ""}
      <p class="terms">${escape(word[1].join("\n"))}</p>${audio(word[0])}</section>
      <p class="muted small">给这个词标记掌握情况</p>
      <div class="pair">${button("已学会", "score", correct ? "primary" : "", "mastered")}
        ${button("加入复习", "score", correct ? "" : "primary", "review")}</div>`;
  }

  function reveal(skip) {
    const slot = currentSlot();
    const active = selection()[0][slot];
    if (!active || active.revealed || (!skip && !rules.normalize(active.input))) return;
    active.revealed = true;
    active.skipped = skip;
    persist();
    sessionPage(slot);
  }

  function leave() {
    const slot = currentSlot();
    const [book] = selection();
    const active = book[slot];
    const finished = active && rules.complete(active);
    if (finished) book[slot] = null;
    persist();
    stopAudio();
    if (slot === "exam") exam("review");
    else if (finished && ["due", "pass", "weekend"].includes(active.kind)) picker(active.kind);
    else home();
  }

  function onClick(event) {
    const control = event.target.closest("button[data-action]");
    if (!control || !state) return;
    const [book, data] = selection();
    const slot = currentSlot();
    const active = route.endsWith("-session") ? book[slot] : null;
    const { action, value } = control.dataset;
    const day = rules.localDay();
    switch (action) {
      case "home": home(); break;
      case "dictionary-page": showDictionary(Number(value)); break;
      case "dictionary-history": showDictionaryHistory(); break;
      case "dictionary-repeat": searchDictionary(value); break;
      case "dictionary-detail": dictionaryDetail(value); break;
      case "dictionary-results": showDictionary(dictionaryOffset); break;
      case "dictionary-exit": exitDictionary(); break;
      case "book":
        if (state.book === value) return;
        state.book = value;
        persist();
        if (route === "exam") exam(list.kind);
        else home();
        break;
      case "quota":
        quantity("每日新词量", 200, state.quota, count => { state.quota = count; persist(); home(); });
        break;
      case "new":
        start("new", rules.newWords(data.core, book.entries, day, state.quota, state.learnOnWeekends), "chinese");
        break;
      case "picker": picker(value); break;
      case "exam": exam(value || "rank"); break;
      case "records": records(value); break;
      case "resume": sessionPage(value); break;
      case "end-session":
        confirmAction("结束这组练习？", "已学习的词和已提交的结果会保留。",
          () => { book.daily = null; persist(); home(); });
        break;
      case "end-pass":
        confirmAction("结束第 " + book.pass + " 周目？", book.pass === 5 ? "之后可以继续日常复习和考前复习。"
          : "将进入第 " + (book.pass + 1) + " 周目。", () => {
            rules.endPass(book);
            persist();
            home();
          });
        break;
      case "mark-list": {
        const status = route === "records" && list.kind === "review" ? "mastered" : "review";
        rules.mark(book, value, status, day);
        if (route === "records") list.source = list.source.filter(key => key !== value);
        persist();
        showRows();
        break;
      }
      case "exam-start":
        askMode(mode => start("exam", rules.needsReview(book.entries, rules.localDay(), true).slice(0, 35), mode));
        break;
      case "page":
        list.offset += Number(value) * pageSize;
        showRows();
        document.getElementById("word-list").scrollIntoView({ block: "start" });
        break;
      case "clear": list.picked.clear(); showRows(); break;
      case "amount": {
        const candidates = list.source.filter(key => key.includes(list.query));
        if (candidates.length) quantity("按列表顺序选词", candidates.length, "", count => {
          list.picked = new Set(candidates.slice(0, count));
          showRows();
        });
        break;
      }
      case "start-picked": {
        const kind = list.kind;
        const words = list.source.filter(key => list.picked.has(key));
        if (words.length) askMode(mode => start(kind, words, mode));
        break;
      }
      case "speak": play(value, control); break;
      case "hint":
        active.hint = !active.hint;
        document.getElementById("clue").hidden = !active.hint;
        persist();
        break;
      case "learn-mark":
        rules.mark(book, active.words[active.index], value, day);
        persist();
        sessionPage(slot);
        break;
      case "learn-next": active.index++; persist(); sessionPage(slot); break;
      case "learn-test":
        askMode(mode => {
          rules.beginTest(active, Number(value), mode);
          persist();
          sessionPage(slot);
        });
        break;
      case "skip": reveal(true); break;
      case "submit": reveal(false); break;
      case "score": {
        const key = active.words[active.index];
        const word = wordEntry(data, key);
        const correct = !active.skipped && rules.answersMatch(active.input, word[0], word[3]);
        rules.finishQuestion(book, active, key, value, correct, day);
        persist();
        sessionPage(slot);
        break;
      }
      case "leave": leave(); break;
    }
  }

  app.addEventListener("click", onClick);
  app.addEventListener("submit", event => {
    if (!event.target.matches(".dictionary-search")) return;
    event.preventDefault();
    const field = event.target.elements.namedItem("query");
    const query = field.value.trim();
    if (!query) {
      field.setCustomValidity("请输入英文单词或中文释义");
      field.reportValidity();
      return;
    }
    searchDictionary(query);
  });
  app.addEventListener("change", event => {
    if (event.target.id === "weekends") {
      state.learnOnWeekends = event.target.checked;
      persist();
      home();
    } else if (event.target.dataset.word) {
      const key = event.target.dataset.word;
      if (event.target.checked) list.picked.add(key);
      else list.picked.delete(key);
      showSelected();
    }
  });
  app.addEventListener("input", event => {
    if (event.target.name === "query") {
      event.target.setCustomValidity("");
    } else if (event.target.id === "search") {
      list.query = rules.normalize(event.target.value);
      list.offset = 0;
      showRows();
    } else if (event.target.id === "spelling") {
      selection()[0][currentSlot()].input = event.target.value;
      app.querySelector('[data-action="submit"]').disabled = !rules.normalize(event.target.value);
    }
  });
  app.addEventListener("keydown", event => {
    if (event.target.id === "spelling" && event.key === "Enter") {
      event.preventDefault();
      reveal(false);
    }
  });
  window.addEventListener("pagehide", persist);
  document.addEventListener("visibilitychange", () => {
    if (!state) return;
    if (document.hidden) { stopAudio(); persist(); }
    else refreshDate();
  });
  function refreshDate() {
    if (!state || dialog.open || shownDay === rules.localDay()) return;
    if (route === "home") home();
    else if (route.endsWith("-session") && selection()[0][currentSlot()]?.learning) sessionPage(currentSlot());
  }
  setInterval(refreshDate, 1000);

  (async function initialize() {
    try {
      if (!vocab) throw new Error("缺少词库资源。");
      const saved = host ? await native("load") : localStorage.getItem(storageKey);
      state = saved ? JSON.parse(saved) : rules.freshState();
      if (state.version !== 1 || !state.books?.[state.book]) throw new Error("无法读取学习记录。");
      home();
    } catch (error) {
      showNotice(error.message || "词间启动失败。");
    }
  })();
})();
