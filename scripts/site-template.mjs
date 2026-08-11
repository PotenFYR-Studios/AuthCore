// Shared site shell: dark/blue theme, top nav, sidebar TOC, scrollspy,
// scroll progress, copy buttons, back-to-top — used by every generated page.

export const SITE_TITLE = "AuthCore Docs";
export const REPO = "PotenFYR-Studios/AuthCore";
export const BRANCH = "main";

export const NAV = [
  { slug: "guide", href: "guide.html", label: "Admin Guide", icon: "🧭" },
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
  { slug: "guide", title: "Server Admin Guide", icon: "🧭", description: "START HERE — jar selection, install, config walkthrough, auth flows, commands, troubleshooting + learning path." },
  { slug: "config", title: "Configuration Reference", icon: "⚙️", description: "Every setting, default and use-case — the single source of truth for settings.conf." },
  { slug: "proxy", title: "Proxy Support", icon: "🔁", description: "Velocity / BungeeCord forwarding, modern identity (HMAC), proxy-side auth." },
  { slug: "webpanel", title: "Web Admin Panel", icon: "🌐", description: "HTTP/HTTPS setup, REST reference, token auth, curl examples." },
  { slug: "security", title: "Security Model", icon: "🛡️", description: "Threat analysis (OWASP + Minecraft) and every defense in depth." },
  { slug: "26x", title: "26.1 – 26.2 Builds", icon: "📦", description: "Range jars, the unobfuscated era, architecture and verification." },
  { slug: "api", title: "Developer API", icon: "🔌", description: "AuthCoreApi, database schema, integration guide for plugin authors." },
  { slug: "development", title: "Development & Architecture", icon: "⚙️", description: "Build system, multi-version / multi-loader management, testing." },
  { slug: "changelog", title: "Changelog", icon: "📜", description: "Full release history — from the first alpha to 1.0.0." },
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
  return `<aside class="toc" id="toc">
    <div class="toc-title">On this page</div>
    <ul>${rows}</ul>
  </aside>`;
}

export function pageShell({ title, description, canonical, body, toc, active, basePrefix }) {
  const nav = navHTML(active, basePrefix);
  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} — ${SITE_TITLE}</title>
