// Shared site shell: dark black/red fortress-cyber theme, top nav, sidebar TOC,
// scrollspy, scroll progress, copy buttons, back-to-top, used by every generated page.

export const SITE_TITLE = "AuthCore Docs";
export const REPO = "PotenFYR-Studios/AuthCore";
export const BRANCH = "main";

export const NAV = [
  { slug: "guide", href: "guide.html", label: "Admin Guide", icon: "🧭" },
  { slug: "flows", href: "flows.html", label: "Flows", icon: "🔀" },
  { slug: "config", href: "config.html", label: "Config", icon: "⚙️" },
  { slug: "proxy", href: "proxy.html", label: "Proxy", icon: "🔁" },
  { slug: "webpanel", href: "webpanel.html", label: "Web Panel", icon: "🌐" },
  { slug: "security", href: "security.html", label: "Security", icon: "🛡️" },
  { slug: "26x", href: "26x.html", label: "26.x", icon: "📦" },
  { slug: "api", href: "api.html", label: "API", icon: "🔌" },
  { slug: "development", href: "development.html", label: "Dev", icon: "⚙️" },
  { slug: "changelog", href: "changelog.html", label: "Changelog", icon: "📜" },
];

export const DOCS = [
  { slug: "guide", title: "Server Admin Guide", icon: "🧭", description: "START HERE, jar selection, install, config walkthrough, auth flows, commands, troubleshooting + learning path." },
  { slug: "flows", title: "Authentication Flows", icon: "🔀", description: "Every flow explained step by step with the functions involved: join, limbo, register, login, resume, premium verification, migrations." },
  { slug: "config", title: "Configuration Reference", icon: "⚙️", description: "Every setting, default and use-case, the single source of truth for settings.conf." },
  { slug: "proxy", title: "Proxy Support", icon: "🔁", description: "Velocity / BungeeCord forwarding, modern identity (HMAC), proxy-side auth." },
  { slug: "webpanel", title: "Web Admin Panel", icon: "🌐", description: "HTTP/HTTPS setup, REST reference, token auth, curl examples." },
  { slug: "security", title: "Security Model", icon: "🛡️", description: "Threat analysis (OWASP + Minecraft) and every defense in depth." },
  { slug: "26x", title: "26.1 - 26.2 Builds", icon: "📦", description: "Range jars, the unobfuscated era, architecture and verification." },
  { slug: "api", title: "Developer API", icon: "🔌", description: "AuthCoreApi, database schema, integration guide for plugin authors." },
  { slug: "development", title: "Development & Architecture", icon: "⚙️", description: "Build system, multi-version / multi-loader management, testing." },
  { slug: "changelog", title: "Changelog", icon: "📜", description: "Full release history, from the first alpha to 1.0.0." },
];

// GitHub-style heading anchor (matches github.com's generated anchors,
// including the double-hyphen quirk for em-dash headings).
export function ghAnchor(text) {
  return text
    .toLowerCase()
    .replace(/<[^>]*>/g, "")
    .replace(/[^\w\s-]/g, "")
    .trim()
    .replace(/\s/g, "-");
}

export function navHTML(activeSlug, basePrefix) {
  const home = `${basePrefix}index.html`;
  const items = NAV.map(
    (n) =>
      `<a class="nav-link${n.slug === activeSlug ? " active" : ""}" href="${basePrefix}docs/1.0.0/${n.href}"><span class="nav-icon">${n.icon}</span>${n.label}</a>`,
  ).join("\n      ");
  return `
  <header class="site-header">
    <a class="brand" href="${home}">
      <span class="brand-mark">🏰🔐</span>
      <span class="brand-text">AuthCore<span class="brand-dot">.</span>docs</span>
    </a>
    <button class="nav-toggle" aria-label="Toggle navigation" aria-expanded="false">
      <span></span><span></span><span></span>
    </button>
    <nav class="site-nav">
      <a class="nav-link${activeSlug === "home" ? " active" : ""}" href="${home}">Home</a>
      <a class="nav-link${activeSlug === "hub" ? " active" : ""}" href="${basePrefix}docs/1.0.0/index.html">Docs Hub</a>
      ${items}
      <a class="nav-link nav-gh" href="https://github.com/${REPO}" target="_blank" rel="noopener">GitHub ↗</a>
    </nav>
  </header>`;
}

