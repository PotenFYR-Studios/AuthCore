// Maintenance tool: re-skins every docs/1.0.0/*.html page with the current
// template shell from site-template.mjs, preserving the rendered content.
// Use it whenever the shell changes (layout, nav, theme):
//   node scripts/render-docs.mjs

import { readFileSync, writeFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { pageShell, SITE_TITLE, REPO, BRANCH } from "./site-template.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const DIR = join(ROOT, "docs", "1.0.0");
const SITE = `https://authcore.potenfyr.in/docs/1.0.0/`;

for (const file of readdirSync(DIR).filter((f) => f.endsWith(".html"))) {
  const c = readFileSync(join(DIR, file), "utf8");

  const title = c.match(/<title>(.*?)<\/title>/)[1].replace(` - ${SITE_TITLE}`, "");
  const description = c.match(/<meta name="description" content="(.*?)">/)[1];
  const canonical = c.match(/<link rel="canonical" href="(.*?)">/)[1];
  const tocMatch = c.match(/<aside class="toc"[\s\S]*?<\/aside>/);
  const toc = tocMatch ? tocMatch[0] : "";
  const body = c
    .match(/<article>([\s\S]*?)<\/article>/)[1]
    .replace(/^\n/, "")
    .replace(/\n$/, "");

  const activeMatch = c.match(
    /<a class="nav-link active" href="[^"]*?docs\/1\.0\.0\/(\w+)\.html"/,
  );
  const active = file === "index.html" ? "hub" : activeMatch ? activeMatch[1] : "hub";

  const html = pageShell({
    title,
    description,
    canonical,
    body,
    toc,
    active,
    basePrefix: "../../",
  });
  writeFileSync(join(DIR, file), html, "utf8");
  console.log(`re-skinned ${file} (${html.length} bytes)`);
}
console.log(`Done. Pages re-skinned with the current template (${SITE}).`);
