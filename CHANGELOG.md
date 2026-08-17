# Changelog

The full changelog lives at [changelogs/changelog.md](changelogs/changelog.md) (source) or the
styled [hosted changelog](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/changelog.html);
it covers every release from the first alpha to 1.0.0 (exact Modrinth version ranges,
bot/backend separation, repository cleanup).

Latest: **[1.0.0]** - The complete 1.0.0 release, one merged changelog: the limbo &
performance overhaul (anti-vibration movement correction, vehicle-movement bypass closed,
a fully inert inventory that never interrupts chat, the "can't hit mobs" attack-callback
fix, context-aware underlined chat buttons with shadow-styled action bars, the server-side
menu removed, O(1) user lookups on every hot path, split configuration with one file per
config block), the out-of-the-box experience & hardening pass (server mode always taken
from `server.properties` - the `server-mode` config is gone, `enable-secure-profile=false`
startup warning on online servers, outage-proof premium auto-login that works on both
modes, premium auto-login players keep a null password, **hybrid mode** - offline players
can join online-mode servers too via `allow-offline-players` (default on), per-account
login style with `/account set-mode online|offline` (existing passwords are kept - those
players just log in), "online-mode"/"offline-mode" terminology project-wide, human-friendly
player messages), the multi-loader foundation (7 jars from a single Stonecutter/Stonecraft
source tree, verified on every range endpoint), the security pass (single risk-scored human
verification on every login, map captcha & server-side GUI removed, race-condition-free
concurrency, 500k+ account scale with no resource spikes, formatted limbo-guard debug
report, debug logging off by default, host-test harness rebuilt with real player
simulation), and the black/red fortress-cyber docs re-skin with an enhanced table of
contents. See the
[changelog](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/changelog.html)
for the full history.
