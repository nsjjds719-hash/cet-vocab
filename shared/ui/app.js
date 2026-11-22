(function () {
  "use strict";

  const rules = window.CijianRules;
  const vocab = window.CijianVocabulary;
  const app = document.getElementById("app");
  const dialog = document.getElementById("dialog");
  const notice = document.getElementById("notice");
  const labels = { cet4: "英语四级", cet6: "英语六级" };
  const names = { get new() { return "新词" + window.CijianLook.value("newEmoji"); }, due: "到期复习", weekend: "周末复习",
    pass: "本轮复习", exam: "临考生词" };
  const formLabels = { 3: "第三人称单数", p: "过去式", d: "过去分词", i: "现在分词",
    s: "复数", r: "比较级", t: "最高级" };
  const numerals = ["", "一", "二", "三", "四", "五", "六"];
  const formatDay = new Intl.DateTimeFormat("zh-CN", { month: "long", day: "numeric" });
  const storageKey = "cijian.study.v1";
  const host = window.chrome?.webview;
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
  let live = null;
  let wordReturn = null;
  const liveSize = 10;

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
    feedback.textContent = "";
    control.classList.add("playing");
    try {
      if (host) await native("speak", word);
      else await browserVoice(word);
      if (token === audioRun) feedback.textContent = "";
    } catch (error) {
      if (token === audioRun) feedback.textContent = error.message;
    } finally {
      control.classList.remove("playing");
    }
  }

  function audio(word, highlighted = false) {
    return `<div class="audio">${button("聆听", "speak", highlighted ? "gold" : "", word)}
      <span class="small message" role="status"></span></div>`;
  }

  function setPage(page, html) {
    route = page;
    shownDay = rules.localDay();
    app.innerHTML = html;
    flames(app);
    window.scrollTo(0, 0);
  }

  // 动态表情：把文字里的指定表情包成会动的 span（对照表在 look.js，动画见 styles.css 的同名类）。
  const lively = window.CijianLook.lively;
  const livelyPattern = new RegExp("(" + Object.keys(lively).sort((a, b) => b.length - a.length).join("|") + ")", "u");
  function flames(root) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) if (livelyPattern.test(walker.currentNode.nodeValue)) nodes.push(walker.currentNode);
    for (const node of nodes) {
      const fragment = document.createDocumentFragment();
      for (const part of node.nodeValue.split(livelyPattern)) {
        if (lively[part]) {
          const emoji = document.createElement("span");
          emoji.className = "lively " + lively[part];
          emoji.textContent = part;
          fragment.append(emoji);
        } else if (part) {
          fragment.append(part);
        }
      }
      node.replaceWith(fragment);
    }
  }

  // 设置里选的表情；选「无」时为空字符串。
  function emoji(key) {
    return window.CijianLook.value(key);
  }

  // 首页轮次徽章，七种样式（设置 → 首页样式），均画在 66×66 的画布上，与 Android 的 StudyView.PassBadge 一致。
  const badgeShapes = {
    ring: () => '<circle cx="33" cy="33" r="31"/>',
    seal: () => '<circle cx="33" cy="33" r="31"/><circle class="dash" cx="33" cy="33" r="26"/>',
    progress: pass => {
      let arcs = "";
      for (let i = 0; i < 5; i++) {
        const from = (-90 + i * 72 + 5) * Math.PI / 180, to = from + 62 * Math.PI / 180;
        const point = angle => (33 + 30 * Math.cos(angle)).toFixed(2) + " " + (33 + 30 * Math.sin(angle)).toFixed(2);
        arcs += `<path class="${i < pass ? "on" : ""}" d="M${point(from)} A30 30 0 0 1 ${point(to)}"/>`;
      }
      return arcs;
    },
    stamp: () => '<g transform="rotate(-8 33 33)"><circle class="fill" cx="33" cy="33" r="31"/>'
      + '<circle class="inner" cx="33" cy="33" r="27"/></g>',
    // 两枝月桂：叶片沿半径 30 的圆弧自下而上排开，越往上越淡。
    laurel: () => {
      let leaves = '<path d="M13 57A30 30 0 0 1 21 6M53 57A30 30 0 0 0 45 6"/>';
      for (const side of [-1, 1]) for (let i = 0; i < 6; i++) {
        const angle = 155 - i * 22, radians = angle * Math.PI / 180;
        const x = (33 + side * 30 * Math.sin(radians)).toFixed(1);
        const y = (33 - 30 * Math.cos(radians)).toFixed(1);
        leaves += `<ellipse cx="${x}" cy="${y}" rx="2.4" ry="5.6" opacity="${(1 - i * .08).toFixed(2)}"
          transform="rotate(${side * (angle - 145)} ${x} ${y})"/>`;
      }
      return leaves;
    },
    glass: () => '<defs><radialGradient id="badge-glass" cx="35%" cy="28%" r="80%">'
      + '<stop offset="0" stop-color="#fff" stop-opacity=".45"/><stop offset=".45" stop-color="#e8c993" stop-opacity=".22"/>'
      + '<stop offset="1" stop-color="#c2643f" stop-opacity=".35"/></radialGradient></defs>'
      + '<circle cx="33" cy="33" r="31" fill="url(#badge-glass)"/>'
      + '<ellipse cx="24" cy="18" rx="11" ry="5" transform="rotate(-25 24 18)"/>',
    orbit: (pass, tag) => '<defs><path id="badge-orbit" d="M33 33m-27 0a27 27 0 1 1 54 0a27 27 0 1 1 -54 0"/></defs>'
      + '<circle cx="33" cy="33" r="20"/><text><textPath href="#badge-orbit">THE · PASS · '
      + tag + ' · THE · PASS · ' + tag + ' ·</textPath></text>'
  };

  function badge(pass, finished, style = window.CijianLook.get("badge")) {
    const label = finished ? "五轮圆满" : "第" + numerals[pass] + "轮";
    const tag = state?.book === "cet6" ? "CET-6" : "CET-4";
    return `<span class="laurel badge-${style}" role="img" aria-label="${label}" title="${label}">
      <svg viewBox="0 0 66 66" aria-hidden="true">${badgeShapes[style](finished ? 5 : pass, tag)}</svg>
      <span class="numeral">${pass}</span></span>`;
  }

  function seal() {
    return `<button type="button" class="seal" data-action="exam" data-value=""
      aria-label="临考 · 考前复习" title="临考">考</button>`;
  }

  function gear() {
    return `<button type="button" class="gear" data-action="settings" data-value=""
      aria-label="设置" title="设置"><svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M4 7h16M4 12h16M4 17h16"/><circle cx="9" cy="7" r="2.2"/><circle cx="15.5" cy="12" r="2.2"/>
      <circle cx="7.5" cy="17" r="2.2"/></svg></button>`;
  }

  // 设置：首页列出四个分组，点进去是子页。外观由 look.js 保存在本机，不进学习记录。
  const settingGroups = {
    look: { name: "外观", glyph: "色" },
    home: { name: "首页样式", glyph: "数" },
    emoji: { name: "动态表情", glyph: "趣" },
    reading: { name: "阅读与动效", glyph: "读" }
  };

  function optionName(key) {
    return window.CijianLook.options[key].values[window.CijianLook.get(key)];
  }

  function settingSummary(group) {
    const look = window.CijianLook;
    switch (group) {
      case "look":
        return [look.themes[look.theme], look.accents[look.accent].name,
          look.photo ? "自定义照片" : "暖纸水印"].join(" · ");
      case "home": return optionName("numeral") + " · " + optionName("badge");
      case "emoji":
        return ["newEmoji", "searchEmoji", "foundEmoji"].map(key => emoji(key) || "无").join("　");
      default:
        return "字号" + optionName("textSize") + " · 跳动" + optionName("wiggle")
          + " · 浮现" + optionName("entrance");
    }
  }

  function settings() {
    const groups = Object.entries(settingGroups).map(([id, group]) =>
      `<button type="button" class="card group" data-action="settings-group" data-value="${id}">
        <span class="glyph" aria-hidden="true">${group.glyph}</span>
        <span class="label"><span class="name">${group.name}</span><span class="muted">${escape(settingSummary(id))}</span></span>
        <span class="chevron" aria-hidden="true">›</span></button>`).join("");
    setPage("settings", `<div class="narrow stack">${title("设置", button("返回", "home", "quiet"),
      "外观只保存在这台设备上，不影响学习记录。")}${groups}<div class="actions">${button("完成", "home", "primary")}</div></div>`);
  }

  // 选项格：data-value 为「设置键:取值」，由 onClick 的 look 分支统一处理。
  function choice(key, value, sample, label, extra = "") {
    const chosen = window.CijianLook.get(key) === value;
    return `<button type="button" class="choice ${extra} ${chosen ? "chosen" : ""}" data-action="look"
      data-value="${escape(key + ":" + value)}" aria-pressed="${chosen}">${sample}<span>${escape(label)}</span></button>`;
  }

  function choices(key, sample, extra = "") {
    return `<div class="choices">${Object.entries(window.CijianLook.options[key].values).map(([value, label]) =>
      choice(key, value, sample(value), label, typeof extra === "function" ? extra(value) : extra)).join("")}</div>`;
  }

  function segmentOf(key, labels = window.CijianLook.options[key].values) {
    return `<div class="segment trio">${Object.entries(labels).map(([value, label]) =>
      button(label, "look", window.CijianLook.get(key) === value ? "chosen" : "", key + ":" + value)).join("")}</div>`;
  }

  function settingsGroup(id) {
    const look = window.CijianLook;
    let body = "";
    if (id === "look") {
      const swatches = Object.entries(look.accents).map(([key, accent]) =>
        `<button type="button" class="swatch ${key === look.accent ? "chosen" : ""}" data-action="look"
          data-value="accent:${key}" aria-pressed="${key === look.accent}"><span class="dot"></span>${escape(accent.name)}</button>`).join("");
      body = `<section class="card stack"><h2>主题</h2>${segmentOf("theme", look.themes)}
          <p class="muted small">跟随系统时，会随电脑或手机的深浅色设置自动切换。</p></section>
        <section class="card stack"><h2>按键颜色</h2><div class="swatches">${swatches}</div>
          <p class="muted small">主按键、开关、进度条与朱印都会换成这个颜色。</p></section>
        <section class="card stack"><h2>背景照片</h2>
          <div class="thumb ${look.photo ? "filled" : ""}" id="photo-thumb" role="img"
            aria-label="${look.photo ? "当前背景照片" : "默认背景：暖纸水印"}">${escape(emoji("watermark") || "　")}</div>
          <input class="photo-file" id="photo-file" type="file" accept="image/*" tabindex="-1">
          <div class="pair">${button(look.photo ? "更换照片" : "选择照片", "photo-pick")}
            ${button("恢复默认", "photo-clear", "quiet", "", !look.photo)}</div>
          <p class="muted small">照片会自动压缩后存在本机，卡片会加一层磨砂以便看清文字。</p>
          ${look.photo ? `<h3>薄纱浓度</h3>${segmentOf("veil")}<p class="muted small">照片上那层薄纱，越浓照片越淡、字越清楚。</p>` : ""}</section>
        <section class="card stack"><h2>背景水印字</h2>
          ${choices("watermark", value => `<span class="sample mark">${value === "none" ? "—" : value}</span>`)}
          <p class="muted small">右下角那枚大号宋体字；用了背景照片时不显示。</p></section>
        <section class="card stack"><h2>磨砂浓度</h2>${segmentOf("frost")}
          <p class="muted small">卡片和按键玻璃的透明程度，越浓越不透。</p></section>`;
    } else if (id === "home") {
      const preview = `<section class="card hero preview-hero stack" data-mark="${state.book === "cet4" ? "IV" : "VI"}">
          <p class="tag">预览</p>${statsMeter(...previewSelection())}</section>`;
      body = `${preview}<section class="card stack"><h2>数字字体</h2>
          ${choices("numeral", () => '<span class="sample digits">128</span>', value => "on-ink numeral-" + value)}
          <p class="muted small">首页统计数字和轮次徽章里的数字。</p></section>
        <section class="card stack"><h2>轮次徽章</h2>
          ${choices("badge", value => badge(3, false, value), "on-ink")}
          <p class="muted small">首页统计卡左侧的圆形标记，圈内是第几轮。</p></section>`;
    } else if (id === "emoji") {
      const sample = value => `<span class="sample">${value === "none" ? "—" : value}</span>`;
      body = `<section class="card stack"><h2>新词</h2>${choices("newEmoji", sample)}
          <p class="muted small">「新词」按钮、学习页标题和首页日期行。</p></section>
        <section class="card stack"><h2>查阅按钮</h2>${choices("searchEmoji", sample)}
          <p class="muted small">查词框右边的按钮；选「无」时显示「查阅」二字。</p></section>
        <section class="card stack"><h2>查词结果</h2>${choices("foundEmoji", sample)}
          <p class="muted small">结果列表上方「N 条」前面的表情。</p></section>`;
    } else {
      const toggle = (key, label, note) => `<label class="control row between">${label}
          <input id="look-${key}" type="checkbox" role="switch" ${look.get(key) === "on" ? "checked" : ""}></label>
          <p class="muted small">${note}</p>`;
      body = `<section class="card stack"><h2>字号</h2>${segmentOf("textSize")}
          <p class="muted small">释义、按钮和单词卡一起放大。</p></section>
        <section class="card stack"><h2>动效</h2>
          ${toggle("wiggle", "表情跳动", "关掉后表情保持静止。")}
          ${toggle("entrance", "换页浮现", "关掉后换页时内容直接出现，不再逐块浮上来。")}</section>`;
    }
    setPage("settings-" + id, `<div class="narrow stack">${title(settingGroups[id].name,
      button("返回", "settings", "quiet"))}${body}<div class="actions">${button("完成", "home", "primary")}</div></div>`);
    const thumb = document.getElementById("photo-thumb");
    if (thumb && look.photo) thumb.style.backgroundImage = `url("${look.photo}")`;
  }

  function currentGroup() {
    return route.startsWith("settings-") ? route.slice("settings-".length) : "";
  }

  function levelButtons() {
    return `<div class="pair segment">${Object.keys(labels).map(key =>
      button(labels[key], "book", key === state.book ? "chosen" : "", key)).join("")}</div>`;
  }

  function selection() {
    return [state.books[state.book], vocab.books[state.book]];
  }

  function previewSelection() {
    const [book, data] = selection();
    if (rules.reconcile(book, data.core)) persist();
    return [book, data];
  }

  // 首页统计卡的轮次与计数；设置页预览共用，保证与真实进度一致。
  function statsMeter(book, data) {
    // 「已读」按读过学习卡计数，不必等本组默写完；进入第二轮仍以默写完成为准。
    const firstPassCompleted = rules.readCount(data.core, book.entries);
    const hasNew = firstPassCompleted < data.core.length;
    let meter;
    if (book.pass === 1) {
      meter = `<div class="pass-row">${badge(1, false)}<div><p class="muted caption">首轮已读</p>
          <p class="figure">${firstPassCompleted}<small> / ${data.core.length}</small></p></div></div>
        <progress class="bar" value="${firstPassCompleted}" max="${data.core.length}" aria-label="首轮完成进度"></progress>`;
    } else {
      const count = Object.values(book.entries).filter(entry => rules.reviewedInPass(entry, book.pass)).length;
      meter = `<div class="pass-row">${badge(book.pass, book.finished)}<div>
          <p class="muted caption">${book.finished ? "五轮圆满 · 已复习" : "本轮已复习"}</p>
          <p class="figure">${count}<small> 词</small></p></div></div>`;
      if (hasNew) meter += `<p class="muted small">尚余 ${data.core.length - firstPassCompleted} 词待补读</p>`;
    }
    return meter;
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
    const fire = emoji("newEmoji");
    const description = formatDay.format(new Date())
      + (weekend ? (canLearn ? " · 周末" + (fire || "新词") : " · 周末复习") : fire ? " · " + fire : "");
    const meter = statsMeter(book, data);
    let controls = "";
    if (book.daily && rules.complete(book.daily)) {
      book.daily = null;
      persist();
    }
    if (book.daily) {
      controls = `<div class="pair">${button("接续" + names[book.daily.kind], "resume", "primary", "daily")}
        ${button("收起本组", "end-session", "quiet")}</div>`;
    } else {
      let reviewAvailable = false;
      if (weekend) {
        reviewAvailable = rules.oldWords(book.entries, book.pass).length > 0;
        if (reviewAvailable) controls += button("择词复习", "picker", "primary", "weekend");
      } else if (due.length) {
        reviewAvailable = true;
        controls += button("复习到期词", "picker", "primary", "due");
      }
      if (canLearn) {
        const newItems = rules.newWords(data.core, book.entries, day, state.quota, state.learnOnWeekends);
        controls += newItems.length ? button(book.pass === 1 ? "开始今日新词" : "补读新词",
          "new", reviewAvailable ? "" : "primary")
          : '<p class="muted small">今日新词已读完。</p>';
      }
      if (book.pass > 1 && !book.finished) {
        if (!weekend) controls += button("本轮择词复习", "picker", reviewAvailable ? "" : "primary", "pass");
        controls += button("完结本轮", "end-pass", "quiet");
      }
      if (!controls) controls = '<p class="muted small">今日无旧词需复习。</p>';
    }
    const preferences = hasNew ? `<label class="control row between">周末也读新词
      <input id="weekends" type="checkbox" role="switch" ${state.learnOnWeekends ? "checked" : ""}></label>` : "";
    setPage("home", `<div class="stack">${title("词间", `<div class="corner">${gear()}${seal()}</div>`, description)}
      ${dictionaryField()}${state.dictionaryHistory?.length ? button("查阅记录", "dictionary-history", "quiet") : ""}
      ${levelButtons()}<div class="dashboard">
        <section class="card hero stack" data-mark="${state.book === "cet4" ? "IV" : "VI"}"><p class="tag">${state.book === "cet4" ? "CET-4" : "CET-6"}</p>${meter}</section>
        <section class="card stack"><div class="row between"><h2>今日</h2>
          ${hasNew ? button("每日词量", "quota", "quiet") : ""}</div>
          <p class="muted small">${canLearn ? `新词 ${studied} / ${state.quota}　·　` : ""}
            到期复习 ${due.length} 词</p><div class="actions stack">${controls}</div>${preferences}</section>
      </div><div class="pair">${button("已熟记 · " + mastered, "records", "", "mastered")}
        ${button("待复习 · " + pending.length, "records", "", "review")}</div></div>`);
  }

  function dictionaryField(query = "") {
    return `<form class="row dictionary-search" role="search">
      <input name="query" type="search" placeholder="中英互查词典"
        aria-label="中英互查词典" enterkeyhint="search"
        value="${escape(query)}" required>
      <button class="ink search-run ${emoji("searchEmoji") ? "" : "text"}" type="submit" aria-label="查阅"
        title="查阅">${emoji("searchEmoji") || "查阅"}</button></form>`;
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
      results = history.length ? `<section class="stack"><h2>最近查阅</h2><div class="actions stack">
        ${history.map(query => button(query, "dictionary-repeat", "", query)).join("")}</div></section>`
        : '<p class="muted">还没有查阅记录</p>';
    } else {
      results = rows ? `<p class="muted small">${emoji("foundEmoji") ? emoji("foundEmoji") + " " : ""}${dictionaryMatches.length} 条</p>${rows}${paging}`
        : '<p class="muted">无结果</p>';
    }
    setPage("dictionary", `<div class="narrow stack">${title("词典", button("合上词典", "dictionary-exit", "quiet"))}
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
    setPage("dictionary-detail", `<div class="narrow stack">${title("词条",
      button("返回结果", "dictionary-results", "quiet"))}
      ${button("合上词典", "dictionary-exit", "quiet")}
      <article class="card stack word-card" data-mark="${escape(entry.spelling)}"><h2 lang="en" class="learn-title">${escape(entry.spelling)}</h2>
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
      openDialog("收入复习？", `<p>把 <span lang="en">${escape(chosen.spelling)}</span> 收入${labels[state.book]}的待复习吗？</p>
        <div class="pair">${button("不必", "dictionary-no")}${button("收入复习", "dictionary-yes", "primary")}</div>`,
        action => {
          dialog.close();
          if (action === "dictionary-yes") markDictionaryWord(chosen);
          else home();
        });
      return;
    }
    const visible = dictionaryMatches.slice(dictionaryOffset, dictionaryOffset + dictionaryPageSize);
    openDialog("选一个词收入复习？", `<div class="actions stack">${visible.map((item, i) =>
      button("收入 " + item.spelling, "choose-" + i)).join("")}${button("不必", "dictionary-no", "quiet")}</div>`,
      action => {
        dialog.close();
        if (action === "dictionary-no") home();
        else markDictionaryWord(visible[Number(action.substring("choose-".length))]);
      });
  }

  function listPage(page, kind, source, heading, extra = "") {
    list = { kind, source, query: "", offset: 0, picked: new Set() };
    setPage(page, `<div class="narrow stack">${title(heading, button("返回", "home", "quiet"))}
      ${extra}<input id="search" type="search" aria-label="筛选英文单词" placeholder="筛选单词">
      ${page === "picker" ? `<div class="pair">${button("按数择词", "amount")}
        ${button("清空所选", "clear", "quiet")}</div>` : ""}
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
        const info = importance === 0 ? "待复习" : importance === 1 ? "上轮生疏"
          : label === "mastered" ? "已熟记" : "已读过";
        return `<div class="row between word-row"><label class="control"><input data-word="${escape(key)}"
          type="checkbox" ${list.picked.has(key) ? "checked" : ""}><strong lang="en">${word}</strong></label>
          <small class="${importance === 0 ? "warning" : "muted"}">${info}</small></div>`;
      }
      if (route === "exam" && list.kind === "review") {
        return `<div class="row between word-row">${wordLink(key, word)}${starButton(book, key, word)}</div>`;
      }
      const action = route === "exam" ? (list.kind === "rank" ? "review" : null)
        : (list.kind === "review" ? "mastered" : "review");
      const mark = action ? button(label === action ? "已收录" : action === "review" ? "生疏" : "已熟记",
        "mark-list", "quiet", key, label === action) : "";
      return `<div class="row between word-row">${wordLink(key, word)}${mark}</div>`;
    }).join("");
    const pagination = matching.length > pageSize ? `<div class="row between pager">
      ${button("上一页", "page", "", "-1", !list.offset)}
      <span class="small muted">${list.offset + 1}–${Math.min(list.offset + pageSize, matching.length)} / ${matching.length}</span>
      ${button("下一页", "page", "", "1", list.offset + pageSize >= matching.length)}</div>` : "";
    document.getElementById("word-list").innerHTML = rows
      ? rows + pagination : '<p class="muted">没有相符的单词。</p>';
    showSelected();
  }

  /** 词目里的单词：点开进入学习页。word 须已转义。 */
  function wordLink(key, word) {
    return `<button type="button" class="word-link" data-action="word" data-value="${escape(key)}"
      lang="en" aria-label="${word}，查看学习页">${word}</button>`;
  }

  function openWord(key) {
    wordReturn = { route, scroll: window.scrollY, list: list && { kind: list.kind, query: list.query,
      offset: list.offset, picked: new Set(list.picked) } };
    wordPage(key);
  }

  function wordPage(key) {
    stopAudio();
    const [book, data] = selection();
    const word = wordEntry(data, key);
    const forms = vocab.forms[key];
    const variations = forms ? Object.entries(formLabels).filter(([code]) => forms[code])
      .map(([code, name]) => name + "  " + forms[code]) : [];
    const spellings = variations.length ? `<section class="stack"><h3>词形变化</h3>
      <p class="terms">${escape(variations.join("\n"))}</p></section>` : "";
    const roots = word[2]?.length ? `<section class="stack"><h3>同根词</h3>
      <p class="terms">${escape(word[2].join("\n"))}</p></section>` : "";
    const status = book.entries[key]?.status;
    const star = status === "review" ? `<div class="row between"><span class="muted small">生词本星标</span>
      ${starButton(book, key, escape(word[0]))}</div>` : "";
    setPage("word", `<div class="narrow stack">${title("学习", button("返回", "word-back", "quiet"), labels[state.book])}
      <section class="card stack word-card" data-mark="${escape(word[0])}"><h2 class="learn-title" lang="en">${escape(word[0])}</h2>
      <p class="terms">${escape(word[1].join("\n"))}</p>${variantNote(word)}${audio(word[0])}</section>
      ${spellings || roots ? `<div class="card stack">${spellings}${roots}</div>` : ""}
      <div class="pair">${button(status === "mastered" ? "✓ 已熟记" : "熟记", "word-mark",
        status === "mastered" ? "primary" : "", key + "|mastered")}
        ${button(status === "review" ? "✓ 待复习" : "待复习", "word-mark",
          status === "review" ? "primary" : "", key + "|review")}</div>${star}</div>`);
  }

  function closeWord() {
    stopAudio();
    const back = wordReturn;
    wordReturn = null;
    if (!back) { home(); return; }
    if (back.route === "live" && live) livePage();
    else if (back.route === "exam") exam(back.list.kind);
    else if (back.route === "records") records(back.list.kind);
    else { home(); return; }
    if (back.list && list) {
      Object.assign(list, { query: back.list.query, offset: back.list.offset, picked: back.list.picked });
      const search = document.getElementById("search");
      if (search) search.value = back.list.query;
      showRows();
    }
    window.scrollTo(0, back.scroll);
  }

  function starButton(book, key, word) {
    const on = rules.starred(book, key);
    return `<button type="button" class="star${on ? " on" : ""}" data-action="star" data-value="${escape(key)}"
      aria-pressed="${on}" aria-label="${on ? "去除星标" : "加星标"} ${word}" title="${on ? "去除星标" : "加星标"}">${on ? "★" : "☆"}</button>`;
  }

  function showSelected() {
    if (route === "picker") {
      document.getElementById("picker-actions").innerHTML = button(
        list.picked.size ? "开始复习 · " + list.picked.size + " 词" : "开始复习",
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
    let option = kind === "review" && (book.exam || source.length)
      ? button(book.exam ? "接续生词默写" : "生词默写", book.exam ? "resume" : "exam-start", "primary", "exam") : "";
    if (kind === "review" && source.length) {
      const stars = source.filter(key => rules.starred(book, key)).length;
      option += `${button("实时测试", "live-start")}<p class="muted small">从生词本随机抽词，每轮 ${Math.min(liveSize, source.length)} 词，可无限循环；不影响复习进度。${stars ? "　已加星 " + stars + " 词" : ""}</p>`;
    }
    listPage("exam", kind, source, "临考",
      `${levelButtons()}<div class="pair segment">${button("真题高频", "exam", kind === "rank" ? "chosen" : "", "rank")}
        ${button("生词本", "exam", kind === "review" ? "chosen" : "", "review")}</div>${option}`);
  }

  function records(kind) {
    const [book] = selection();
    const words = kind === "review" ? rules.needsReview(book.entries, rules.localDay(), true)
      : Object.keys(book.entries).filter(key => book.entries[key].status === "mastered").sort();
    listPage("records", kind, words, kind === "review" ? "待复习" : "已熟记");
  }

  function picker(kind) {
    const [book] = selection();
    const words = kind === "due" ? rules.needsReview(book.entries, rules.localDay(), false)
      : rules.oldWords(book.entries, book.finished ? 1 : book.pass);
    listPage("picker", kind, words, names[kind], '<p class="muted small">逐个勾选，或按列表顺序取若干词。</p>');
  }

  function askMode(callback) {
    openDialog("如何默写", `<div class="pair">${button("依义默写", "dialog-cn", "primary")}
      ${button("闻声默写", "dialog-listen")}</div>`, action => {
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
      ${button("就这样", "dialog-submit", "primary") }`, () => {
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
    openDialog(heading, `<p>${escape(description)}</p>${button("确定", "dialog-confirm", "primary")}`,
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
      setPage(page, `<div class="narrow stack">${title("本组圆满")}
        <section class="card hero stack" data-mark="fin."><p class="tag">${names[active.kind]}</p>
        <h2>${active.correct} / ${active.words.length}</h2><p class="muted">拼写无误</p></section>
        <div class="actions">${button("收卷", "leave", "primary")}</div></div>`);
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
        setPage(page, `<div class="narrow stack">${title("默写这一组", button("稍后再续", "leave", "quiet"))}
          <p>已读 ${active.index} 词，来默写吧。</p>${button("开始默写", "learn-test", "primary", active.index)}</div>`);
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
    const mode = active.learning ? "研读" : active.mode === "listen" ? "闻声默写" : "依义默写";
    setPage(page, `<div class="narrow stack">${title(names[active.kind], button("稍后再续", "leave", "quiet"),
      mode + "　·　" + (active.index + 1) + " / " + active.words.length)}${content}</div>`);
  }

  function studyCard(active, word, key, entry) {
    const forms = vocab.forms[key];
    const variations = forms ? Object.entries(formLabels).filter(([code]) => forms[code])
      .map(([code, name]) => name + "  " + forms[code]) : [];
    const spellings = variations.length ? `<section class="stack"><h3>词形变化</h3>
      <p class="terms">${escape(variations.join("\n"))}</p></section>` : "";
    const roots = word[2].length ? `<section class="stack"><h3>同根词</h3>
      <p class="terms">${escape(word[2].join("\n"))}</p></section>` : "";
    const status = entry?.status;
    const last = active.index + 1 === active.words.length;
    return `<section class="card stack word-card" data-mark="${escape(word[0])}"><h2 class="learn-title" lang="en">${escape(word[0])}</h2>
      <p class="terms">${escape(word[1].join("\n"))}</p>${variantNote(word)}${audio(word[0])}</section>
      ${spellings || roots ? `<div class="card stack">${spellings}${roots}</div>` : ""}
      <div class="pair">${button(status === "mastered" ? "✓ 已熟记" : "熟记", "learn-mark",
        status === "mastered" ? "primary" : "", "mastered")}
        ${button(status === "review" ? "✓ 待复习" : "待复习", "learn-mark",
          status === "review" ? "primary" : "", "review")}</div>
      <div class="actions">${last ? button("开始默写", "learn-test", "primary", active.words.length)
        : button("下一词", "learn-next", "primary")}</div>`;
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
    const instruction = listening ? "闻其声，书其形" : "观其义，书其形";
    const meaning = `<p id="clue" class="terms" ${listening && !active.hint ? "hidden" : ""}>
      ${escape(clue(word))}</p>`;
    return `<section class="card stack"><h2>${instruction}</h2>${listening
      ? audio(word[0]) + button("释义提示", "hint", "quiet") : ""}${meaning}</section>
      <input id="spelling" lang="en" aria-label="输入英文拼写" placeholder="在此拼写" value="${escape(active.input)}"
        autocomplete="off" spellcheck="false" autocapitalize="none" enterkeyhint="done">
      <div class="pair">${button("揭晓答案", "skip")}${button("核对", "submit", "primary",
        "", !rules.normalize(active.input))}</div>`;
  }

  function answerCard(active, word) {
    const correct = !active.skipped && rules.answersMatch(active.input, word[0], word[3]);
    return `<section class="card stack ${correct ? "success" : "retry"}">
      <p class="verdict ${correct ? "" : "warning"}">${correct ? "拼写无误" : active.skipped ? "答案在此" : "差之毫厘"}</p>
      <h2 class="learn-title" lang="en">${escape(word[0])}</h2>
      ${variantNote(word)}
      ${!correct && !active.skipped ? `<p class="warning">你写的是　<span lang="en">${escape(active.input)}</span></p>` : ""}
      <p class="terms">${escape(word[1].join("\n"))}</p>${audio(word[0])}</section>
      <p class="muted small">这个词，记住了吗？</p>
      <div class="pair">${button("熟记", "score", correct ? "primary" : "", "mastered")}
        ${button("待复习", "score", correct ? "" : "primary", "review")}</div>`;
  }

  function reveal(skip) {
    const slot = currentSlot();
    const active = route === "live" ? live : selection()[0][slot];
    if (!active || active.revealed || (!skip && !rules.normalize(active.input))) return;
    active.revealed = true;
    active.skipped = skip;
    if (route === "live") { livePage(); return; }
    persist();
    sessionPage(slot);
  }

  function livePool() {
    return rules.needsReview(selection()[0].entries, rules.localDay(), true);
  }

  function liveRound() {
    const pool = livePool();
    if (!pool.length) { live = null; exam("review"); return; }
    const round = rules.liveRound(pool, live.deck, liveSize);
    Object.assign(live, { words: round.words, deck: round.deck, round: live.round + 1, index: 0,
      results: [], input: "", revealed: false, skipped: false, hint: false });
    livePage();
  }

  function startLive(mode) {
    live = { mode, deck: [], round: 0 };
    liveRound();
  }

  function livePage() {
    stopAudio();
    const [book, data] = selection();
    const header = action => title("实时测试", button("结束测试", "live-exit", "quiet"), action);
    if (live.index >= live.words.length) {
      const right = live.results.filter(Boolean).length;
      const missed = live.words.filter((key, i) => !live.results[i] && !rules.starred(book, key));
      const rows = live.words.map((key, i) => {
        const word = wordEntry(data, key);
        return `<div class="row between word-row"><div class="live-word">${wordLink(key, escape(word[0]))}
          <small class="${live.results[i] ? "muted" : "warning"}">${live.results[i] ? "会" : "不会"}　${escape(word[1][0] ?? "")}</small></div>
          ${starButton(book, key, escape(word[0]))}</div>`;
      }).join("");
      setPage("live", `<div class="narrow stack">${header("第 " + live.round + " 轮完成")}
        <section class="card hero stack" data-mark="live"><p class="tag">实时测试</p>
        <h2>${right} / ${live.words.length}</h2><p class="muted">拼写无误</p></section>
        <p class="muted small">给不会的词加星，星标会显示在生词本里，可随时去除。</p>
        <div>${rows}</div>
        ${missed.length ? button("不会的词全部加星 · " + missed.length, "live-star-missed") : ""}
        <div class="actions">${button("再来一轮", "live-next-round", "primary")}</div></div>`);
      return;
    }
    const word = wordEntry(data, live.words[live.index]);
    let content;
    if (live.revealed) {
      const correct = !live.skipped && rules.answersMatch(live.input, word[0], word[3]);
      const last = live.index + 1 === live.words.length;
      content = `<section class="card stack ${correct ? "success" : "retry"}">
        <p class="verdict ${correct ? "" : "warning"}">${correct ? "拼写无误" : live.skipped ? "答案在此" : "差之毫厘"}</p>
        <h2 class="learn-title" lang="en">${escape(word[0])}</h2>${variantNote(word)}
        ${!correct && !live.skipped ? `<p class="warning">你写的是　<span lang="en">${escape(live.input)}</span></p>` : ""}
        <p class="terms">${escape(word[1].join("\n"))}</p>${audio(word[0])}</section>
        <div class="actions">${button(last ? "查看本轮结果" : "下一词", "live-next", "primary")}</div>`;
    } else {
      content = questionCard(live, word);
    }
    const mode = live.mode === "listen" ? "闻声默写" : "依义默写";
    setPage("live", `<div class="narrow stack">${header(mode + "　·　第 " + live.round + " 轮　·　"
      + (live.index + 1) + " / " + live.words.length)}${content}</div>`);
    if (!live.revealed) document.getElementById("spelling")?.focus();
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
    const active = route === "live" ? live : route.endsWith("-session") ? book[slot] : null;
    const { action, value } = control.dataset;
    const day = rules.localDay();
    switch (action) {
      case "home": home(); break;
      case "settings": settings(); break;
      case "settings-group": settingsGroup(value); break;
      case "look": {
        const split = value.indexOf(":");
        window.CijianLook.set(value.slice(0, split), value.slice(split + 1));
        settingsGroup(currentGroup());
        break;
      }
      case "photo-pick": document.getElementById("photo-file")?.click(); break;
      case "photo-clear":
        confirmAction("恢复默认背景？", "照片会从本机删除，背景回到暖纸水印。",
          () => { window.CijianLook.clearPhoto(); settingsGroup("look"); });
        break;
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
        quantity("每日词量", 200, state.quota, count => { state.quota = count; persist(); home(); });
        break;
      case "new":
        start("new", rules.newWords(data.core, book.entries, day, state.quota, state.learnOnWeekends), "chinese");
        break;
      case "picker": picker(value); break;
      case "exam": exam(value || "rank"); break;
      case "records": records(value); break;
      case "resume": sessionPage(value); break;
      case "end-session":
        confirmAction("收起本组？", "已读的词和已核对的结果都会保留。",
          () => { book.daily = null; persist(); home(); });
        break;
      case "end-pass":
        confirmAction("完结第" + numerals[book.pass] + "轮？", book.pass === 5 ? "之后仍可日常复习与临考。"
          : "将开启第" + numerals[book.pass + 1] + "轮。", () => {
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
        if (candidates.length) quantity("按序择词", candidates.length, "", count => {
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
      case "star": {
        const on = !rules.starred(book, value);
        rules.setStar(book, value, on);
        persist();
        control.outerHTML = starButton(book, value, escape(wordEntry(data, value)[0]));
        if (route === "live") {
          const missed = live.words.filter((key, i) => !live.results[i] && !rules.starred(book, key)).length;
          const bulk = app.querySelector('[data-action="live-star-missed"]');
          if (bulk) {
            bulk.textContent = "不会的词全部加星 · " + missed;
            bulk.hidden = !missed;
          }
        }
        break;
      }
      case "word": openWord(value); break;
      case "word-back": closeWord(); break;
      case "word-mark": {
        const split = value.lastIndexOf("|");
        rules.mark(book, value.slice(0, split), value.slice(split + 1), day);
        persist();
        const y = window.scrollY;
        wordPage(value.slice(0, split));
        window.scrollTo(0, y);
        break;
      }
      case "live-start": askMode(startLive); break;
      case "live-next": {
        const word = wordEntry(data, live.words[live.index]);
        live.results.push(!live.skipped && rules.answersMatch(live.input, word[0], word[3]));
        Object.assign(live, { index: live.index + 1, input: "", revealed: false, skipped: false, hint: false });
        livePage();
        break;
      }
      case "live-star-missed":
        live.words.forEach((key, i) => { if (!live.results[i]) rules.setStar(book, key, true); });
        persist();
        app.querySelectorAll('[data-action="star"]').forEach(star =>
          { star.outerHTML = starButton(book, star.dataset.value, escape(wordEntry(data, star.dataset.value)[0])); });
        control.hidden = true;
        break;
      case "live-next-round": liveRound(); break;
      case "live-exit": stopAudio(); live = null; exam("review"); break;
      case "speak": play(value, control); break;
      case "hint":
        active.hint = !active.hint;
        document.getElementById("clue").hidden = !active.hint;
        if (route !== "live") persist();
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
    if (event.target.id?.startsWith("look-")) {
      window.CijianLook.set(event.target.id.slice(5), event.target.checked ? "on" : "off");
    } else if (event.target.id === "photo-file") {
      const file = event.target.files?.[0];
      if (!file) return;
      showNotice("");
      window.CijianLook.setPhoto(file).then(() => { if (route === "settings-look") settingsGroup("look"); },
        error => showNotice(error.message || "照片没能设置成功。"));
    } else if (event.target.id === "weekends") {
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
      (route === "live" ? live : selection()[0][currentSlot()]).input = event.target.value;
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
