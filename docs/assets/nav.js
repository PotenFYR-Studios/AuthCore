/* ============================================================================
 * AuthCore docs - shared version bar.
 *
 * Injected on every page (main page + every version-specific doc set) so all
 * versions of the documentation live under one site and stay cross-linked:
 *
 *   <script src="/docs/assets/nav.js" defer></script>
 *
 * The site is served at the domain root (authcore.potenfyr.in), so all paths
 * are root-relative and match the canonical URLs:
 *   - main (common) page:            /index.html
 *   - version registry:              /docs/versions.json
 *   - version docs:                  /docs/<version>/...
 *
 * The bar lists every version registered in docs/versions.json (newest first)
 * and links back to the common main page.
 * ==========================================================================*/
(function () {
  "use strict";

  var script = document.currentScript;
  var current = script ? (script.getAttribute("data-version") || "") : "";

  var CSS = [
    "#ac-vbar{position:sticky;top:0;z-index:999;background:#0b0f17ee;backdrop-filter:blur(6px);",
    "border-bottom:1px solid #1f2a3d;padding:8px 20px;display:flex;align-items:center;gap:14px;",
    "font-family:ui-sans-serif,system-ui,'Segoe UI',Roboto,sans-serif;font-size:13.5px;color:#d8e1f0}",
    "#ac-vbar a{color:#38bdf8;text-decoration:none;font-weight:600}",
    "#ac-vbar a:hover{text-decoration:underline}",
    "#ac-vbar .ac-sep{color:#3c4a63}",
    "#ac-vbar .ac-label{color:#7f8ca6;font-size:11.5px;text-transform:uppercase;letter-spacing:.07em}",
    "#ac-vbar select{background:#121926;color:#d8e1f0;border:1px solid #1f2a3d;border-radius:8px;",
    "padding:4px 10px;font-size:13px;cursor:pointer}",
    "#ac-vbar .ac-badge{background:#14311f;color:#22c55e;border:1px solid #1c3a2a;border-radius:20px;",
    "padding:2px 10px;font-size:11px;font-weight:700}"
  ].join("");

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }

  function render(versions) {
    var bar = document.createElement("div");
    bar.id = "ac-vbar";

    var style = document.createElement("style");
    style.textContent = CSS;
    bar.appendChild(style);

    var home = el("a", null, "\ud83d\udda3\ufe0f AuthCore Docs");
    home.href = "/index.html";
    bar.appendChild(home);
    bar.appendChild(el("span", "ac-sep", "|"));

    if (current) {
      bar.appendChild(el("span", "ac-label", "docs for"));
      var sel = document.createElement("select");
      versions.forEach(function (v) {
        var o = document.createElement("option");
        o.value = v.docs;
        o.textContent = "v" + v.version + (v.status === "deprecated" ? " (deprecated)" : "");
        if (v.version === current) o.selected = true;
        sel.appendChild(o);
      });
      sel.addEventListener("change", function () { window.location.href = sel.value; });
      bar.appendChild(sel);
      var me = versions.filter(function (v) { return v.version === current; })[0];
      if (me && me.status === "stable") bar.appendChild(el("span", "ac-badge", "Latest"));
    } else {
      bar.appendChild(el("span", "ac-label", "common documentation (all versions)"));
    }
    bar.appendChild(el("span", "ac-sep", "|"));
    var gh = el("a", null, "GitHub \u2197");
    gh.href = "https://github.com/DawnOfDedSec/AuthCore";
    gh.target = "_blank";
    gh.rel = "noopener";
    bar.appendChild(gh);

    document.body.insertBefore(bar, document.body.firstChild);
  }

  fetch("/docs/versions.json", { cache: "no-cache" })
    .then(function (r) { return r.json(); })
    .then(render)
    .catch(function () { render([]); });
})();
