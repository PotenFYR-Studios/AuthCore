## [1.0.0-alpha.1] - 2026-01-01

### 🚀 The "Total Control" Update

This update introduces a complete overhaul of the configuration system, allowing server owners to customize every single
interaction, message, and restriction within the authentication lifecycle.

### ✨ New Features

* **Granular Limbo Restrictions:**
    * Separated entity interaction blocking into specific categories: `Hostile`, `Neutral`, `Friendly`, `Animal`, and
      `Player`.
    * Added specific flags for `Block Breaking`, `Block Placing`, `Item Usage`, `Item Dropping`, and
      `Inventory Movement`.
    * Added `Mounting` restriction for rideable entities (horses, boats, pigs).
    * *Benefit:* You can now allow players to walk around spawn but prevent them from punching villagers or stealing
      crops while unlogged.
* **Hybrid Authentication Mode:**
    * Added native support for **Premium (Online-Mode)** auto-login alongside **Cracked** accounts.
    * Implemented UUID spoofing protection: `prompt-user-premium-different-u-u-i-d` kicks players if a cracked client
      tries to join with a username registered as Premium in the database.
* **Proxy Forwarding Support:**
    * Added `proxy-mode` configuration.
    * Server now correctly parses real IPs from Velocity/BungeeCord, enabling accurate session management and IP bans on
      networks.
* **Password Complexity Logic:**
    * Added configurable requirements for:
        * Minimum/Maximum Length.
        * Minimum Uppercase letters.
        * Minimum Lowercase letters.
        * Minimum Digits.
    * Added distinct error messages for each violation (e.g., `prompt-user-digit-not-present`).

### ⚙️ Configuration & Messages

* **Triple-Format Messaging System:**
    * Every single event now supports three simultaneous display methods:
        1. **Chat:** Standard text with hex color support.
        2. **Title/Subtitle:** Large screen overlays with customizable fade-in/out times.
        3. **Action Bar:** Subtle text above the hotbar.
* **New Message Keys:**
    * `prompt-user-welcome-lobby-user`: Customizable title shown immediately upon joining the Limbo world.
    * `prompt-user-session-resumed`: Feedback when a player rejoins quickly and skips auth.
    * `prompt-user-premium-auto-login`: VIP recognition message for premium users.
* **Periodic Reminders:**
    * Added `prompt-user-register-command-reminder-interval` and `prompt-user-login-command-reminder-interval` to nag
      players who sit AFK without authenticating.

### 🛡️ Security

* **Anti-Bot & Anti-VPN:**
    * Added `prompt-user-proxy-not-allowed`: Native kick reason when a VPN/Proxy is detected.
    * Added `prompt-user-exceeded-login-attempts`: Temporarily bans/cooldowns IP addresses that fail passwords multiple
      times.
* **Session Hijacking Protection:**
    * Added `prompt-user-different-ip-login-not-allowed`: If a valid session exists, but a connection comes from a *new*
      IP, the connection is rejected immediately.
    * Added `prompt-user-another-account-session`: Kicks the active session if a valid login comes from a new client (
      configurable).

### 🛠️ Commands

* Added `/authcore whois <player>`: Displays detailed info including UUID, Platform (Java/Bedrock), IP, and Registration
  Date.
* Added `/authcore setmode <player> <online/offline>`: Allows admins to manually toggle a specific user's authentication
  method.
* Added `/authcore setspawn`: Updates the exact location where unauthenticated players are sent (Limbo).

---

## [1.0.0-alpha.1] - 2026-01-01

### 🎉 Initial Release

* Core authentication framework for Fabric.
* Basic `/login` and `/register` commands.
* Flatfile (JSON) database storage.
* Argon2 password hashing.
* Basic session management.

### 🐛 Bug Fixes

* Fixed an issue where "Time Left" placeholders in reminder messages were not calculating correctly.
* Fixed a race condition where two players registering the same name simultaneously could corrupt the flatfile
  database (`prompt-user-another-account-is-registering`).
* Corrected color formatting for `RED` and `GREEN` in default Action Bar presets.