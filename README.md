# 🔐 AuthCore

### The Fortress Framework for Fabric Servers.

[![Modrinth](https://img.shields.io/modrinth/dt/gKATUjN3?color=brightgreen&label=Downloads)](https://modrinth.com/mod/authCore)
[![License](https://img.shields.io/github/license/DawnOfDedSec/AuthCore)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/DawnOfDedSec/AuthCore/build.yml?branch=main)](https://github.com/DawnOfDedSec/AuthCore/actions)

**AuthCore** is a high-performance, server-side login framework designed for **large-scale Fabric 1.21+ environments**.
While other mods just check a password, AuthCore manages the entire lifecycle of a player's session—securing the server
against bot attacks, griefing attempts during login, and session hijacking.

> **Ideal for:** Massive SMPs, Public Community Servers, and Hybrid Networks demanding 20 TPS.

---

## 🏢 Enterprise-Grade Features

Designed for server owners who need absolute control. Here is how AuthCore’s configuration translates to real-world
server benefits.

| ⚙️ Configuration / Features                                                                                     | 🚀 Benefits for Minecraft Server                                                                                                                                                              |
|:----------------------------------------------------------------------------------------------------------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Hermetic Limbo State**<br>*(Blocks: Breaking, Placing, Item Use, Drops, Movement, Chat, Commands)*            | **Zero-Grief Lobby.**<br>Unauthenticated players are effectively ghosts. They cannot spam chat, ruin spawn, steal items, or generate chunk updates that lag the server.                       |
| **Granular Entity Protection**<br>*(Separately blocks attacks on: Players, Hostiles, Animals, Pets, Villagers)* | **Spawn Integrity.**<br>Prevents malicious users from logging in just to kill the community pet or trade-lock villagers before they even authenticate.                                        |
| **Smart Session Management**<br>*(IP Locking, Session Timeouts, Re-login Windows)*                              | **Anti-Hijacking.**<br>If a player disconnects, their session is saved briefly for quick rejoins. However, if a *different* IP tries to join, the gate slams shut. Prevents session stealing. |
| **Hybrid Authentication**<br>*(Premium Auto-Login & Cracked Support)*                                           | **Frictionless UX.**<br>Paid Minecraft users (`online-mode`) skip the password prompt entirely. Cracked users are securely sandboxed. Best of both worlds.                                    |
| **Brute-Force Mitigation**<br>*(Max Login Attempts, Kick Cooldowns, Proxy Blocking)*                            | **DDoS/Bot Resilience.**<br>Automatically purges bad actors guessing passwords. Built-in Proxy/VPN checks reduce bot traffic without needing external firewalls.                              |
| **UX-First Feedback**<br>*(Titles, Subtitles, Action Bars, Chat - All Configurable)*                            | **Professional Polish.**<br>Don't spam the chat box. Use clean **Title Screens** and **Action Bars** to guide users, keeping the chat clean for actual players.                               |
| **Password Complexity Logic**<br>*(Regex: Min Length, Upper/Lower, Digits)*                                     | **Security Compliance.**<br>Force your admins and VIPs to use real passwords, not "12345", reducing the risk of social engineering hacks.                                                     |

---

## 🚀 Getting Started

1. **Install Prerequisites:** Grab [Fabric Loader](https://fabricmc.net/use/)
   and [Fabric API](https://modrinth.com/mod/fabric-api).
2. **Download:** Get the latest `authcore-x.x.x.jar` from [Modrinth](https://modrinth.com/mod/authCore)
   or [GitHub Releases](https://github.com/DawnOfDedSec/AuthCore/releases).
3. **Install:** Drop the `.jar` into your server's `mods/` folder.
4. **Run:** Start the server. The configuration file will generate at `config/authcore/settings.conf`.

---

# 🛠️ Command Reference

### 👤 Player Commands

*Basic commands available to everyone.*

| Command         | Usage                                       | Description                                                     |
|:----------------|:--------------------------------------------|:----------------------------------------------------------------|
| **Register**    | `/register <password> [<confirm-password>]` | Create an identity. Confirmation required if enabled in config. |
| **Login**       | `/login <password>`                         | Authenticate credentials and release the Limbo state.           |
| **Logout**      | `/account logout`                           | Manually end session (force password on next join).             |
| **Change Pass** | `/account set-password <new-password>`      | Update credentials securely.                                    |
| **Unregister**  | `/account unregister`                       | Self-service account deletion (GDPR compliance).                |

### 👮 Admin Commands

*Requires OP or Permission Level 3+.*

| Command              | Usage                                                | Description                                               |
|:---------------------|:-----------------------------------------------------|:----------------------------------------------------------|
| **Reload**           | `/authcore reload`                                   | Hot-swaps `settings.conf` and messages without a restart. |
| **List Players**     | `/authcore list players`                             | Lists all registered player usernames.                    |
| **List Online**      | `/authcore list online-players`                      | Lists all premium (online-mode) players.                  |
| **List Offline**     | `/authcore list offline-players`                     | Lists all cracked (offline-mode) players.                 |
| **Delete Player**    | `/authcore delete player <player>`                   | Wipes player data from the DB. The "Nuclear Option".      |
| **Destroy Sess**     | `/authcore destroy-session <player>`                 | Destroys a session and disconnects the user immediately.  |
| **WhoIs**            | `/authcore whois <username>  <uuid> <player>`        | Displays UUID, IP, Reg Date, Last Login, and Auth Mode.   |
| **Set Password**     | `/authcore set-password <player> <new-password>`     | Administrative password reset.                            |
| **Set Mode Online**  | `/authcore set-mode online <player>`                 | Toggles specific users to Premium validation.             |
| **Set Mode Offline** | `/authcore set-mode offline <player> <new-password>` | Toggles specific users to Cracked validation.             |
| **Set Spawn**        | `/authcore set-spawn limbo <x> <y> <z>`              | Sets the precise X/Y/Z for the Limbo spawn.               |

---

## 📝 Roadmap (Todo)

- [ ] **Velocity / BungeeCord Plugin:** A dedicated upstream plugin for network-wide auth handling.
- [ ] **2FA / MFA Support:** Integration with TOTP apps (Google Auth) for Staff accounts. 📱
- [ ] **Visual Captchas:** Map-based or inventory-based CAPTCHA to stop advanced bots. 🤖
- [ ] **Database SQL Support:** MySQL/MariaDB/PostgreSQL support for syncing data across multiple backend servers. 🗄️
- [ ] **Web Panel:** A lightweight HTML interface for admins to manage users externally.
- [ ] **Progressive Punishment:** Exponential cooldowns (`5s` -> `30s` -> `5m`) for failed password attempts.

---

## 🛠️ Dev Corner

**Building from source (if you're brave):**

- Requirements: Java 21+ & Gradle 8+

```bash
# Clone the repo
git clone [https://github.com/DawnOfDedSec/AuthCore.git](https://github.com/DawnOfDedSec/AuthCore.git)
cd AuthCore

# Build the jar
./gradlew build
```

## 🤝 Contributing

Contributions are welcome!
To contribute:

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
