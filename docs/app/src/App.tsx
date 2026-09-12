import { useEffect, useMemo, useRef, useState } from "react";
import { Search, ChevronLeft, ChevronRight, Menu, X } from "lucide-react";
import {
  PAGES,
  pageById,
  extractHeadings,
  extractMain,
  currentPageId,
  type Heading,
} from "./content";
import { DotPattern } from "../magicui";

const CANON_HUB = "/docs/1.0.0/index.html";

/* --------------------------------------------------- version selector menu */
interface DocVersion {
  version: string;
  docs: string;
  status: string;
}

function VersionSelect() {
  const [versions, setVersions] = useState<DocVersion[]>([]);
  const [current, setCurrent] = useState<string>("");
  useEffect(() => {
    fetch("/docs/versions.json", { cache: "no-cache" })
      .then((r) => r.json())
      .then((vs: DocVersion[]) => {
        setVersions(vs);
        const path = location.pathname;
        const m = path.match(/\/docs\/([^/]+)\//);
        const dir = m?.[1];
        const hit = vs.find((v) => v.docs.includes(`/${dir}/`));
        setCurrent(hit?.version ?? vs[0]?.version ?? "");
      })
      .catch(() => {});
  }, []);
  if (!versions.length) return null;
  const active = versions.find((v) => v.version === current);
  void active;
  return (
    <span className="flex items-center gap-2">
      <span className="text-line-light">|</span>
      <select
        value={current}
        onChange={(e) => {
          const v = versions.find((x) => x.version === e.target.value);
          if (v) location.href = v.docs;
        }}
        className="rounded-lg border border-line-light bg-[#151828] px-2.5 py-1 font-mono text-xs text-[#e8eaf2] outline-none transition-colors hover:border-brand-violet/50 focus:border-brand-violet"
        aria-label="Documentation version"
      >
        {versions.map((v) => (
          <option key={v.version} value={v.version}>
            v{v.version}
            {v.status === "deprecated" ? " (deprecated)" : ""}
          </option>
        ))}
      </select>
    </span>
  );
}

/* ------------------------------------------------------------------ topbar */
function Topbar({
  onSearch,
  onMenu,
}: {
  onSearch: () => void;
  onMenu: () => void;
}) {
  return (
    <header className="sticky top-0 z-40 flex h-14 items-center gap-3 border-b border-line-light bg-[#0b0d14]/80 px-5 backdrop-blur-xl">
      <button
        className="md:hidden text-[#9aa0b4] hover:text-white"
        onClick={onMenu}
        aria-label="Toggle sidebar"
      >
        <Menu size={18} />
      </button>
      <a href="/index.html" className="text-sm font-semibold text-[#e8eaf2]">
        AuthCore<span className="text-brand-pink">.</span>docs
      </a>
      <span className="text-line-light">|</span>
      <a
        href={CANON_HUB}
        className="text-xs uppercase tracking-widest text-[#6a7089] hover:text-[#c4b5fd]"
      >
        Docs
      </a>
      <VersionSelect />
      <button
        onClick={onSearch}
        className="ml-auto flex shrink-0 items-center gap-2 rounded-lg border border-line-light bg-white/[0.03] px-3 py-1.5 text-xs text-[#9aa0b4] hover:border-brand-violet/50 hover:text-[#e8eaf2] transition-colors"
      >
        <Search size={13} /> <span className="hidden sm:inline">Search</span>
        <kbd className="hidden sm:inline rounded border border-line-light px-1.5 py-0.5 font-mono text-[10px]">
          ⌘K
        </kbd>
      </button>
      <a
        href="https://potenfyr.in"
        className="ml-auto text-xs text-[#9aa0b4] hover:text-[#c4b5fd]"
      >
        Website
      </a>
      <a
        href="https://discord.com/invite/zUaN2FPBec"
        className="text-xs text-[#9aa0b4] hover:text-[#c4b5fd]"
      >
        Discord
      </a>
      <a
        href="https://github.com/PotenFYR-Studios/AuthCore"
        target="_blank"
        rel="noopener"
        className="text-xs text-[#9aa0b4] hover:text-white"
      >
        GitHub
      </a>
    </header>
  );
}

/* ----------------------------------------------------------------- sidebar */
function Sidebar({
  current,
  open,
  onClose,
}: {
  current: string;
  open: boolean;
  onClose: () => void;
}) {
  return (
    <aside
      className={`${open ? "flex" : "hidden"} md:flex flex-col gap-1 sticky top-14 max-h-[calc(100vh-3.5rem)] overflow-y-auto
        w-60 shrink-0 py-8 pr-2 border-r border-line-light/50 max-md:fixed max-md:inset-y-14 max-md:left-0 max-md:z-30
        max-md:bg-[#0b0d14]/98 max-md:px-4 scrollbar-thin`}
    >
      <button
        onClick={onClose}
        className="md:hidden self-end text-[#9aa0b4] mb-2"
        aria-label="Close sidebar"
      >
        <X size={16} />
      </button>
      <p className="px-3 pb-2 text-[10px] font-bold uppercase tracking-[0.15em] text-[#6a7089]">
        Get started
      </p>
      {PAGES.slice(0, 2).map((p) => (
        <SideLink
          key={p.id}
          p={p}
          active={p.id === current}
          onClick={onClose}
        />
      ))}
      <p className="px-3 pb-2 pt-4 text-[10px] font-bold uppercase tracking-[0.15em] text-[#6a7089]">
        Core topics
      </p>
      {PAGES.slice(2, 8).map((p) => (
        <SideLink
          key={p.id}
          p={p}
          active={p.id === current}
          onClick={onClose}
        />
      ))}
      <p className="px-3 pb-2 pt-4 text-[10px] font-bold uppercase tracking-[0.15em] text-[#6a7089]">
        Developers
      </p>
      {PAGES.slice(8).map((p) => (
        <SideLink
          key={p.id}
          p={p}
          active={p.id === current}
          onClick={onClose}
        />
      ))}
    </aside>
  );
}

function SideLink({
  p,
  active,
  onClick,
}: {
  p: { id: string; title: string };
  active: boolean;
  onClick: () => void;
}) {
  return (
    <a
      href={p.id === "index" ? CANON_HUB : `/docs/1.0.0/${p.id}.html`}
      onClick={onClick}
      className={`rounded-lg px-3 py-1.5 text-[13px] transition-colors ${
        active
          ? "bg-brand-violet/15 text-white shadow-[inset_0_0_0_1px_rgba(139,92,246,.4)]"
          : "text-[#9aa0b4] hover:bg-white/5 hover:text-[#e8eaf2]"
      }`}
    >
      {p.title}
    </a>
  );
}

/* --------------------------------------------------------------------- toc */
function Toc({ headings }: { headings: Heading[] }) {
  const [active, setActive] = useState("");
  useEffect(() => {
    if (!headings.length) return;
    const obs = new IntersectionObserver(
      (entries) => {
        for (const en of entries) {
          if (en.isIntersecting) setActive(en.target.id);
        }
      },
      { rootMargin: "-80px 0px -65% 0px" },
    );
    headings.forEach((h) => {
      const el = document.getElementById(h.id);
      if (el) obs.observe(el);
    });
    return () => obs.disconnect();
  }, [headings]);
  if (!headings.length) return null;
  return (
    <nav
      className="hidden xl:block sticky top-14 max-h-[calc(100vh-3.5rem)] overflow-y-auto w-52 shrink-0 py-8 pl-4"
      aria-label="On this page"
    >
      <p className="mb-3 text-[10px] font-bold uppercase tracking-[0.15em] text-[#6a7089]">
        On this page
      </p>
      <ul className="space-y-0.5">
        {headings.map((h) => (
          <li key={h.id}>
            <a
              href={`#${h.id}`}
              className={`block border-l-2 py-1 text-[12px] leading-snug transition-colors ${
                h.level === 3
                  ? "pl-6 text-[10.5px] text-[#6a7089]"
                  : "pl-3 text-[#9aa0b4]"
              } hover:text-[#e8eaf2] ${active === h.id ? "toc-item active" : "border-line"}`}
            >
              {h.text}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}

/* ------------------------------------------------------------- cmdk palette */
function Palette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [q, setQ] = useState("");
  const inputRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (open) setTimeout(() => inputRef.current?.focus(), 30);
  }, [open]);
  if (!open) return null;
  const pageItems = PAGES.map((p) => ({
    label: p.title,
    href: p.id === "index" ? CANON_HUB : `/docs/1.0.0/${p.id}.html`,
    kind: "page" as const,
  }));
  const sectionItems = extractHeadings(extractMain(currentPageId())).map(
    (h) => ({
      label: h.text,
      href: `#${h.id}`,
      kind: "section" as const,
    }),
  );
  const all = [...sectionItems, ...pageItems]
    .filter((i) => i.label.toLowerCase().includes(q.toLowerCase()))
    .slice(0, 14);
  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center bg-black/60 pt-28 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="w-full max-w-lg overflow-hidden rounded-2xl border border-line bg-[#101320] shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <input
          ref={inputRef}
          autoFocus
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Jump to a section or page…"
          className="w-full border-b border-line-light bg-transparent px-4 py-3.5 text-sm text-white outline-none placeholder:text-[#6a7089]"
          onKeyDown={(e) => {
            if (e.key === "Escape") onClose();
            if (e.key === "Enter") {
              const first = all[0];
              if (first) {
                onClose();
                location.href = first.href;
              }
            }
          }}
        />
        <div className="max-h-80 overflow-y-auto p-2">
          {all.map((i, idx) => (
            <a
              key={idx}
              href={i.href}
              onClick={onClose}
              className={`flex items-center gap-3 rounded-lg px-3 py-2 text-[13px] ${idx === 0 ? "bg-brand-violet/15 text-white" : "text-[#9aa0b4] hover:bg-white/5"}`}
            >
              <span className="font-mono text-[10px] text-[#6a7089]">
                {i.kind === "section" ? "§" : "→"}
              </span>
              {i.label}
            </a>
          ))}
          {!all.length && (
            <div className="px-3 py-6 text-center text-xs text-[#6a7089]">
              No matches
            </div>
          )}
        </div>
        <div className="border-t border-line-light px-4 py-2 font-mono text-[10px] text-[#6a7089]">
          ↑↓ navigate · Enter open · Esc close
        </div>
      </div>
    </div>
  );
}

/* --------------------------------------------------------------- pagination */
function Pagination({ current }: { current: string }) {
  const i = PAGES.findIndex((p) => p.id === current);
  if (i < 0 || current === "index") return null;
  const prev = PAGES[i - 1];
  const next = PAGES[i + 1];
  return (
    <nav className="mt-16 flex items-stretch justify-between gap-4">
      {prev ? (
        <a
          href={`/docs/1.0.0/${prev.id}.html`}
          className="group flex-1 rounded-xl border border-line-light bg-white/[0.02] p-4 transition-all hover:-translate-y-0.5 hover:border-brand-violet/50"
        >
          <span className="flex items-center gap-1 text-[10px] font-bold uppercase tracking-widest text-[#6a7089]">
            <ChevronLeft size={11} /> Previous
          </span>
          <strong className="mt-1 block text-sm text-[#e8eaf2] group-hover:text-[#c4b5fd]">
            {prev.title}
          </strong>
        </a>
      ) : (
        <span className="flex-1" />
      )}
      {next ? (
        <a
          href={`/docs/1.0.0/${next.id}.html`}
          className="group flex-1 rounded-xl border border-line-light bg-white/[0.02] p-4 text-right transition-all hover:-translate-y-0.5 hover:border-brand-pink/50"
        >
          <span className="flex items-center justify-end gap-1 text-[10px] font-bold uppercase tracking-widest text-[#6a7089]">
            Next <ChevronRight size={11} />
          </span>
          <strong className="mt-1 block text-sm text-[#e8eaf2] group-hover:text-[#f9a8d4]">
            {next.title}
          </strong>
        </a>
      ) : (
        <span className="flex-1" />
      )}
    </nav>
  );
}

/* ------------------------------------------------------------------ effects */
function useContentEffects(html: string) {
  useEffect(() => {
    document.querySelectorAll(".doc-content pre").forEach((pre) => {
      if (pre.querySelector(".copy-btn")) return;
      const btn = document.createElement("button");
      btn.className = "copy-btn";
      btn.type = "button";
      btn.textContent = "Copy";
      btn.addEventListener("click", () => {
        const code = pre.querySelector("code");
        navigator.clipboard
          .writeText(code?.innerText ?? pre.innerText)
          .then(() => {
            btn.textContent = "Copied!";
            btn.classList.add("ok");
            setTimeout(() => {
              btn.textContent = "Copy";
              btn.classList.remove("ok");
            }, 1400);
          });
      });
      pre.appendChild(btn);
    });
  }, [html]);
}

/* ---------------------------------------------------------------------- app */
export default function App() {
  const current = currentPageId();
  const page = pageById(current)!;
  const headings = useMemo(
    () => extractHeadings(extractMain(current)),
    [current],
  );
  const contentHtml = useMemo(() => extractMain(current), [current]);
  const [searchOpen, setSearchOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  useContentEffects(contentHtml);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setSearchOpen((v) => !v);
      }
      const i = PAGES.findIndex((p) => p.id === current);
      if (i >= 0 && current !== "index") {
        if (e.key === "]") {
          const t = PAGES[i + 1];
          if (t) location.href = `/docs/1.0.0/${t.id}.html`;
        }
        if (e.key === "[") {
          const t = PAGES[i - 1];
          if (t) location.href = `/docs/1.0.0/${t.id}.html`;
        }
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [current]);

  const isHub = current === "index";

  return (
    <div className="relative min-h-screen">
      <DotPattern className="[mask-image:radial-gradient(750px_circle_at_50%_0,white,transparent)]" />
      <Topbar
        onSearch={() => setSearchOpen(true)}
        onMenu={() => setMenuOpen((v) => !v)}
      />
      <Palette open={searchOpen} onClose={() => setSearchOpen(false)} />

      {isHub ? (
        /* -------- docs hub: fumadocs-style card grid -------- */
        <main className="relative z-10 mx-auto max-w-5xl px-6 py-16">
          <p className="mb-4 inline-flex items-center gap-2 rounded-full border border-line-light bg-white/[0.03] px-3.5 py-1 font-mono text-xs text-[#9aa0b4] backdrop-blur-md">
            AuthCore v1.0.0
          </p>
          <h1 className="mb-3 bg-gradient-to-br from-[#c4b5fd] via-[#f9a8d4] to-[#fdba74] bg-clip-text text-4xl font-extrabold tracking-tight text-transparent">
            Documentation
          </h1>
          <p className="mb-10 max-w-xl text-[15px] text-[#9aa0b4]">
            {page.description}
          </p>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {PAGES.filter((p) => p.id !== "index").map((p) => (
              <a
                key={p.id}
                href={`/docs/1.0.0/${p.id}.html`}
                className="group relative overflow-hidden rounded-2xl border border-line-light bg-white/[0.02] p-5 backdrop-blur-md transition-all hover:-translate-y-1 hover:border-brand-violet/50 hover:shadow-[0_12px_40px_rgba(0,0,0,.5)]"
              >
                <h3 className="mb-1.5 text-[15px] font-semibold text-white group-hover:text-[#c4b5fd]">
                  {p.title}
                </h3>
                <p className="text-xs leading-relaxed text-[#9aa0b4]">
                  {p.description.slice(0, 110)}…
                </p>
              </a>
            ))}
          </div>
        </main>
      ) : (
        /* -------- doc page: sidebar / content / toc -------- */
        <div className="relative z-10 mx-auto flex max-w-[1400px] gap-8 px-6">
          <Sidebar
            current={current}
            open={menuOpen}
            onClose={() => setMenuOpen(false)}
          />
          <main className="min-w-0 flex-1 py-10">
            <header className="mb-8">
              <p className="mb-2 font-mono text-xs uppercase tracking-[0.15em] text-[#6a7089]">
                AuthCore Docs · v1.0.0
              </p>
              <h1 className="bg-gradient-to-br from-[#c4b5fd] via-[#f9a8d4] to-[#fdba74] bg-clip-text text-3xl font-extrabold tracking-tight text-transparent">
                {page.title}
              </h1>
            </header>
            <article
              className="doc-content"
              dangerouslySetInnerHTML={{ __html: contentHtml }}
            />
            <Pagination current={current} />
          </main>
          <Toc headings={headings} />
        </div>
      )}

      <footer className="relative z-10 border-t border-line-light px-6 py-8 text-center text-xs text-[#6a7089]">
        AuthCore ·{" "}
        <a
          className="hover:text-[#c4b5fd]"
          href="https://github.com/PotenFYR-Studios/AuthCore"
        >
          GitHub
        </a>{" "}
        ·{" "}
        <a
          className="hover:text-[#c4b5fd]"
          href="https://modrinth.com/mod/authcore"
        >
          Modrinth
        </a>{" "}
        · Apache-2.0 + Commons Clause
      </footer>
    </div>
  );
}