<meta name="description" content="${description}">
<meta property="og:title" content="${title} — ${SITE_TITLE}">
<meta property="og:description" content="${description}">
<meta property="og:type" content="website">
<link rel="canonical" href="${canonical}">
<style>
  :root {
    --bg0: #030508;
    --bg1: #0a1128;
    --bg2: #0d2a5e;
    --panel: rgba(10, 17, 40, 0.72);
    --panel-2: rgba(13, 42, 94, 0.35);
    --accent: #3b82f6;
    --accent-2: #60a5fa;
    --accent-3: #93c5fd;
    --green: #22c55e;
    --text: #e2e8f0;
    --muted: #8b95a7;
    --border: rgba(59, 130, 246, 0.22);
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
      radial-gradient(1200px 420px at 50% -100px, rgba(59, 130, 246, 0.15) 0%, rgba(59, 130, 246, 0) 65%),
      linear-gradient(160deg, var(--bg0) 0%, var(--bg1) 45%, var(--bg2) 140%);
    background-attachment: fixed;
    overflow-x: hidden;
  }

  /* ---------- scroll progress ---------- */
  #progress {
    position: fixed; top: 0; left: 0; height: 3px; width: 0;
    background: linear-gradient(90deg, var(--accent), var(--accent-3), #fff);
    box-shadow: 0 0 12px rgba(59, 130, 246, 0.8);
    z-index: 1000; border-radius: 0 3px 3px 0;
  }

  /* ---------- header ---------- */
  .site-header {
    position: sticky; top: 0; z-index: 500;
    display: flex; align-items: center; gap: 18px;
    padding: 10px 22px;
    background: rgba(3, 5, 8, 0.72);
    backdrop-filter: blur(14px);
    border-bottom: 1px solid var(--border);
    box-shadow: 0 8px 30px rgba(0, 0, 0, 0.35);
  }
  .brand { display: flex; align-items: center; gap: 10px; text-decoration: none; color: #fff; font-weight: 700; letter-spacing: 0.2px; white-space: nowrap; }
  .brand-mark { font-size: 1.25em; filter: drop-shadow(0 0 8px rgba(59,130,246,.7)); }
  .brand-text { font-size: 1.05em; }
  .brand-dot { color: var(--accent-3); }
  .site-nav { display: flex; flex-wrap: wrap; gap: 2px; flex: 1; justify-content: center; }
  .nav-link {
    color: var(--muted); text-decoration: none; font-size: 0.86em; font-weight: 500;
    padding: 6px 10px; border-radius: 8px; transition: color .18s, background .18s, box-shadow .18s;
    position: relative;
  }
  .nav-link .nav-icon { margin-right: 4px; font-size: 0.92em; }
  .nav-link:hover { color: #fff; background: rgba(59, 130, 246, 0.12); }
  .nav-link.active { color: #fff; background: linear-gradient(135deg, rgba(59,130,246,.28), rgba(13,42,94,.45)); box-shadow: inset 0 0 0 1px rgba(59,130,246,.35), 0 0 14px rgba(59,130,246,.25); }
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
      background: rgba(3, 5, 8, 0.96); backdrop-filter: blur(14px);
      border-bottom: 1px solid var(--border); padding: 10px 14px;
    }
    .site-nav.open { display: flex; }
  }

  /* ---------- layout ---------- */
  .layout { display: grid; grid-template-columns: 250px minmax(0, 1fr); gap: 28px; max-width: 1180px; margin: 0 auto; padding: 30px 22px 60px; }
  @media (max-width: 1020px) { .layout { grid-template-columns: 1fr; } }

  /* ---------- sidebar TOC ---------- */
  .toc {
    position: sticky; top: 78px; align-self: start;
    max-height: calc(100vh - 100px); overflow-y: auto;
    background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius);
    padding: 14px 16px;
    backdrop-filter: blur(8px);
    box-shadow: 0 10px 34px rgba(0, 0, 0, 0.35), inset 0 1px 0 rgba(255, 255, 255, 0.05);
    animation: fadeUp .5s ease both;
  }
  .toc-title { font-size: 0.72em; font-weight: 700; letter-spacing: 0.14em; text-transform: uppercase; color: var(--accent-3); margin: 2px 0 10px; }
  .toc ul { list-style: none; margin: 0; padding: 0; }
  .toc-item { margin: 1px 0; }
  .toc-item a {
    display: block; color: var(--muted); text-decoration: none; font-size: 0.84em;
    padding: 4px 10px; border-left: 2px solid transparent; border-radius: 0 8px 8px 0;
    transition: color .15s, border-color .15s, background .15s;
  }
  .toc-item a:hover { color: #fff; background: rgba(59, 130, 246, 0.1); }
  .toc-item.toc-3 a { padding-left: 22px; font-size: 0.8em; }
  .toc-item a.active { color: #fff; border-left-color: var(--accent); background: linear-gradient(90deg, rgba(59,130,246,.18), transparent); }

  /* ---------- content ---------- */
  article {
    background: var(--panel);
    border: 1px solid var(--border);
    border-radius: calc(var(--radius) + 4px);
    padding: 36px 44px 52px;
    box-shadow: 0 20px 64px rgba(0, 0, 0, 0.5), 0 0 100px rgba(37, 99, 235, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.05);
    backdrop-filter: blur(8px);
    animation: fadeUp .55s ease both;
  }
  @keyframes fadeUp { from { opacity: 0; transform: translateY(16px); } to { opacity: 1; transform: none; } }
  article > :first-child { margin-top: 0; }
  article h1, article h2, article h3, article h4 { line-height: 1.3; scroll-margin-top: 90px; }
  article h1 {
    font-size: 2em; font-weight: 800; margin: 0 0 18px; padding-bottom: 14px;
    background: linear-gradient(90deg, #fff 0%, var(--accent-3) 55%, var(--accent) 100%);
    -webkit-background-clip: text; background-clip: text; -webkit-text-fill-color: transparent;
    border-bottom: 1px solid var(--border);
  }
  article h2 {
    font-size: 1.38em; color: #f8fafc; margin: 1.9em 0 0.6em; padding-bottom: 8px;
    border-bottom: 1px solid rgba(59, 130, 246, 0.16);
    animation: fadeUp .5s ease both;
  }
  article h2::before {
    content: ""; display: inline-block; width: 7px; height: 0.95em; margin-right: 11px;
    background: linear-gradient(180deg, var(--accent-3), var(--accent)); border-radius: 4px;
    box-shadow: 0 0 14px rgba(59, 130, 246, 0.65); vertical-align: -0.08em;
  }
  article h3 { font-size: 1.12em; color: var(--accent-3); margin: 1.5em 0 0.5em; }
  article h4 { font-size: 1em; color: #dbeafe; }
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
    background: linear-gradient(90deg, rgba(59, 130, 246, 0.14), rgba(59, 130, 246, 0.02));
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
    background: linear-gradient(180deg, rgba(59, 130, 246, 0.26), rgba(59, 130, 246, 0.1));
    color: #dbeafe; font-weight: 600; text-align: left;
  }
  article th, article td { padding: 10px 14px; border-bottom: 1px solid var(--border); white-space: nowrap; }
  article th:last-child, article td:last-child { white-space: normal; }
  article tr:last-child td { border-bottom: 0; }
  article tbody tr { transition: background .15s; }
  article tbody tr:hover { background: rgba(59, 130, 246, 0.08); }
  article code {
    background: rgba(59, 130, 246, 0.14); color: var(--accent-3);
    border: 1px solid rgba(59, 130, 246, 0.2); border-radius: 6px;
    padding: 1px 6px; font-family: var(--mono); font-size: 0.87em;
  }
  article pre {
    position: relative; margin: 1.2em 0; padding: 18px 20px;
    background: rgba(2, 6, 16, 0.88);
    border: 1px solid rgba(59, 130, 246, 0.28); border-radius: 12px;
    box-shadow: inset 0 0 40px rgba(37, 99, 235, 0.08), 0 8px 26px rgba(0, 0, 0, 0.4);
    overflow-x: auto;
  }
  article pre code { background: none; border: 0; padding: 0; color: #dbeafe; font-size: 0.87em; line-height: 1.6; }
  .copy-btn {
    position: absolute; top: 8px; right: 8px; opacity: 0; transition: opacity .2s, transform .15s;
    background: rgba(13, 42, 94, 0.8); color: var(--accent-3); border: 1px solid var(--border);
    border-radius: 8px; padding: 4px 10px; font-size: 0.75em; cursor: pointer; font-family: var(--font);
  }
  article pre:hover .copy-btn { opacity: 1; }
  .copy-btn:hover { transform: scale(1.05); color: #fff; background: rgba(37, 99, 235, 0.5); }
  .copy-btn.done { color: #86efac; border-color: rgba(34, 197, 94, 0.5); }

  /* ---------- docs hub cards ---------- */
  .docs-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 16px; margin: 24px 0; }
  .doc-card {
    display: block; text-decoration: none !important; background-image: none !important;
    padding: 20px 20px 16px; border-radius: 14px; position: relative;
    background: var(--panel-2); border: 1px solid var(--border);
    transition: transform .2s, box-shadow .2s, border-color .2s;
    animation: fadeUp .5s ease both;
  }
  .doc-card:hover { transform: translateY(-4px); border-color: rgba(59, 130, 246, 0.55); box-shadow: 0 14px 40px rgba(0, 0, 0, 0.45), 0 0 30px rgba(59, 130, 246, 0.25); }
  .doc-card .dc-icon { font-size: 1.7em; filter: drop-shadow(0 0 10px rgba(59,130,246,.6)); }
  .doc-card .dc-title { display: block; margin: 10px 0 6px; color: #fff; font-weight: 700; font-size: 1.02em; }
  .doc-card .dc-desc { display: block; color: var(--muted); font-size: 0.86em; line-height: 1.5; }

  /* ---------- back to top ---------- */
  #toTop {
    position: fixed; right: 22px; bottom: 22px; z-index: 600;
    width: 46px; height: 46px; border-radius: 50%; cursor: pointer;
    border: 1px solid rgba(59, 130, 246, 0.5);
    background: linear-gradient(135deg, var(--bg2), #02060d);
    color: var(--accent-3); font-size: 1.2em;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5), 0 0 24px rgba(59, 130, 246, 0.3);
    opacity: 0; transform: translateY(10px); pointer-events: none;
    transition: opacity .25s, transform .25s;
  }
  #toTop.show { opacity: 1; transform: none; pointer-events: auto; }
  #toTop:hover { box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5), 0 0 34px rgba(59, 130, 246, 0.55); color: #fff; }

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
  <strong>AuthCore</strong> — The Fortress Framework for Minecraft Servers ·
  <a href="https://github.com/${REPO}" target="_blank" rel="noopener">GitHub</a> ·
  <a href="https://modrinth.com/mod/authCore" target="_blank" rel="noopener">Modrinth</a>
</footer>
<script>
(function () {
  // scroll progress
  const bar = document.getElementById("progress");
  const toTop = document.getElementById("toTop");
  const onScroll = () => {
    const h = document.documentElement;
    const max = h.scrollHeight - h.clientHeight;
    bar.style.width = (max > 0 ? (h.scrollTop / max) * 100 : 0) + "%";
    toTop.classList.toggle("show", h.scrollTop > 400);
  };
  document.addEventListener("scroll", onScroll, { passive: true });
  onScroll();
  toTop.addEventListener("click", () => window.scrollTo({ top: 0, behavior: "smooth" }));

  // mobile nav
  const toggle = document.querySelector(".nav-toggle");
  const nav = document.querySelector(".site-nav");
  if (toggle && nav) {
    toggle.addEventListener("click", () => {
      const open = nav.classList.toggle("open");
      toggle.setAttribute("aria-expanded", String(open));
    });
  }

  // copy buttons on code blocks
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

  // scrollspy for the sidebar TOC
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
