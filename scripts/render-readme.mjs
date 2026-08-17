// Builds the GitHub Pages site from README.md + the pre-rendered docs/1.0.0 pages.
// Output: scripts/_site/ { index.html, docs/1.0.0/*, CNAME, .nojekyll }

import { readFileSync, writeFileSync, mkdirSync, cpSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { marked } from "marked";
import { pageShell, REPO, BRANCH } from "./site-template.mjs";
import { renderMdPage } from "./render-md.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "scripts", "_site");
const BASE = `https://authcore.potenfyr.in/docs/1.0.0/`;

const readme = readFileSync(join(ROOT, "README.md"), "utf8");

let body = await marked.parse(readme, { gfm: true, breaks: false });

// Rewrite relative links for the hosted site:
//   docs/GUIDE.md           -> docs/1.0.0/guide.html   (same site)
//   changelogs/changelog.md -> docs/1.0.0/changelog.html
//   docs/...                -> docs/1.0.0/<slug>.html
//   anything else relative  -> GitHub blob / raw URLs
const DOC_MD = {
  "guide.md": "guide.html",
  "config.md": "config.html",
  "proxy.md": "proxy.html",
  "webpanel.md": "webpanel.html",
  "security.md": "security.html",
  "26x.md": "26x.html",
  "api.md": "api.html",
  "development.md": "development.html",
  "changelog.md": "changelog.html",
};
const blob = (p) => `https://github.com/${REPO}/blob/${BRANCH}/${p}`;
const raw = (p) => `https://raw.githubusercontent.com/${REPO}/${BRANCH}/${p}`;

body = body.replace(
  /(href|src)="(?!https?:|#|mailto:|tel:|data:)([^"]+)"/g,
  (match, attr, path) => {
    const [p, hash] = path.split("#");
    const suffix = hash ? `#${hash}` : "";
    const parts = p.split("/");
    const fileName = parts.pop().toLowerCase();
    const dir = parts.join("/").toLowerCase();
    if (DOC_MD[fileName] && (dir === "docs" || dir === "changelogs" || dir === "")) {
      return `${attr}="docs/1.0.0/${DOC_MD[fileName]}${suffix}"`;
    }
    if (attr === "src") return `${attr}="${raw(p)}"`;
    return `${attr}="${blob(p)}${suffix}"`;
  },
);

const html = pageShell({
  title: "AuthCore - The Fortress Framework for Minecraft Servers",
  description: "Login & security for offline-mode Minecraft servers, one codebase for Minecraft 1.16.0 to 26.1-26.2 on Fabric/Forge/NeoForge, servers only.",
  canonical: `https://authcore.potenfyr.in/`,
  body,
  toc: "",
  active: "home",
  basePrefix: "",
});

rmSync(OUT, { recursive: true, force: true });
mkdirSync(join(OUT, "docs"), { recursive: true });
cpSync(join(ROOT, "docs", "1.0.0"), join(OUT, "docs", "1.0.0"), { recursive: true });
writeFileSync(join(OUT, "index.html"), html, "utf8");
writeFileSync(join(OUT, "CNAME"), "authcore.potenfyr.in\n", "utf8");
writeFileSync(join(OUT, ".nojekyll"), "", "utf8");

// Regenerate the changelog page from changelogs/changelog.md on every deploy,
// so new release entries automatically appear on the hosted site.
const changelogMd = readFileSync(join(ROOT, "changelogs", "changelog.md"), "utf8");
const { html: changelogHtml } = await renderMdPage({
  title: "Changelog",
  description: "Full release history, from the first alpha to 1.0.1.",
  canonical: `${BASE}changelog.html`,
  md: changelogMd,
  basePrefix: "../../",
  active: "changelog",
});
writeFileSync(join(OUT, "docs", "1.0.0", "changelog.html"), changelogHtml, "utf8");

console.log(`Site rendered to ${OUT} (index ${html.length} bytes, docs/1.0.0 copied, changelog regenerated)`);
