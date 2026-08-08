# ⚙️ AuthCore Configuration Reference

This document describes **every** setting in AuthCore's `config/settings.conf` (HOCON format).
Settings are grouped into sections; keys are shown in kebab-case as they appear in the file.
Defaults are the values shipped with a fresh install.

> All paths below are relative to the `session` section unless stated otherwise.

> 🎁 **Universal jar** — one jar runs on Minecraft **1.16.0 – 26.x**, standalone **and** behind
> Velocity/BungeeCord. The same `settings.conf` works in both setups; proxy IP forwarding is the
> only thing you toggle (`session.proxy-support`). The cross-server security event bus and
> Discord link-code storage need **no new settings** — they activate automatically with
> `database.redis.enabled`.

---

## 🌍 Root Settings

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `version` | string | `"1.0.0"` | Internal config version used for automated migrations. Do **not** edit manually. | Never touch it — migrations bump it automatically when the format changes. |
| `language` | string | `"en"` | Language/locale for player-facing messages: `en`, `zh`, `es`, `de`, `fr`, `pt`, `ru`. Bundled locale files are extracted automatically. For a fully custom locale, drop a `messages-<lang>.conf` file into the config directory — see [Custom Locales](#custom-locales). | Set to `"zh"` (or any bundled locale) so your players get messages in their own language. |
| `debug-mode` | boolean | `false` | Detailed debug logging to console. Recommended `false` in production to reduce log spam. | Turn on temporarily while diagnosing a login issue, then switch back off. |
| `cache-max-users` | int | `20000` | In-memory user cache size (lazy-loaded from the DB). Larger = fewer DB queries, more memory. **Online users are never evicted**; idle offline users are pruned after 30 minutes. For 500k+ registered accounts keep this modest (e.g. `10000`). | Lower to 10000 on a 500k-account server to keep memory bounded; raise on small servers for faster lookups. |

### `logging` 📣 (startup output)

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `show-banner` | boolean | `true` | Show the ASCII startup banner. | Keep `true` for the branded console header; disable in CI logs. |
| `show-summary` | boolean | `true` | Show the version/security summary at startup (versions, cache size, DB, Redis status). | Keep on to verify the loaded config at a glance; disable to quiet startup logs. |
| `show-untested-version-warning` | boolean | `true` | Warn when running on an untested Minecraft version (outside the verified 1.16–1.21 set; 26.x is supported but attempts mojmap builds in CI). AuthCore **never refuses to load** — it warns and keeps working. | Keep on when riding a brand-new Minecraft release; set to `false` once you've verified your version in staging. |

---

## 🕒 `session`

### Core Session Settings

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `server-mode` | string | `"online"` | Server operation mode: `online` or `offline`. | Set `"offline"` on cracked servers; keep `"online"` on premium ones (hybrid works either way). |
| `timeout-ms` | int | `3600000` (60 min) | How long an authenticated session remains valid. Reasonable range: `1200000`–`10800000`. | Lower to 20 min on a chaotic server to force re-auth; raise it on a community server with long AFK sessions. |
| `cooldown-after-kick-ms` | int | `120000` (2 min) | Cooldown after a player is kicked for too many failed login attempts (brute-force mitigation). | Raise to 5 minutes while the server is under an active brute-force attack. |
| `skip-combat-detection` | boolean | `false` | Skip combat detection for unauthorized players (they can disconnect without being flagged). | Enable on a PvP server so lobby players aren't punished for disconnecting mid-fight. |
| `combat-timeout` | long | `3000` (3 s) | How long a player is considered in combat after taking damage. | Raise to 5 s if combat-loggers routinely escape during lag spikes. |
| `message-reminder-interval-ms` | int | `8000` (8 s) | Interval between login reminder messages sent to unauthenticated players. Suggested `10000`–`30000`. | Set to 15 s on a busy server to avoid chat spam from the login prompt. |
| `enable-sessions` | boolean | `true` | Persist sessions across reconnects. Strongly recommended to keep enabled. | Keep on so players who disconnect mid-session don't have to re-login every time. |
| `kick-after-session-timeout` | boolean | `false` | Immediately kick players whose session expires while online. `false` = friendlier (re-login instantly). | Enable to force re-authentication on an AFK-heavy server once sessions expire. |
| `session-from-same-ip-only` | boolean | `true` | Require new sessions to come from the same IP as the original login. Protects against session hijacking. | Disable only if players legitimately switch IPs (mobile + PC) and get kicked. |

### `combat-log` ⚔️

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Enable combat-log punishment (disconnecting during combat). | Turn on for a Hardcore-lite server to discourage rage-quitting mid-fight. |
| `kill-on-disconnect` | boolean | `false` | Kill the player entity on disconnect while still inside the combat window. Inventory/XP still drop. | Enable to actually punish combat-loggers; keep off for friendly communities. |

### `progressive-punishment` 📈

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Enable exponentially increasing kick cooldowns for repeated failed authentication. | Enable on a public server with repeat password-guessers. |
| `base-cooldown-ms` | long | `5000` (5 s) | Cooldown for the first offense. | Lower to 2 s to start milder, raise to 10 s to deter immediately. |
| `multiplier` | double | `6.0` | Growth factor per offense (≈ 5 s → 30 s → 3 m). | Raise to 8 for faster escalation against bots. |
| `max-cooldown-ms` | long | `300000` (5 min) | Maximum cooldown cap. | Cap at 10 minutes so legit players aren't locked out too long. |

### `account-lock` 🔒

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `true` | Enable automatic account locking after repeated failed logins (persisted to the database). | Keep on — the lock survives restarts and propagates via Redis. |
| `max-failed-logins` | int | `8` | Failed logins before the account is locked. | Lower to 5 for high-value servers, raise to 10 for casual ones. |
| `lock-duration-ms` | long | `600000` (10 min) | How long the account stays locked. | Raise to 30 minutes during bot waves. |

### `intelligence` 🧠

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `true` | Enable login intelligence (new IP / country detection). | Keep on to detect new-IP / new-country logins on every account. |
| `alert-on-new-ip` | boolean | `true` | Alert admins (webhook + security log) on login from a new IP. | Keep on so you're pinged when a player logs in from a new address. |
| `alert-on-new-country` | boolean | `true` | Alert admins on login from a new country. | Keep on to spot accounts shared across countries. |
| `block-on-new-country` | boolean | `false` | Block logins from a new country (anti account-sharing — use with caution). | Enable to enforce region-locked accounts; expect complaints from legit travellers. |
| `alert-risk-threshold` | int | `60` | Risk score threshold (0–100) above which admins are alerted on login. | Lower to 40 for tighter alerting; raise to 80 to reduce noise. |

> 🖇️ **Network device fingerprint** — on every login AuthCore computes a SHA-256 fingerprint of
> the login origin (`IP + country`) and stores it in the `deviceFingerprint` DB column. When an
> account logs in from a *different* fingerprint than the one recorded, **+15** is added to the
> risk score and a **"Possible Account Sharing"** webhook alert fires. This is automatic — there
> is no toggle.

### `security` 🛡️

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `webhook-url` | string | `""` | Discord webhook URL for login/security events (join, login, failed login, lockouts, suspicious logins). Empty = disabled. | Paste a Discord webhook so logins and brute-force attempts land in your admin channel. |
| `role-sync-webhook-url` | string | `""` | Optional *separate* Discord webhook used only for registration announcements (e.g. consumed by Discord bots assigning roles). Falls back to `webhook-url` when empty. | Point it at a bot that assigns a "Member" role when someone registers. |
| `extra-webhook-urls` | list | `[]` | Additional generic webhook URLs (Slack, Telegram, custom) that receive the **same** events as the Discord webhook. | Add a Slack/Telegram webhook to mirror security events into your team's chat. |
| `log-file` | string | `"security.log"` | File name (in the config directory) for the security event log. Empty = disabled. | Keep the default; set to `""` only if you don't want on-disk history. |
| `log-max-bytes` | long | `5242880` (5 MB) | Maximum size of the security log before it is rotated to `security.log.1`, then `.2`, then `.3` (oldest deleted). | Raise to 25 MB if you want longer local history before rotation. |
| `ip-rules-file` | string | `"ip-rules.conf"` | Per-IP allow/deny rules file (in the config directory). One rule per line: `allow 1.2.3.4` / `deny 5.6.7.8`; lines starting with `#` are comments. **First match wins**, default is allow. Denied IPs are kicked on join. | Manually `deny` a griefing IP — and the honeypot appends its auto-bans here too. |

### `shadow-ban` 👻

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Shadow-ban mode. When enabled, security blocks (e.g. new-country blocking) use a generic `disconnect-reason` disconnect instead of revealing the security message. A webhook alert **still fires**, so you keep visibility without tipping off the player. | Enable against account traders who adapt to specific block messages. |
| `disconnect-reason` | string | `"Connection lost"` | The generic disconnect reason shown to shadow-banned players. | Customize it if `"Connection lost"` looks too suspicious to savvy players. |

### `rate-limit` 🚦

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `true` | Enable per-IP join/login rate limiting. | Keep on to survive bot join floods. |
| `max-joins-per-window` | int | `10` | Maximum joins per IP within the window. | Raise to 25 for a big launch day; lower to 5 under attack. |
| `window-ms` | long | `60000` (1 min) | Rate-limit window length. | Widen to 5 minutes to catch slow-flood bots. |
| `command-cooldown-ms` | long | `1000` (1 s) | Minimum delay between `/login` and `/register` executions **per player** (anti-spam, not IP-based). | Raise to 3 s to throttle password-spray scripts. |
| `over-limit-message` | string | `"Too many connections from your address. Please wait a moment!"` | Kick message shown when the join rate limit is exceeded. | Write a friendly custom message for rate-limited players. |
| `alert-on-fast-rejoin` | boolean | `false` | Alert when a player rejoins extremely fast after disconnecting (bot pattern). Alert-only (no kick) — webhook + security log. | Enable to get webhook alerts for the bot reconnect pattern. |
| `fast-rejoin-window-ms` | long | `500` | Minimum disconnect-to-rejoin window for the fast-rejoin alert (ms). | Raise to 1000 ms if quick bots slip under the alert window. |

### `proxy-support` 🌐

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Enable proxy IP-forwarding support (Velocity / BungeeCord). Enable only when your network runs a proxy in IP-forwarding mode. | Enable on a Velocity/BungeeCord network so the real client IP is used. |
| `protocol` | string | `"auto"` | Forwarding protocol: `auto` (detect), `bungeecord` (BungeeCord / Velocity legacy `ip\0uuid\0properties`), `velocity` (Velocity modern forwarding, legacy parse as fallback). | `auto` works for most setups; pick `velocity` when using modern forwarding. |
| `velocity-secret` | string | `""` | Velocity modern forwarding shared secret (optional, for verifying forwarded payloads). | Set the shared secret when your proxy uses Velocity modern forwarding. |

### `web-panel` 🖥️

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Enable the built-in web admin panel. | Turn on to get the browser dashboard on `127.0.0.1`. |
| `host` | string | `"127.0.0.1"` | Interface to bind to. `127.0.0.1` = local only (recommended); `0.0.0.0` = all interfaces (not recommended without a token). | Keep `127.0.0.1` and SSH-tunnel; never bind `0.0.0.0` publicly. |
| `port` | int | `25570` | Panel port (HTTP). | Change if 25570 collides with another service. |
| `https-enabled` | boolean | `false` | Serve the panel over HTTPS. A self-signed certificate is generated automatically on first start and its fingerprint is printed to the console. | Enable for encrypted browser access; verify the printed fingerprint. |
| `https-port` | int | `25571` | HTTPS port. | Change if 25571 collides. |
| `https-keystore` | string | `""` | Path to a custom PKCS12/JKS keystore. Empty = auto-generated self-signed keystore. | Point at your own certificate to avoid browser warnings. |
| `https-keystore-password` | string | `""` | Password of the custom keystore (ignored for the auto-generated one). | Set the password of the custom keystore you configured above. |
| `token` | string | `""` | Access token (required). Sent as `Authorization: Bearer <token>`. The panel will **not** start without one. Generate with `openssl rand -hex 16`. | Generate a hex token — full read/write admin access. |
| `token-file` | string | `""` | Path (in the config directory) to a file containing the access token. When set, this **wins over** the inline `token` — convenient for CI/secrets management (keeps the token out of `settings.conf`). | Store the secret in `web-panel-token.txt` provisioned by your secret manager. |
| `readonly-token` | string | `""` | Optional read-only token: requests authenticated with this token can view data but **cannot** run actions (kick, delete, set-password, link…). Empty = disabled. | Hand a second token to a moderator tool that should only *view* stats and players. |

### `email` 📧

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Enable SMTP email features (login alerts, recovery codes). | Enable to email players login alerts and password recovery codes. |
| `host` | string | `""` | SMTP server host, e.g. `smtp.gmail.com`. | Use your provider's SMTP host (Gmail, Outlook, your own). |
| `port` | int | `587` | SMTP port: `587` (STARTTLS) or `465` (implicit SSL). | Keep 587 unless your provider only supports implicit SSL. |
| `username` | string | `""` | SMTP username (usually the full email address). | The sending account's address/username. |
| `password` | string | `""` | SMTP password / app password. | Use an app password for Gmail/Outlook (2FA-safe). |
| `from` | string | `""` | From address shown in sent emails. | Set to `AuthCore <you@server.com>` so alerts are recognizable. |
| `use-ssl` | boolean | `false` | Use implicit SSL (port 465). When `false`, STARTTLS is used (port 587). | Enable if your provider requires implicit SSL on 465. |
| `alert-on-new-login` | boolean | `true` | Send a login alert email when an account logs in from a new IP or country. | Keep on so players are emailed when their account logs in elsewhere. |

### `auto-whitelist` 📜

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Add successfully registered accounts to the **vanilla server whitelist** automatically (only applies while the whitelist is enabled). Works across 1.16.0 – 26.x via the Compat layer. | Enable on a whitelisted server so registered players never get locked out. |

### `trusted` ⭐

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `true` | Enable the trusted-player system: after a successful login the account is marked trusted and **skips the captcha** on rejoins. | Keep on so returning players skip the captcha. |
| `bypass-captcha-hours` | int | `24` | How long a successful login keeps the account trusted (hours). `0` = disable the bypass. | Raise to 72 h for a community server; lower for strict security. |

### `maintenance` 🛠️

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Maintenance mode: temporarily block **all** joins with a custom message. Toggle at runtime with `/authcore maintenance on\|off`. | Block all joins while you update the world — run `/authcore maintenance on`, update, then `off`. |
| `kick-message` | string | `"Server is under maintenance! Please check back later."` | Kick reason shown to players while maintenance mode is active. | Set a friendly text like `"Back in 30 minutes — world update!"`. |

### `honeypot` 🍯

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Fake-server honeypot: an additional listening port that treats **every** connection as an attacker scan, logs it, and **auto-bans** the IP by appending `deny <ip>` to `ip-rules.conf`. | Enable on a public server so scanners probing port 25599 get permanently banned. |
| `port` | int | `25599` | Port the honeypot listens on (any connection = attacker). | Pick a port that looks like a real Minecraft port to attract scanners. |

### `backup` 💾

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `interval-hours` | int | `0` | Automatic backup interval in hours (`0` = disabled). SQLite: full file copy to `config/authcore/backups/`. MySQL/PostgreSQL: the export command is recommended instead. | Set to `6` to auto-copy the SQLite database every 6 hours; `0` disables. |
| `keep` | int | `10` | Maximum number of automatic backups kept (oldest are deleted). | Lower to 3 to save disk; raise to 30 for long retention. |

---

## 🔐 `session.authentication`

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `max-login-attempts` | int | `8` | Maximum failed login attempts before the player is kicked. Lower = stricter. | Lower to 5 on high-value servers. |
| `allow-totp-support` | boolean | `false` | Enable two-factor authentication (TOTP) support. Recommended for enhanced security. | Enable so players can protect their accounts with Google Authenticator. |
| `allow-bedrock-players` | boolean | `false` | Allow Bedrock/Geyser/Floodgate players to join and authenticate. | Enable on a Geyser server so Bedrock players can register/login. |
| `look-up-by-username` | boolean | `false` | Enforce unique usernames. When `false`, multiple accounts may share a name with different UUIDs. | Enable if you require globally unique names on your server. |
| `premium-auto-login` | boolean | `true` | Auto-login players with legitimate premium (Mojang) accounts without a password. Recommended for hybrid servers. | Keep on for hybrid servers so real Mojang accounts never type a password. |
| `premium-auto-register` | boolean | `true` | Generate a random password for legitimate premium accounts (smooth auth for new premium members). | Keep on so new premium players get an account automatically. |
| `allow-proxy-users` | boolean | `false` | Allow connections from known proxy/VPN services. Disabling blocks many bot attacks. | Keep off to block datacenter/VPN bot farms. |
| `register-password-confirmation` | boolean | `true` | Require password confirmation during registration (prevents typos). | Keep on to catch typo'd passwords. |
| `allow-login-after-registration` | boolean | `true` | Automatically log the player in immediately after successful registration. | Keep on so new players get into the world instantly. |
| `allow-online-name-by-offline` | boolean | `false` | Allow offline players to use a premium username. Disabling prevents username squatting. | Keep off so offline players can't squat premium names. |
| `block-duplicate-register` | boolean | `true` | Prevent the same account name from being registered from multiple locations at once. | Keep on to stop one name registering from many IPs at once. |
| `block-duplicate-session` | boolean | `true` | Prevent the same account name from being authenticated from multiple locations at once. | Keep on so an account can't be logged in twice at once. |
| `auto-luck-perms-group` | string | `""` | Automatically assign a LuckPerms group to newly registered accounts. Empty = disabled. | Set to `"member"` so every new registration is auto-grouped in LuckPerms. |
| `bind-bedrock-xuid` | boolean | `false` | Bind Bedrock (Geyser/Floodgate) accounts to their XUID. On join, the player's XUID must match the stored one or they are kicked. | Enable so Bedrock accounts are locked to their XUID, blocking impersonation. |
| `premium-api-strict` | boolean | `false` | `false` = when the Mojang API is unreachable, premium checks degrade gracefully and **no one is blocked** (recommended). `true` = legacy behavior, the premium-name restriction is enforced even during outages. | Keep `false` so a Mojang outage never locks out premium players. |

> 🔑 **2FA reset on recovery** — completing `/account recover` (email-based password recovery)
> also **clears the TOTP secret**, so the account's two-factor authentication is disabled and the
> player must re-setup 2FA after recovering the account.

---

## 🔤 `password-rules`

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `upper-case.enabled` | boolean | `true` | Enforce the uppercase (A–Z) rule. | Disable if you want simpler passwords for younger players. |
| `upper-case.min` | int | `1` | Minimum uppercase letters. | Raise to 2 for stricter policy. |
| `upper-case.max` | int | `10` | Maximum uppercase letters. | Leave at 10; only relevant for absurd inputs. |
| `lower-case.enabled` | boolean | `true` | Enforce the lowercase (a–z) rule. | Keep on — most passwords need lowercase. |
| `lower-case.min` | int | `3` | Minimum lowercase letters. | Raise to 5 for a stricter policy. |
| `lower-case.max` | int | `10` | Maximum lowercase letters. | Leave at 10. |
| `digits.enabled` | boolean | `true` | Enforce the digit (0–9) rule. | Keep on so passwords can't be all letters. |
| `digits.min` | int | `4` | Minimum digits. | Raise to 6 for high-security servers. |
| `digits.max` | int | `10` | Maximum digits. | Leave at 10. |
| `length.enabled` | boolean | `true` | Enforce the overall length rule. | Keep on — it ties all category limits together. |
| `length.min` | int | auto (sum of category mins) | Minimum total password length. | Override with a fixed `min` if you want a simpler rule set. |
| `length.max` | int | auto (sum of category maxes) | Maximum total password length. | Override if you want to cap total length explicitly. |
| `allow-reuse` | boolean | `false` | Allow reusing a previously used password when changing it. | Keep `false` so players can't cycle back to an old password. |
| `history-size` | int | `5` | Password history: how many previous passwords are remembered and **blocked from reuse** (`0` = disabled). | Set to 10 to block reuse of the last 10 passwords. |
| `password-hash-algorithm` | string | `"argon2"` | Hashing algorithm: `argon2` (default, memory-hard, GPU-resistant), `bcrypt`, `scrypt`, `pbkdf2`, `sha-512`, `sha-256`, or `md5` (insecure — do **not** use). | Keep `argon2`; switch to `bcrypt` only for legacy compatibility. |

---

## 🗄️ `database`

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `sqlite` | string | `"authCore-db.sqlite"` | SQLite file name (stored in `config/database/`). No external server required. | Keep the default for a simple single-server setup. |

### `database.mysql`

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Enable MySQL instead of SQLite. | Switch to MySQL once you outgrow SQLite or run multiple servers. |
| `host` | string | `""` | Server host, e.g. `localhost` or `db.example.com`. | Point at your MySQL host. |
| `port` | int | `3306` | Server port. | Keep 3306 unless your DB runs elsewhere. |
| `database` | string | `""` | Database/schema name. | The schema AuthCore should use. |
| `username` | string | `""` | Database username. | A user with full rights on that schema. |
| `password` | string | `""` | Database password (stored in plain text — protect the config file). | Use a dedicated, least-privilege account. |
| `ssl` | boolean | `false` | Use SSL/TLS for the database connection. Recommended for remote databases. | Enable when the DB is not on localhost. |

### `database.postgres`

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Enable PostgreSQL instead of SQLite/MySQL. | Use PostgreSQL if your infrastructure is already Postgres-based. |
| `host` | string | `""` | Server host. | Point at your PostgreSQL host. |
| `port` | int | `5432` | Server port. | Keep 5432 unless customized. |
| `database` | string | `""` | Database/schema name. | The schema AuthCore should use. |
| `username` | string | `""` | Database username. | A user with full rights on that schema. |
| `password` | string | `""` | Database password (stored in plain text — protect the config file). | Use a dedicated, least-privilege account. |
| `ssl` | boolean | `false` | Use SSL/TLS for the database connection. | Enable when the DB is not on localhost. |

### `database.redis`

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Enable Redis session & ban synchronization across a network. | Enable on multi-server networks for shared sessions/bans + the event bus. |
| `host` | string | `"localhost"` | Redis server host. | Point at the shared Redis instance. |
| `port` | int | `6379` | Redis server port. | Keep 6379 unless customized. |
| `password` | string | `""` | Redis password (empty if auth is disabled). | Set if your Redis requires AUTH. |
| `database` | int | `0` | Logical database index. | Leave 0 unless you share a Redis instance with other apps. |

> 🔁 **Distributed config overrides** — when Redis is enabled, a HOCON snippet stored under the
> Redis key `authcore:config:overrides` is **merged over the local `settings.conf` on load**.
> This lets a server network push shared settings (webhooks, rule files, lobby policies) from a
> single place; keys present in the snippet win over the local file. Store it with any Redis
> client, e.g. `SET authcore:config:overrides "session { security { webhook-url = \"...\" } }"`.

---

## 🏠 `lobby` (limbo restrictions)

### `limbo-config` (teleport)

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `true` | Teleport unauthenticated players to the configured location. | Keep on to teleport newcomers into your registration area. |
| `only-on-first-time` | boolean | `true` | Only teleport on the player's first join. | Keep on so only brand-new players get teleported. |
| `location.dimension` | string | `"minecraft:overworld"` | Target dimension key, e.g. `minecraft:the_nether`. | Set to your spawn plaza's dimension. |
| `location.x` | double | `0.0` | X coordinate. | Set via `/authcore set-spawn` for the correct value. |
| `location.y` | double | `64.0` | Y coordinate. | Set via `/authcore set-spawn`. |
| `location.z` | double | `0.0` | Z coordinate. | Set via `/authcore set-spawn`. |

### `timeout` (dynamic login timeout by ping)

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `true` | Enable dynamic login timeout based on player ping. | Keep on so high-ping players get more login time. |
| `time-in-ms` | int | `120000` (2 min) | Timeout for players with ping ≤ 200 ms. | Raise if your locals type slowly. |
| `timeout-above-200-latency-ms` | int | `180000` (3 min) | Timeout for players with ping > 200 ms. | Tune per tier if your player base is geographically far away. |
| `timeout-above-400-latency-ms` | int | `240000` (4 min) | Timeout for players with ping > 400 ms. | Tune per tier if your player base is geographically far away. |
| `timeout-above-600-latency-ms` | int | `300000` (5 min) | Timeout for players with ping > 600 ms. | Tune per tier if your player base is geographically far away. |

### Lobby Behavior

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `max-lobby-users` | int | `50` | Maximum simultaneously lobby (unauthenticated) players. Prevents overload from many unauthenticated connections. | Lower to 20 if bots spam connections during attacks. |
| `safe-operators` | boolean | `true` | Temporarily remove OP status until authentication succeeds; restore it after login if the player was OP. | Keep on so unauthenticated players can never use OP powers. |
| `allow-chat` | boolean | `false` | Allow lobby players to use global chat. | Keep off to stop pre-auth spam. |
| `allow-commands` | boolean | `false` | Allow lobby players to execute commands (except auth commands). | Keep off; enable only if you trust your lobby guests. |
| `whitelisted-commands` | list | `["login", "account", "register"]` | Commands affected by the whitelist/blacklist logic. Auth commands are always included. | Add e.g. `"rules"` if guests should read server rules. |
| `use-whitelist-as-blacklist` | boolean | `false` | `true` = listed commands are **blocked** (blacklist mode); `false` = only listed commands are allowed (whitelist mode). | Flip to blacklist mode to block a few commands but allow the rest. |
| `allow-movement` | boolean | `false` | Allow basic movement (walking, jumping, sprinting). | Enable for a free-roam lobby; keep off for a strict spawn lock. |
| `allow-block-interaction` | boolean | `false` | Allow right-click block interaction (doors, chests, buttons…). | Enable if your lobby features interactive elements. |
| `allow-block-breaking` | boolean | `false` | Allow breaking or placing blocks. | Keep off — lobby guests shouldn't modify your spawn. |
| `allow-attacking-player` | boolean | `false` | Allow attacking other players. | Keep off to prevent lobby PvP trolling. |
| `allow-attacking-hostile-mobs` | boolean | `false` | Allow attacking hostile mobs. | Enable for a survival-style lobby if you wish. |
| `allow-attacking-animals` | boolean | `false` | Allow attacking passive animals. | Keep off to protect decorative mobs. |
| `allow-attacking-friendly-mobs` | boolean | `false` | Allow attacking villagers/traders. | Keep off to protect traders. |
| `allow-attack-neutral-mobs` | boolean | `false` | Allow attacking neutral mobs (endermen, piglins…). | Keep off unless you want lobby combat. |
| `allow-attack-mountable-entity` | boolean | `false` | Allow attacking mountable entities (horses, boats…). | Keep off. |
| `allow-attack-entity` | boolean | `false` | Allow attacking other entities (item frames, armor stands…). | Keep off. |
| `allow-item-drop` | boolean | `false` | Allow dropping items from inventory. | Keep off so guests can't litter your spawn. |
| `allow-item-pickup` | boolean | `false` | Allow picking up items from the ground. | Keep off to prevent loot-stealing in the lobby. |
| `allow-item-moving` | boolean | `false` | Allow moving/rearranging items inside the inventory. | Keep off. |
| `allow-item-use` | boolean | `false` | Allow using items (eating, shooting bows…). | Keep off unless your lobby has food stations. |
| `allow-player-interact-with` | boolean | `false` | Allow right-click interaction with other players. | Keep off. |
| `allow-animal-interact-with` | boolean | `false` | Allow right-click interaction with animals. | Keep off. |
| `allow-mountable-interact-with` | boolean | `false` | Allow right-click interaction with mountable entities. | Enable if you want lobby horse rides. |
| `allow-entity-interact-with` | boolean | `false` | Allow right-click interaction with miscellaneous entities. | Keep off. |
| `allow-hostile-mobs-interact-with` | boolean | `false` | Allow right-click interaction with hostile mobs. | Keep off. |
| `allow-friendly-mobs-interact-with` | boolean | `false` | Allow right-click interaction with friendly/villager mobs. | Keep off. |
| `allow-neutral-mobs-interact-with` | boolean | `false` | Allow right-click interaction with neutral mobs. | Keep off. |
| `invisible-unauthorized` | boolean | `true` | Make unauthenticated players invisible to authenticated players. | Keep on to reduce visual clutter at spawn. |
| `apply-blindness-effect` | boolean | `true` | Apply permanent blindness to lobby players (focus on the auth prompt). | Keep on so guests focus on logging in. |
| `hide-inventory` | boolean | `false` | Completely hide the player's inventory UI while in lobby. | Enable for a fully immersive auth screen. |
| `allow-mob-damage` | boolean | `false` | Allow mobs to damage lobby players. | Keep off to prevent unfair deaths pre-auth. |
| `force-adventure-mode` | boolean | `true` | Force lobby players into Adventure mode (prevents accidental block breaking/placing). | Keep on — the simplest block protection. |
| `prevent-damage` | boolean | `true` | Prevent all damage to lobby players from items/projectiles. | Keep on so guests are invulnerable until they log in. |
| `prevent-status-effect` | boolean | `true` | Block status effects from being applied to lobby players. | Keep on. |
| `prevent-player-damage` | boolean | `true` | Protect lobby players from damage by authenticated players. | Keep on to stop logged-in players griefing the lobby. |

### `announcements` 📢 (rotating)

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `announcements` | list | `[]` | Rotating server announcements shown to authenticated players. Each entry is shown for `announcement-interval-sec`, then the next one. Supports the `%player%` placeholder. | List messages like `"Vote for us!"`, `"Join our Discord!"` — rotated on a timer. |
| `announcement-interval-sec` | int | `600` | Seconds between rotating announcements. | Set to 300 to rotate every 5 minutes. |

### `announcement` 📢 (join)

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Show a server announcement to players right after they authenticate. | Enable to greet players as soon as they log in. |
| `text` | string | `""` | The announcement message. Supports the `%player%` placeholder (replaced with the player's username). | Set to `"Welcome back, %player%!"` for a personal touch. |

### `captcha` 🤖

| Setting | Type | Default | Description | Scenario |
|---|---|---|---|---|
| `enabled` | boolean | `false` | Enable captcha for registration/login. The lobby shows a code; `/login` and `/register` require it as an extra argument. | Enable during bot waves; disable when it annoys legits. |
| `length` | int | `5` | Captcha code length. | Lower to 4 for mobile players; raise to 6 under bot attacks. |
| `ttl-ms` | long | `60000` (1 min) | Captcha code validity. | Raise to 2 minutes for slow typists. |
| `disable-when-tps-below` | double | `0` | **TPS-adaptive captcha** — while server TPS is below this value the captcha is skipped entirely (prevents bots exploiting lag-spikes). `0` = never disable. | Set to 15 so the captcha vanishes during lag spikes. |

---

## 🧑‍💻 `commands`

Every command supports **both** a LuckPerms node and a vanilla permission level (used when
LuckPerms is not loaded: `0` = all players, `1` = moderators, `2` = gamemasters, `3` = admins,
`4` = owners).

### User Commands

| Command | LuckPerms Node | Permissions Level | Description | Scenario |
|---|---|---|---|---|
| `user.login` | `authcore.user.login` | `0` | Permission for `/login`. | Keep at level 0 so everyone can log in. |
| `user.logout` | `authcore.user.logout` | `0` | Permission for `/account logout` — log out while authenticated. | Keep at 0 — harmless and useful. |
| `user.register` | `authcore.user.load` | `0` | Permission for `/register` — create an account. | Keep at 0 so new players can register. |
| `user.unregister` | `authcore.user.unregister` | `0` | Permission for `/account unregister` — delete your own account. | Keep at 0 so players can wipe their own data. |
| `user.nickname` | `authcore.user.logout` | `0` | Permission for `/account nickname <name>` — set your display nickname (2–24 chars, no spaces; shown in whois and the web panel). | Keep at 0 so everyone can personalize their display name. |
| `user.change-password` | `authcore.user.changepassword` | `0` | Permission for `/account set-password <new>` — change your password. | Keep at 0 — everyone should rotate passwords. |

### Admin Commands

| Command | LuckPerms Node | Permissions Level | Description | Scenario |
|---|---|---|---|---|
| `admin.reload` | `authcore.admin.reload` | `3` | `/authcore reload` — reload configuration without restarting. | Level 3 keeps config control in the admin team. |
| `admin.delete-player` | `authcore.admin.deleteplayer` | `3` | `/authcore delete player <player>` — permanently remove an account. | Give to trusted moderators only — destructive. |
| `admin.list-players` | `authcore.admin.listPlayers` | `3` | `/authcore list players` — list all registered accounts (database-backed, safe for 100k+ users). | Useful for audits of the registered player base. |
| `admin.export-users` | `authcore.admin.reload` | `3` | `/authcore export` — export all users to `config/authcore/backups/users-export-<timestamp>.json` (works with SQLite/MySQL/PostgreSQL). | Run weekly to keep an off-server JSON copy. |
| `admin.list-online-mode-players` | `authcore.admin.listOnlineModePlayers` | `3` | `/authcore list online-players` — list connected premium players. | Check who is premium during hybrid-server debugging. |
| `admin.list-offline-mode-players` | `authcore.admin.listOfflineModePlayers` | `3` | `/authcore list offline-players` — list connected offline players. | Spot guests who still need to register. |
| `admin.destroy-player-session` | `authcore.admin.destroyPlayerSession` | `3` | `/authcore destroy-session <player>` — force re-authentication. | Use when you suspect a session was hijacked. |
| `admin.set-player-password` | `authcore.admin.setPlayerPassword` | `3` | `/authcore set-password <player> <new>` — admin-forced password change. | Reset a password for a player who forgot theirs. |
| `admin.reset-player-password` | `authcore.admin.setPlayerPassword` | `3` | `/authcore resetpw <player> <new-password>` — console-friendly alias of `set-password`. | Run from the server console to reset a password without Discord/panel. |
| `admin.whois-username` | `authcore.admin.whoisUsername` | `3` | `/authcore whois [<username>] [<uuid>] [<player>]` — detailed account info (database-backed). | Investigate an account's IP/country/mode/risk. |
| `admin.set-offline-mode-player` | `authcore.admin.setOfflineModePlayer` | `3` | `/authcore set-mode offline <player> <new-password>` — force offline auth mode. | Convert a stolen premium account back to password auth. |
| `admin.set-online-mode-player` | `authcore.admin.setOnlineModePlayer` | `3` | `/authcore set-mode online <player>` — force premium auth mode. | Restore a player who proved premium ownership. |
| `admin.set-spawn-location` | `authcore.admin.setSpawnLocation` | `3` | `/authcore set-spawn <dimension> <x> <y> <z>` — set the default spawn used by lobby teleports. | Set the limbo spawn to your registration plaza. |
| `admin.maintenance` | `authcore.admin.reload` | `3` | `/authcore maintenance on\|off` — block all joins with the configured `maintenance.kick-message`. | Toggle maintenance around world updates or security incidents. |
| `admin.validate` | `authcore.admin.reload` | `3` | `/authcore validate` — dry-run config validation: reports invalid algorithms, ports, missing DB fields, missing panel token, bad webhook URL. | Run after hand-editing `settings.conf` to catch typos before they break things. |

---

## 🔗 Discord Account Linking

Discord linking has **no settings** — it reuses `session.security.webhook-url` (the code is
published there) and `database.redis` (code storage + `discordId` mapping). Players use
`/discord link` / `/discord unlink`; Discord bots complete the pairing via the web panel API
(`action: "link"` — see
[WEBPANEL.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/WEBPANEL.md)).

### Message keys

The player-facing texts are fully editable in `messages.conf` (and per-locale files):

| Key | Default (English) | Scenario |
|---|---|---|
| `prompt-user-discord-link-code` | Shows the generated 6-char link code (sent to the bot) | Re-word it to explain where the code should be sent. |
| `prompt-user-discord-already-linked` | Shown by `/discord link` when the account is already linked | Customize the reminder that accounts can hold one link. |
| `prompt-user-discord-not-linked` | Shown by `/discord unlink` when no link exists | Customize for players who never linked. |
| `prompt-user-discord-unlinked` | Confirmation after a successful `/discord unlink` | Customize the success feedback. |

> 📡 **Cross-server event bus** — with Redis enabled, login/logout/register/brute-force/
> account-locked/kick events are published on the `authcore:events` channel and handled on every
> backend (security log + webhook + local actions). There is nothing to configure.

---

## 🗣️ Custom Locales

AuthCore ships with bundled locales for every supported `language` (extracted on first start).
To fully customize a locale — or add one of your own — drop a `messages-<lang>.conf` file into
**`config/authcore/`**:

| File | `language` setting | Result |
|---|---|---|
| `config/authcore/messages.conf` | `"en"` | Overrides the English messages |
| `config/authcore/messages-fr.conf` | `"fr"` | Overrides the French messages |
| `config/authcore/messages-de.conf` | `"de"` | …and so on for any code |

How it works:

1. Pick the code you want (`language = "fr"` in `settings.conf`) and create
   `config/authcore/messages-fr.conf`.
2. Copy the structure from the extracted `messages.conf` (or the file from a previous export)
   and edit the texts you want to change.
3. On startup the mod picks the custom file up **automatically** and merges it over the bundled
   locale.
4. **Completeness check** — if any message keys are missing from your custom file, AuthCore
   logs a **WARNING listing the missing keys** so you know exactly what to translate.

> ✏️ Note: a custom locale file only needs the keys you want to override — the rest fall back
> to the bundled defaults. The completeness warning helps translators deliver 100% coverage.

---

## 🧪 Security Testing

The security & business-logic core ships with a standalone test suite (no Minecraft required)
at **`tools/security-tests/`** — **57 checks** covering password hashing round-trips for all 6
algorithms, unique per-hash salts, captcha lifecycle, email recovery codes, the rate limiter,
proxy IP parsing, device fingerprints and time formatting. Run it after a build:

```powershell
.\gradlew.bat build                          # once: produces the compiled classes + cached jars
powershell -ExecutionPolicy Bypass -File tools\security-tests\run-tests.ps1
```

The suite is part of the security process — see
[SECURITY.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/SECURITY.md) for the PBKDF2
bug it caught and how it guards regressions. CI runs the suite on every push
(`.github/workflows/build.yml`, alongside the 1.16.5 + 1.21.11 double build) and the
multi-version matrix (`multi-version-check.yml`) verifies 1.16.5 → 1.21.11 plus a 26.2 mojmap
build attempt on every push and PR.
