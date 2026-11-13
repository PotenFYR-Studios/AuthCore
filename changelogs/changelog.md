<div align="center" style="font-family: 'Clash of Clans', 'Comic Sans MS', 'Comic Sans', cursive;">

# AuthCore Changelog

All notable changes to AuthCore, from the first alpha to the current release.

[![Version](https://shieldcn.dev/badge/version-1.0.3-blue.svg)](https://github.com/PotenFYR-Studios/AuthCore/releases) [![Build](https://shieldcn.dev/github/ci/PotenFYR-Studios/AuthCore.svg)](https://github.com/PotenFYR-Studios/AuthCore/actions) [![Back to README](https://shieldcn.dev/badge/%F0%9F%93%9A-Back_to_README-5865F2.svg)](https://github.com/PotenFYR-Studios/AuthCore/blob/main/README.md)

</div>

---

## [1.0.3] - 2026-11-08

> Follow-up to the issue #11 reopen ("I used the new version and it happened again - there
> wasn't any death message"). Root-cause analysis of what the 1.0.2 safe-landing fix still
> missed, the remaining restore-death holes, the limbo anchor fall/snap loop, and the
> diagnostics that make the next report actionable.
>
> **Second pass (2026-11-08)** - the reporter's "it happened again, there wasn't any death message"
> traced to two causes the first pass did not cover (the combat-log kill on logout and the limbo
> healing a player who joined dead), plus an audit of every reflective call, the crash-recovery
> snapshot, credential commands, offline-server premium verification, the database write path and
> the red CI host-tests. The first-pass notes follow the second-pass sections below.

### Upgrading to 1.0.3 - what server owners need to do

> 1.0.3 is rebuilt in place: the release keeps one version number and its jars are replaced on
> every green build. **If you already run a 1.0.3 jar, download it again** - the premium fixes
> in the next section are only in jars built from 2026-11-11 on (compare with `SHA256SUMS` on the
> release).

**Everyone, from any version**

1. Back up `config/authcore/` and the world before the first boot. Take the AuthCore backup with
   `/authcore backup`, not by copying the `.db` file: a raw copy of a SQLite database in WAL mode
   can miss committed data.
2. Replace the jar with the one for your loader and Minecraft version. Behind BungeeCord/Velocity,
   update the proxy plugin together with the backend mod - the interop messages changed.
3. Boot once and read the log. New config and message keys are added automatically; your existing
   values are never overwritten, so **changed defaults do not reach an existing config**:

   | Setting | Old value an existing config keeps | What to do |
   | --- | --- | --- |
   | `session.combat-log.detect-from-mobs` | `true` - a single mob hit counts as combat, and a disconnect within the combat window kills the player | Set it to `false` (the new default), or set `kill-on-disconnect=false` in the same block to disable the punishment. This was a cause of issue #11. |
   | web panel `https-enabled` | `false` - the bearer token travels in clear text | Set it to `true` if the panel is reachable from outside the machine, then use `https://` and the configured https port. |
   | `alert-on-fast-rejoin` | `false` | Optional. Alert-only. |
   | `allow-bedrock-players` | `false` | Optional. No effect without Geyser/Floodgate. |

4. **Fabric and Forge: `kill-on-disconnect` now really kills.** The kill silently did nothing on
   those loaders before, so a server that had it enabled starts punishing combat loggers with
   this update. Check the combat-log block before players find out.
5. Settings that were documented but did nothing now take effect without a config change:
   `lobby.safe-operators`, `lobby.limbo-config`, `lobby.prevent-status-effect`,
   `allow-entity-interact-with`, `allow-crafting`, `allow-mountable-*`, auto-whitelist,
   `client-guard.allowed-name-regex` (new registrations only), `authentication.auto-luck-perms-group`,
   `captcha.trigger-risk-threshold`, `password-rules.history-size`. Review them if you ever set them.
6. Removed keys are ignored: `skip-combat-detection`, `bind-bedrock-xuid`, `risk2fa-threshold`
   (2FA is always required once it is configured).
7. `ip-rules.conf` takes one rule per line (`deny 45.155.0.0/16`). The `deny = [...]` list shown in
   older READMEs was never parsed - rewrite such rules; malformed lines are now logged with their
   line number.
8. Proxy networks: the `velocity-secret` setting and the built-in Velocity modern-forwarding
   receiver are gone. Use FabricProxy-Lite / Proxy-Compatible-Forge for modern forwarding; legacy
   (BungeeCord) forwarding is read natively. IP forwarding never worked before 1.0.3, so GeoIP,
   rate limits, IP rules and same-IP sessions now see real client addresses for the first time.
9. Forge: the 1.16-1.18 jar loads on Forge **1.18-1.18.2 only** (Forge cannot run Java 17 before
   1.18). On Forge 1.16/1.17 use Fabric.

**Premium players on an offline-mode server** (all versions - see the next section for the cause)

- A premium player who never set a password is logged in automatically again after this update;
  nothing to do.
- A premium player who was pushed through `/register` by an older build now owns a **password
  account**. They log in with that password once and run `/account set-mode online` (or an admin
  runs `/authcore set-mode online <player>` while they are online). From the next join they are
  logged in automatically.
- Minecraft 1.16 - 1.19.0: the client does not send its account id, so AuthCore can only verify
  names whose account is already online-mode. Premium players register once and run
  `/account set-mode online`; every later join is verified and automatic.
- **Risk to know:** an online-mode account without a password can now only be joined with a
  Mojang-verified session. While Mojang's session servers are unreachable those players are
  disconnected with a clear message instead of being offered `/register` (which let anyone set a
  password on their account). Players who want a fallback set a password and stay in offline-mode
  authentication.
- Default skin: fixed by the new `premium-skins` option (on by default). Real UUIDs: opt-in, read
  the `premium-real-uuid` notes below before enabling it.

**Coming from 1.0.2**

- Nothing extra. If players died on login (issue #11): the cause was the combat-log default in
  step 3 plus the limbo healing a player who joined dead; both are fixed, items already lost are not
  restorable.

**Coming from 1.0.1** - everything above, plus

- Fabric 1.16.5 / 1.17.1: password hashing was completely broken on 1.0.1 (registration and login
  could not work). Fixed by the jar; no data to repair.
- A `PASSWORD_HISTORY` table is created on boot. Password reuse is now really blocked and weak
  passwords are rejected by `/account set-password`, admin resets and the web panel.
- Discord linking through the web panel: the bot must send the real numeric Discord id together
  with the link code. Update a custom bot. A link created on 1.0.1 may have stored the code in
  place of the id; re-link any account whose role sync does not work.
- Web panel CORS is same-origin only - a dashboard hosted on another origin stops working.
  If the panel token was ever used from a browser on 1.0.1, rotate it.
- Redis config overrides are no longer written to disk. If your Redis was reachable by anything
  untrusted, compare `config/authcore/` with a backup: an override could have been persisted.
- Usernames outside `[A-Za-z0-9_.-]{1,16}` are rejected at join.
- Several limbo bypasses existed (UUID-drift on offline servers, 26.x melee, inventory clicks,
  portals). If you suspect abuse, review `security.log` for the period you ran 1.0.1.

**Coming from 1.0.0** - everything above, plus

- 1.0.0 crashed Fabric/NeoForge 26.1 and 26.2 servers when a player joined (issue #10). Fixed by
  the jar.
- The limbo command gate could be bypassed (`msg bob hi /login` passed as `login`): an
  unauthenticated player could run arbitrary commands. Review the server log / `security.log` if
  the server was public on 1.0.0.
- On the 1.16-1.21 jars every `/authcore` admin command was available to **any** player
  (permission check failed open). After upgrading, admin commands are denied when the permission
  API cannot be resolved - use LuckPerms nodes or the console. Review what was run while on 1.0.0
  / 1.0.1 / 1.0.2 (bans, `set-mode`, password resets) and reset passwords of staff accounts if in
  doubt.
- New passwords are always hashed with argon2 when `password-hash-algorithm` is `md5`, `sha-256`,
  `sha-512` or unknown. Old hashes keep working and are upgraded on the next login.
- TOTP codes are single-use: a player who just logged in waits for the next code to log in again.

### Premium players on offline-mode servers: verified but left in the limbo; skins; real UUIDs (2026-11-11)

> Reports: "I have a legitimate account and the premium option is enabled, but it asks me to
> register", then on the current 1.0.3 build "it says I was verified but it is still trapping me in
> the limbo" and "I am getting the default skin" - Fabric 26.3, offline-mode server.

- **Verified premium players were still sent to the limbo.** The Mojang verification added in the
  second pass worked, but auto-login was only granted to a brand-new account or one already
  flagged online-mode. A premium player who had joined under 1.0.0 - 1.0.2 (which never verified
  real accounts on offline servers) already had an ordinary account, so the server logged
  "session verified" and then showed the register prompt. A verified session is now logged in
  automatically whenever the account has no password; the account is flagged online-mode.
- **Online-mode flags were wiped on every restart of an offline-mode server.** A boot-time
  "migration" from the time when offline servers could not verify anyone cleared the flag of every
  account, so even a correctly auto-logged-in premium player was back at the register prompt
  after the first restart. It no longer runs when AuthCore verifies sessions itself.
- **One unverified join cost the real owner their auto-login.** A join that was not verified
  switched the account to offline-mode in memory and never switched it back, so anybody typing a
  premium player's name disabled that player's auto-login until the account left the cache. The
  switch is now per session: it is undone when the session ends unless a password login proved
  ownership, and it is never written to the database before that.
- **Account takeover (high): `/register` onto a premium player's account.** On an offline-mode
  server everyone claiming a name shares its offline UUID. An unverified client using a premium
  player's name was offered `/register` (the account has no password) and received the account and
  its inventory. Such a session is now disconnected (new message
  `promptUserPremiumSessionUnverified`, translated in all bundled locales). This only became
  reachable once verification started working, i.e. in earlier 1.0.3 jars.
- **Minecraft 1.16 - 1.19.0 on offline-mode servers.** Those clients send no account id, so
  verification never started. Names whose stored account is online-mode are now challenged (the
  account lookup runs off the network thread). An account with a password gets one unchallenged
  attempt after two unanswered challenges, so an owner on an offline client can still get in with
  the password.
- **New: `session.authentication.premium-skins` (default `true`).** Verified premium players get
  their real skin on offline-mode servers. The offline UUID is kept, so no data moves. Not applied
  on 1.19 - 1.19.2, where the client's chat key is checked against the join profile.
- **New: `session.authentication.premium-real-uuid` (default `false`).** Verified premium players
  join with their real Mojang UUID, as on an online-mode server. **Read before enabling on an
  existing server:**
  - The UUID is the player's identity everywhere. AuthCore moves the vanilla files
    (`playerdata`, `advancements`, `stats`) from the offline UUID to the real UUID on the first
    verified join, rewrites the UUID stored inside the player data, and renames every moved file to
    `<name>.authcore-bak`. Whitelist and operator entries are carried over; a banned offline
    profile stays banned.
  - **Not migrated:** anything other mods or plugins store per UUID (LuckPerms, claims, economy,
    homes, waystones ...) and references to the old UUID inside the world (tamed animals, owned
    entities). Migrate those with the mod's own tools (LuckPerms: `/lp user <old> clone <new>`), or
    accept that the player starts fresh there. Test with one account first.
  - The real UUID is used when the session is verified **and** the account for that name is new,
    is online-mode, has no password, or already lives under that UUID. A premium player who owns a
    password account keeps the offline UUID until they run `/account set-mode online` - a premium
    account must not be able to take a same-named offline player's inventory just by connecting.
  - While enabled, an account stored under a real UUID can only be joined with a verified session.
    A Mojang outage keeps those players out until it ends. Offline-mode players are unaffected.
  - Turning the option off does not move data back. To undo it for a player, stop the server and
    restore the `.authcore-bak` files (remove the suffix) in `playerdata`, `advancements`, `stats`.
  - Best fit: a new server, or an online-mode server that is being switched to offline-mode
    (premium players then keep their existing data instead of losing it to the offline UUID).
- Both options need `premium-auto-login`, an offline-mode server and `proxy-support` disabled, and
  apply on every loader and version group (1.16-1.18, 1.19-1.21, 26.x).
- Tests: 40 new checks for the identity decision, the in-file UUID rewrite, the file move
  (including "never overwrite" and "fail without changing anything") and the 1.16 - 1.19.0
  challenge rules.

### Forge 1.16-1.18: mixins never loaded; commands only shown when they make sense (2026-11-09)

> Report: "/register and /login are missing (red in chat), even for ops; premium players are not
> logged in automatically" - Forge 1.18.2, Vault Hunters 3rd Edition.

- **Forge 1.16-1.18 jar loaded none of AuthCore's mixins.** The mixin config was only declared
  in the `[[mixins]]` block of `mods.toml`, which legacy Forge does not read (it is a NeoForge /
  modern-Forge feature) - old FML only applies configs named in the jar manifest. All 27 mixins were
  inactive on that jar: no premium (online-mode) verification, so premium players were never
  auto-logged-in, and no packet-level limbo guards or command gate. The Forge build now writes
  `MixinConfigs` into the manifest. Fabric (fabric.mod.json) and NeoForge (mods.toml) were not
  affected.
- **The command list is re-sent on every auth-state change.** Vanilla sends a player's command
  tree once, while placing them in the world - before AuthCore knows them. It is now re-sent when
  the limbo starts, after any login (password, premium auto-login, session resume, SSO), on
  `/authcore reload` and when a Discord link changes. All versions and loaders.
- **Every AuthCore command has one state rule** (`CommandVisibility`) that decides both whether it
  is offered and whether it runs:
  - **Players** are offered a command only while it applies: `/register` for an unregistered
    account in the limbo, `/login` for a registered one (both disappear after login);
    `/account recover` before login (SMTP configured); `logout`, `unregister`, `set-password`
    after login on a password account; `nickname` after login; `set-mode online` only for an
    offline-mode account (premium auto-login on), `set-mode offline` only for a premium account;
    `codes` only with TOTP set up; `email` only with SMTP; `/discord link` after login when not
    linked and a bot can receive the code, `/discord unlink` when linked. `/account` and
    `/discord` show only while one of their subcommands does (`/account logout` and both
    `/discord` subcommands used to be offered in the limbo).
  - **Admins** (console, OP level 2+, or any AuthCore admin permission) see every AuthCore
    command they have permission for, in every state.
  - **Running a command in the wrong state** - possible for admins, or by typing it - never runs
    it and never shows "Unknown command": the player gets the reason ("You are already logged in.",
    "Please log in first.", "You already have an account - use /login <password>.", "This feature
    is not available here.", "Your account already uses automatic login." ...). Several executors
    had no state check of their own (`set-mode`, `nickname`, `discord link/unlink`, `recover`).
  - `/authcore`: the root opens for ANY admin permission (it required the reload permission,
    locking out staff with narrower nodes); a player must be logged in to run any subcommand (an
    admin name in the limbo keeps its LuckPerms nodes and is told to log in first - no lobby
    violation); `map-image` gets its own permission check (it had none and relied on the root);
    `map-image`, `network-ban/unban` and `simulate` explain when their feature is off; branches
    with nothing permitted below them are hidden.
  - Limbo players do not see vanilla or other mods' commands the lobby gate would reject.
- **Every player-facing AuthCore text is now in messages.conf.** New keys (`prompt-user-...` / `prompt-admin-...` in messages.conf):
  `already-logged-in`, `login-required`, `already-registered-use-login`, `feature-unavailable`,
  `no-password-account`, `mode-already-set`, `invalid-username`, the limbo hint
  (`lobby-login-button`, `lobby-register-button`, `lobby2fa-hint`), the captcha tasks and
  `captcha-passed`, and the admin feedback for maintenance, backup, network bans and import usage
  (these were hard-coded, the network-ban results always in red).
- **The limbo hint shows the real command:** `<confirm>` only when password confirmation is on,
  `<2fa-code>` only when the account has a TOTP secret.
- **Bedrock setting no longer kicks everyone:** with `allow-bedrock-players` on and Floodgate not
  installed, every offline-mode login was disconnected with "Floodgate is not installed". Bedrock
  players are only recognised through Floodgate, so the check is gone.
- **Defaults** (new installs; existing configs keep their values): `allow-bedrock-players = true`
  (Bedrock players are recognised only through Floodgate, so it is a no-op without Geyser),
  web panel `https-enabled = true` (the bearer token no longer travels in clear text by default),
  `alert-on-fast-rejoin = true` (alert-only). Premium auto-login was already on by default.
- **Tests:** command-tree pruning checks - 287 security checks total.

### Verification pass - API lookups that only worked under development names (2026-11-09)

> Follow-up audit of the Forge 1.18.2 report: every mixin target and every direct Minecraft
> member reference of all six jars was checked against the real runtime classes (SRG, intermediary,
> Mojang) - all resolve. Several REFLECTIVE lookups did not: they named Yarn or Mojang members as
> strings, which the build never remaps, so they silently did nothing on the affected servers.

- **Proxy IP forwarding never applied (all versions).** The handshake host was read by the Yarn
  name `address`, which no runtime has; behind BungeeCord/Velocity legacy forwarding every player
  kept the proxy's IP (GeoIP, rate limits, IP rules, same-IP sessions). The field is now located by
  its shape.
- **Crash-safe limbo snapshot.** Inventory (with `hide-inventory`) and effects were serialised by
  name - `""` on every Fabric 1.16-1.21 and Forge server, so a crash during the limbo lost the hidden
  inventory. Direct calls per era now, through registry-aware codecs on 1.20.5+.
- **Limbo dimension / crash-recovery position.** Dimension keys were built from Yarn classes that
  never exist: `lobby.limbo-config` (on by default) and the recovery teleport were silently skipped
  everywhere. Levels are now matched by id; an unknown dimension is logged.
- **Clickable /login and /register hints** rendered as plain text on Fabric and Forge; **limbo
  position correction** was never sent on Fabric 1.21.2-1.21.11; **`allow-mountable-*`** never
  matched a mount; **auto-whitelist** never added anyone. All fixed.
- **Interop plugin messages** (`AUTH_CHANGED` for proxy gates, companion messages) were never sent
  on any version. 1.16-1.18 send them directly; 1.20.2+ append the bytes to an empty vanilla payload
  on the netty pipeline (no per-loader codec or NeoForge channel negotiation needed). The proxy side
  was fixed too: Velocity never registered the interop channels, so backend messages were dropped
  before the listener ran (the gate then blocked every transfer); both BungeeCord and Velocity now
  register `authcore:auth` + `bungeecord:main`, consume client-sent messages on them, and accept
  only a well-formed `AUTH_CHANGED|<uuid>|<username>|<1|0>` with a canonical UUID. Proxy gates no
  longer need Redis to see logins.
- **Velocity modern forwarding** was never functional on the backend (no login query was sent, and
  the parser expected a payload shape Velocity does not send). The dead receiver, its unused
  `velocity-secret` option and the misleading "HMAC verified" documentation are gone: modern
  forwarding is performed by FabricProxy-Lite / Proxy-Compatible-Forge, and `protocol = "velocity"`
  now logs one clear notice instead of silently no-op IP forwarding. Legacy (bungeecord)
  forwarding is read natively.
- **Forge 1.16-1.18 jar declares Minecraft `[1.18,1.18.2]`** (Forge 38+). Forge 1.16/1.17 cannot
  load it; they now get a clean version error instead of a crash.
- **Self-test:** new `snapshot-serialization` check (21 checks).

### Host-harness fixes it caught (2026-11-09)

- **Forge/NeoForge admins are no longer told to install Fabric API.** The "fabric-api missing"
  notice only fires on a Fabric server now (the old check compared the loader string against
  "FML", which no loader reports, so every Forge/NeoForge boot carried a Fabric-only warning).
- **The mixin config declares `minVersion: 0.8`**, which removes the Mixin ERROR legacy Forge
  printed on every boot ("does not specify minVersion property").
- **The web-panel self-signed certificate notice is INFO, not WARN.** Generating the certificate
  on first boot is the documented behaviour, so it must not read as a problem.
- **The host harness exercises the panel it actually gets:** HTTPS on the configured https port
  with the configured token (it was curling plain HTTP on the HTTP port after HTTPS became the
  default, so the auth/CORS/discord-link checks could never pass). All six variant boots
  (1.18.2 fabric/forge, 1.21.11 fabric/neoforge, 26.3 fabric/neoforge) pass locally.

### CI - verified artifacts, fast metadata failures (2026-11-09)

- **Two fast jobs run beside the build.** `guard` fails the run in seconds when `mod.version` has
  no changelog section, disagrees with the version the mod reports at runtime, or when a
  `versions/*` node is missing from the build matrix; `lint` runs actionlint over the workflow so
  a broken expression or stale action major is caught before it burns a build leg.
- **Jar verification now covers what actually breaks at runtime:** the Mixin config declaration
  (fabric.mod.json, mods.toml, neoforge.mods.toml) *and* the legacy Forge `MixinConfigs` manifest
  entry, the proxy descriptors' version (templated from `mod.version` - `bungee.yml` and
  `velocity-plugin.json` no longer carry a hardcoded copy), and the bytecode target of each group
  (classfile 61/65/69) so a toolchain change can never ship Java 25 classes to a 1.16 server.
- **Releases carry `SHA256SUMS` and a build-provenance attestation**, and the publish step
  re-downloads its own assets and verifies them (`sha256sum -c`, 6 jars + checksums) before the
  run is green.

### Security pass - brute-force, rate-limit and step-up bypasses (2026-11-09)

- **Eight wrong passwords no longer lock an account out for good.** The failure counter was never
  reset by a reconnect or by the lock expiring, so after the lock ended the owner's FIRST `/login` -
  even with the correct password - was kicked and re-locked. Anyone could permanently lock any
  account. Failures now only count when a password check really fails; the brute-force kick resets
  the per-session counter (the cooldown is the punishment) and a separate streak drives the account
  lock, which is reset once the lock is applied.
- **The brute-force kick cooldown is enforced.** The kick used a path that never armed the
  cooldown, so the attacker could rejoin instantly and progressive punishment never escalated. The
  progressive cooldown is now stored and checked at join; rejected rejoins no longer extend it, and
  the "wait" message shows the real remaining time (it printed a negative duration).
- **2FA brute force on `/register`.** Re-registering an account that still had a TOTP secret
  checked the code with no replay protection and only the 1 s command cooldown. It now uses the
  shared 2FA budget, single-use codes and the 5-strike lockout of `/login`.
- **`/register` no longer hashes on the server thread.** Argon2id (64 MiB) ran on the tick thread
  for every registration, so a handful of bots could lag the server. The hash runs on the IO pool
  with the same concurrency cap as `/login`; the result is applied after re-checking the limbo state.
- **`/account email` and `/account codes` need the same step-up as a password change.** The
  recovery email is a password-reset channel and recovery codes are a second factor: both could be
  changed/printed from a silent same-IP session resume or without the second factor
  (`require-mfa-for-sensitive` promised otherwise). Both now require the password proven on this
  connection and, with 2FA on, the verified second factor.
- **Recovery codes are bound to the account.** Codes were keyed by email only, so a code issued
  for one account was redeemable on another account sharing the address. Code redemption also gets a
  per-account budget (10/hour), and a completed recovery clears the lock, old recovery codes, the
  TOTP replay history, the companion session token and the network session.
- **Rate limiter: floods can no longer reset security budgets.** All limits shared one bounded map;
  a join flood from 10k fresh addresses evicted the 2FA/recovery counters. Limits now live in
  per-namespace buckets, blocking windows are evicted last, the window slides (no 2x burst across a
  window boundary) and IPv6 clients are aggregated per /64 for join, rejoin and 2FA budgets (the 2FA
  account budget is checked first, so IP rotation cannot reset it).
- **Web panel brute-force lockout.** Cycling through 2048 source addresses made the panel clear its
  whole lockout table (including the attacker's lockout), and every IPv6 address got its own
  budget. Lockouts are now per address / IPv6 /64, are never cleared wholesale, and a global budget
  (30 failures/minute) restricts token attempts to recently authenticated addresses while it is
  exceeded.
- **Tests:** 16 new checks (bucket isolation, eviction order, sliding window, /64 aggregation,
  2FA budget vs. IPv6 rotation, account-bound recovery codes) - 281 security checks total.

### Settings and features that only half worked (third pass, 2026-11-08)

- **`/authcore reload` now re-reads `ip-rules.conf`.** The rules file is separate from the HOCON
  configs and was only loaded at boot (or, by accident, on the next honeypot hit) - an edited
  allow/deny list silently did nothing until a restart.
- **IPv6 in `ip-rules.conf` and `trusted-proxies`.** CIDR matching was IPv4-only: `deny
  2001:db8::/32` never matched, and exact IPv6 rules only matched the identical spelling (`::1` vs
  `0:0:0:0:0:0:0:1`). Both address families now match by value with full CIDR support; IPv4-mapped
  IPv6 addresses (`::ffff:1.2.3.4`) hit IPv4 rules; hostnames are never resolved. Malformed rule
  lines are logged with their line number instead of being dropped silently, and trailing
  `# comments` after a rule are allowed. Honeypot bans strip IPv6 zone ids so their rule lines stay
  valid.
- **Maintenance mode no longer locks out the admins.** It kicked every join, including the
  operator who turned it on. Operators (`maintenance.bypass-operators`, default `true`) and holders
  of the LuckPerms node `authcore.maintenance.bypass` (`maintenance.bypass-permission`) can now
  join - and still authenticate in the limbo as usual.
- **Shadow-ban covers every security block.** It only applied to the new-country block; it now
  also hides the reason for `ip-rules.conf` denials and network bans behind the generic
  disconnect message.
- **Docs:** the README promised ASN rules and showed a `deny = [...]` list syntax that the parser
  never accepted - both corrected to the real one-rule-per-line format.
- **Tests:** 11 new checks (IPv6 CIDR boundaries, family isolation, mapped addresses, no DNS
  resolution, rule-value validation, socket-address normalization) - 265 security checks total.

### Issue #11 (second pass) - the remaining "killed on login, no death message, everything lost" causes

- **The limbo no longer heals (and the restore no longer revives) a player who joined DEAD.**
  `prevent-damage` topped every limbo player up to max health - including a player whose saved
  data was already dead (they quit on the death screen, or the combat-log punishment killed them
  on logout). On 1.0.2 the unlock then restored the snapshot's 0 HP: the player "died" the moment
  they logged in, with no death message (no damage event ever happened) and no item drops. 1.0.3's
  health clamp turned that into a free revive at 1 HP at the death position (a death/combat-log
  bypass). A dead snapshot now keeps vanilla semantics: the death screen stays, the player
  respawns normally, and if they respawn while still in the limbo they are released at the
  respawn point vanilla chose (recorded on respawn, the new entity is re-shielded). XP is not
  restored for a dead snapshot (it would duplicate the dropped orbs).
- **Combat-log punishment only punishes a disconnect the player chose.** It used to kill on ANY
  disconnect inside the combat window: AuthCore's own kicks, duplicate-login kicks, vanilla
  kicks/timeouts, a server stop/restart, and players still unauthenticated in the limbo. All of
  these are now exempt (logged at INFO with the reason), the combat window is reset after every
  leave (a quick rejoin + leave was punished twice), and an already-dead player is never killed
  again.
- **Combat logging is PvP-only by default.** `detect-from-mobs` now defaults to `false`: one
  zombie hit followed by a disconnect cost the player every item. Existing configs keep their
  value - set `session.combat-log.detect-from-mobs=false` (or `kill-on-disconnect=false`) to opt
  out on an existing server.
- **The kill is visible.** The kill is logged at INFO (it happens while the player disconnects, so
  on the next join they only see a cause-less death screen), and the player is told why on the next
  join (new message `promptUserKilledForCombatLog`).
- **`Compat.kill` works on every loader.** It used Mojang-name reflection, so the same config killed
  combat loggers on NeoForge/26.x and silently did nothing on Fabric (intermediary) and Forge (SRG).
  Direct, build-remapped calls now (`kill()` / `kill(ServerLevel)`, `setHealth(0)+die(genericKill)`
  on the 1.19-1.21.1 runtimes inside the range jar); when a runtime has no safe kill path the
  player is not punished instead of being left half-dead without drops.
- **Fall distance is actually reset on Fabric 1.16-1.21.** The reflective fall-distance write used a
  wrong intermediary id (`field_23362`; the real one is `field_6017`), so every earlier "pin the
  fall distance" step was a no-op on Fabric. `resetFallDistance()` is now called directly.

### Crash-recovery snapshot (item loss / stuck in Adventure)

- **The crash snapshot stores the PRE-limbo state.** It was serialised from the live player AFTER the
  limbo had emptied the inventory, replaced the effects with infinite invisibility, forced Adventure
  and topped up health. A server crash during the limbo therefore wiped the inventory on recovery,
  restored Adventure as the "original" game mode and granted permanent invisibility after login. The
  file is now built from the snapshot, written atomically, and carries the operator status (see
  below) so a crash can no longer deop an admin for good. Old-format files are applied safely
  (position only; a forced Adventure mode falls back to the server default).

### Never-working reflection (Yarn names that exist on no runtime)

Production runtimes use intermediary (Fabric), SRG (Forge) or Mojang names (NeoForge, 26.x) - never
Yarn. These lookups therefore failed everywhere and silently fell back:

- **`lobby.safe-operators` never deopped anyone** (`PlayerManager$PlayerConfigEntry`,
  `isOperator`, `addToOperators`...). Rewritten with build-remapped calls (GameProfile API on
  1.16-1.21.8, NameAndId API on 1.21.9+/26.x) plus a signature-shape fallback for the older runtimes
  inside the 1.21.11 range jar. The exact level and bypass flag are restored, and AuthCore's own
  temporary removal is never mistaken for an admin deop.
- **Yaw/pitch** (`getYaw`/`getPitch`): every limbo/restore teleport snapped the view to 0/0.
- **Latency** (`networkHandler`/`pingMilliseconds`): the ping-based login timeouts never applied.
- **Gliding/elytra stop, frozen ticks, world bottom/top, world spawn**: now direct calls with
  name fallbacks for Mojang-named runtimes across renames.
- **Player-level permission checks** (`hasLevel`): read "no permission" on Fabric/Forge; now
  checked through the player's own command source with the era's API.
- **`Compat.sourcePlayer`** had no SRG candidate: on Forge every player command could read as
  "console". Uses `CommandSourceStack#getEntity()` directly now.
- **Limbo sneak/sprint blocked on Fabric/Forge**: the player-command guard read the action by the
  Mojang name `getAction`, failed, treated it as UNKNOWN and cancelled it - which also made the
  sneak task of the human-verification captcha impossible there.
- **`allow-entity-interact-with` and `allow-crafting` never took effect**: the interact packet and
  container-click slot were read by names that do not exist; both use the packets' own API now.

### Security

- **Credential changes need the password on THIS connection.** `/account set-password` and
  `/account unregister` were allowed in any authenticated session - including a RESUMED one
  (same-IP session resume, SSO, companion token). A second person behind the same IP within the
  session window could replace the password or delete the account and register their own (account
  takeover without knowing the password). Both now require that the password was proven on the
  current connection (`/login`, `/register`); otherwise the player is asked to log in again
  (new message `promptUserReauthRequired`). `/account unregister` also gets the same MFA step-up as
  a password change.
- **Offline-server premium verification challenged the wrong clients.** It only started the
  handshake for clients claiming the OFFLINE uuid (cracked clients, which can only fail it) and
  skipped every genuine premium client (which sends its real account uuid), so premium auto-login on
  offline servers never triggered for real accounts. It now challenges only clients claiming a real
  account uuid, keys the marker by the offline uuid the join actually uses, and requires the
  verified profile's name and uuid to match the claim. The whole handshake (fields, packets, crypto,
  session service, hello replay) now uses build-remapped calls and type-based field lookups, so it
  also works on Fabric (intermediary) and Forge (SRG) instead of silently falling back to offline.
- **Premium-verification markers are bound to the network connection.** On an offline server every
  client claiming a name shares the same offline UUID; a marker set by a genuine premium player's
  handshake could be consumed by a different (cracked) connection racing the same name to the join
  (premium auto-login takeover). Markers are now single-use AND connection-bound; an offline
  verification whose connection cannot be resolved is never trusted.
- **Password recovery** honours the password history and can no longer leave an account without
  credentials when hashing fails (it used to null the password = silently unregister).

### Performance

- **User rows are written behind the tick.** Every `User#update` (join, leave, login, IP refresh,
  security events) ran JDBC on the server thread and also waited on the serialised shared
  connection - a network round-trip + transaction per call on MySQL/PostgreSQL. Rows are now
  captured on the calling thread (immutable copy) and written by one ordered writer thread,
  coalesced per account; deletes and backups flush first and a JVM shutdown hook drains the queue.
  Failed-login audit rows use the same writer. Emergency switch: `-Dauthcore.syncWrites=true`.
- `/account set-password`: the reuse check, the history verification (up to `history-size`
  memory-hard hashes) and the new hash run on the IO pool instead of stalling the tick; the change
  is applied on the server thread only if the password did not change meanwhile, and a hashing
  failure no longer leaves the account without a password.
- Legacy-hash upgrade on /login (argon2/bcrypt re-hash) moved off the server thread.
- `ReflectCache`: cache key includes parameter types (overloads collided), cached misses no longer
  allocate a new exception with a stack trace on every packet.
- User-cache pruning is rate-limited (it ran a full O(n) scan + index rebuild on every lookup while
  the cache sat above its limit with nothing evictable).
- `blockPosFloored` no longer throws/catches a `NoSuchMethodException` on every call.
- Listener -> `Connection` resolution is type-based, hierarchy-aware and cached (it silently returned
  the listener itself on every 1.20.2+ play listener).

### CI

- **Host-tests were red on every group since this release.** The in-server `/authcore simulate`
  check `srg-runtime-simulation` still asserted the OLD fail-open behaviour (an unresolvable
  command source treated as the console). The admin-permission hardening above correctly made that
  fail closed, so the self-test reported a FAIL on 1.16-1.18, 1.19-1.21 and 26.1-26.3. The check now
  asserts the secure behaviour: unknown source shapes are denied, the real console source is allowed.
- The new disconnect-tracking mixin is `@Pseudo`, so the 1.19-1.20.1 endpoints of the full matrix
  (no `ServerCommonPacketListenerImpl` yet) do not log a missing-target WARN that the harness would
  count as a failure.

### Repository & dead-code cleanup

- Removed the retired per-repo docs site (`index.html`, `404.html`, `CNAME`, `favicon.png`,
  `og.png`, `robots.txt`, `sitemap.xml`, `llms.txt`, `scripts/og/`). The documentation lives in the
  unified hub at docs.potenfyr.in/repo/authcore; issue templates, the PR template and
  CONTRIBUTING.md now point there.
- Removed code nothing called: the never-used batched GeoIP resolver (its scheduler thread woke
  up every 250 ms for nothing), the unused Redis config-override setter, the rank helper, three
  unused user-row queries, unused Yarn-name reflection helpers, assorted unused helpers and ~90
  unused imports.

### Settings that did nothing - now implemented

- `lobby.prevent-status-effect`: no status effect can be applied to a limbo player any more
  (potions, splash/lingering clouds, beacons, wither, `/effect`, other mods); the limbo's own
  invisibility/blindness are exempt.
- `client-guard.allowed-name-regex`: enforced when a NEW account registers (existing accounts are
  never locked out, Bedrock/Floodgate names are exempt; new message `promptUserNameNotAllowed`).
- `authentication.auto-luck-perms-group`: newly registered accounts get the LuckPerms group.
- `security.role-sync-webhook-url`: registration announcements go to that webhook when set.
- `email.alert-on-new-login`: the new-IP/new-country login email now honours the switch.
- `intelligence.login-timing.window-sec` / `min-samples` / `cv-threshold`: used instead of the
  hardcoded values.
- `captcha.trigger-risk-threshold`: a ClientGuard risk at/above it now challenges on its own
  (the composite score caps the risk part at 50, so it could never trigger alone before).
- `cache-max-users` is applied on boot and reload; the lobby click-flood detector is fed; the
  client companion receives the `IN_LOBBY` / `OUT_OF_LOBBY` markers that open/close its GUI; the
  `promptUserCaptchaActionRequired` message is shown when the human verification starts.
- Removed instead (no sensible behaviour): `skip-combat-detection` (limbo players are never
  combat-tagged anyway), `bind-bedrock-xuid` (Floodgate UUIDs are already derived from the XUID),
  `risk2fa-threshold` (2FA is always required when it is configured), and the unused messages
  `promptUserDifferentIpLoginNotAllowed` / `promptUserModeUpdated`.

### Network-wide bans (finished)

- The Redis ban list existed but nothing could set a ban and nothing enforced it. New
  `/authcore network-ban <name> [minutes]` and `/authcore network-unban <name>`
  (`commands.admin.network-ban`, LuckPerms `authcore.admin.networkban`, op level 3): the ban is
  stored in Redis, enforced at join on every server of the network, and online players are kicked
  on every server through the event bus (new message `promptUserNetworkBanned`).

### Backups

- `/authcore backup` and the automatic backups now produce a consistent snapshot (`VACUUM INTO`,
  WAL checkpoint fallback). Copying the raw `.db` file in WAL mode could miss committed data that
  still lived in the `-wal` file. Queued user writes are flushed first.
- New messages are translated in all bundled locales (de, es, fr, pt, ru, zh).

### Releases

- One GitHub release per version: every green master build creates the release for
  `mod.version` the first time and afterwards replaces its jars and notes in place (and moves the
  tag) - never a second release for the same version. Nothing is published unless every build and
  host-test group passed.

### Tests (second pass)

- Host suite: 255 checks (adds the dead-state decision and `ReflectCache` overload keys / cached
  misses).
- All changed code type-checked against 1.18.2 (Fabric + Forge), 1.21.11 (Fabric) and 26.3
  (Fabric).

### First pass (2026-11-07)

### The limbo restore can no longer kill the player

- **The landing is now total.** `getGroundBelow` returns `null` when the column under the
  restore position has no usable surface - a liquid or hazard floor, a covered pocket (standable
  block without the two blocks of headroom the player needs) or the void. The 1.0.2 restore
  treated that as "nothing to do" and left the player exactly where they were, falling from their
  logout height; that is the remaining way the restore itself could kill. The restore now falls
  back to the first safe standing position at or above the column's motion-blocking surface
  (preferring a water surface, which cancels fall damage, and never lava/fire/cactus/magma/
  campfire) and finally to the world spawn, and it logs the move at **INFO** so it appears in
  `latest.log`.
- **Decorated ground is landable again - the real reason the 1.0.2 landing almost never fired.**
  The headroom check required strict AIR above the landing block, but vanilla only needs two
  collision-free blocks for the 1.8-block-tall body: tall grass, flowers, torches, rails, signs,
  snow layers, carpets and slabs are all safe to stand in/on. Every restore above such a surface
  classified as `BLOCKED`, the resolver returned `null` and the player was left in the air - which
  is exactly the grass/flower terrain the reopened screenshot shows. Headroom is now decided by
  collision height (partial shapes up to 0.6 are landable, full blocks are not).
- **Landings work in the deep world (Y < 0).** `Compat.getBottomY` reflected
  `Level.getBottomY`, a method the unobfuscated 26.x era no longer has (it is `getMinY`
  there), so it silently returned `0`: every landing search below Y=0 found nothing and every
  mid-air restore in the modern deep world was left falling. `getBottomY`/`getMaxBuildHeight`
  now resolve the whole rename chain (`getMinY`/`getMaxY`/`getHeight` and the older names).
- **The restore puts the player back at their EXACT saved position.** The snapshot only stored the
  block position and restored to the block CENTRE (+0.5 on every axis), which pushed the head into
  the ceiling of every two-block-high space (doorways, mineshaft tunnels) and embedded the feet in
  partial blocks. The exact feet coordinates are captured now; the safe-landing pass only moves the
  player when that exact spot is genuinely unsafe.
- **The temporary anti-float platform is never ground.** The barrier is excluded from every landing
  surface (it has a full collision shape, so the old resolver happily landed players on it and
  dropped them when it was removed), and the per-session limbo anchor is reset on lock - a reused
  `Lobby` instance used to keep the previous session's anchor, so the platform was placed (and the
  unlock comparison made) against stale coordinates.
- **Fall distance is pinned across every restore teleport and for four seconds afterwards.** Every
  restore teleport now zeroes the fall distance immediately (not only at the end of the restore),
  and a short post-restore safety window keeps it pinned to zero and re-lands a player the landing
  pass could not resolve. Vanilla turns the distance accumulated since the last ground contact into
  fall damage on the next landing, so this makes "the restore dealt fall damage" impossible even
  when a geometry probe is wrong (chunk still loading, hazard floor, rubber-band after the
  teleport).
- **Hazard floors are never used as a landing.** Fire, soul fire, cactus, magma blocks and
  campfires are treated as a blocked column instead of a standable surface, so a restore can never
  place a player on a block that damages them on arrival.
- **Restored health is clamped.** A snapshot captured while the player was dead (health 0, e.g.
  they disconnected on the death screen) used to be restored verbatim, leaving an authenticated
  player at 0 HP where the first point of damage kills them - the "died right after logging in"
  pattern. Restored health is now clamped to `[1, max health]` (NaN/negative/absurd values fall
  back to max health).
- **The temporary anti-float platform can no longer drop a player.** The platform in the restore
  column is removed before the restore teleport (not only when it happens to sit within two blocks
  of the saved Y), and the delayed restore of the original block is deferred - bounded - while a
  player is still standing on it, instead of deleting the block underneath them.
- **The restore is exception-safe.** A failure inside `Snapshot#reset` no longer leaves the player
  unguarded, restricted or airborne: the error is logged and the safe-restore fallback lands the
  player, zeroes the fall distance and arms the safety window.
- **The restore is visible in `latest.log`.** The "landed on safe ground" line was DEBUG-only and
  never reached `latest.log` unless AuthCore debug mode was on; the noteworthy restore outcomes
  (landed, moved to the column surface, could not be placed, restore failed) are now logged at
  INFO/WARN.

### Limbo anchor: no more perpetual falling/jumping

- **The limbo no longer leaves the player half a block above the surface.** The anchor teleport
  placed the player's feet at `blockY + 0.5`, so the client fell those 0.5 blocks and the movement
  guard (which compares the client against the anchor) snapped it back - a permanent fall/snap loop
  that looked like the player was endlessly jumping or falling even with a block right under them.
  Teleport targets are now FEET blocks and the vertical offset is exact (0), both for the limbo
  anchor and for the post-login restore, and the movement guard compares Y against the same exact
  height.
- **The limbo anchor is normalised to a feet position.** A configured limbo location that points
  straight at the floor produced a `candidate` that was itself a standable block - the old code
  relied on the +0.5 offset to compensate and could leave the player embedded in the ground.
- **The anti-float platform no longer replaces real ground.** It is only placed when the player
  really has nothing solid below (air/water), never over a solid block - which removes the
  "floating over an invisible hole" look and the vanilla "standing on air - force-sending blocks
  below" warning on a normal limbo anchor.

### Death diagnostics - the "no death message" mystery

- **Servers with `/gamerule showDeathMessages false` send an EMPTY death message.** Vanilla's
  `ServerPlayer#die` sends `ClientboundPlayerCombatKillPacket(id, CommonComponents.EMPTY)` in
  that branch and broadcasts nothing, so the death screen shows no cause line (exactly the
  screenshot on the issue) and the chat stays silent. That was never caused by AuthCore - but it
  is why the report could not say what killed the player.
- **New death diagnostic.** Every player death now writes one INFO line to the server log with the
  real damage type, attacker type, position, dimension, health, fall distance and limbo state, plus
  a WARN line when the death happened inside the post-restore safety window (i.e. when the restore
  is actually involved). This survives `showDeathMessages=false` and makes the next occurrence
  diagnosable from `latest.log` alone.

### Tests

- `/authcore simulate` gains two pure checks - `landing-clearance` (decorated/partial blocks are
  landable, full blocks are not) and `restore-health-clamp` (dead/negative/NaN/oversized snapshot
  values) - bringing the in-mod suite to **20/20**.

---

### Hardening from the 1.0.3 edge/race/bypass audit

- **Admin permission resolution no longer fails open (critical).** The player was resolved through
  mojmap-only reflection, which does not resolve on the production runtimes of the older jars
  (intermediary on Fabric, SRG on Forge), so `hasForSource` fell through to its console fallback
  and **any** player - including one sitting in the limbo - passed every `/authcore` admin
  `requires`. The source's entity is now read with a direct, build-remapped
  `CommandSourceStack#getEntity()` call, and the permission level is checked against the SOURCE
  itself with the era's own API (`hasPermission(int)` on 1.16-1.20.4, the `PermissionSet` API on
  1.21.11/26.x). The console (and command blocks) keep working. On a runtime where neither the
  source-level nor the player-level API can resolve, admin commands are now **denied** (fail closed)
  - use LuckPerms (node check, direct API, works everywhere) or the console there.
- **`lock()` publishes the limbo entry first and fails closed.** `isInLobby` is defined as map
  membership, so a throw between the snapshot and the previously-late registration left an ACTIVE,
  unauthenticated player outside the limbo where `isAuthenticated` reads true and every guard
  fast-paths out (free roam). The entry is now reserved first; any failure unwinds it, cancels the
  armed tasks and kicks the player. A leaked entry from an earlier session is replaced instead of
  permanently marking the account "in limbo".
- **`unlock()` is ownership-checked**: a stale or second unlock can no longer delete a newer
  session's limbo entry or restore an old snapshot over it.
- **A stale connection's leave no longer tears down the NEW session.** `onPlayerLeave` now computes
  the stale-connection decision before the per-player cache teardown, so a kicked old connection can
  no longer delete the new session's ClientGuard profile, map image, captcha state, premium marker
  or join-UUID alias.
- **Limbo crash recovery runs before any authentication shortcut.** A leftover snapshot (unclean
  shutdown) is restored and deleted right after `User.connect`, so session-resume / premium
  auto-login cannot skip it (stale forced-Adventure playerdata, and re-loading a stale inventory
  over the live one - item duplication).
- **Movement guard compares the ANCHOR, not the live entity position** - successive sub-0.5 packets
  can no longer walk the server entity away one tolerated step at a time.
- **Block interaction has a packet-level fallback** (`handleUseItemOn`), so the lobby stays closed
  even if the `ServerPlayerGameMode` inject silently misses on a future signature.
- **Off-thread state mutations removed**: the public API (`login`/`logout`/`kickPlayer`) and the
  Redis event-bus kick now hop onto the server thread instead of touching session/connection state
  from arbitrary threads.
- **`forceCloseInventory` also closes the server-side menu**, so a container opened before the lock
  can no longer be fed by hopper/dispenser logic while the player is unauthenticated.
- **Edge-case fixes from the same audit**: hazard blocks (fire/cactus/magma/campfire) are rejected
  as landings everywhere (not only in the primary resolver); the limbo anchor and the restore use
  the **exact support height** (slabs/carpets/snow/stairs no longer embed or re-trigger the
  guard); `LinkageError` (NoSuchMethodError - the issue #10 failure class) is handled on every
  restore path; a BARRIER is never "real ground" for the platform bookkeeping; the fallback landing
  validates the feet cell and warns when it has to move a player far; the death diagnostic keeps a
  2-minute restore-attribution window; the post-restore guard refuses to re-land across
  dimensions; the head cell needs full collision clearance; and `getMaxBuildHeight` no longer
  mis-converts the `getHeight()` length on runtimes without the other accessors.
- **Known limitations (documented, not fixed in this patch):** the single 1.19-1.21 jar is built
  against 1.21.11, so a handful of era-gated guards (legacy `handlePickItem`, the 1.19 portal
  driver name, `deop(GameProfile)` tracking, the 2-arg `startRiding`) only apply on the build-era
  runtime; limbo mixins use `require = 0` so a future signature change degrades to "unguarded"
  instead of crashing (no boot-time applied-injection audit yet); the proxy gates keep their
  fail-open early returns for unresolvable targets; and the detection signals (packet sequencing,
  captcha, brand) remain advisory by design.

## [1.0.2] - 2026-11-02

> Everything below this heading happened after the 1.0.1 release: the issue #11 fix, the flow/bypass
> hardening pass, the map-image feature and the full audit/hardening pass. The 1.0.1 and older
> sections are released history and are kept unchanged.

### Protocol & anti-bypass hardening (audit pass)

- **Lobby packet gate extended (no hacked-client/action bypass).** Several
  `ServerGamePacketListenerImpl` handlers with real side effects were not guarded, so an
  unauthenticated player (or a hacked client crafting the packet directly) could still act during
  the limbo: `handleUseItem` (ender pearl/chorus fruit teleport the player server-side, fireworks,
  consumables), `handleEditBook`/`handleRenameItem` (NBT/item mutation), `handleContainerButtonClick`
  (enchanting/anvil), `handleSelectTrade` (villager item generation), `handleSignUpdate` (block
  entity writes), `handlePickItem*` (creative item creation) and `handleClientCommand`
  (respawn/relocation), plus `handleTeleportToEntityPacket` and the gliding action of
  `handlePlayerCommand`. All of them now go through `LobbyPacketPolicy` (fail-closed, config-aware:
  `allow-item-use`, `allow-item-moving`, `allow-block-interaction`, `allow-movement`, `allow-crafting`
  and `allow-entity-interact-with` restore exactly the documented behaviour) with
  `require = 0` injections, so a future version that renames a handler degrades to "unguarded on
  that version" instead of crashing. Sneak/sprint stay allowed - the human-verification captcha
  measures them.
- **Malformed-packet resilience.** Every new guard is wrapped: an unreadable packet action, a null
  player, a broken user object or a failing inventory resync can never throw out of a packet
  handler. Unknown/unreadable actions are treated as blocked in the lobby (`UNKNOWN`), never as
  allowed. Blocked packets are logged through a per-player 5-second throttle (with a suppressed
  count) so a hacked client spamming one packet cannot bury real security events or hammer the
  disk, and the respawn guard only applies to alive players - a legitimately dead player can always
  respawn (no lobby soft-lock).
- **Invalid-username connections rejected.** A vanilla Java client can only send
  `[A-Za-z0-9_.-]{1,16}`, but the hello name is client-controlled: control characters, newlines or
  Unicode lookalikes reached the database, chat and staff tooling (log-injection and
  staff-impersonation vector). Joins with such a name are now disconnected before any session state
  is created and the attempt is logged sanitised.
- **Log injection closed.** `security.log` now sanitises every field (`Sanitizer`): CR/LF, ANSI
  escapes, NUL and Unicode line separators are stripped and the line length is capped, so a crafted
  username/command/IP can no longer forge extra entries or bloat the file. The configured log file
  is resolved under the config directory (no traversal).
- **No off-thread player mutation / JDBC corruption.** Two concurrency defects were fixed:
  1. The web panel's `link` and `set-password` actions mutated live `User` objects and wrote the
     database from an HTTP worker thread (racing the server tick). Both now hash/validate
     synchronously and schedule the mutation on the server thread; Discord ids must be numeric
     snowflakes (they are embedded in outbound API URLs) and panel query parameters are bounded.
  2. The single shared JDBC `Connection` was used by the server thread, the IO pool and the panel
     concurrently, and `User.insert()` toggles `autoCommit` for a two-statement transaction - a
     statement from another thread could join or roll back that transaction. The connection is now
     handed out as a serialising proxy (every JDBC call acquires one monitor) and the transaction
     holds that monitor for its whole duration, which also makes connection replacement during
     reconnect safe.

### Reliability, leaks & concurrency (audit pass)

- **Premium-verification tracking bounded.** The login-handshake tracking maps (`PENDING`,
  `HANDLED`) are cleaned on disconnect, but a version whose disconnect hook does not fire could grow
  them forever (each key holds a connection). They are now size-capped.
- **Confusable-name index capped.** The lookalike-name index (rebuilt from the registered-name
  list) is bounded at 200k names so a network with millions of accounts cannot turn a detection aid
  into a huge cache.
- **GeoIP hardening.** AURI-parse/runtime failure in one provider no longer aborts the lookup
  (`RuntimeException` is caught per provider), and the batched resolver dispatches at most 256
  lookups per flush so a join burst cannot flood the IO pool.
- **Recovery-code cache eviction.** When the pending email-recovery map is full it now evicts the
  OLDEST codes instead of clearing every pending code, so a request flood cannot invalidate
  legitimate users' in-flight codes.
- **Raw username validation, `Sanitizer` utilities.** Central helpers for log-safe text, username
  and Discord-id validation, file-name sanitisation and path containment; used by the security log,
  the join path, the web panel and (below) the importer.

### Business-logic & flow verification (audit pass)

- **18-check in-mod simulation suite.** Added `sanitizer-simulation` (hostile log/username/path
  inputs), `lobby-packet-policy-simulation` (blocked/allowed matrix for every guarded action and a
  malformed action) and `concurrency-simulation` (8 threads hammering the rate limiter, session
  cache and captcha store while asserting no failures, bounded caches and that every generated
  captcha verifies exactly once). Together with the existing checks the suite now covers hashing,
  password policy/history, rate limiting, landing geometry, the join -> limbo -> guard flow,
  proxy-gate fail-closed/open, SRG runtime shapes, bounded caches, map rendering, the map give/take
  lifecycle, map content/QR/captcha, input hardening, packet-gate decisions and concurrency.
- **Host suite unchanged at 229 checks** (they compile and pass against the patched sources); the
  Docker matrix still runs every check inside all 17 real (version, loader) servers.

### Performance & memory

- **Per-packet reflection caching.** The packet guards resolved methods with
  `getClass().getMethod(...)` on every attack/interact/chat/command/click packet - each call
  walks the class hierarchy and allocates. A bounded `ReflectCache` (keyed by class + name +
  arity; the key space is bounded by the runtime's packet classes) now serves them.
- **Lobby fast path.** Per-packet guards first check whether anyone is in the limbo
  (`Lobby.users.isEmpty()`, O(1)) and return before touching the user cache when nobody is.
- **Bounded security caches (leaks).** `Honeypot.BANNED`, `AuthCoreServer.PREMIUM_VERIFIED`,
  `Security.EmailOtp.STORE`, the `AuthIntelligence` per-IP/per-account windows and
  `McApiManager`'s GeoIP cache are size-bounded with expired-entry pruning, so a botnet
  rotating IPs or a code-request flood can no longer grow memory without limit.
- **Connection-state leak fixed.** The `ServerEvents` leave handler now removes its dedupe
  marker and packet-sequence state in a `finally` block - previously every disconnect leaked
  one entry keyed by the connection (a strong reference to the player/level) for the whole
  server uptime.
- **User cache cleanup.** Deleting an account now also drops its last-access timestamp.

### Fixed

- **Players dying right after login - fall damage from the restore (issue #11).** Two defects
  in the post-login restore combined into a lethal fall:
  1. `Lobby.Snapshot.getGroundBelow` could never return a landing surface - the loop only
     descended while the block *below* was air and then tested the air block it had just moved
     into, so the "land airborne players on safe ground" teleport was always a no-op.
  2. The restore decided whether a player was airborne from vanilla's `Entity#onGround()` flag.
     That flag is only refreshed by entity movement and incoming move packets, and the unlock
     teleport bypasses both - while in the limbo the anti-float platform makes it read
     "on ground", so even the landing attempt was skipped.
  A player who disconnected mid-air (or while gliding) was therefore put back at their logout
  position with nothing to stop the fall, and died (lethal fast with Lifesteal-style reduced max
  health). The restore now derives support from world geometry (collision shapes under the
  player's bounding-box footprint, including partial blocks and climbables) and the ground
  resolver actually finds the first safe surface below (skipping tall grass/flowers, stopping at
  fluids/void). AuthCore's temporary limbo barrier never counts as ground, and if it occupies
  the restore position it is restored before the teleport so the player cannot be embedded in
  it. The same support decision is used when picking the limbo anchor position. Covered by new
  host-suite regression checks (`Safe-landing support geometry`, `Ground-below resolver`).

### Flow & bypass hardening

- **UUID-drift limbo bypass (critical).** On offline-mode servers a case-changed name (or an
  online/offline switch) gives the same account a different joining UUID. The account is indexed
  by its stored UUID, but every per-packet limbo guard resolves the user by the joining player's
  UUID - so all guards saw `user == null` and let an unauthenticated player move, break blocks,
  chat, run commands and use items freely. `User.connect` now aliases the joining UUID to the
  account for the session (dropped on leave, replaced on login) and the player-based
  `User.getUser` overloads fall back to the in-memory name index.
- **26.x melee-attack bypass (high).** 26.1+ split attacks out of `ServerboundInteractPacket`
  into `ServerboundAttackPacket`, which no limbo guard covered - an unauthenticated 26.x player
  could attack players, mobs, item frames and armor stands. The guard now hooks the
  version-stable `Player#attack` / `ServerPlayer#attack` methods (covering every version,
  including 26.3 where `ServerPlayer` no longer overrides `attack`), deliberately without a
  version-specific class in the mixin descriptor - a descriptor naming a class absent on an
  older endpoint of a range would fail the whole mixin.
- **Inventory-click bypass (medium).** `allow-item-moving` / `allow-crafting` had no click-layer
  enforcement; inventory clicks were only incidentally blocked via `Inventory.removeItem` when
  `allow-item-drop` was off. With item dropping enabled an unauthenticated player could
  move/equip items and hand-craft. Raw container clicks are now cancelled (with a client resync)
  while in the limbo unless the config allows it.
- **Pre-auth premium-flag write (high).** A hybrid-mode client claiming a premium player's name
  could permanently clear that account's stored online-mode flag (the join path wrote the
  downgrade before any password check). The downgrade is now in-memory only and is persisted
  after a successful password login; on online-mode servers an unverified session claiming a
  stored premium account's name is rejected outright.
- **"kick-old" concurrent-login policy (high).** It armed the 2-minute account kick cooldown
  (so the NEW join was kicked too) and left the old session's limbo entry in place, making the
  new session's lock a no-op and restoring the old position/inventory at login. The old limbo is
  now released first and the old connection is disconnected without touching account kick state.
- **Deferred limbo lock (medium).** If the player entity was not resolvable at join, `lock()`
  returned without restricting the player (an active, non-lobby player reads as authenticated).
  The lock now retries on the next tick and kicks the session if the entity never resolves.
- **Password policy / reuse bypass on `/account set-password` (medium).** The command validated a
  HASH against the complexity rules and compared two independently salted hashes for reuse, so a
  weak password was always accepted and reuse was always allowed. It now validates the raw
  password and verifies it against the current hash.
- **Admin permission fail-open (medium).** `McApiManager.hasForSource` treated an unresolvable
  command source as console and allowed it. The console/player decision now uses the same
  multi-candidate accessors as `resolvePlayer` (their names differ between Mojang-mapped, SRG and
  intermediary runtimes - a single Mojang name such as `getEntity` silently denied the CONSOLE on
  every production runtime): an existing accessor that reports no player is the console, a
  resolvable player goes through the normal permission check, and only a runtime where no accessor
  resolves at all (SRG on Forge 1.16-1.18) keeps the legacy fail-open so admins can still use the
  console there.
- **Rate-limiter global lockout DoS (medium).** Once 10 000 live windows existed, every caller
  was rate-limited until they expired (a botnet could block all joins/logins). The oldest window
  is evicted instead, while per-key limits still apply.
- **Web panel off-thread player mutation (medium).** `unlock`, `delete`, `kick`, `logout` and
  config reload ran on the HTTP worker thread and mutated live player state (teleport, inventory,
  player-list removal, global config) concurrently with the server tick. They are now scheduled
  onto the server thread.
- **Web panel CORS (medium).** Every response carried `Access-Control-Allow-Origin: *`, letting
  any page (or DNS rebinding to 127.0.0.1) read the admin API once the token leaked. CORS headers
  are now only returned for a same-origin request.
- **Honeypot / IP rules join-path stall (medium).** `IpRules.isDenied` (server thread, every
  join) shared a monitor with the file-I/O `IpRules.load()`, so a port scanner hitting the
  honeypot stalled joins. Rules are now an immutable copy-on-write snapshot; honeypot bans are
  deduped per IP.
- **Redis config overrides persisted to disk (medium).** Runtime overrides were written into the
  local config files, letting anyone with Redis write access permanently rewrite the server's
  security config. Overrides are runtime-only now and local config is never overwritten while
  they are active.
- **Broken config file silently replaced (medium).** A section file that failed to parse fell
  back to defaults and was then overwritten by the save. A parse failure now backs the file up
  and skips the save.
- **Legacy MD5 hashes could never verify (low).** `md5` was implemented as SHA-256, so imported
  32-hex MD5 hashes (inferred as `md5`) never authenticated. Real MD5 is used for verification.
- **User cache name index leaked (low).** The lowercase-name index was not pruned with the user
  cache, pinning evicted accounts in memory on large databases.
- **Proxy `block-unauthenticated` could never be satisfied (high).** The gate ran on the proxy's
  login event, before any backend - but the session it checked is only written after an in-game
  login the gate had already blocked. The gate now runs when a player connects to a backend
  (`ServerPreConnectEvent` / `ServerConnectEvent`), is satisfied by the backend `AUTH_CHANGED`
  interop session cache (no Redis round-trip needed) or Redis, sends unauthenticated players to
  the new `auth-server` (initial connect is redirected there; transfers to game servers are
  blocked), and only falls back to fail-closed Redis handling when no session can be confirmed.
- **26.3 signed-command packet gate (low).** Added the packet-level lobby command gate for
  `handleSignedChatCommand` (1.19.1+), matching the unsigned handler.
- **Item-drop guard on 26.3 (low).** 26.3 replaced the drop ownership boolean with a `Prediction`
  enum, so the old `drop(ItemStack,ZZ)` guard silently missed there. Instead of adding a
  descriptor naming the 26.3-only class, the drop key is now blocked at the stable packet level
  (`handlePlayerAction` / `DROP_ITEM` / `DROP_ALL_ITEMS`), which covers every version;
  `Inventory.removeItem` stays the second backstop.
- **Portal travel guard (low).** An unauthenticated player can no longer be carried to another
  dimension by a portal. The driver was renamed from `handleNetherPortal` to `handlePortal` in the
  1.20 portal refactor, so the target is Stonecutter-scoped per era (`< 1.20` / `>= 1.20`) - listing
  both names in one mixin makes the annotation processor warn that one of them cannot be remapped
  for every variant. AuthCore's own teleports are unaffected.
- **Web panel Discord link stored the link CODE as the Discord ID (medium).** The bot must now
  send the real Discord ID alongside the code, and the code-completion branch is handled before
  the UUID requirement - the Discord bot knows the code, not the Minecraft UUID, so the documented
  bot flow previously could not reach it at all. Direct-ID linking is unchanged. Accounts are no
  longer bound to a bogus ID that broke role sync.
- **SSO `trustVanilla` had no address binding (medium).** With SSO enabled and `trust-vanilla`,
  a client claiming another player's UUID while an SSO ticket existed could authenticate without
  a password. The session is now only trusted when the joining IP matches the stored session IP.
- **Web panel password resets bypassed the password policy (low).** Panel resets and admin
  `/authcore set-password` now enforce the configured complexity rules (the policy check is now
  player-independent), and the previous password is kept in history.
- **Password history was documented but never implemented (low).** `password-rules.history-size`
  now works: the previous password is recorded before every change (player command, admin reset,
  web panel) in a new `PASSWORD_HISTORY` table and blocked from reuse.
- **Honeypot bind address was hard-coded (low).** Added `session.honeypot.host` (default
  `0.0.0.0`) so operators can pin the honeypot to a single interface.
- **Docker host harness now exercises real code paths (test infra).** Every container boots with
  the web panel and honeypot enabled from a pre-created config and asserts: the password-history
  schema + index exist in the boot-created database, the honeypot bind-address key is generated,
  web-panel auth (401 without token / 200 with), no wildcard CORS grant for cross-origin requests,
  the Discord link code without a real `discordId` is rejected, the in-mod `/authcore simulate`
  suite passes, and the honeypot bans the source IP exactly once (dedupe). Every boot also fails
  on mixin application warnings/errors, so the per-version mixin targets are exercised on all 9
  endpoints. The matrix now covers **17 (version, loader) combinations**: Forge 1.18.2 is no longer
  skipped (it was a stale blanket skip - the only blocker was Forge's own benign "missing
  mods.toml" log line, now allowlisted). The two remaining SKIPs are genuine platform limits, not
  harness gaps: Forge 1.16.5/1.17.1 cannot run this Java-17 jar (Forge itself does not support
  Java 17 before 1.18) and NeoForge does not exist for 1.19.4 (it starts at 1.20.1).
- **Command dispatch via Mojang-name reflection (low).** `Compat.runCommand` reflected on
  `performPrefixedCommand`/`performCommand` by their Mojang names, which never resolve on
  intermediary/SRG runtimes - dispatched commands silently did nothing on Fabric/Forge. The player
  and console helpers now use Stonecutter-gated direct calls (remapped by the build), so they work
  on every loader/version.
- **Password hashing failed on Fabric 1.16.5/1.17.1 (critical).** password4j's `SaltGenerator`
  statically loads an slf4j logger, and those vanilla server bundles ship no slf4j - so every
  `Encrypter.hash` call died with `NoClassDefFoundError: org/slf4j/LoggerFactory` (registration
  and login could not work). The Argon2 salt now comes from a plain `SecureRandom` (identical
  format, no logging dependency), and Redis initialisation catches `Throwable` so a missing
  slf4j in Jedis can never take the mod down either.
- **Deleted accounts kept their password history (low).** `User.delete()` removed the account row
  but left the stored password hashes in `PASSWORD_HISTORY`; deletion now purges them too.
- **New owner-only `/authcore simulate` self-test (feature).** Runs the built-in simulation suite
  inside the live server - real hash/verify with the configured algorithm, a live `PASSWORD_HISTORY`
  round-trip (insert, trim, newest-first, cleanup), rate-limiter eviction, the landing/support
  geometry against dummy block columns and the config mapping, a join -> limbo -> guard session
  simulation (dummy user + real summoned entity targets), a proxy-gate simulation (dummy proxy
  config, session cache, fail-closed/fail-open), an SRG/Forge runtime simulation (dummy command
  sources whose mapping names are unreachable), a real map-image render check, a map give/take
  lifecycle simulation (dummy inventory) and a map-content simulation (QR codes rendered through
  the map palette and sampled back module-by-module, captcha glyphs sampled cell-by-cell, the
  captcha state machine and every input bound) - 18 checks, and it prints a PASS/FAIL report.
  Disabled by default (`commands.admin.simulate.enabled=false`), requires OP level 4 or the
  `authcore.admin.simulate` node, and is never exposed to normal players.

### Map image rendering (new)

- **128x128 images on in-game maps (feature).** `/authcore map-image show <player>` renders any
  RGB image (QR codes, captcha/2FA art, server banners) onto a real `filled_map` the player carries;
  `/authcore map-image clear <player>` takes it back. Controlled by `lobby.map-image.enabled`
  (default `true`) and `lobby.map-image.max-per-minute` (default `4`).
- **The image actually renders.** A filled map whose id has no server-side `MapItemSavedData` falls
  back to the client's default terrain map ("it only shows my location"), so the display creates the
  map data first, writes all 16384 colour bytes, and replaces it with a **locked** copy - an
  unlocked map is re-coloured from world terrain by the vanilla updater the moment it is carried,
  which erased the image.
- **Palette discovered at runtime, by shape.** The colour table is resolved from the live
  `MapColor`/`MaterialColor` classes instead of a hard-coded list, because the class was renamed in
  1.19.4 (`MaterialColor` -> `MapColor`), the colour accessor was renamed in 1.21.2
  (`calculateRGBColor` -> `calculateARGBColor`), the packed-id helpers changed type, and 1.16.5/1.17.1
  have no `Brightness` enum at all (their palette is rebuilt from the `COLORS` table and the vanilla
  shade multipliers). A 32x32x32 nearest-colour LUT keeps rendering O(1) per pixel.
- **Lifecycle is leak- and dupe-free.** The map is removed from any slot (offhand included) and the
  replaced item is restored into the slot the map just left, so a full inventory cannot swallow it;
  the item is only restored when the map is still carried, so dying/respawning in between can never
  duplicate it. Disconnects clear the display before player data is saved, and map ids are persisted
  to `config/authcore/map-images.txt` (bounded) so a crash/mid-display disconnect is cleaned up on
  the next join.
- **QR codes, generated in-mod (feature).** A dependency-free ISO/IEC 18004 encoder (`QrCode`: byte
  mode, versions 1-10, all four error-correction levels, ECI/UTF-8 for non-ASCII payloads, all eight
  masks with penalty-based selection) makes `/authcore map-image qr <player> <text>` render a
  genuinely scannable code (Discord invite, `otpauth://` 2FA enrolment, docs link) on a 128x128 map.
  Payloads that would render below two pixels per module are rejected instead of producing an
  unscannable image. The encoder was cross-checked against two independent decoders (zxing-cpp:
  960/960 pinned version/ECC/mask matrices and all real-world/UTF-8 payloads decoded to the exact
  input; OpenCV agreed on every matrix it could detect) and the matrices are pinned as regression
  hashes in the in-mod suite. The payload is never logged; note that a payload passed on the
  command line can still appear in server command logs, so 2FA enrolment secrets should be sent
  through the server-side `MapImageService.showQr` API instead.
- **Map captchas (feature).** `/authcore map-image captcha <player>` shows a random 6-character code
  (unambiguous alphabet, `SecureRandom`) rendered as large glyphs, and
  `map-image captcha-verify <player> <code>` checks the answer. Only a SHA-256 digest of the code is
  kept in memory (never logged, never echoed into chat), verification is single-use, expires after
  2 minutes, allows 3 guesses and compares in constant time; state is bounded at 4096 challenges and
  a pending challenge dies with its map.
- **No new attack surface.** The whole map-content path (encoding, glyph rendering, palette
  lookups) is pure computation over length-bounded strings and int arrays: no file, network, process
  or reflection access, no deserialisation, and no client-controlled input - the only inputs are an
  admin command or server-side API call. All inputs are bounded and validated (payload size, QR
  module scale, label charset/length, captcha answer normalisation) and failures throw
  `IllegalArgumentException` instead of allocating or partially rendering. The new in-mod
  `map-content-simulation` check renders QR codes and captcha text through the real palette,
  samples every module/glyph cell back, exercises the captcha state machine and asserts the input
  bounds, so the pipeline is verified on every endpoint/loader.

---

## [1.0.1] - 2026-10-22

> Security audit release: lobby command-gate bypass, TOTP replay, second-factor races,
> async password verification, database hardening, plus the 26.1/26.2 join-crash fix.

### Security audit fixes (2026-10-21)

- **High - lobby command gate bypass fixed.** `Security.isCommandAllowedInLobby` derived the
  command root from the text after the first `/` anywhere in the string. Commands arrive
  without a leading slash, so `msg bob hi /login` was judged as the always-allowed root
  `login` and any command could be smuggled past the whitelist/blacklist. Only a leading
  slash is now stripped.
- **TOTP replay protection.** An accepted TOTP code can no longer be reused inside its
  validity window (RFC 6238 §5.2). A player who just logged in must wait for the next code
  to log in again.
- **Second-factor atomicity.** Recovery-code consumption, email OTP and email-recovery codes
  are now single-use and attempt-capped under concurrent submissions.
- **`/login` no longer hashes on the server thread.** The Argon2/bcrypt check runs on the IO
  executor (max 4 concurrent, one in flight per account) and its outcome is applied back on
  the server thread, where the lobby state is re-validated first (the player may have
  disconnected or authenticated in the meantime). Wrong-password spam can no longer stall the
  server.
- **No weak algorithms for new passwords.** `md5`, `sha-256`, `sha-512` and unknown values of
  `password-hash-algorithm` are replaced by `argon2` when a password is created or changed
  (registration, `/changepassword`, admin and web-panel resets); the stored algorithm label
  always matches the hash. Existing legacy hashes still verify and are upgraded on login.
- Hardening: the offline-UUID check no longer depends on the platform default charset, and two
  admin/history queries now close their JDBC statements.
- **Database.** `User` writes (upsert + extended columns) run in one transaction, JDBC
  statements/result sets are closed, account deletion matches by uuid or case-insensitive
  username, and lookup indexes (`LOWER(username)`, `LOGIN_HISTORY.uuid`) are created
  idempotently on boot (best-effort; failures only log at debug).

### Minecraft 26.1/26.2 join crash fixed (2026-10-21)

- Fixed `NoSuchMethodError: ServerPlayer.setPermanentlyInvulnerable(boolean)` that crashed
  Fabric/NeoForge 26.1 and 26.2 servers the moment a player joined (issue #10): the
  26.3-built G3 jar is one artifact for the whole open-ended `>=26.1` range, and the
  compile-time Stonecutter switch always baked the 26.3-renamed setter into its bytecode.
  The limbo damage shield now dispatches between `setPermanentlyInvulnerable` (26.3+) and
  `setInvulnerable` (26.2 and older) at runtime via `Compat` (`Compat.setInvulnerable` /
  `Compat.isInvulnerable`) - the same single-jar-per-range pattern as `setFallDistance`.
- The limbo snapshot now captures the PRE-lobby invulnerability flag and restores it on
  unlock, so a player who was already invulnerable before the limbo keeps that state after
  authenticating. The crash-safe limbo snapshot file persists the flag as well (older files
  without the key clear the flag, as before).

---

## [1.0.0] - 2026-08-15

> One merged changelog for the whole 1.0.0 line - every feature, fix and hardening
> pass that ever shipped under a `1.0.0*` label lives in this single section
> (the separate `alpha.1`-`alpha.5` entries were folded in; nothing was lost).

### 26.3 support, session IP-lockout fix & CI cleanup (2026-09-22)

**Minecraft 26.3 (latest release) support**

- The G3 group now builds against **26.3** instead of 26.2: artifacts are released as
  `authcore-26.1-26.3-{fabric,neoforge}-<v>.jar` (the range stays open-ended,
  `>=26.1` / NeoForge `[26.1,)`). Pins: Fabric loader 0.19.5, Fabric API
  `0.161.0+26.3`, NeoForge `26.3.0.8-beta`.
- Stonecraft bumped `1.10.+` -> `1.14.+` (1.12.7 is the first release with the
  26.3 pack-format map; without it `processResources` fails with
  `Unknown Minecraft version: 26.3`). Gradle itself must run on JDK 25 for the
  26.x variants (unchanged requirement, now enforced by 26.3 too).
- 26.3 renamed `Entity#setInvulnerable(boolean)` to
  `setPermanentlyInvulnerable(boolean)`; the lobby damage shield now selects the
  method with a `>= 26.3` Stonecutter conditional (`Lobby.java`).
- Velocity API bumped to 3.4.0 (Java 17 / Guice 6) rather than 3.5.x, which
  requires Java 21 and would break the Java-17 G1 group. The proxy entrypoint now
  imports `com.google.inject.Inject` (the annotation Velocity's docs specify, and
  which exists in Guice 5/6/7), so it is future-proof against Guice 7's
  `javax.inject` removal.
- CI matrix, the Docker harness (`versions.json`: build 26.3, verify
  26.1.2 / 26.2 / 26.3), docs and the untested-version banner were updated to
  26.1-26.3.

**Non-breaking dependency updates**

- Shaded libraries: MySQL Connector/J 9.5.0 -> 9.7.0, SQLite JDBC 3.51.1.0 ->
  3.53.4.0, PostgreSQL 42.7.5 -> 42.7.13, commons-pool2 2.12.0 -> 2.13.1,
  Gson 2.13.2 -> 2.14.0, Kotlin stdlib 2.2.20 -> 2.4.20, BouncyCastle
  1.78.1 -> 1.86, geantyref 1.3.13 -> 1.3.16.
- Compile-only APIs: LuckPerms 5.4 -> 5.5, Velocity API 3.1.1 -> 3.4.0. Jedis
  (5.2.0), Configurate (4.2.0), password4j (1.8.4), kyori-option (1.1.0),
  two-factor-auth (1.3) and the BungeeCord API (1.21-R0.3) are already current.
- Every update stays within the dependency's existing major version (no breaking
  API changes); the Gradle wrapper moves 9.7.0 -> 9.7.1.

**Session same-IP lockout fixed**

- The join-time same-IP check no longer kicks a player whose ISP rotated their IP.
  `session.session-from-same-ip-only` now only suppresses the silent session resume
  (forcing a normal `/login`, after which the new IP is persisted) instead of aborting
  the join before `login()` could refresh the address - which permanently locked out
  dynamic-IP players.
- `intelligence.block-on-new-country` now uses a dedicated "different country" message
  instead of the misleading different-IP text.

**Weekly snapshot-compatibility workflow removed**

- `.github/workflows/snapshot-compat.yml` and its README badge were removed; the CI
  `build` + `host-tests` jobs remain the compatibility gate.

### Build, mixin & proxy-gate hardening (2026-09-06)

**Build-time remap errors fixed (all 4 `Cannot remap` warnings gone)**

- `startSleeping` lobby restriction: the `Either`-returning overload never existed on any
  supported version; unified to the void overload that exists on every version.
- Elytra block: `startFallFlying` was removed from the mappings long ago (the injection
  silently never ran); it now injects `updateFallFlying`, the per-tick glide driver, which
  also extends the elytra + jump lobby restrictions to the 1.16-1.18 range.
- Mount block: `startRiding(Entity,Z)` was replaced by a 3-arg overload in 1.21.9; the mixin
  now injects the 1-arg final entrypoint (present on every version) plus the era-specific
  force overload, so mount blocking actually runs on 1.19-1.21 instead of silently no-oping.
- Deop tracking: the `GameProfile` -> `NameAndId` API swap happened in 1.21.9, not 26.x;
  the stonecutter cut point was corrected and the handler now uses `@Coerce` so the same
  jar's bytecode never references the 1.21.9-only `NameAndId` type.

**Runtime mixin crashes fixed (host matrix back to green)**

- `ServerPlayNetworkHandlerMixin` legacy handlers and `PlayerListOpMixin` used `Object`
  handler parameters, which Mixin rejects at runtime (`InvalidInjectionException`) - the
  1.16-1.18 fabric legs and all 26.x legs failed as a result. Handlers now declare the
  exact packet types.

**Startup banner fixed on log4j-era loaders**

- On runtimes without slf4j (1.16-1.18 Fabric/Forge) the entire banner printed literal
  `{}` placeholders instead of values. The fallback console logger now substitutes
  slf4j-style placeholders, so version, Minecraft version, database type and every
  security flag display correctly.

**Proxy-gate bypass & spoofing hardening**

- Velocity/BungeeCord proxy gate: a gate error previously failed OPEN (unauthenticated
  players allowed through even with `block-unauthenticated=true`); it now fails CLOSED
  by default - deny unless the operator explicitly sets `fail-closed=false`.
- Interop messages (`AUTH_CHANGED`) are no longer accepted from player connections -
  only backend-server senders are trusted, and messages are consumed so they can never
  be forwarded to clients.

**CI: rolling latest-build release**

- Every `main` push that passes build + host tests now republishes the `latest`
  GitHub release: same-version uploads REPLACE the jars and regenerate the changelog
  notes; the stable `v*` tag release remains the "Latest" release.

**Test suite grown to 180+ checks**

- New suites: proxy spoof-guard hardening, trusted-proxy source + CIDR validation,
  proxy-config parsing strictness, interop message parsing, proxy-side session cache.
- Docker harness now verifies server-mode auto-detection and banner data correctness
  on every leg.

### Detection hardening & bypass resistance (2026-09-05)

**7-layer defense-in-depth stack**

- **Layer 1 - Session Binding**: per-server random 32-byte companion attestation key,
  generated on first boot, persisted to `config/authcore/attestation.key`, rotated on
  `/authcore reload` (invalidates all pending challenges). No hardcoded keys anywhere.
- **Layer 2 - Packet Sequence Validation**: login packet state machine (HELLO → SETTINGS →
  READY) tracks every connection; anomalous sequences are logged as `PACKET_SEQUENCE_ANOMALY`.
  Stale entries are pruned on tick and on player leave.
- **Layer 3 - Behavioral Profiling**: ClientGuard risk scoring now includes confusable-name
  detection (O(1) normalized-name index), concurrent-connection fingerprinting
  (`CONCURRENT_FARM`: ≥3 distinct usernames from same IP within 5s = +25 risk),
  look-pattern bot detection (camera rotation delta variance = +20), and observation-window
  entropy analysis (low timing variance = +15).
- **Layer 4 - Look-Pattern Analysis**: tracks per-player look deltas (pitch/yaw changes
  between packets); computes coefficient of variation - bots have zero or perfectly regular
  patterns, humans have natural variation.
- **Layer 5 - Login Timing Distribution**: IP-level login timestamp analysis (60s window,
  ≥3 samples); low coefficient of variation (< 0.15) flags bot-farm synchronized timers.
- **Layer 6 - Concurrent Connection Fingerprinting**: `ClientGuard.checkConcurrentFarm`
  tracks distinct usernames per IP within a 5-second window and attaches `CONCURRENT_FARM`
  (+25) to the joining player's profile.
- **Layer 7 - Login Intelligence**: device fingerprint (SHA-256 of IP + country), new-IP /
  new-country alerts (+15 risk), 2FA code rate limiting via `RateLimiter.tryMfa` (5
  attempts/minute per IP + account).

**Fail-closed security defaults**

- Proxy support is **hard-disabled** when `trusted-proxies` is empty (was a warning, now a
  startup block via `SecurityConfiguration.enforce`).
- Redis-backed proxy auth gate defaults to **fail-closed** (`fail-closed = true` in
  `ProxyConfig`) - Redis unreachable blocks proxy-authenticated connections instead of
  allowing them.
- Session token TTL enforcement: tokens expire after 24 hours regardless of session timeout,
  preventing indefinite session fixation.

**Performance & robustness**

- Batch GeoIP lookups: IPs are collected over a 500ms window and resolved in parallel,
  bounded by a 4-permit semaphore - eliminates thundering herd under join floods.
- Detection state integrity: `PACKET_SEQUENCES` map is pruned on tick (expired + disconnected
  connections); all detection maps have cardinality bounds and self-pruning.
- Web panel CORS support: OPTIONS preflight handler + `Access-Control-Allow-Origin` headers
  on every response; CORS headers also set on error paths.

**Test coverage**

- Security test suite expanded with 12 new test cases: attestation key generation/rotation,
  concurrent-farm detection, look-pattern inner classes, login timing null-safety, packet
  sequence tracking null-safety, and 2FA rate limiter integration.

### Test-infra overhaul & harness fixes (2026-08-28)

- **Temurin-based Docker verification**: the host-test harness runs on the official
  [eclipse-temurin](https://hub.docker.com/_/eclipse-temurin) JRE images (17/21/25,
  one per Minecraft version group) and boots the whole matrix IN PARALLEL - one
  container per MC/loader combination. New `test/docker/` (Dockerfile + self-
  provisioning entrypoint + parallel runner), `test/build.sh` (builds all 7 variants
  with no local JDK needed) and `test/run-security-tests.sh` (bash port of the
  standalone security suite). Old `tools/host-tests`, `tools/security-tests`,
  `scripts/` and their workflows removed.
  `test/build.sh` (builds all 7 variants inside the official eclipse-temurin JDK image with
  no local JDK needed) and `test/run-security-tests.sh` (bash port of the
  standalone security suite). Old `tools/host-tests`, `tools/security-tests`,
  `scripts/` and their workflows removed.
- **Minecraft version detection fixed on Forge/NeoForge**: `Compat.getGameVersion()`
  relied on `SharedConstants.getCurrentVersion().getName()` via reflection - method
  names are SRG-renamed on Forge runtimes, so the startup banner printed
  `Minecraft : unknown` (and falsely warned "not in the officially tested set").
  Now resolves through FML's non-obfuscated `VersionInfo` (Forge static accessor;
  NeoForge's `FMLLoader.getCurrentOrNull().getVersionInfo()`), with the Mojang
  reflection kept as last resort.
- **Versioned documentation site**: a common main page (`index.html`: downloads,
  quick start, FAQ) plus per-release doc sets under `docs/<version>/`, cross-linked
  through a shared version bar (`docs/assets/nav.js` + `docs/versions.json`) and
  deployed by the recreated `.github/workflows/pages.yml` - the existing
  `authcore.potenfyr.in/docs/<version>/...` URLs are preserved.
- **Parallel Docker host tests, never stuck**: the whole (version × loader) smoke/
  verify matrix boots in parallel containers; server state lives in named Docker
  volumes (Windows 9p bind mounts stalled the Forge/NeoForge installers), the
  installer runs under a hard timeout and every test has an outer kill deadline.
- **Premium-detection hardening on 1.18.2**: `Compat.serverUsesAuthentication` could
  miss the mojmap method name and default to "online", routing cracked players through
  the wrong auth flow. Now tries `isOnlineMode`/`getOnlineMode`/`usesAuthentication`
  plus an `onlineMode` field fallback before defaulting.
- **IDEA**: broken fabric-only run configurations replaced with Gradle-based
  `Build all variants` configs driving the new `buildAll`/`testAll`/`dockerTest`
  aggregate tasks (all loaders × all ranges in one pass).

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
- **Third-party mod integrations** (`in.potenfyr.authcore.integration`): optional, reflection-based,
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
  `src/modern/java` = Mojang 26.1-26.2 code) behind the `in.potenfyr.authcore.compat` reflection layer and
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