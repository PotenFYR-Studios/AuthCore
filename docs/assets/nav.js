/* ============================================================================
 * AuthCore docs - shared top bar.
 *
 * Injected on every static page (main page + any legacy version-specific doc
 * set) so all versions of the documentation live under one site and stay
 * cross-linked:
 *
 *   <script src="/docs/assets/nav.js" defer></script>
 *
 * The bar mirrors the React docs app Topbar (docs/app/src/App.tsx):
 *   AuthCore.docs | Docs   ......   [ Search ⌘K ]  Website Discord GitHub
 *
 * The site is served at the domain root (authcore.potenfyr.in), so all paths
 * are root-relative and match the canonical URLs:
 *   - main (common) page:            /index.html
 *   - version registry:              /docs/versions.json
 *   - version docs:                  /docs/<version>/...
 * ==========================================================================*/
(function () {
  "use strict";

  var script = document.currentScript;
  var current = script ? (script.getAttribute("data-version") || "") : "";

  var CSS = [
    "#ac-vbar{position:sticky;top:0;z-index:999;display:flex;align-items:center;gap:12px;height:56px;padding:0 20px;background:rgba(11,13,20,.8);backdrop-filter:blur(14px) saturate(1.4);border-bottom:1px solid rgba(255,255,255,.08);box-sizing:border-box;font-family:Inter,ui-sans-serif,system-ui,'Segoe UI',sans-serif;color:#e8eaf2}",
    "#ac-vbar a{text-decoration:none;transition:color .15s}",
    "#ac-vbar a:focus-visible,#ac-vbar select:focus-visible{outline:2px solid #8b5cf6;outline-offset:2px;border-radius:6px}",
    "#ac-vbar .ac-brand{font-size:14px;font-weight:650;color:#e8eaf2;white-space:nowrap}",
    "#ac-vbar .ac-brand:hover{color:#fff}",
    "#ac-vbar .ac-brand .ac-dot{color:#ec4899}",
    "#ac-vbar .ac-sep{color:rgba(255,255,255,.08)}",
    "#ac-vbar .ac-docs{font-size:11px;letter-spacing:.1em;text-transform:uppercase;color:#6a7089;white-space:nowrap}",
    "#ac-vbar .ac-docs:hover{color:#c4b5fd}",
    "#ac-vbar .ac-label{font-size:11px;letter-spacing:.1em;text-transform:uppercase;color:#6a7089}",
    "#ac-vbar select{background:#151828;color:#e8eaf2;border:1px solid rgba(139,92,246,.16);border-radius:8px;padding:4px 10px;font-size:12px;cursor:pointer;transition:border-color .15s}",
    "#ac-vbar select:hover{border-color:rgba(139,92,246,.5)}",
    "#ac-vbar .ac-search{margin-left:auto;display:flex;align-items:center;gap:6px;border:1px solid rgba(255,255,255,.08);background:rgba(255,255,255,.03);border-radius:8px;padding:6px 10px;font-size:12px;color:#9aa0b4;cursor:pointer;white-space:nowrap;transition:border-color .15s,color .15s}",
    "#ac-vbar .ac-search:hover{border-color:rgba(139,92,246,.5);color:#e8eaf2}",
    "#ac-vbar .ac-search svg{display:block}",
    "#ac-vbar .ac-search kbd{font-family:'Fira Code',ui-monospace,SFMono-Regular,Consolas,monospace;font-size:10px;color:#6a7089;border:1px solid rgba(255,255,255,.08);border-radius:4px;padding:1px 5px}",
    "#ac-vbar .ac-right{margin-left:auto;display:flex;align-items:center;gap:16px;white-space:nowrap}",
    "#ac-vbar .ac-right a{font-size:12px;font-weight:500;color:#9aa0b4}",
    "#ac-vbar .ac-right a:hover{color:#c4b5fd}",
    "#ac-vbar .ac-right a.ac-gh:hover{color:#fff}",
    "@media (max-width:640px){#ac-vbar{gap:10px;padding:0 14px}#ac-vbar .ac-search span,#ac-vbar .ac-search kbd{display:none}#ac-vbar .ac-right{gap:12px}}",
    "@media (max-width:460px){#ac-vbar .ac-hide-sm{display:none}}",
    "@media (prefers-reduced-motion:reduce){#ac-vbar a,#ac-vbar select{transition:none}}"
  ].join("");

  var SEARCH_ICON =
    '<svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/></svg>';

  var DOCS_HUB = "/docs/1.0.0/index.html";

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }

  function render(versions) {
    var bar = el("div");
    bar.id = "ac-vbar";

    var style = document.createElement("style");
    style.textContent = CSS;
    bar.appendChild(style);

    var home = el("a", "ac-brand");
    home.innerHTML = 'AuthCore<span class="ac-dot">.</span>docs';
    home.href = "/index.html";
    bar.appendChild(home);

    var sep = el("span", "ac-sep", "|");
    sep.setAttribute("aria-hidden", "true");
    bar.appendChild(sep);

    if (current) {
      bar.appendChild(el("span", "ac-label", "Docs"));
      var sel = document.createElement("select");
      sel.setAttribute("aria-label", "Documentation version");
      versions.forEach(function (v) {
        var o = document.createElement("option");
        o.value = v.docs;
        o.textContent = "v" + v.version + (v.status === "deprecated" ? " (deprecated)" : "");
        if (v.version === current) o.selected = true;
        sel.appendChild(o);
      });
      sel.addEventListener("change", function () { window.location.href = sel.value; });
      bar.appendChild(sel);
    } else {
      var newest = versions.length ? versions[0].docs : DOCS_HUB;
      var docsLink = el("a", "ac-docs", "Docs");
      docsLink.href = newest;
      bar.appendChild(docsLink);
    }

    var search = el("a", "ac-search");
    search.href = DOCS_HUB;
    search.setAttribute("aria-label", "Search the documentation");
    search.innerHTML = SEARCH_ICON + "<span>Search</span>";
    var kbd = el("kbd", null, "⌘K");
    kbd.setAttribute("aria-hidden", "true");
    search.appendChild(kbd);
    bar.appendChild(search);

    var right = el("span", "ac-right");
    var ws = el("a", "ac-hide-sm", "Website");
    ws.href = "https://potenfyr.in";
    right.appendChild(ws);
    var dc = el("a", "ac-hide-sm", "Discord");
    dc.href = "https://discord.com/invite/zUaN2FPBec";
    right.appendChild(dc);
    var gh = el("a", "ac-gh", "GitHub");
    gh.href = "https://github.com/PotenFYR-Studios/AuthCore";
    gh.target = "_blank";
    gh.rel = "noopener";
    right.appendChild(gh);
    bar.appendChild(right);

    document.body.insertBefore(bar, document.body.firstChild);
  }

  /* Ctrl/Cmd + K jumps into the docs hub, where the command palette lives. */
  document.addEventListener("keydown", function (e) {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
      e.preventDefault();
      window.location.href = DOCS_HUB;
    }
  });

  fetch("/docs/versions.json", { cache: "no-cache" })
    .then(function (r) { return r.json(); })
    .then(render)
    .catch(function () { render([]); });
})();