export function tocHTML(headings) {
  if (headings.length < 3) return "";
  const rows = headings
    .map(
      (h) =>
        `<li class="toc-item toc-${h.level}"><a href="#${h.anchor}">${h.text}</a></li>`,
    )
    .join("");
  const count = headings.length + (headings.length === 1 ? " topic" : " topics");
  return `<aside class="toc" id="toc">
    <div class="toc-head"><div class="toc-title">On this page</div><span class="toc-count">${count}</span></div>
    <ul>${rows}</ul>
    <div class="toc-progress"><span></span></div>
    <a class="toc-top" href="#top">Back to top</a>
  </aside>`;
}

export function pageShell({ title, description, canonical, body, toc, active, basePrefix }) {
  const nav = navHTML(active, basePrefix);
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} - ${SITE_TITLE}</title>
<meta name="description" content="${description}">
<meta property="og:title" content="${title} - ${SITE_TITLE}">
<meta property="og:description" content="${description}">
<meta property="og:type" content="website">
<link rel="canonical" href="${canonical}">
<style>
  :root {
    --bg0: #060607;
    --bg1: #0c0a0a;
    --bg2: #1a0c0c;
    --panel: rgba(14, 8, 8, 0.78);
    --panel-2: rgba(70, 12, 12, 0.32);
    --accent: #ef4444;
    --accent-2: #f87171;
    --accent-3: #fca5a5;
    --green: #22c55e;
    --text: #e9e7ea;
    --muted: #a29aa0;
    --border: rgba(239, 68, 68, 0.24);
    --radius: 16px;
    --font: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
    --mono: ui-monospace, SFMono-Regular, "Cascadia Code", Consolas, "Liberation Mono", monospace;
  }
  * { box-sizing: border-box; }
  html { scroll-behavior: smooth; }
  body {
    margin: 0;
    min-height: 100vh;
    color: var(--text);
    font-family: var(--font);
    font-size: 16px;
    line-height: 1.65;
    background:
      radial-gradient(1200px 420px at 50% -100px, rgba(239, 68, 68, 0.13) 0%, rgba(239, 68, 68, 0) 65%),
      linear-gradient(160deg, var(--bg0) 0%, var(--bg1) 45%, var(--bg2) 140%);
    background-attachment: fixed;
    overflow-x: hidden;
  }

  /* ---------- scroll progress ---------- */
  #progress {
    position: fixed; top: 0; left: 0; height: 3px; width: 0;
    background: linear-gradient(90deg, var(--accent), var(--accent-3), #fff);
    box-shadow: 0 0 12px rgba(239, 68, 68, 0.8);
    z-index: 1000; border-radius: 0 3px 3px 0;
  }

  /* ---------- header ---------- */
  .site-header {
    position: sticky; top: 0; z-index: 500;
    display: flex; align-items: center; gap: 18px;
    padding: 10px 22px;
    background: rgba(6, 6, 7, 0.74);
    backdrop-filter: blur(14px);
    border-bottom: 1px solid var(--border);
    box-shadow: 0 8px 30px rgba(0, 0, 0, 0.35);
  }
  .brand { display: flex; align-items: center; gap: 10px; text-decoration: none; color: #fff; font-weight: 700; letter-spacing: 0.2px; white-space: nowrap; }
  .brand-mark { font-size: 1.25em; filter: drop-shadow(0 0 8px rgba(239,68,68,.7)); }
  .brand-text { font-size: 1.05em; }
  .brand-dot { color: var(--accent-3); }
  .site-nav { display: flex; flex-wrap: wrap; gap: 2px; flex: 1; justify-content: center; }
  .nav-link {
    color: var(--muted); text-decoration: none; font-size: 0.86em; font-weight: 500;
    padding: 6px 10px; border-radius: 8px; transition: color .18s, background .18s, box-shadow .18s;
    position: relative;
  }
  .nav-link .nav-icon { margin-right: 4px; font-size: 0.92em; }
  .nav-link:hover { color: #fff; background: rgba(239, 68, 68, 0.12); }
  .nav-link.active { color: #fff; background: linear-gradient(135deg, rgba(239,68,68,.28), rgba(70,12,12,.5)); box-shadow: inset 0 0 0 1px rgba(239,68,68,.4), 0 0 14px rgba(239,68,68,.3); }
  .nav-gh { margin-left: 6px; color: var(--accent-3); }
  .nav-toggle { display: none; background: none; border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; cursor: pointer; margin-left: auto; }
  .nav-toggle span { display: block; width: 18px; height: 2px; background: var(--accent-3); margin: 3px 0; border-radius: 2px; transition: transform .2s; }
  .nav-toggle[aria-expanded="true"] span:nth-child(1) { transform: translateY(5px) rotate(45deg); }
  .nav-toggle[aria-expanded="true"] span:nth-child(2) { opacity: 0; }
  .nav-toggle[aria-expanded="true"] span:nth-child(3) { transform: translateY(-5px) rotate(-45deg); }
  @media (max-width: 900px) {
    .nav-toggle { display: block; }
    .site-nav {
      display: none; position: absolute; top: 100%; left: 0; right: 0;
      flex-direction: column; align-items: stretch; gap: 4px;
      background: rgba(6, 6, 7, 0.97); backdrop-filter: blur(14px);
      border-bottom: 1px solid var(--border); padding: 10px 14px;
    }
    .site-nav.open { display: flex; }
  }

  /* ---------- layout (wide, content spreads out) ---------- */
  .layout { display: grid; grid-template-columns: 300px minmax(0, 1fr); gap: 34px; max-width: 1580px; margin: 0 auto; padding: 34px 28px 70px; align-items: start; }
  /* pages without a sidebar TOC (home, docs hub) span the full width */
  .layout > :only-child { grid-column: 1 / -1; }
  .layout:has(> :only-child) { max-width: 1500px; }
  @media (max-width: 1120px) { .layout { grid-template-columns: 1fr; } }

  /* ---------- sidebar TOC (enhanced navigation) ---------- */
  .toc {
    position: sticky; top: 82px; align-self: start;
    max-height: calc(100vh - 104px); overflow-y: auto;
    background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius);
    padding: 16px 14px 14px;
    backdrop-filter: blur(10px);
    box-shadow: 0 10px 34px rgba(0, 0, 0, 0.4), inset 0 1px 0 rgba(255, 255, 255, 0.05);
    animation: fadeUp .5s ease both;
    scrollbar-width: thin; scrollbar-color: rgba(239,68,68,.45) transparent;
  }
  .toc::-webkit-scrollbar { width: 6px; }
  .toc::-webkit-scrollbar-thumb { background: rgba(239,68,68,.45); border-radius: 6px; }
  .toc-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 0 4px 10px; }
  .toc-title { font-size: 0.72em; font-weight: 700; letter-spacing: 0.16em; text-transform: uppercase; color: var(--accent-3); }
  .toc-count { font-size: 0.68em; color: var(--muted); letter-spacing: 0.04em; background: rgba(239,68,68,.14); border: 1px solid var(--border); border-radius: 999px; padding: 2px 8px; }
  .toc ul { list-style: none; margin: 0; padding: 0; }
  .toc-item { margin: 1px 0; }
  .toc-item a {
    display: flex; align-items: center; gap: 8px; color: var(--muted); text-decoration: none; font-size: 0.84em;
    padding: 5px 10px 5px 8px; border-left: 2px solid transparent; border-radius: 0 8px 8px 0;
    transition: color .15s, border-color .15s, background .15s, padding-left .15s;
    position: relative;
  }
  .toc-item a::before { content: ""; width: 4px; height: 4px; border-radius: 50%; background: var(--muted); flex: 0 0 auto; transition: background .15s, box-shadow .15s; }
  .toc-item a:hover { color: #fff; background: rgba(239, 68, 68, 0.1); padding-left: 12px; }
  .toc-item a:hover::before { background: var(--accent-3); box-shadow: 0 0 8px rgba(239,68,68,.7); }
  .toc-item.toc-3 a { padding-left: 24px; font-size: 0.8em; }
  .toc-item.toc-3 a:hover { padding-left: 28px; }
  .toc-item a.active { color: #fff; border-left-color: var(--accent); background: linear-gradient(90deg, rgba(239,68,68,.22), rgba(239,68,68,.02)); font-weight: 600; }
  .toc-item a.active::before { background: var(--accent); box-shadow: 0 0 8px rgba(239,68,68,.8); }
  .toc-progress { height: 3px; border-radius: 3px; background: rgba(239,68,68,.15); margin: 10px 4px 4px; overflow: hidden; }
  .toc-progress > span { display: block; height: 100%; width: 0; background: linear-gradient(90deg, var(--accent), var(--accent-3)); box-shadow: 0 0 10px rgba(239,68,68,.6); transition: width .1s linear; }
  .toc-top { display: block; margin: 10px 4px 0; text-align: center; font-size: 0.74em; color: var(--accent-2); text-decoration: none; border: 1px solid var(--border); border-radius: 8px; padding: 5px 8px; transition: background .15s, color .15s; }
  .toc-top:hover { background: rgba(239,68,68,.12); color: #fff; }
  .toc-top::before { content: "↑ "; }

  /* ---------- content ---------- */
  article {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: calc(var(--radius) + 4px);
    padding: 36px 44px 52px;
    box-shadow: 0 20px 64px rgba(0, 0, 0, 0.5), 0 0 100px rgba(220, 38, 38, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.05);
    backdrop-filter: blur(8px);
    animation: fadeUp .55s ease both;
  }
  @keyframes fadeUp { from { opacity: 0; transform: translateY(16px); } to { opacity: 1; transform: none; } }
  article > :first-child { margin-top: 0; }
  article h1, article h2, article h3, article h4 { line-height: 1.3; scroll-margin-top: 90px; }
  article h1 {
    font-size: 2em; font-weight: 800; margin: 0 0 18px; padding-bottom: 14px;
    color: #f8fafc;
    border-bottom: 1px solid var(--border);
    position: relative;
  }
  article h1::after {
    content: ""; display: block; position: absolute; left: 0; right: 0; bottom: -1px;
    height: 2px; border-radius: 2px;
    background: linear-gradient(90deg, var(--accent-3), var(--accent), transparent);
    box-shadow: 0 0 12px rgba(239, 68, 68, 0.5);
  }
  article h2 {
    font-size: 1.38em; color: #f8fafc; margin: 1.9em 0 0.6em; padding-bottom: 8px;
    border-bottom: 1px solid rgba(239, 68, 68, 0.18);
    animation: fadeUp .5s ease both;
  }
  article h2::before {
    content: ""; display: inline-block; width: 7px; height: 0.95em; margin-right: 11px;
    background: linear-gradient(180deg, var(--accent-3), var(--accent)); border-radius: 4px;
    box-shadow: 0 0 14px rgba(239, 68, 68, 0.7); vertical-align: -0.08em;
  }
  article h3 { font-size: 1.12em; color: var(--accent-3); margin: 1.5em 0 0.5em; }
  article h4 { font-size: 1em; color: #fee2e2; }
  article p { margin: 0.85em 0; }
  article strong { color: #f8fafc; }
  article a {
    color: var(--accent-2); text-decoration: none;
    background-image: linear-gradient(90deg, var(--accent-3), var(--accent));
    background-size: 0% 1.5px; background-position: 0 100%; background-repeat: no-repeat;
    transition: background-size .25s, color .15s;
  }
  article a:hover { color: #fff; background-size: 100% 1.5px; }
  article hr {
    border: 0; height: 1px; margin: 2.2em 0;
    background: linear-gradient(90deg, transparent, var(--accent), transparent);
  }
  article blockquote {
    margin: 1.1em 0; padding: 0.7em 1.2em;
    border-left: 3px solid var(--accent); border-radius: 0 12px 12px 0;
    background: linear-gradient(90deg, rgba(239, 68, 68, 0.13), rgba(239, 68, 68, 0.02));
  }
  article blockquote p { margin: 0.4em 0; }
  article img { max-width: 100%; border-radius: 12px; }
  article table {
    width: 100%; margin: 1.2em 0; border-collapse: separate; border-spacing: 0;
    border: 1px solid var(--border); border-radius: 12px; overflow: hidden;
    display: block; overflow-x: auto;
    animation: fadeUp .5s ease both;
  }
  article th {
    background: linear-gradient(180deg, rgba(239, 68, 68, 0.28), rgba(239, 68, 68, 0.1));
    color: #fee2e2; font-weight: 600; text-align: left;
  }
  article th, article td { padding: 10px 14px; border-bottom: 1px solid var(--border); white-space: nowrap; }
  article th:last-child, article td:last-child { white-space: normal; }
  article tr:last-child td { border-bottom: 0; }
  article tbody tr { transition: background .15s; }
  article tbody tr:hover { background: rgba(239, 68, 68, 0.08); }
  article code {
    background: rgba(239, 68, 68, 0.12); color: var(--accent-3);
    border: 1px solid rgba(239, 68, 68, 0.22); border-radius: 6px;
    padding: 1px 6px; font-family: var(--mono); font-size: 0.87em;
  }
  article pre {
    position: relative; margin: 1.2em 0; padding: 18px 20px;
    background: rgba(10, 4, 4, 0.9);
    border: 1px solid rgba(239, 68, 68, 0.3); border-radius: 12px;
    box-shadow: inset 0 0 40px rgba(220, 38, 38, 0.08), 0 8px 26px rgba(0, 0, 0, 0.4);
    overflow-x: auto;
  }
  article pre code { background: none; border: 0; padding: 0; color: #fdecec; font-size: 0.87em; line-height: 1.6; }
  .copy-btn {
    position: absolute; top: 8px; right: 8px; opacity: 0; transition: opacity .2s, transform .15s;
    background: rgba(70, 12, 12, 0.85); color: var(--accent-3); border: 1px solid var(--border);
    border-radius: 8px; padding: 4px 10px; font-size: 0.75em; cursor: pointer; font-family: var(--font);
  }
  article pre:hover .copy-btn { opacity: 1; }
  .copy-btn:hover { transform: scale(1.05); color: #fff; background: rgba(220, 38, 38, 0.5); }
  .copy-btn.done { color: #86efac; border-color: rgba(34, 197, 94, 0.5); }

  /* ---------- docs hub cards ---------- */
  .docs-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 16px; margin: 24px 0; }
  .doc-card {
    display: flex; flex-direction: column; text-decoration: none !important; background-image: none !important;
    padding: 20px 20px 16px; border-radius: 14px; position: relative;
    background: var(--panel-2); border: 1px solid var(--border);
    transition: transform .2s, box-shadow .2s, border-color .2s;
    animation: fadeUp .5s ease both;
  }
  .doc-card:hover { transform: translateY(-4px); border-color: rgba(239, 68, 68, 0.6); box-shadow: 0 14px 40px rgba(0, 0, 0, 0.45), 0 0 30px rgba(239, 68, 68, 0.28); }
  .doc-card .dc-icon { display: block; font-size: 1.7em; line-height: 1; filter: drop-shadow(0 0 10px rgba(239,68,68,.6)); }
  .doc-card .dc-title { display: block; margin: 10px 0 6px; color: #fff; font-weight: 700; font-size: 1.02em; line-height: 1.35; }
  .doc-card .dc-desc { display: block; color: var(--muted); font-size: 0.86em; line-height: 1.55; }

  /* ---------- back to top ---------- */
  #toTop {
    position: fixed; right: 22px; bottom: 22px; z-index: 600;
    width: 46px; height: 46px; border-radius: 50%; cursor: pointer;
    border: 1px solid rgba(239, 68, 68, 0.5);
    background: linear-gradient(135deg, var(--bg2), #060607);
    color: var(--accent-3); font-size: 1.2em;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5), 0 0 24px rgba(239, 68, 68, 0.3);
    opacity: 0; transform: translateY(10px); pointer-events: none;
    transition: opacity .25s, transform .25s;
  }
  #toTop.show { opacity: 1; transform: none; pointer-events: auto; }
  #toTop:hover { box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5), 0 0 34px rgba(239, 68, 68, 0.6); color: #fff; }

  footer {
    text-align: center; color: var(--muted); font-size: 0.84em;
    padding: 8px 0 30px;
  }
  footer a { color: var(--accent-2); text-decoration: none; }
  footer a:hover { color: #fff; }
  @media (max-width: 720px) {
    article { padding: 24px 18px 34px; border-radius: 12px; }
    .layout { padding: 16px 10px 40px; }
  }
</style>
</head>
<body>
<div id="progress"></div>
<div id="top" aria-hidden="true"></div>
${nav}
<div class="layout">
  ${toc}
  <main>
    <article>
${body}
    </article>
  </main>
</div>
<button id="toTop" aria-label="Back to top">↑</button>
<footer>
  <strong>AuthCore</strong>, The Fortress Framework for Minecraft Servers ·
  <a href="https://github.com/${REPO}" target="_blank" rel="noopener">GitHub</a> ·
  <a href="https://modrinth.com/mod/authCore" target="_blank" rel="noopener">Modrinth</a>
</footer>
<script>
(function () {
  const h = () => document.documentElement;
  const bar = document.getElementById("progress");
  const toTop = document.getElementById("toTop");

  // ---- TOC navigation enhancements: head + topic count, progress bar, back-to-top ----
  const toc = document.querySelector(".toc");
  let tocBar = null;
  if (toc) {
    const items = toc.querySelectorAll(".toc-item a");
    const title = toc.querySelector(".toc-title");
    if (title && !toc.querySelector(".toc-head")) {
      const head = document.createElement("div");
      head.className = "toc-head";
      toc.insertBefore(head, title);
      head.appendChild(title);
      const badge = document.createElement("span");
      badge.className = "toc-count";
      badge.textContent = items.length + (items.length === 1 ? " topic" : " topics");
      head.appendChild(badge);
    }
    if (!toc.querySelector(".toc-progress")) {
      const p = document.createElement("div");
      p.className = "toc-progress";
      p.innerHTML = "<span></span>";
      toc.appendChild(p);
      tocBar = p.firstElementChild;
    } else tocBar = toc.querySelector(".toc-progress span");
    if (!toc.querySelector(".toc-top")) {
      const top = document.createElement("a");
      top.className = "toc-top";
      top.href = "#top";
      top.textContent = "Back to top";
      top.addEventListener("click", (e) => { e.preventDefault(); window.scrollTo({ top: 0, behavior: "smooth" }); });
      toc.appendChild(top);
    }
    // clicking a topic also closes the mobile nav
    items.forEach((a) => a.addEventListener("click", () => {
      const nav = document.querySelector(".site-nav");
      const toggle = document.querySelector(".nav-toggle");
      if (nav && toggle && nav.classList.contains("open")) { nav.classList.remove("open"); toggle.setAttribute("aria-expanded", "false"); }
    }));
  }

  // ---- scroll progress (top bar + in-TOC bar) ----
  const onScroll = () => {
    const max = h().scrollHeight - h().clientHeight;
    const pct = max > 0 ? (h().scrollTop / max) * 100 : 0;
    bar.style.width = pct + "%";
    if (tocBar) tocBar.style.width = pct + "%";
    toTop.classList.toggle("show", h().scrollTop > 400);
  };
  document.addEventListener("scroll", onScroll, { passive: true });
  onScroll();
  toTop.addEventListener("click", () => window.scrollTo({ top: 0, behavior: "smooth" }));

  // ---- mobile nav ----
  const toggle = document.querySelector(".nav-toggle");
  const nav = document.querySelector(".site-nav");
  if (toggle && nav) {
    toggle.addEventListener("click", () => {
      const open = nav.classList.toggle("open");
      toggle.setAttribute("aria-expanded", String(open));
    });
  }

  // ---- copy buttons on code blocks ----
  document.querySelectorAll("article pre").forEach((pre) => {
    const btn = document.createElement("button");
    btn.className = "copy-btn";
    btn.textContent = "Copy";
    btn.addEventListener("click", async () => {
      const text = pre.querySelector("code").innerText;
      try {
        await navigator.clipboard.writeText(text);
        btn.textContent = "Copied!";
        btn.classList.add("done");
      } catch {
        btn.textContent = "Error";
      }
      setTimeout(() => { btn.textContent = "Copy"; btn.classList.remove("done"); }, 1500);
    });
    pre.appendChild(btn);
  });

  // ---- scrollspy for the sidebar TOC (highlights the nearest heading) ----
  const links = document.querySelectorAll(".toc-item a");
  if (links.length) {
    const map = new Map();
    links.forEach((a) => {
      const id = a.getAttribute("href").slice(1);
      const el = document.getElementById(id);
      if (el) map.set(el, a);
    });
    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((e) => {
          if (e.isIntersecting) {
            links.forEach((l) => l.classList.remove("active"));
            const a = map.get(e.target);
            if (a) a.classList.add("active");
          }
        });
      },
      { rootMargin: "-90px 0px -70% 0px" },
    );
    map.forEach((_, el) => io.observe(el));
  }
})();
</script>
</body>
</html>
`;
}
