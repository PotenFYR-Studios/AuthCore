/* ==========================================================================
 * AuthCore Docs: navigation toolkit (shared, org standard)
 *   • copy buttons on code blocks
 *   • scrollspy: highlights the active TOC entry while scrolling
 *   • Ctrl/⌘+K command palette: jump to any section or doc page
 *   • prev/next pagination footer
 *   • keyboard: [ and ] move between pages, Esc closes palette
 * ========================================================================== */
(function () {
  "use strict";

  var PAGES = [
    { id: "index",      title: "Documentation Hub" },
    { id: "guide",      title: "Admin Guide" },
    { id: "flows",      title: "Auth Flows" },
    { id: "config",     title: "Configuration" },
    { id: "proxy",      title: "Proxy Support" },
    { id: "webpanel",   title: "Web Panel" },
    { id: "security",   title: "Security Model" },
    { id: "26x",        title: "26.x Builds" },
    { id: "api",        title: "Developer API" },
    { id: "development",title: "Development" },
    { id: "changelog",  title: "Changelog" }
  ];
  var BASE = "/docs/1.0.0/";

  var currentId = (function () {
    var m = location.pathname.match(/\/docs\/[^/]+\/([a-z0-9x]+)\.html$/i);
    if (m) return m[1].toLowerCase();
    if (location.pathname.match(/\/docs\/?$/)) return "index";
    return null;
  })();

  /* ---------- 1. copy buttons on code blocks ---------- */
  function initCopyButtons() {
    document.querySelectorAll("main pre").forEach(function (pre) {
      if (pre.querySelector(".copy-btn")) return;
      var btn = document.createElement("button");
      btn.className = "copy-btn";
      btn.type = "button";
      btn.setAttribute("aria-label", "Copy code");
      btn.textContent = "Copy";
      btn.addEventListener("click", function () {
        var code = pre.querySelector("code");
        navigator.clipboard.writeText(code ? code.innerText : pre.innerText).then(function () {
          btn.textContent = "Copied!";
          btn.classList.add("ok");
          setTimeout(function () { btn.textContent = "Copy"; btn.classList.remove("ok"); }, 1400);
        });
      });
      pre.appendChild(btn);
    });
  }

  /* ---------- 2. scrollspy for the TOC ---------- */
  function initScrollspy() {
    var links = Array.prototype.slice.call(document.querySelectorAll('.toc a[href^="#"]'));
    if (!links.length || !("IntersectionObserver" in window)) return;
    var map = {};
    links.forEach(function (a) {
      var id = decodeURIComponent(a.getAttribute("href").slice(1));
      var h = document.getElementById(id);
      if (h) map[id] = a;
    });
    var obs = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) {
          links.forEach(function (a) { a.classList.remove("active"); a.removeAttribute("aria-current"); });
          var a = map[en.target.id];
          if (a) { a.classList.add("active"); a.setAttribute("aria-current", "true"); }
        }
      });
    }, { rootMargin: "-80px 0px -65% 0px" });
    Object.keys(map).forEach(function (id) { obs.observe(document.getElementById(id)); });
  }

  /* ---------- 3. prev / next pagination ---------- */
  function initPagination() {
    if (!currentId || currentId === "index") return;
    var i = PAGES.findIndex(function (p) { return p.id === currentId; });
    if (i < 0) return;
    var prev = PAGES[i - 1], next = PAGES[i + 1];
    var nav = document.createElement("nav");
    nav.className = "page-nav";
    var html = "";
    if (prev) html += '<a class="pn prev" href="' + BASE + prev.id + '.html"><span>Previous</span><strong>' + prev.title + "</strong></a>";
    else html += '<span class="pn ghost"></span>';
    if (next) html += '<a class="pn next" href="' + BASE + next.id + '.html"><span>Next</span><strong>' + next.title + "</strong></a>";
    else html += '<span class="pn ghost"></span>';
    nav.innerHTML = html;
    var main = document.querySelector("main");
    (main || document.body).appendChild(nav);
  }

  /* ---------- 4. command palette (Ctrl/⌘ + K) ---------- */
  function initPalette() {
    var overlay = document.createElement("div");
    overlay.className = "cmdk-overlay";
    overlay.hidden = true;
    overlay.innerHTML =
      '<div class="cmdk" role="dialog" aria-label="Quick navigation">' +
      '<input class="cmdk-input" type="text" placeholder="Jump to a section or page…  (Esc to close)" aria-label="Search docs">' +
      '<div class="cmdk-list"></div>' +
      '<div class="cmdk-hint">↑↓ navigate · Enter open · Esc close</div>' +
      "</div>";
    document.body.appendChild(overlay);
    var input = overlay.querySelector(".cmdk-input");
    var list = overlay.querySelector(".cmdk-list");
    var items = [];

    function buildItems() {
      items = PAGES.filter(function (p) { return p.id !== currentId; })
        .map(function (p) { return { label: p.title, kind: "page", href: BASE + p.id + ".html" }; });
      document.querySelectorAll("main h2[id], main h3[id]").forEach(function (h) {
        items.unshift({ label: h.textContent.trim(), kind: "section", href: "#" + h.id });
      });
    }

    function render(q) {
      q = (q || "").toLowerCase();
      var shown = items.filter(function (it) { return it.label.toLowerCase().indexOf(q) !== -1; }).slice(0, 14);
      list.innerHTML = shown.map(function (it, i) {
        return '<a class="cmdk-item' + (i === 0 ? " sel" : "") + '" data-kind="' + it.kind + '" href="' + it.href + '">' +
          '<span class="k">' + (it.kind === "section" ? "§" : "→") + "</span>" + it.label + "</a>";
      }).join("") || '<div class="cmdk-empty">No matches</div>';
      list._shown = shown;
    }

    function open() { buildItems(); render(""); overlay.hidden = false; input.value = ""; input.focus(); document.body.style.overflow = "hidden"; }
    function close() { overlay.hidden = true; document.body.style.overflow = ""; }

    document.addEventListener("keydown", function (e) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") { e.preventDefault(); overlay.hidden ? open() : close(); return; }
      if (e.key === "Escape" && !overlay.hidden) close();
      if (overlay.hidden) {
        if (e.key === "]" || e.key === "[") {
          var i = PAGES.findIndex(function (p) { return p.id === currentId; });
          if (i < 0) return;
          var t = PAGES[e.key === "]" ? i + 1 : i - 1];
          if (t) location.href = BASE + t.id + ".html";
        }
        return;
      }
      var sel = list.querySelector(".cmdk-item.sel");
      var all = Array.prototype.slice.call(list.querySelectorAll(".cmdk-item"));
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        if (!sel) return;
        var ni = all.indexOf(sel) + (e.key === "ArrowDown" ? 1 : -1);
        if (ni >= 0 && ni < all.length) { sel.classList.remove("sel"); all[ni].classList.add("sel"); all[ni].scrollIntoView({ block: "nearest" }); }
      } else if (e.key === "Enter" && sel) {
        e.preventDefault(); sel.click();
      }
    });
    input.addEventListener("input", function () { render(input.value); });
    list.addEventListener("click", function (e) {
      if (e.target.closest(".cmdk-item") && (e.target.closest(".cmdk-item").getAttribute("href") || "").charAt(0) === "#") close();
    });
    overlay.addEventListener("click", function (e) { if (e.target === overlay) close(); });

    /* floating trigger button (bottom right) */
    var fab = document.createElement("button");
    fab.className = "cmdk-fab";
    fab.type = "button";
    fab.innerHTML = "⌘K";
    fab.title = "Quick navigation (Ctrl+K)";
    fab.addEventListener("click", open);
    document.body.appendChild(fab);
  }

  function boot() {
    initCopyButtons();
    initScrollspy();
    initPagination();
    initPalette();
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
