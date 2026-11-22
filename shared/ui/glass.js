(function () {
  "use strict";

  // 液态玻璃：高光跟随指针，按下时在触点处亮起。
  const app = document.getElementById("app");
  const surfaces = ".card, button";

  function light(event) {
    const target = event.target.closest?.(surfaces);
    if (!target) return;
    const box = target.getBoundingClientRect();
    target.style.setProperty("--mx", (event.clientX - box.left) + "px");
    target.style.setProperty("--my", (event.clientY - box.top) + "px");
  }

  document.addEventListener("pointermove", light, { passive: true });
  document.addEventListener("pointerdown", event => {
    light(event);
    const target = event.target.closest?.(surfaces);
    if (!target) return;
    target.classList.add("lit");
    const release = () => {
      setTimeout(() => target.classList.remove("lit"), 260);
      document.removeEventListener("pointerup", release);
      document.removeEventListener("pointercancel", release);
    };
    document.addEventListener("pointerup", release);
    document.addEventListener("pointercancel", release);
  }, { passive: true });

  // 换页时逐块浮现；同一页内的局部刷新（标记、翻页）不重播。
  let signature = "";
  new MutationObserver(() => {
    const heading = app.querySelector("h1")?.textContent ?? "";
    const word = app.querySelector(".learn-title")?.textContent ?? "";
    const next = heading + "|" + word + "|" + (app.querySelector("header .muted")?.textContent ?? "");
    if (next === signature) return;
    signature = next;
    app.classList.remove("enter");
    void app.offsetWidth;
    app.classList.add("enter");
  }).observe(app, { childList: true });
})();
