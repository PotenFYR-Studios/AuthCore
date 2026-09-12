/** Content pipeline: legacy doc pages loaded as raw HTML at build time. */

const raw = import.meta.glob("../content/*.html", {
  query: "?raw",
  import: "default",
  eager: true,
}) as Record<string, string>;

export interface DocPage {
  id: string;
  title: string;
  description: string;
  html: string;
}

export const PAGES: DocPage[] = [
  { id: "index", title: "Documentation Hub" },
  { id: "guide", title: "Admin Guide" },
  { id: "flows", title: "Auth Flows" },
  { id: "config", title: "Configuration Reference" },
  { id: "proxy", title: "Proxy Support" },
  { id: "webpanel", title: "Web Admin Panel" },
  { id: "security", title: "Security Model" },
  { id: "26x", title: "26.x Builds" },
  { id: "api", title: "Developer API" },
  { id: "development", title: "Development & Architecture" },
  { id: "changelog", title: "Changelog" },
].map((p) => {
  const html = raw[`../content/${p.id}.html`] ?? "";
  const d = html.match(/<meta name="description" content="([^"]*)"/);
  return {
    ...p,
    description: d ? d[1] : `AuthCore documentation — ${p.title}.`,
    html,
  };
});

export function pageById(id: string): DocPage | undefined {
  return PAGES.find((p) => p.id === id);
}

export interface Heading {
  id: string;
  text: string;
  level: 2 | 3;
}

const mainCache = new Map<string, string>();

/**
 * Extract only the article content from a legacy page: the <main> inside the
 * old .layout wrapper — dropping the legacy site header, TOC, progress bar,
 * back-to-top button and scripts, all replaced by the React layout.
 */
export function extractMain(id: string): string {
  if (mainCache.has(id)) return mainCache.get(id)!;
  const html = pageById(id)?.html ?? "";
  const doc = new DOMParser().parseFromString(html, "text/html");
  doc
    .querySelectorAll(
      "script, .site-header, .toc, #progress, #authcore-versionbar, .page-nav, #back-to-top, .back-to-top, .foot",
    )
    .forEach((n) => n.remove());
  const main =
    doc.querySelector(".layout > main") ??
    doc.querySelector("main") ??
    doc.body;
  const inner = main.innerHTML;
  mainCache.set(id, inner);
  return inner;
}

/** Extract h2/h3 headings (with ids) from a content HTML string. */
export function extractHeadings(html: string): Heading[] {
  const doc = new DOMParser().parseFromString(html, "text/html");
  return [...doc.querySelectorAll("h2[id], h3[id]")].map((h) => ({
    id: h.id,
    text: h.textContent?.trim() ?? "",
    level: h.tagName === "H2" ? 2 : 3,
  }));
}

/** Current page id from the URL path (/docs/1.0.0/<id>.html). */
export function currentPageId(): string {
  const m = location.pathname.match(/\/docs\/[^/]+\/([a-z0-9x]+)\.html$/i);
  if (m) {
    const id = m[1].toLowerCase();
    if (PAGES.some((p) => p.id === id)) return id;
  }
  return "index";
}
