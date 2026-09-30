/**
 * PotenFYR OG card generator
 *
 * Rasterises scripts/og/card.html to public/og.png at exactly 1200x630 using a
 * local Chromium/Chrome binary (no browser-automation dependency, so `bun run
 * build` stays browser-free). The PNG is committed; run this only when the
 * card copy or branding changes:
 *
 *   bun run og:image
 *
 * Override the browser with CHROME_PATH if the auto-discovery misses yours.
 */
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync, statSync } from 'node:fs';
import { copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const cardPath = join(here, 'card.html');
const outPath = resolve(root, 'og.png');

const WIDTH = 1200;
const HEIGHT = 630;

const CANDIDATES = [
  process.env.CHROME_PATH,
  join(process.env.LOCALAPPDATA ?? '', 'ms-playwright/chromium-1243/chrome-win64/chrome.exe'),
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
].filter((p) => Boolean(p));

function findBrowser() {
  for (const candidate of CANDIDATES) {
    if (existsSync(candidate)) return candidate;
  }
  return null;
}

const browser = findBrowser();
if (!browser) {
  console.error('[og] No Chromium/Chrome found. Set CHROME_PATH to your browser binary.');
  process.exit(1);
}

const work = mkdtempSync(join(tmpdir(), 'potenfyr-og-'));
const shot = join(work, 'og.png');

const args = [
  '--headless=new',
  '--disable-gpu',
  '--hide-scrollbars',
  '--no-first-run',
  '--no-default-browser-check',
  '--force-device-scale-factor=1',
  '--force-color-profile=srgb',
  `--window-size=${WIDTH},${HEIGHT}`,
  '--virtual-time-budget=12000',
  `--screenshot=${shot}`,
  pathToFileURL(cardPath).href,
];

console.log(`[og] Rendering ${WIDTH}x${HEIGHT} card with ${browser}`);
const run = spawnSync(browser, args, { stdio: ['ignore', 'pipe', 'pipe'], encoding: 'utf8' });
if (run.status !== 0 && !existsSync(shot)) {
  console.error(run.stderr || run.stdout);
  rmSync(work, { recursive: true, force: true });
  process.exit(1);
}

copyFileSync(shot, outPath);
rmSync(work, { recursive: true, force: true });

const kb = (statSync(outPath).size / 1024).toFixed(1);
console.log(`[og] Wrote ${outPath} (${kb} KB)`);
