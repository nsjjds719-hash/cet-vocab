(function () {
  "use strict";

  // 外观设置：主题、按键颜色、背景照片，以及表情、数字字体、轮次徽章、水印、磨砂、薄纱、字号、动效。
  // 只存在本机浏览器存储里，与学习记录分开；在页面绘制前就套用，避免闪一下默认外观。
  // 选项与色值和 Android 的 Look.java 保持一致。
  const lookKey = "cijian.look.v1";
  const photoKey = "cijian.photo.v1";
  const accents = {
    clay: { name: "陶土", color: "#c2643f", deep: "#9e4c2d", light: "#e08a64" },
    indigo: { name: "黛蓝", color: "#47699a", deep: "#34507a", light: "#7d9fd0" },
    pine: { name: "松青", color: "#3e8573", deep: "#2d6658", light: "#6fb9a4" },
    rouge: { name: "胭脂", color: "#b04660", deep: "#8a3249", light: "#da7a91" },
    wisteria: { name: "藤紫", color: "#7b5ea7", deep: "#5f4686", light: "#a98fd3" }
  };
  const themes = { light: "浅色", dark: "深色", system: "跟随系统" };

  // 表情 → 动画类名（关键帧见 styles.css，Android 见 StudyView.Lively）。
  const lively = { "💪": "flex", "🔥": "flame", "🚀": "rocket", "✊": "punch", "🏃": "run",
    "✍️": "write", "🌟": "shine", "🔍": "scan", "🎯": "target", "✨": "twinkle", "🎉": "party",
    "💡": "bulb", "💎": "gem" };
  const studyEmoji = { "💪": "举臂发力", "🔥": "火苗跃动", "🚀": "火箭冲刺", "✊": "握拳打气",
    "🏃": "奔跑", "✍️": "奋笔疾书", "🌟": "闪亮星星" };
  const options = {
    newEmoji: { default: "🔥", values: { ...studyEmoji, none: "无" } },
    searchEmoji: { default: "🏃", values: { ...studyEmoji, "🔍": "放大镜", none: "无" } },
    foundEmoji: { default: "🎯", values: { "🔍": "扫视", "🎯": "正中靶心", "✨": "闪闪发光",
      "🎉": "撒花庆祝", "💡": "灵光一闪", "💎": "寻到宝石", none: "无" } },
    numeral: { default: "playfair", values: { playfair: "Playfair", bodoni: "Bodoni",
      cormorant: "Cormorant", abril: "Abril", cinzel: "Cinzel", italiana: "Italiana", fraunces: "Fraunces" } },
    badge: { default: "laurel", values: { ring: "细环", seal: "双环印", progress: "进度环",
      stamp: "朱砂印", laurel: "月桂", glass: "玻璃球", orbit: "铭文环" } },
    watermark: { default: "词", values: { "词": "词", "学": "学", "勤": "勤", "书": "书", none: "无" } },
    frost: { default: "medium", values: { light: "轻", medium: "中", heavy: "浓" } },
    veil: { default: "medium", values: { light: "轻", medium: "中", heavy: "浓" } },
    textSize: { default: "normal", values: { normal: "标准", large: "大", xlarge: "特大" } },
    wiggle: { default: "on", values: { on: "开", off: "关" } },
    entrance: { default: "on", values: { on: "开", off: "关" } }
  };
  // 数字字体：CSS 字族、字形、字重（@font-face 见 styles.css；子集说明见 fonts/README.txt）。
  const numerals = {
    playfair: ["\"Cijian Numeral\"", "italic", 400],
    bodoni: ["\"Cijian Numeral Bodoni\"", "normal", 400],
    cormorant: ["\"Cijian Numeral Cormorant\"", "italic", 500],
    abril: ["\"Cijian Numeral Abril\"", "normal", 400],
    cinzel: ["\"Cijian Numeral Cinzel\"", "normal", 400],
    italiana: ["\"Cijian Numeral Italiana\"", "normal", 400],
    fraunces: ["\"Cijian Numeral Fraunces\"", "italic", 400]
  };
  const frostLevels = { light: .68, medium: 1, heavy: 1.32 };
  const veilLevels = { light: .45, medium: 1, heavy: 1.75 };
  const textSizes = { normal: "100%", large: "112.5%", xlarge: "125%" };

  const root = document.documentElement;
  const system = window.matchMedia?.("(prefers-color-scheme: dark)");
  const look = { theme: "light", accent: "clay" };
  for (const key in options) look[key] = options[key].default;
  let photo = "";

  try {
    const saved = JSON.parse(localStorage.getItem(lookKey) || "{}");
    if (themes[saved.theme]) look.theme = saved.theme;
    if (accents[saved.accent]) look.accent = saved.accent;
    for (const key in options) if (saved[key] in options[key].values) look[key] = saved[key];
    photo = localStorage.getItem(photoKey) || "";
  } catch { /* 存储不可用时用默认外观 */ }

  function resolved() {
    return look.theme === "system" ? (system?.matches ? "dark" : "light") : look.theme;
  }

  // 「无」以 none 存储，取值时变成空字符串。
  function value(key) {
    return look[key] === "none" ? "" : look[key];
  }

  function flag(name, on) {
    if (on) root.setAttribute(name, "");
    else root.removeAttribute(name);
  }

  function apply() {
    const mode = resolved();
    const accent = accents[look.accent];
    const [family, style, weight] = numerals[look.numeral];
    root.dataset.theme = mode;
    root.dataset.accent = look.accent;
    root.style.setProperty("--clay", accent.color);
    root.style.setProperty("--clay-deep", mode === "dark" ? accent.light : accent.deep);
    root.style.setProperty("--numeral", `${family}, "Playfair Display", Georgia, serif`);
    root.style.setProperty("--numeral-style", style);
    root.style.setProperty("--numeral-weight", String(weight));
    root.style.setProperty("--mark", value("watermark") ? `"${value("watermark")}"` : "\"\"");
    root.style.setProperty("--frost", String(frostLevels[look.frost]));
    root.style.setProperty("--veil", String(veilLevels[look.veil]));
    root.style.fontSize = textSizes[look.textSize];
    root.style.colorScheme = mode;
    flag("data-still", look.wiggle === "off");
    flag("data-calm", look.entrance === "off");
    document.querySelector('meta[name="theme-color"]')
      ?.setAttribute("content", mode === "dark" ? "#1b1815" : "#f3efe6");
    const backdrop = document.querySelector(".backdrop");
    if (backdrop) backdrop.style.backgroundImage = photo ? `url("${photo}")` : "";
    root.classList.toggle("photo", Boolean(photo));
  }

  function store() {
    try { localStorage.setItem(lookKey, JSON.stringify(look)); } catch { /* 忽略 */ }
  }

  function set(key, next) {
    const valid = key === "theme" ? next in themes : key === "accent" ? next in accents
      : key in options && next in options[key].values;
    if (!valid) return;
    look[key] = next;
    store();
    apply();
  }

  // 把照片缩到长边 1600 像素、转成 JPEG，存为 data URL（CSP 不允许 blob:）。
  function readFile(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = () => reject(new Error("无法读取这张照片。"));
      reader.readAsDataURL(file);
    });
  }

  function loadImage(source) {
    return new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve(image);
      image.onerror = () => reject(new Error("这张照片的格式暂不支持，请换一张 JPG 或 PNG。"));
      image.src = source;
    });
  }

  async function setPhoto(file) {
    if (!file || !file.type.startsWith("image/")) throw new Error("请选择一张图片。");
    const image = await loadImage(await readFile(file));
    const scale = Math.min(1, 1600 / Math.max(image.naturalWidth, image.naturalHeight));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
    canvas.getContext("2d").drawImage(image, 0, 0, canvas.width, canvas.height);
    let quality = .84;
    let encoded = canvas.toDataURL("image/jpeg", quality);
    while (encoded.length > 1_400_000 && quality > .5) {
      quality -= .1;
      encoded = canvas.toDataURL("image/jpeg", quality);
    }
    try {
      localStorage.setItem(photoKey, encoded);
    } catch {
      throw new Error("本机存储空间不足，照片没能保存。");
    }
    photo = encoded;
    apply();
  }

  function clearPhoto() {
    photo = "";
    try { localStorage.removeItem(photoKey); } catch { /* 忽略 */ }
    apply();
  }

  system?.addEventListener?.("change", () => { if (look.theme === "system") apply(); });
  apply();
  document.addEventListener("DOMContentLoaded", apply);

  window.CijianLook = {
    accents,
    themes,
    options,
    lively,
    get theme() { return look.theme; },
    get accent() { return look.accent; },
    get photo() { return photo; },
    get(key) { return look[key]; },
    value,
    set,
    setPhoto,
    clearPhoto
  };
})();
