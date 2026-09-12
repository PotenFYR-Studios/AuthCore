import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { readFileSync, writeFileSync, mkdirSync, copyFileSync, existsSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dir = dirname(fileURLToPath(import.meta.url));

const PAGES = [
  { id: "index", title: "Documentation Hub" },
  { id: "guide", title: "Admin Guide" },
  { id: "flows", title: "Auth Flows" },
  { id: "config", title: "Configuration Reference" },
  { id: "proxy", title: "Proxy Support" },
  { id: "webpanel", title: "Web Admin Panel" },
  { id: "security", title: "Security Model" },
  { id: "26x", title: "26.1 - 26.2 Builds" },
  { id: "api", title: "Developer API" },
  { id: "development", title: "Development & Architecture" },
  { id: "changelog", title: "Changelog" },
] as const;

const CANON = "https://authcore.docs.potenfyr.in";

/** Emit /1.0.0/<page>.html copies of the SPA shell with per-page SEO meta. */
function multiPageEmit(): Plugin {
  return {
    name: "authcore-multi-page",
    closeBundle() {
      const outDir = resolve(__dir, "dist");
      const shell = readFileSync(resolve(outDir, "index.html"), "utf8");
      mkdirSync(resolve(outDir, "1.0.0"), { recursive: true });
      for (const p of PAGES) {
        const rawPath = resolve(__dir, "content", `${p.id}.html`);
        let desc = `AuthCore documentation — ${p.title}.`;
        let canon = `${CANON}/docs/1.0.0/${p.id}.html`;
        try {
          const raw = readFileSync(rawPath, "utf8");
          const d = raw.match(/<meta name="description" content="([^"]*)"/);
          if (d) desc = d[1];
          const c = raw.match(/rel="canonical" href="([^"]*)"/);
          if (c) canon = c[1];
        } catch { /* content optional */ }
        let html = shell
          .replace(/<title>.*?<\/title>/, `<title>${p.title} - AuthCore Docs</title>`)
          .replace("</head>", `  <meta name="description" content="${desc.replace(/"/g, "&quot;")}">\n<link rel="canonical" href="${canon}">\n</head>`);
        writeFileSync(resolve(outDir, "1.0.0", `${p.id}.html`), html);
      }
      // keep legacy static assets (used by the static root homepage) served under /docs/assets/
      const assetsOut = resolve(outDir, "assets");
      mkdirSync(assetsOut, { recursive: true });
      for (const f of ["site.css", "nav.js"]) {
        try { copyFileSync(resolve(__dir, "../assets", f), resolve(assetsOut, f)); } catch { /* optional */ }
      }
    },
  };
}

export default defineConfig({
  root: __dirname,
  base: "/docs/",
  plugins: [react(), tailwindcss(), multiPageEmit()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: false,
  },
  server: { port: 5174 },
});
