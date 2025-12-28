# 🔐 AuthCore – Secure Minecraft Login for Fabric Servers

[![Modrinth](https://img.shields.io/modrinth/dt/authCore?color=brightgreen&label=Modrinth%20Downloads)](https://modrinth.com/mod/authCore)
[![License](https://img.shields.io/github/license/DawnOfDedSec/AuthCore)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/DawnOfDedSec/AuthCore/build.yml?branch=master)](https://github.com/DawnOfDedSec/AuthCore/actions)

**AuthCore** is a lightweight, server-side authentication mod for [Fabric](https://fabricmc.net/) Minecraft servers. It provides a secure login and registration system for offline-mode servers, helping prevent unauthorized access, griefing, and account impersonation.

---

## ✨ Features

- 🔐 **Password-based authentication** for players
- 🧾 **`/register`** and **`/login`** commands
- 🚫 Prevents movement and interaction before login
- 🧍‍♂️ Optional spawn-locking until authentication
- ⚙️ Configurable settings for timeout, kick messages, and more
- 🔄 Support for cracked/premium account migration
- 🔄 Live config reload with `/reloadauthCore`
- 📦 Lightweight and dependency-free

---

## 📦 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Download the latest version of AuthCore from [Modrinth](https://modrinth.com/mod/authCore) or the [Releases](https://github.com/DawnOfDedSec/AuthCore/releases) page.
3. Place the `authCore-x.y.z.jar` file into your server's `mods/` folder.
4. Start your server. A default config file will be generated in `config/authCore.json`.

---

## ✅ Supported Versions

| Minecraft Version | Fabric Loader | Status      |
|-------------------|---------------|-------------|
| 1.21.x            | 0.15+         | ✅ Supported |

> Check the [Releases](https://github.com/DawnOfDedSec/AuthCore/releases) page for exact compatibility and updates.

---

## 🛠️ Commands

| Command                 | Description                                              | Usage Example                 |
|-------------------------|----------------------------------------------------------|-------------------------------|
| `/register <pw> <pw>`   | Register a new password                                  | `/register hunter2 hunter2`   |
| `/login <pw>`           | Log in with your password                                | `/login hunter2`              |
| `/logout`               | Log out of your current session                          | `/logout`                     |
| `/account cracked <pw>` | Convert your account to cracked mode with a new password | `/account cracked newpass123` |
| `/account premium`      | Convert your account to premium (online-mode)            | `/account premium`            |
| `/reloadauthCore`       | Reload AuthCore configuration and player data            | `/reloadauthCore`             |

> Players must register on first join and log in on subsequent joins. Admin-only commands like `/reloadauthCore` require appropriate permissions.

---

## ⚙️ Configuration

The config file is located at `config/authCore.json`. Key options include:

- `kickTimeoutSeconds`: Time before unauthenticated players are kicked
- `lockMovement`: Prevents movement before login
- `lockInteraction`: Prevents block/entity interaction before login
- `spawnOnJoin`: Teleports players to spawn until login

## 🧪 Development

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

Please follow the existing code style and include clear commit messages. For major changes, open an issue first to discuss what you’d like to change.

---

## 📄 License

This project is licensed under the [CC0 1.0 Universal (Public Domain)](LICENSE). You are free to use, modify, and distribute this mod without any restrictions.

---

## 📬 Support & Feedback

Found a bug or have a feature request?  
Open an issue: https://github.com/DawnOfDedSec/AuthCore/issues  
Start a discussion: https://github.com/DawnOfDedSec/AuthCore/discussions
