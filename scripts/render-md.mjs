// Shared markdown -> styled HTML page renderer (used by render-docs.mjs for the
// static docs and by render-readme.mjs for the live changelog page).

import { marked } from "marked";
import { pageShell, tocHTML, ghAnchor, REPO, BRANCH } from "./site-template.mjs";

// GitHub-style anchors with duplicate handling (fenced code blocks excluded)
function collectHeadings(md) {
  const seen = new Map();
  const out = [];
  const noCode = md.replace(/```[\s\S]*?```/g, "");
  const re = /^(#{1,4})\s+(.+)$/gm;
  let m;
  while ((m = re.exec(noCode)) !== null) {
    const level = m[1].length;
    if (level < 2 || level > 3) continue;
    const raw = m[2].replace(/`/g, "").replace(/\*\*/g, "").trim();
    const anchor = ghAnchor(raw);
    const n = (seen.get(anchor) || 0) + 1;
    seen.set(anchor, n);
    out.push({ level, text: raw, anchor: n === 1 ? anchor : `${anchor}-${n - 1}` });
  }
  return out;
}

// Give every rendered h2/h3 the id its TOC entry points to.
function injectHeadingIds(html, headings) {
  let idx = 0;
  return html.replace(/<h([23])([^>]*)>([\s\S]*?)<\/h\1>/g, (m, level, attrs, inner) => {
    const h = headings[idx];
    idx++;
    if (!h || h.level !== Number(level)) return m;
    return `<h${level} id="${h.anchor}"${attrs}>${inner}</h${level}>`;
  });
}

// Rewrite relative .md links to sibling .html pages; everything else relative
// goes to GitHub blob / raw URLs. Absolute github.com blob links that point at
// the removed docs/*.md files also map to the local html page.
export function rewriteMdLinks(html, mdToHtml) {
  html = html.replace(
    /(href|src)="(?!https?:|#|mailto:|tel:|data:)([^"]+)"/g,
    (match, attr, path) => {
      const [p, hash] = path.split("#");
      const suffix = hash ? `#${hash}` : "";
      const file = p.split("/").pop().toLowerCase();
      if (mdToHtml.has(file)) return `${attr}="${mdToHtml.get(file)}${suffix}"`;
      if (attr === "src") return `${attr}="https://raw.githubusercontent.com/${REPO}/${BRANCH}/${p}"`;
      return `${attr}="https://github.com/${REPO}/blob/${BRANCH}/${p}${suffix}"`;
    },
  );
  return html.replace(
    /https:\/\/github\.com\/(?:DawnOfDedSec|PotenFYR-Studios)\/AuthCore\/blob\/main\/docs\/([a-z0-9-]+\.md)(#[^"]*)?/gi,
    (match, file, hash) => {
      const lower = file.toLowerCase();
      if (!mdToHtml.has(lower)) return match;
      return `${mdToHtml.get(lower)}${hash || ""}`;
    },
  );
}

export async function renderMdPage({ title, description, canonical, md, basePrefix, mdToHtml, active }) {
  const headings = collectHeadings(md);
  const toc = tocHTML(headings);
  let body = await marked.parse(md, { gfm: true, breaks: false });
  body = injectHeadingIds(body, headings);
  if (mdToHtml) body = rewriteMdLinks(body, mdToHtml);
  return {
    html: pageShell({ title, description, canonical, body, toc, active, basePrefix }),
    headings,
  };
}
