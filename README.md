# 🔐 AuthCore – Secure Minecraft Login for Fabric Servers

[![Modrinth](https://img.shields.io/modrinth/dt/gKATUjN3?color=brightgreen&label=Modrinth%20Downloads)](https://modrinth.com/mod/authCore)
[![License](https://img.shields.io/github/license/DawnOfDedSec/AuthCore)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/DawnOfDedSec/AuthCore/build.yml?branch=main)](https://github.com/DawnOfDedSec/AuthCore/actions)

**AuthCore** is a lightweight, server-side authentication mod for [Fabric](https://fabricmc.net/) Minecraft servers. It
provides a secure login and registration system for offline-mode servers, preventing unauthorized access, griefing, and
account impersonation.

> **Perfect for** cracked servers, public communities, and premium-hybrid setups.

---

## ✨ Features

- 🔐 **Password-based authentication** with secure hashing
- 🧾 **`/register`**, **`/login`**, and **`/account`** command suite
- 🚫 **Movement & interaction blocking** until authenticated
- 🧍‍♂️ **Spawn-locking** with configurable limbo zones
- 🔄 **Premium/cracked account migration** support
- ⚙️ **Live config reload** (`/authcore reload`)
- 📊 **Admin dashboard** commands for player management
- 📦 **Zero dependencies** – lightweight & performant
- 🔒 **Session management** with logout/unregister options

---

## 📦 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api)
2. Download latest [AuthCore](https://modrinth.com/mod/authCore) from Modrinth
   or [GitHub Releases](https://github.com/DawnOfDedSec/AuthCore/releases)
3. Drop `authCore-x.y.z.jar` into your `mods/` folder
4. **Start server** – config generates automatically at `config/authcore/settings.conf`

**✅ Done!** Players will need to register on first join.

---

## ✅ Supported Versions

| Minecraft Version | Fabric Loader | Status      |
|-------------------|---------------|-------------|
| 1.21.x            | 0.15+         | ✅ Supported |

> Check the [Releases](https://github.com/DawnOfDedSec/AuthCore/releases) page for exact compatibility and updates.

---

## 🛠️ Commands

### User Commands

| Command                      | Description                     | Usage Example                      | Permission Level |
|------------------------------|---------------------------------|------------------------------------|------------------|
| `/register <pw> [confirm]`   | Register a new password         | `/register hunter2 hunter2`        | 0                |
| `/login <pw>`                | Log in with your password       | `/login hunter2`                   | 0                |
| `/account logout`            | Log out of your current session | `/account logout`                  | 0                |
| `/account unregister`        | Permanently remove your account | `/account unregister`              | 0                |
| `/account set-password <pw>` | Change your password            | `/account set-password newpass123` | 0                |

### Admin Commands

| Command                                       | Description                                   | Usage Example                                          | Permission Level |
|-----------------------------------------------|-----------------------------------------------|--------------------------------------------------------|------------------|
| `/authcore reload`                            | Reload AuthCore configuration and player data | `/authcore reload`                                     | 3                |
| `/authcore list players`                      | List all registered players                   | `/authcore list players`                               | 3                |
| `/authcore list online-players`               | List premium/online-mode players              | `/authcore list online-players`                        | 3                |
| `/authcore list offline-players`              | List cracked/offline-mode players             | `/authcore list offline-players`                       | 3                |
| `/authcore delete player <player>`            | Permanently delete a player's account         | `/authcore delete player Steve`                        | 3                |
| `/authcore destroy-session <player>`          | Force a player to re-authenticate             | `/authcore destroy-session Steve`                      | 3                |
| `/authcore set-password <player> <pw>`        | Admin-forced password change                  | `/authcore set-password Steve newpass123`              | 3                |
| `/authcore whois <username>`                  | Show detailed player info                     | `/authcore whois Steve`                                | 3                |
| `/authcore set-mode online <player>`          | Force player to premium/online mode           | `/authcore set-mode online Steve`                      | 3                |
| `/authcore set-mode offline <player>`         | Force player to cracked/offline mode          | `/authcore set-mode offline Steve`                     | 3                |
| `/authcore set-spawn limbo <dim> <x> <y> <z>` | Set limbo spawn location                      | `/authcore set-spawn limbo minecraft:overworld 0 64 0` | 3                |

> Players must register on first join and log in on subsequent joins. Commands require appropriate permissions (
> LuckPerms or OP level). Admin commands require OP level 3 or higher.

---

## ⚙️ Configuration

The config file is located at `config/authcore/settings.conf`.

📖 **Detailed Configuration Wiki**  
For a complete guide to all available options, explanations, and best practices, check out the [Configuration Wiki](https://github.com/DawnOfDedSec/AuthCore/wiki/) – because who doesn't love a good wiki dive while tweaking server security? 🕵️‍♂️✨

## 🧪 Development

### 🚀 Todo (Future Shenanigans Incoming!)
Planned features to make AuthCore even more unbreakable (and fun) in upcoming releases:

- **🛡️ Velocity Proxy Support**  
  Full native integration with Velocity – because why stop at one server when you can secure an entire fleet? Proxy forwarding, secure sessions, and no more "wait, which server am I on again?" moments.

- **🤖 Captcha Chaos**  
  Configurable captchas (images, math puzzles, or "prove you're not a bot by typing 'I love mining diamonds'") to brutally reject script kiddies and bot armies. Sorry robots, no griefing today! 🚫

- **🔐 2FA / MFA Madness**  
  Optional two-factor authentication via authenticator apps (TOTP). Because one password is good, but making attackers cry with a second layer is *chef's kiss*.

- **📧 Email OTP Mayhem**  
  Email-based one-time passwords for registration and critical actions. Nothing says "secure" like waiting 30 seconds for that sweet, sweet code while staring at your inbox like it's a loot chest.

- **⏱️ Dynamic Login Timeout Torture**  
  Smart, escalating delays after failed login attempts (e.g., 5s → 30s → 5min → "go touch grass"). Brute-forcers will rage-quit while legit players barely notice. Because nothing's funnier than watching a bot sweat in limbo forever. 😈

### Requirements

- Java 21+
- [Fabric Loom](https://github.com/FabricMC/fabric-loom)
- Gradle 10+

### Build Instructions

```bash
git clone https://github.com/DawnOfDedSec/AuthCore.git
cd AuthCore
./gradlew build
```

## 🤝 Contributing

Contributions are welcome! To contribute:

1. Fork the repository
2. Create a new branch: `git checkout -b feature/your-feature-name`
3. Make your changes and commit: `git commit -m 'Add new feature'`
4. Push to your fork: `git push origin feature/your-feature-name`
5. Open a pull request: https://github.com/DawnOfDedSec/AuthCore/pulls

Please follow the existing code style and include clear commit messages. For major changes, open an issue first to
discuss what you’d like to change.

---

## 📄 License

This project is licensed under the [CC0 1.0 Universal (Public Domain)](LICENSE). You are free to use, modify, and
distribute this mod without any restrictions.

---

## 📬 Support & Feedback

Found a bug or have a feature request?  
Open an issue: https://github.com/DawnOfDedSec/AuthCore/issues  
Start a discussion: https://github.com/DawnOfDedSec/AuthCore/discussions
