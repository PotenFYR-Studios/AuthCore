// One-time converter: docs/*.md + changelogs/changelog.md -> docs/1.0.0/*.html
// (self-contained static pages with sidebar TOC, scrollspy, copy buttons,
//  dark/blue theme). Run:  node scripts/render-docs.mjs

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { pageShell, REPO, BRANCH, DOCS } from "./site-template.mjs";
import { renderMdPage } from "./render-md.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "docs", "1.0.0");
const BASE = `https://potenfyr-studios.github.io/${REPO}/docs/1.0.0/`;

// source files -> (slug, page title, description)
const SOURCES = [
  ["guide", "docs/GUIDE.md", "Server Admin Guide", "START HERE — newbie setup: jars, install, config walkthrough, auth flows, commands, troubleshooting + learning path."],
  ["config", "docs/CONFIG.md", "Configuration Reference", "Every setting, default and use-case."],
  ["proxy", "docs/PROXY.md", "Proxy Support", "Velocity / BungeeCord forwarding, modern identity, proxy-side auth."],
  ["webpanel", "docs/WEBPANEL.md", "Web Admin Panel", "HTTP/HTTPS setup, REST reference, token auth, curl examples."],
  ["security", "docs/SECURITY.md", "Security Model", "Threat analysis (OWASP + Minecraft) and every defense in depth."],
  ["26x", "docs/26x.md", "26.1 – 26.2 Builds", "Range jars, the unobfuscated era, architecture and verification."],
  ["api", "docs/API.md", "Developer API", "AuthCoreApi, database schema, integration guide."],
  ["development", "docs/DEVELOPMENT.md", "Development & Architecture", "Build system, multi-version / multi-loader management, testing."],
  ["changelog", "changelogs/changelog.md", "Changelog", "Full release history — from the first alpha to 1.0.0."],
];

const mdToHtml = new Map(
  SOURCES.map(([, src]) => {
    const base = src.split("/").pop().toLowerCase().replace(/\.md$/i, "");
    return [`${base}.md`, `${base}.html`];
  }),
);

mkdirSync(OUT, { recursive: true });

for (const [slug, srcFile, title, description] of SOURCES) {
  const md = readFileSync(join(ROOT, srcFile), "utf8");
  const { html } = await renderMdPage({
    title,
    description,
    canonical: `${BASE}${slug}.html`,
    md,
    basePrefix: "../../",
    mdToHtml,
    active: slug,
  });
  writeFileSync(join(OUT, `${slug}.html`), html, "utf8");
  console.log(`docs/1.0.0/${slug}.html  (${html.length} bytes)`);
}

// docs hub landing page
const cards = DOCS.map(
  (d) =>
    `<a class="doc-card" href="${d.slug}.html"><span class="dc-icon">${d.icon}</span><span class="dc-title">${d.title}</span><span class="dc-desc">${d.description}</span></a>`,
).join("\n  ");
const hub = pageShell({
  title: "Documentation",
  description: "AuthCore documentation hub — admin guide, configuration, proxy, web panel, security, API and more.",
  canonical: `${BASE}index.html`,
  body: `<h1>📚 Documentation</h1>
<p>Welcome to the <strong>AuthCore</strong> documentation. Start with the Admin Guide if you are
new, then explore each topic below. Every page is also available in the
<a href="https://github.com/${REPO}/blob/${BRANCH}/README.md">repository README</a>.</p>
<div class="docs-grid">\n  ${cards}\n</div>
<blockquote><p>💡 <strong>New here?</strong> Read the <a href="guide.html">Admin Guide</a> first — it walks
you from jar selection to a fully secured server, and maps every topic to the deeper docs.</p></blockquote>`,
  toc: "",
  active: "hub",
  basePrefix: "../../",
});
writeFileSync(join(OUT, "index.html"), hub, "utf8");
console.log(`docs/1.0.0/index.html  (${hub.length} bytes, docs hub)`);

console.log("Done. The .md sources are gone — docs/1.0.0/*.html is the source of truth.");
