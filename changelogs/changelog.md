<div align="center" style="font-family: 'Clash of Clans', 'Comic Sans MS', 'Comic Sans', cursive;">

# AuthCore Changelog

All notable changes to AuthCore, from the first alpha to the current release.

[![Version](https://shieldcn.dev/badge/version-1.0.0-blue.svg)](https://github.com/DawnOfDedSec/AuthCore/releases) [![Build](https://shieldcn.dev/github/ci/DawnOfDedSec/AuthCore.svg)](https://github.com/DawnOfDedSec/AuthCore/actions) [![Back to README](https://shieldcn.dev/badge/%F0%9F%93%9A-Back_to_README-5865F2.svg)](https://github.com/DawnOfDedSec/AuthCore/blob/main/README.md)

</div>

---

## [1.0.0] - 2026-08-15

> One merged changelog for the whole 1.0.0 line - every feature, fix and hardening
> pass that ever shipped under a `1.0.0*` label lives in this single section
> (the separate `alpha.1`-`alpha.5` entries were folded in; nothing was lost).

### Security-audit & CI pass (2026-08-26)

**Critical fixes**

- **MySQL / PostgreSQL servers no longer brick at boot**: the hard-coded SQLite DDL
  (`AUTOINCREMENT`, `TEXT` primary key) threw on both dialects, which set
  `migrationBlocked = true` and suspended login/register for the whole server. Table
  creation is now dialect-aware (AUTO_INCREMENT / BIGSERIAL / VARCHAR(36) keys).
- **Session-resume premature kick fixed**: resuming a session re-ran `login()` without
  stopping the previous session's timeout task - the stale timer fired at the ORIGINAL
  deadline and kicked freshly-authenticated players. The old timer is now cancelled on
  every (re)login.
- **Direct-client IP spoofing closed**: proxy IP forwarding accepted a BARE handshake
  address as a forwarded IP - but that field is client-controlled, so any modified client
  could claim an arbitrary IP (defeating rate limits, GeoIP, login intelligence and IP
  rules). Only the real NUL-separated proxy forwarding payload is trusted now.
- **Limbo command gate unified + auth commands unblockable**: `/login` and `/register`
  are now ALWAYS allowed in the limbo (a misconfigured whitelist/blacklist could
  permanently trap players), blacklist mode behaves identically on every enforcement
  layer, and all three dispatcher/packet layers share one decision function.

**Security hardening**

- Email password-recovery requests are rate-limited (one per minute per player) -
  previously an unauthenticated limbo player could spam unlimited SMTP sends.
- Discord link-code generation is rate-limited (webhook spam vector).
- Web admin panel: POST bodies are capped (64 KB) against memory-exhaustion, responses
  carry `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy:
  no-referrer` and a strict CSP on the dashboard; dead authorization path removed.
- Crash-snapshot restore clamps health to `[finite, max]` - a tampered limbo snapshot
  file can no longer restore NaN/negative/absurd health.
- Limbo lock registration is atomic (`putIfAbsent`) - a double-lock race unwinds cleanly
  instead of corrupting snapshot state.
- `ip-rules.conf` supports IPv4 CIDR ranges (`deny 203.0.113.0/24`), sharing the same
  matcher as the proxy allowlist.
- Redis security-event payloads are built with Gson instead of hand-rolled escaping.
- SMTP response parser no longer throws on bare 3-char continuation lines.
- Action-captcha challenge selection uses one shared `SecureRandom`.

**Compatibility & CI**

- **Future-snapshot support**: any modern major line (26.x today, 27.x+ tomorrow) is
  detected dynamically in the build - future targets get their own open-ended jar label
  and Minecraft range instead of being mislabeled, and the untested-version boot warning
  understands numeric majors.
- **CI builds all seven variants in PARALLEL** (matrix, one runner per variant) with a
  dedicated merge/count-gate job; least-privilege workflow permissions; robust dev-release
  jar-name parsing for future label shapes.
- **Modrinth publishing**: stable `v*` releases now publish to Modrinth alongside GitHub
  Releases - one listing per Minecraft range with correct loaders + dependencies, and a
  same-version rebuild REPLACES the existing Modrinth listing (fresh jars + changelog),
  mirroring the GitHub Release upsert behavior.
- **VS Code integration**: one-click tasks for build (active/all variants), security
  tests (+ optional config-migration suite), Docker host tests, site rendering and jar
  collection; recommended extensions and workspace search/watcher tuning.
- The config-migration test suite (`AuthCoreMigrationTest`) is now wired into the test
  script via `-IncludeMigration`.

### CI, security & compatibility pass (2026-08-25)

**GitHub Actions & releases**
- **Rolling dev builds**: every push to `main` now refreshes a `latest` PRERELEASE on
  GitHub Releases with all seven jars of that exact commit (marked prerelease + not
  "Latest", so stable `v*` releases always stay on top). Development builds are finally
  downloadable without cutting a tag.
- Stable `v*` tag releases unchanged; the release job additionally enforces
  `--latest=true` so a re-tagged build always wins the "Latest" badge.
- Jar-count gate (exactly 7 jars), fail-loud jar-role verification, Gradle wrapper
  validation, job timeouts, non-cancellable tagged builds, build-report artifacts on
  failure.
- Compat scan publishes are rebase-safe (a moved `main` during a scan no longer
  discards the result).

**Forward-compatibility: future Minecraft versions load instead of being rejected**
- The 26.1-26.2 jars declare an OPEN-ENDED Minecraft range (`>=26.1`, NeoForge
  `[26.1,)`) - new 26.x releases and snapshots boot the mod with the standard
  untested-version warning banner instead of refusing to load. The 1.x-era jars keep
  their exact verified ranges (intermediary mappings make open ranges unsafe there).

**Security hardening (audit-driven)**
- Duplicate-login race closed: the leave event of a kicked old connection can no longer
  unlock the NEW session's limbo (connection-identity check).
- Limbo unlock no longer resurrects operator status captured at lock time - a deop while
  sitting in the lobby stays a deop.
- Open containers are force-closed at lock time (carried stacks can no longer be
  deposited into world chests while unauthenticated).
- Creative-mode slot packets are blocked in the lobby regardless of game mode.
- MySQL nickname SQLi closed (parameterized), TOTP enrollment QR no longer sent to an
  external service (local rendering), mistyped passwords are redacted in spray alerts,
  `mfaVerified`/session tokens reset on logout, recovery-code printing requires an
  authenticated session, rate-limit overflow eviction is per-key (an IPv6 flood can no
  longer clear other players' lockouts).
- Forge/NeoForge parity for entity attack/interaction blocking in the limbo (previously
  fabric-api-only).

### Hybrid mode, per-account login style & release hardening

**Anti-float platform - mid-air logouts can never kick players in the limbo**
- A player who logs out mid-air (or underwater) used to be kicked by vanilla's
  "Flying is not enabled" floating check while standing in the limbo. AuthCore now
  places an invisible BARRIER platform under the limbo player (`lobby.anti-float-platform`,
  on by default); diving players are stood on a platform at the water/lava surface instead
  of being left submerged. The original block is restored shortly after the authentication
  flow completes (`lobby.anti-float-platform-delay-ms`, default 10 seconds) - never
  overwriting a block another player placed in the meantime.
- **Crash recovery for the limbo** (already present, verified): the pre-limbo snapshot
  (exact position + dimension, inventory, effects, game mode, health/food/xp) is persisted
  at lock time; if the server (or the player's session) crashes mid-limbo, the snapshot is
  restored on the next join BEFORE the fresh lock, so after login the player always returns
  to the exact spot they were at their last disconnect - regardless of the admin-configured
  limbo location.
- **Movement lock hardened**: with movement disabled, the per-tick limbo re-assert now
  snaps the player back at a 0.25-block drift (was 1.0) - on runtimes without the
  movement-cancel mixin (Fabric/Forge 1.16-1.21 without a mixin refmap) this is the only
  server-side lock, and it now holds the player essentially in place. Known limitation:
  on those runtimes the client can briefly ghost-walk up to the snap interval; the
  restriction is fully packet-level on NeoForge and every 26.x jar.

**Server mode is always taken from `server.properties` - the config override is gone**
- The `session.server-mode` setting is removed entirely: the mod always reads the real
  `online-mode` from the running Minecraft server (`MinecraftServer#usesAuthentication`).
  Premium auto-login works the same on online AND offline-mode servers (on offline servers
  AuthCore re-runs the vanilla encryption handshake and verifies with the server's own
  Mojang session service - no external API calls; failures fall back to offline
  register/login, players are never blocked or kicked).
- Online-mode servers get a startup warning to keep `enable-secure-profile=false` in
  `server.properties`, so clients without a secure chat profile (cracked/modded players)
  can still join and chat.

**Hybrid servers: offline-mode players can join BOTH server modes**
- New `allow-offline-players` config (session.authentication, default `true`): with it on,
  offline (cracked) players can join and register/login on **online-mode servers too** -
  the login mixin intercepts offline-UUID clients on online-mode servers and runs them
  through vanilla's own offline accept flow (no Mojang session check, offline UUID kept;
  online-mode players still use their real UUID and the normal session verification).
  Fail-safe: on versions where the offline accept flow cannot be driven, vanilla's normal
  rejection takes over - nobody is stranded.
- With `allow-offline-players = false` the server is online-mode-only everywhere: offline
  players are kicked with a clear message on arrival (join gate, before session resume /
  register / login), and on online-mode servers they are disconnected at login.

**Premium auto-login respects its config on BOTH modes; auto-login players keep a null password**
- The `premium-auto-login` config (on by default) is now honored regardless of the server's
  mode. Auto-login players are NEVER given an auto-generated password - their stored
  password stays null (the old `premium-auto-register` random-password path is removed).
- If auto-login is turned off (or a player switches to password login), verified online-mode
  players are treated like any standard account: because their password is null they are
  asked to `/register` on their next re-auth. Their online-mode status is preserved, so
  auto-login simply resumes when the config (or their mode choice) is switched back.

**Per-account login style: players and admins can switch online/offline mode**
- New player command `/account set-mode online|offline` (permission `authcore.user.setmode`,
  level 0): switches the player's own account between automatic login and password login.
- Switching to password login is smart about passwords: a player who ALREADY has a stored
  password keeps it and simply logs in with it (`/login`); only auto-login accounts with a
  null password are asked to `/register` a new one. The active session is destroyed either
  way so the change takes effect on the next join.
- `/authcore set-mode offline <player>` no longer takes a password argument and follows the
  same rule (existing password kept / register when null). Both admin mode commands destroy
  the session too.
- A player's own mode choice is honored on join: accounts explicitly set to password login
  are never auto-logged-in, even on an online-mode server where the session was verified.

**Terminology: "premium"/"cracked" replaced project-wide**
- Non-user-facing text (console logs, debug output, admin commands, web panel labels, code
  comments, docs) now uses **online-mode players** and **offline-mode players** instead of
  "premium players" and "cracked players". Internal identifiers and the `premium-auto-login`
  config key stay unchanged for API/config compatibility.
- Player-facing messages stay human: auto-login greets with "Welcome to the Server!", mode
  switches talk about "automatic login" / "password login" - no technical jargon anywhere
  in chat, titles or kick screens.

**Limbo guard debug output**
- The per-join limbo guard report is now debug-level and formatted like the startup banner:
  one aligned row per guard (movement / chat / commands / block-break / block-use / item-use
  / item-drop / attacks, each `allowed` or `LOCKED`) plus a live lobby-usage line
  ("35% used (7/20)", "unlimited" when no cap).

**Scale, race-condition safety & docs**
- Performance/scalability pass documented for **500k+ registered accounts** and thousands of
  concurrent players: O(1) user lookups on every hot path, lazy DB loading, bounded
  self-cleaning caches, no resource spikes under join/login bursts (per-user throttles,
  rate limits, fixed-size daemon IO pool), race-condition-free concurrency (canonical
  single-`User`-per-account cache, `synchronized` DB access, `volatile` shared state,
  deduped join/leave hooks, atomic counters).
- Docs re-skin: the hosted docs now use a black + red fortress-cyber theme with an enhanced
  sidebar table of contents (topic count, in-TOC scroll progress, back-to-top, glowing
  active states) and a wider, spread-out content layout; README and every docs page updated
  for the current behavior.

### Limbo, performance & configuration overhaul

**Limbo quality pass (no more screen vibration, no bypasses)**
- **Anti-vibration movement correction**: the client was snapped back on EVERY violating
  movement packet (up to 40 position packets/s â†’ rubber-banding). Corrections are now
  distance- and throttle-based: `lobby.movement-correction-radius` (default 1.5 blocks) and
  `lobby.movement-correction-interval-ms` (default 600ms), the classic AuthMe feel
  (ghost-walk a little, one clean snap). Movement packets are still cancelled on every
  packet, so the server entity never leaves the anchor (no bypass).
- **Vehicle-movement bypass closed**: `handleMoveVehicle` was uncovered: lobby players on
  boats/minecarts (or spoofing the packet) could move freely. Now cancelled + anchored like
  player movement.
- **Inventory lock without touching chat**: the inventory is fully inert in the limbo
  (every slot click blocked and force-closed on interaction, including shift-clicks and
  armor equipping), while the chat input is NEVER interrupted, so `/register` and `/login`
  always work. A periodic force-close packet was tried and removed again: the client closes
  ANY screen (including chat) on a container-close packet, and there is no server-side
  signal for "inventory open"; click-based blocking is the only safe approach.
- **Attack-callback fix (the "can't hit mobs" bug)**: the fabric `AttackEntityCallback`
  listener was registered under the wrong method name (`attack` instead of `interact`),
  so the reflective proxy returned null for every attack and the fabric event cancelled
  ALL attacks for everyone, in and out of the lobby. One-line fix.
- **Server-side auth menu removed** (chest menu + book input + `/menu` command): auth is
  purely chat-driven with clickable buttons; the menu system, its mixins and its command
  are deleted entirely.
- **Context-aware chat buttons**: the login/register buttons build the EXACT command shape
  the player needs (password confirmation, 2FA code, captcha code) and show it in the
  action bar; no confusion about which auth factors apply.
- **Styling**: clickable chat buttons are now underlined; the action bar gets the same
  drop-shadow as titles/subtitles.
- **Crash-safe limbo verified**: the pre-limbo snapshot is saved at lock, restored before
  the fresh lock on rejoin after a crash, and deleted on clean unlock; the unlock lifts
  restrictions before any restore step so a failed restore can never keep a player stuck.

**Performance pass (constant per-packet cost, bounded memory)**
- **O(1) user lookups on every hot path**: new `User.getUser(UUID)` / `User.getUser(player)`
  . a single map get, no string allocations, no scans, no DB, and all per-packet mixin guards
  (movement, clicks, chat, ticks, entity events, commands) now use it.
- **Indexed username lookups**: precomputed lowercase names + a `byLowerName` index make
  `lookUpByUsername` mode O(1) too (previously a full-map scan with per-entry
  `toLowerCase` allocations).
- **Throttled cache touches**: the last-access map put now happens at most once per minute
  per user instead of on every packet.

**Split configuration (one file per config block)**
- `settings.conf` (root: language, debugMode, logging, cache) + `lobby.conf` +
  `session.conf` + `password-rules.conf` + `commands.conf` + `database.conf` +
  `messages-<lang>.conf`. Section files override the same block in settings.conf and are
  written with defaults on first boot; existing single-file configs migrate automatically
  (legacy sections are stripped from settings.conf on save; every setting has exactly one
  owner). Redis-distributed config overrides still merge on top.

**Hybrid / hub networks**
- Session resume no longer requires the same IP when `session.session-from-same-ip-only`
  is disabled: on proxy networks the forwarded IP can legitimately differ hub â†” game,
  which previously silently dropped sessions on every hub transfer.

**Fixed since 1.0.1**
- `ServerEventsFallbackLeaveMixin` silently missing from built jars (stale incremental
  compile state dropped the new class); rebuilt with `--rerun-tasks`; all 21 mixins
  verified present in every jar.
- Mixin handler descriptors now match the target methods exactly on 26.x
  (`placeNewPlayer` 3-arg, `tickServer(BooleanSupplier)`), so the server no longer aborts
  with `InvalidInjectionException`.

### 1.19-1.21 backward compatibility (NeoForge 21.1.x)

- **NeoForge 21.1.x boot fixed**: the `1.19-1.21` NeoForge jar crashed at startup on
  NeoForge 21.1.x (e.g. 21.1.248, Minecraft 1.21.1) with a `NoClassDefFoundError` on
  `net/minecraft/resources/Identifier` - the `ResourceLocation` â†’ `Identifier` rename
  happened at 1.21.11, so the build target's class name could not load on older 1.21.x.
  The font style no longer calls `Identifier.tryParse()` directly (it is a deliberate
  no-op - the font API keeps changing shape every version and the old reflective lookups
  hung the server thread), and `Compat`/`Lobby` no longer import either class name - the
  compat layer stays fully reflective.
- **NeoForge loader minimum corrected**: `neoforge.mods.toml` no longer requires
  `[21.11.45,)` (the build-target pin, which rejected every NeoForge 21.1.x server). The
  G2 jar now declares `[20.2.59-beta,)` (the first NeoForge supporting the group's lowest
  Minecraft version), matching the Forge/Fabric minimums.
- **gson no longer bundled in NeoForge jars**: the shaded `com.google.gson` inside the
  jar-in-jar set made the NeoForge module layer ambiguous (`com.google.gson` is already
  provided by the game) and prevented startup. Forge AND NeoForge builds now exclude gson
  from the shaded configuration; Fabric keeps the shaded copy.
- **`1.21.1` added to the host-test matrix**: the Docker harness now boots the G2 jars on
  Minecraft 1.21.1 (with the matching NeoForge 21.1.x / Fabric / Forge loaders) in
  addition to 1.19.4, 1.20.6 and 1.21.11, so this regression is caught automatically.

### Out-of-the-box experience & hardening (2026-08-14)

**Server mode is now auto-detected (`server-mode = "auto"`, the new default)**
- The real `server.properties` online-mode is read from the running server
  (`MinecraftServer#usesAuthentication`) - cracked AND premium servers work with zero config
  changes. Explicit `online`/`offline` values still override, with a one-time mismatch warning.
- Previously the default (`"online"`) kicked online-mode players on offline-mode servers with a
  bogus "Your Authentication Token is invalid" (premium-UUID mismatch against their
  offline-mode UUID) and silently auto-registered offline-mode players as premium (no
  register/login prompt, no limbo). Both are fixed.

**Premium auto-login works on offline-mode servers, outage-proof**
- Premium status of new accounts is verified **asynchronously** (IO pool) - the join path
  makes zero blocking Mojang API calls.
- If the Mojang API is unreachable at join, the player is shown "Checking your premium
  account..." and the check **auto-retries in the background** (every 20s, up to 4 min) -
  auto-register + auto-login resume the moment the API confirms the name.
- Tri-state premium lookups (`PREMIUM` / `NOT_PREMIUM` / `UNAVAILABLE`) so a cached null can
  never be mistaken for a definitive negative; API failures are throttled (30s error cache).
- HTTP 204 ("not a premium profile") now counts as a healthy API response - previously every
  offline-UUID lookup decayed the API-health window and produced bogus "Mojang API is
  currently unreachable" warnings.
- The premium-name squatting guard only fires when premium auto-login is disabled (it used to
  kick legitimate online-mode players joining offline servers before auto-login could run).
- Auto-registered online-mode players now see "Registered! Your account has been created!" as
  clear feedback.

**Messages display correctly on every version and loader**
- Title packets were broken on 26.x (the compat layer only knew the 1.16-1.21 Yarn class
  names) - every title/subtitle message was silently dropped. `Compat.sendTitle` now tries the
  26.x Mojang names (`ClientboundSetTitleTextPacket` / `ClientboundSetSubtitleTextPacket` /
  `ClientboundSetTitlesAnimationPacket`), the older Mojang fade name, Yarn names, then the
  1.16 combined API.
- Richer multi-channel templates: login, registration, wrong password, not registered, captcha,
  password change, session resume, premium auto-login and the lobby welcome now use
  title + subtitle + action bar combinations.
- Title fade timings are floored so a title can never render with 0-tick fades.

**Audit fixes (logic, errors and bypasses)**
- **Chat restriction bypass on 26.x**: the chat handler was renamed to `handleChat` - the
  mixin now targets it, so lobby players can no longer chat on 26.x.
- **Command restriction on Forge/NeoForge**: the lobby command whitelist/blacklist only matched
  Yarn names (`method_9249`); the Mojang `performCommand`/`execute` targets are now covered,
  so it applies on Forge 1.16-1.21 and NeoForge too.
- **Stale companion-token kick**: `verifySessionClaim` no longer kicks players who are already
  authenticated this join (premium auto-login etc.) over a stale/absent companion token.
- **`allowMobDamage` honored**: mobs can now target lobby players when the config allows it
  (previously the mixin blocked targeting unconditionally).
- **Hash-failure free-roam closed**: if password hashing fails during register, the player is
  locked into the lobby instead of being left authenticated-but-unregistered with full access.
- **Interop parity**: `User.login()`/`logout()` now broadcast the auth state on the
  `authcore:auth` channel - premium auto-login, session resume and deferred verification
  previously stayed silent to proxies/other mods.
- **Legacy-hash verification**: `Encrypter.verify` falls back through all supported algorithms
  instead of throwing password4j parse errors ("Bad salt length" / "Invalid salt version") on
  migrated/foreign DB rows; argon2 hashing uses an explicit spec-conformant 16-byte salt.
- **Ghost-detection window** no longer goes negative with small config values.
- **Chat commands on 1.16-1.18.2**: chat commands ride the chat packet there - the chat
  restriction now lets "/"-prefixed messages through to the command dispatcher (the lobby
  whitelist still blocks non-auth commands), so `/login` and `/register` were unblockable
  for lobby players on the classic line.
- **Adventure-mode limbo applied**: `lobby.force-adventure-mode` now actually switches the
  player into adventure on lock (the game-mode-change mixin only blocked leaving it).
- **Mode-switch / proxy safety**: online-mode players whose account is keyed by the offline UUID
  (server switched online-mode, or Velocity forwarding on an offline backend) are no longer
  kicked with the "Authentication Token is invalid" mismatch - the genuine premium profile
  is confirmed against the Mojang API and auto-logged-in instead.
- **Failed-hash guard**: a password hashing failure in `/account set-password`, email
  recovery, `/authcore set-password`, mode changes or the web panel can no longer silently
  set the stored password to null and unregister the account.
- **IP-rules whitelist semantics**: `allow` rules are no longer silent no-ops - when any
  `allow` rule exists, unmatched IPs are denied (whitelist mode).
- **Login history parity**: every login path (premium auto-login, session resume, deferred
  verification, SSO) now records a login-history row, not just `/login`.
- **Returning-premium re-validation**: on offline-mode servers the premium claim of returning
  accounts is re-checked against the Mojang API asynchronously - accounts auto-created as
  "premium" by earlier builds are downgraded to offline-mode (register/login prompt) while
  legit premium names keep auto-login (fail-open during API outages, cache-aware tri-state).
- **Mojang API removed for premium detection**: premium status now comes ONLY from the
  server's OWN Mojang session authentication - a login mixin captures the profile that
  vanilla's `hasJoinedServer` verified (carries Mojang textures properties). All direct
  Mojang HTTP lookups (name/uuid profile APIs) were removed from the join flow. On
  offline-mode servers nobody can be premium (the server authenticates no one), so offline
  players are NEVER auto-registered or auto-logged-in anymore - stale "online-mode" DB flags
  from earlier builds are detected and downgraded on join (register/login prompt).
- **Server-side premium verification (offline servers)**: with premium auto-login enabled the
  login mixin now runs the vanilla encryption handshake on offline-mode servers and verifies
  the session with the server's own `MinecraftSessionService` - genuine online-mode players are
  auto-logged-in while cracked clients (and any Mojang outage) fall back to the normal
  offline register/login flow. Fail-safe: handshake failures, API timeouts and clients that
  never answer (15s watchdog) all continue as offline - nobody is ever kicked or stranded.
- **Extra limbo restrictions**: item usage (`useItem`), riding/vehicle entry (`startRiding`),
  sleeping in beds, and offhand-item swapping are now blocked in the lobby on EVERY loader;
  players riding into the lobby are dismounted on lock. New config keys
  `lobby.allow-sleeping` and `lobby.allow-item-swapping` (both default false).
- **Full-proof post-login restore**: the snapshot restore now re-mounts the player's previous
  vehicle, lands airborne survival players on safe ground (elytra gliding / mid-air), rescues
  players from suffocation when blocks changed while they were in the lobby, keeps swimmers in
  the water column, and clears fall damage from the restore itself - flying (creative/
  spectator) is restored via the original game mode.
- **Auto-migration of everything**: ConfigMigrator now also refreshes the enriched
  multi-channel message defaults for configs that still hold the old single-channel values
  (custom messages are preserved); on first offline-mode detection the database is
  bulk-migrated once per boot (stale "online-mode" flags cleared); schema and config keys
  migrate automatically as before.
- **Command availability fix**: /register, /login and /account were reported as "Unknown
  command" on 1.20.5+ (26.x) because the OP-level permission check used APIs that no longer
  exist there (PermissionLevel / getPermissions). The check now short-circuits for level 0
  (all players) and resolves the new `net.minecraft.server.permissions` API on 26.x, the
  older permission API on 1.20.5-1.21, and the legacy method on 1.16-1.18.
- **New docs page - Authentication Flows** (`docs/1.0.0/flows.html`, linked from the nav,
  README and guide learning path): every flow explained step by step in plain language with
  the functions involved (join, limbo lockdown, register, login, session resume, logout,
  premium verification, auto-migration) plus the failure-safety guarantees.
- **Repository hygiene**: removed ~5.5 GB of generated artifacts from the working tree
  (host-test work dirs, variant build outputs, rendered site, node_modules) and removed
  dead code (unused config keys `allowOnlineNameByOffline` / `premiumApiStrict`, unused
  message template, unused User suppliers and Snapshot fields).
- Security suite now **86 checks** (legacy-hash fallback, wrong-algorithm verification,
  AuthMe `$SHA$` verification, algorithm inference, weak-algorithm detection).
- **Third-party mod integrations** (`net.ded3ec.integration`): optional, reflection-based,
  best-effort support for **DiscordSRV** (the linked Discord account is auto-imported on
  authentication so webhooks/notifications can use it) and **InteractiveChat** (compatible -
  AuthCore restrictions are lobby-scoped and never touch other mods). New `/authcore compat`
  command reports loader, server mode and integration state.
- **Version-gated config migrations** (`ConfigMigrator`): runs after config load, applies
  registered upgrade steps for newer versions, bumps `config.version` and persists - the
  pipeline for future structural config changes (1.0.0 -> 1.0.1 ships with no transforms
  needed; runtime auto-detection handles the server-mode default change).
- **Transparent password-hash upgrade**: weak (md5 / sha-256 / sha-512) or outdated stored
  hashes - including AuthMe-style imported ones - are re-hashed with the configured algorithm
  on the account's next successful login (never blocks the login, flagged in the security log).
- **AuthMe import** (`/authcore import authme <file>`): imports accounts from an AuthMe
  SQLite database; existing accounts are never overwritten; legacy hash formats are supported
  (`$SHA$`, bcrypt, argon2, pbkdf2, scrypt, plain hex digests) and verified/upgraded on login.
- Security suite now **82 checks** (legacy-hash fallback, wrong-algorithm verification,
  AuthMe `$SHA$` verification, algorithm inference, weak-algorithm detection).

### Universal single-jar architecture (1.16.x - 26.1-26.2) (2026-08-11)
- **One source, every Minecraft version** - the old per-version source sets are gone; version
  variants live under `src/` (`src/main/java` + `src/client/java` = classic yarn code,
  `src/modern/java` = Mojang 26.1-26.2 code) behind the `net.ded3ec.compat` reflection layer and
  version-stable mixin targets. Verified by compiling the identical source against **1.16.5,
  1.17.1, 1.18.2, 1.19.4, 1.20.6, 1.21.1 and 1.21.11** (all green), with a per-push CI matrix.
- **Universal mixins**: login hello (reflects over `getProfile()` vs `name()/profileId()`, plus
  authlib's `getName()/getId()` vs record `name()/id()`), handshake proxy forwarding (record
  accessor vs private field), chat restriction (single mixin covering `onGameMessage` /
  `onChatMessage` / `handleChatMessage`).
- **`FabricHooks`** registers commands (API v1/v2), item-use and damage events reflectively -
  missing fabric APIs are skipped gracefully.
- `fabric.mod.json` declares `minecraft >=1.16.0`, `java >=16`, `environment "*"` - the same jar
  runs on servers AND clients, standalone or behind **Velocity / BungeeCord**.
- Version-independent features (commands, config, database, security, web panel, email, Redis)
  work unchanged on every version.
- **Client companion always included**: the universal jar bundles the client login-screen
  companion (auto-login GUI) for 1.20.2+ clients. The companion is fully reflection-guarded,
  so the jar loads safely on older clients (1.16 - 1.20.1) and simply skips the screen there.
- If the client cannot send the auto-login command (e.g. signed-chat restrictions), the player
  gets an in-chat hint with the exact command to type instead of failing silently.
- **Limbo tick re-assert guards**: every tick the lobby re-applies the blindness/invisibility
  effects (lost e.g. by milk) and teleports lobby players back when movement is disabled and
  they drifted - fallbacks for environments where the version-specific mixins cannot apply.
- **Defense in depth**: `/login` and `/register` re-verify their prerequisites at execution
  time, not only in the command `requires` predicate.
- **Release automation**: tag-triggered GitHub workflow (`ci.yml` - the ONLY workflow now)
  builds the universal jar, extracts the changelog section for the tag and drafts a release
  with the jar attached.
- **One workflow, honest CI**: all previous workflows (build / client-check / lint-test /
  gradle-validate / dependency-audit / multi-version-check / release) were merged into a
  single `ci.yml`. Matrix builds no longer mask failures (`gradlew` exec-bit bug fixed, yarn
  versions corrected: 1.19.4+build.2, 1.20.6+build.3, fabric-api 0.46.1+1.17, 0.100.8+1.20.6).
- **Full proxy-side auth**: the proxy plugin (BungeeCord + Velocity) can now disconnect players WITHOUT a valid Redis session (block-unauthenticated=true in config/authcore-proxy.properties) before they reach any backend - zero-dependency RESP Redis client, fail-open on Redis outage, /authcore status command.
- **26.1-26.2 snapshot compile checks**: new daily CI job compiles the modern source against the NEWEST 26.1-26.2 release the moment Fabric publishes yarn mappings for it - fails visibly on breakage.
- **Velocity modern forwarding (Fabric server): HMAC-verified velocity:player_info login receiver applies the real UUID/username when velocity-secret is set; legacy/BungeeCord handshake parsing auto-detected (protocol = auto).
- **Interop channel** authcore:auth + BungeeCord AuthCore subchannel - other mods and proxies can coexist with a DIFFERENT auth mod on the backend; broadcasts on join/login/register/logout/kick/unregister (session.interop).
- **Separate database config**: optional config/authcore/database.conf (only the database { } block) is merged over settings.conf, so credentials can live outside the main config.
- **Config per role**: server = settings.conf, client = authcore-client.json, proxy = authcore-proxy.properties, database = database.conf.
- **26.1-26.2 support (real build)**: Minecraft 26.0+ is **unobfuscated** (Mojang names at runtime,
  intermediary gone), so AuthCore ships a **second jar** built from the Mojang-mapped
  source (`src/modern/java`, `-Pmodern=true`, loom 1.16.x, Java 25, no mappings):
  `authcore-modern-1.0.0.jar` for **26.0+** servers/clients. The classic universal jar covers
  **1.16.0 - 1.21.11**. Both jars carry the client login-screen companion (`environment "*"`),
  and the release workflow attaches both. The two name-spaces cannot coexist in one jar - see
  `docs/26x.md` for the migration/sync workflow.

### Multi-loader & multi-version workspace (Stonecutter / Stonecraft)

- One Mojang-mapped source tree, three range jars per loader: fabric/forge/neoforge for
  1.16-1.18, 1.19-1.21 and 26.1-26.2 (7 release jars in total).
- Verified on every range endpoint: 1.16.5, 1.17.1, 1.18.2, 1.19.4, 1.20.6, 1.21.1,
  1.21.11, 26.1.2, 26.2, all 22 harness checks PASS on all 7 loader build targets.

### Modrinth version range fix

- Uploaded jars no longer claim every Minecraft version. The shipped metadata now declares the
  exact supported range per jar, so Modrinth pre-selects precisely the tested versions instead
  of the full grid:
  - Fabric (`fabric.mod.json`): `>=1.16 <=1.18.2` / `>=1.19 <=1.21.11` / `>=26.1 <=26.2`.
  - Forge / NeoForge (`mods.toml` / `neoforge.mods.toml`): `[1.16,1.18.2]` / `[1.19,1.21.11]` /
    `[26.1,26.2]` (maven syntax).
- Loader minimums are now the group-aware floors (the first loader version supporting each
  group's LOWEST Minecraft version, never the build target - otherwise in-range servers are
  rejected): Fabric loader `>=0.14.24` (1.19 line) / `>=0.16.0` (26.1 line), FML
  `[41.1.0,)` (1.19 line) / `[36.1.0,)` (1.16 line), NeoForge `[20.2.59-beta,)`
  (1.20.2 line) / `[26.1.0,)` (26.1 line).
- Root cause: the range placeholders never reached the built jars - `fabric.mod.json` shipped
  `"minecraft": "*"` (Modrinth reads this as "all versions") and `mods.toml` shipped only the
  single build target. All 7 jars were rebuilt and their metadata verified.

### Security & anti-bypass

- **ClientGuard**: behavioral profiles, 16 detection signals (ghost clients, missing
  client settings, packet/click/chat/payload floods, tab probing, fake companions,
  confusable names, concurrent logins), weighted risk score with a decision matrix.
- **Companion attestation**: challenge-response HMAC, periodic re-challenges, session
  tokens (rotated on every login, hashed at rest) and token-based session resume.
- **MFA / 2FA**: TOTP + single-use recovery codes + optional email OTP + MFA step-up
  for sensitive commands.
- **Network SSO**: Redis-backed single sign-on across a server network (optional).
- **Error codes**: console-only AC-... codes at every failure site, decodable by the
  author; no internals leak to clients.

### Security fixes found by the new test suite
- **PBKDF2 DoS fixed** - password4j's PBKDF2 `check()` could hang the server thread during
  login. PBKDF2 is now a self-contained JDK `SecretKeyFactory` implementation
  (`$pbkdf2-sha256$iter$salt$hash`, constant-time comparison).

### Performance for 100k+ users
- **Lazy user loading** - users are fetched from the database on demand (join/login/whois)
  instead of loading the whole table at startup. A 100k-registered server keeps only online +
  recently-touched users in memory.
- **Bounded LRU cache** (20k max) with idle eviction; online users are never evicted.
- Admin `list` commands, `whois`, export and the web panel are now **database-backed** (bounded,
  searchable queries) instead of iterating the in-memory map.
- Thread-safe canonical cache (a single User instance per account under concurrent access).

### Race-condition hardening
- `AuthCoreServer.config/messages`, user session fields, `TpsManager.tickCounter` and the DB
  connection are now `volatile`.
- All database access is `synchronized` (single shared JDBC connection is never used
  concurrently).
- User cache-miss fetches are serialized under a dedicated lock.

### New features
- **Cross-server security event bus** (Redis pub/sub `authcore:events`): login, logout, register,
  brute-force, account-locked and kick events are broadcast network-wide; receivers log, webhook
  and execute remote kicks.
- **Discord account linking**: `/discord link|unlink`, 6-char link codes (webhook + Redis,
  10-min TTL), bot completion via the web panel `link` action, `discordId` stored per account.
- **Maintenance mode**: `/authcore maintenance on|off` blocks all joins with a custom message.
- **Honeypot**: a fake listener port auto-bans every connecting IP (writes `deny` rules to
  `ip-rules.conf`).
- **Automated backups**: scheduled SQLite copies / JSON exports with rotation (`session.backup`).
- **Rotating announcements**: list-based, interval-driven (`lobby.announcements`).
- **Password history**: `password-rules.history-size` blocks reuse of recent passwords.
- **Fast-rejoin alert**: bot-pattern detection (alert-only).
- **Web panel**: read-only token, `/metrics` endpoint, DB-backed player list.
- **Extra webhooks**: `security.extra-webhook-urls` (Slack/Telegram/custom).
- **`/authcore validate`** config dry-run, **`/authcore resetpw`** alias.
- New config surface for admins: `session.maintenance`, `session.honeypot`, `session.backup`,
  `session.authentication.auto-luck-perms-group`, `bind-bedrock-xuid`,
  `web-panel.readonly-token`, `rate-limit.alert-on-fast-rejoin`, `lobby.announcements`,
  `password-rules.history-size`, `security.extra-webhook-urls` - all fully commented in
  `settings.conf` and documented in `docs/CONFIG.md` with defaults + scenarios.

### Quality
- `tools/security-tests/` - standalone test suite (57 checks) covering password hashing
  round-trips, captcha lifecycle, email recovery, rate limiting, proxy parsing, device
  fingerprints. Run via `tools/security-tests/run-tests.ps1`.
- Access control validation pass: every command re-verified (player vs admin vs console),
  `/discord` guarded in lobby, read-only web token.

### Bot / backend separation

- The Discord bot integration is now strictly backend-owned: the bot **never touches the
  database**. Every write is executed by the mod backend through the web panel API; the bot
  communicates over **Redis** (link codes `authcore:discordlink:*`, mapping
  `authcore:discord:*`, `authcore:events` pub/sub) plus the API. Docs (`API.md`,
  `WEBPANEL.md`) state the rule explicitly.

### Tooling

- Host-compat harness: range/loader/version selection, forward-compat scan, live logs,
  parallel 6, professional HTML dashboard + markdown coverage matrix, 22 checks.
- GitHub Actions: builds all 7 variants, Docker host-tests on every push/schedule,
  weekly compat scan that auto-releases new validated versions with changelog entries.

### Cleanup

- Removed legacy / migration leftovers: `src/common/`, `src/client/`, `_migration/`,
  `postman/`, `release.sh`, `docs/migration.md`.
- Removed IDE artifacts (`.settings/`, `bin/`, `.classpath`, `.project`, `.factorypath`),
  stale `dist/` jars and the obsolete `authcore-26.x-*` jars.
- Fixed mojibake in the ClientGuard config comment, removed duplicated/corrupt changelog
  sections, corrected stale wording ("26.0+" â†’ 26.1-26.2, Java 17/21/25, multi-loader
  tagline).

### Single human verification (map captcha & GUI removal, intelligence overhaul) (2026-08-16)

**One verification method, scored on EVERY login**
- The map captcha and its remnants are gone completely (no map items, no map-data
  pipeline, no stale "map" text/config); the server-side screen/GUI code is removed too
  (screen mixins, force-close inventory on clicks, the `IN_LOBBY`/`OUT_OF_LOBBY` GUI
  signals - the client companion still ships its optional screens, the server no longer
  drives them).
- The legacy text captcha (`Security.CaptchaManager`), the post-login colored-items
  `HumanVerification` and the captcha-farm detection are deleted - **ActionCaptcha** is
  now the single human-verification method.
- **Every login is scored independently** - a first login does not make an account trusted,
  and an account owner can hand their credentials to a bot, so sessions are never
  pre-trusted. Signals: ghost pattern, ClientGuard risk, instant login (within
  `instant-login-sec`), failed attempts before success, missing 2FA on a TOTP account,
  fresh account age, fast rejoin loops. Trust signals subtract: premium (Mojang-verified),
  valid companion session token, previously trusted account, already passed the challenge.
  A player is challenged (sneak / jump / look-up physical task) only when the score
  reaches `lobby.captcha.bot-score-threshold` (default 60). All weights are configurable
  and every decision is traced in debug logs.
- **No chat spam**: the challenge prompt is a single message at start and one on success -
  no periodic progress spam.
- Fixed the "broken captcha" bug: trusted/TPS-bypass players were blocked at `/login`
  because the pre-auth gate checked `captchaVerified` while the lobby skipped issuing the
  captcha. The pre-auth gate is gone entirely; `/login` and `/register` no longer take a
  `captcha-code` argument.

**Debug logging everywhere, off by default**
- `debug-mode` now defaults to **false** (it was `true`). With `debug-mode = true` admins
  get a full trace of where and when the mod decides: join classification, lobby
  lock/unlock, login attempts, every ClientGuard signal (name + weight + description),
  every AuthIntelligence detection (flood/spray/2FA brute/registration-farm/session-replay/
  ATO with counters and windows), the complete human-verification score breakdown, web-panel
  lockout tracking, webhook delivery and database dialect selection.

**Host-test harness rebuilt (correctness + speed + player simulation)**
- Verdict gaps closed: `securitySummary`, `maintenance`, `portListen`, `panelBadToken`,
  `panelLockout` now actually fail the run (they rendered FAIL cells while the run said
  PASS).
- The graceful-stop check was accidentally nested inside the honeypot `if` - it is now
  top-level and always runs.
- Player-simulation bugs fixed: the global timeout now writes its check file (a crashed/
  timed-out sim used to count as a silent green PASS), the entrypoint captures the sim's
  real exit code, markers reset per reconnect (the limbo prompt was never re-verified),
  the post-login chat check could never detect new violations (stale "before" snapshot),
  and a TDZ crash on every sim connect (`endPromise` referencing `handle` during its own
  construction) is fixed.
- JSON escaping on `fail()` and the result writer (quotes/backslashes can no longer
  corrupt `result.json`); parallel crash-recovery keeps the test identity (the caught
  exception overwrote `$_` â†’ blank FAIL rows).
- Speed: JBR image builds run in parallel, shorter fixed sleeps (post-Done 5sâ†’2s, command
  cadence 3sâ†’2s, sim violation loop 60sâ†’45s, post-login wait 7sâ†’4s), default boot timeout
  900sâ†’480s, container log poll 2sâ†’4s. Sim SKIPs (no protocol data for brand-new MC
  versions) render as n/a instead of FAIL cells, with the reason in the report.
- The player simulation keeps using `minecraft-protocol` (the standard packet-event client;
  `protocolob` does not exist on npm). Verified: 1.18.2 / 1.21.11 / 26.1.2 resolve and
  connect; 26.2 cleanly SKIPs until protocol data is published.

**Docs & setup guide**
- New "Feature-by-feature setup" section in the guide: every feature (human verification,
  MFA, brute force, sessions, ClientGuard, AuthIntelligence, rate limits/IP rules, SSO,
  web panel + honeypot, premium/proxy, maintenance/shadow-ban/whitelist/announcements,
  backups/Discord/locales) with what it does, the exact config, and a real-world scenario.
- README gained a "Feature setup at a glance" table; the config reference documents the
  new captcha scoring settings.

---

### Early development history (1.0.0-alpha.1 -> alpha.5)

The five pre-release milestones (initial framework with hybrid auth, limbo, sessions and
2FA; per-hash salts, no enumeration, console commands; outage-safe online-mode detection;
combat-log punishment, webhooks, intelligence, Redis/PostgreSQL, AuthCoreApi; proxy
support, web panel, email recovery) are all superseded by - and fully documented inside -
the merged [1.0.0] section above.