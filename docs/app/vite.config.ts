import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import {
  copyFileSync,
  readFileSync,
  writeFileSync,
  mkdirSync,
} from "node:fs";
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
  { id: "license", title: "License" },
] as const;

const CANON = "https://authcore.docs.potenfyr.in";

/**
 * Prerender <App/> to static HTML for one docs route. Uses a throwaway Vite
 * SSR server so the rendered tree is EXACTLY what the client hydrates; the
 * browser globals the render path touches are stubbed per route (location)
 * and globally (linkedom's DOMParser for the legacy-content pipeline).
 */
async function makeRenderer() {
  const { createServer } = await import("vite");
  const { DOMParser, document } = await import("linkedom");
  const React = (await import("react")).default;
  const { StrictMode } = await import("react");
  const { renderToString } = await import("react-dom/server");

  globalThis.DOMParser = DOMParser as unknown as typeof globalThis.DOMParser;
  globalThis.document = document as unknown as typeof globalThis.document;

  const server = await createServer({
    root: __dir,
    configFile: false,
    plugins: [react()],
    logLevel: "error",
    server: { middlewareMode: true },
    appType: "custom",
  });
  const { default: App } = (await server.ssrLoadModule("/src/App.tsx")) as {
    default: React.ComponentType;
  };

  return {
    render(pathname: string): string {
      globalThis.location = new URL(
        `${CANON}${pathname}`,
      ) as unknown as Location;
      return renderToString(
        React.createElement(StrictMode, null, React.createElement(App)),
      );
    },
    async dispose() {
      await server.close();
    },
  };
}

/** Emit /1.0.0/<page>.html copies of the SPA shell, prerendered + SEO meta. */
function multiPageEmit(): Plugin {
  return {
    name: "authcore-multi-page",
    async closeBundle() {
      const outDir = resolve(__dir, "dist");
      const shell = readFileSync(resolve(outDir, "index.html"), "utf8");
      mkdirSync(resolve(outDir, "1.0.0"), { recursive: true });

      // Every route shares the "index" tree (the hub) - render it once.
      const renderer = await makeRenderer();
      const bodies: Record<string, string> = { index: "" };
      try {
        for (const p of PAGES) {
          if (p.id !== "index") {
            bodies[p.id] = renderer.render(`/docs/1.0.0/${p.id}.html`);
          }
        }
        bodies.index = renderer.render("/docs/1.0.0/index.html");
      } finally {
        await renderer.dispose();
      }

      for (const p of PAGES) {
        const rawPath = resolve(__dir, "content", `${p.id}.html`);
        let desc = `AuthCore documentation: ${p.title}.`;
        let canon = `${CANON}/docs/1.0.0/${p.id}.html`;
        try {
          const raw = readFileSync(rawPath, "utf8");
          const d = raw.match(/<meta name="description" content="([^"]*)"/);
          if (d) desc = d[1];
          const c = raw.match(/rel="canonical" href="([^"]*)"/);
          if (c) canon = c[1];
        } catch {
          /* content optional */
        }
        const descTag = `<meta name="description" content="${desc.replace(/"/g, "&quot;")}" />`;
        let html = shell
          .replace(
            /<title>.*?<\/title>/,
            `<title>${p.title} - AuthCore Docs</title>`,
          )
          // replace the shell's single description - never append a second one
          .replace(/<meta name="description"[^>]*>/, descTag)
          .replace(
            /<link rel="canonical" href="[^"]*"\s*\/?>/,
            `<link rel="canonical" href="${canon}">`,
          )
          // replacer fn: article markup may contain `$` sequences
          .replace(
            '<div id="root"></div>',
            () => `<div id="root">${bodies[p.id]}</div>`,
          );
        if (!/<meta name="description"/.test(html)) {
          // shell lost its description tag - fall back to appending one
          html = html.replace("</head>", `  ${descTag}\n</head>`);
        }
        if (!/<link rel="canonical"/.test(html)) {
          // shell lost/changed its canonical - fall back to appending one
          html = html.replace(
            "</head>",
            `<link rel="canonical" href="${canon}">\n</head>`,
          );
        }
        writeFileSync(resolve(outDir, "1.0.0", `${p.id}.html`), html);
      }

      // The /docs/ hub (dist/index.html) gets the same prerendered body, with
      // its own canonical (/docs/) and description kept from the shell.
      writeFileSync(
        resolve(outDir, "index.html"),
        shell.replace(
          '<div id="root"></div>',
          () => `<div id="root">${bodies.index}</div>`,
        ),
      );

      // keep legacy static assets (used by the static root homepage) served under /docs/assets/
      const assetsOut = resolve(outDir, "assets");
      mkdirSync(assetsOut, { recursive: true });
      for (const f of ["site.css", "nav.js"]) {
        try {
          copyFileSync(resolve(__dir, "../assets", f), resolve(assetsOut, f));
        } catch {
          /* optional */
        }
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
