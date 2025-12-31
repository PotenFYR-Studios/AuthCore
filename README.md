# 🔐 AuthCore
### Stop Griefers, Start Gaming.

[![Modrinth](https://img.shields.io/modrinth/dt/gKATUjN3?color=brightgreen&label=Downloads)](https://modrinth.com/mod/authCore)
[![License](https://img.shields.io/github/license/DawnOfDedSec/AuthCore)](LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/DawnOfDedSec/AuthCore/build.yml?branch=main)](https://github.com/DawnOfDedSec/AuthCore/actions)

**AuthCore** is a lightweight, server-side bodyguard for your [Fabric](https://fabricmc.net/) server. It keeps out the "Steve" imposters and ensures your players' accounts stay as safe as a diamond block under Bedrock.

> **Ideal for:** Cracked servers, public communities, and anyone tired of people logging in as "Notch" to burn down spawn.

---

## ✨ Why AuthCore?

- 🔐 **Military-Grade Paranoia:** Secure password hashing (no plain-text leaks here!).
- 🚫 **The "You Shall Not Pass" Protocol:** Blocks movement, chat, and interaction until they log in.
- 🧍‍♂️ **Limbo Purgatory:** Lock unauthenticated players in a configurable spawn box.
- 🔄 **Hybrid Harmony:** Support for both Premium and "Budget-Friendly" (cracked) accounts.
- ⚙️ **Hot-Swapping:** Reload configs live, because restarting servers is so 2012.
- 📦 **Zero Bloat:** Lightweight enough to run on a potato.

---

## 📦 Getting Started

1. Grab [Fabric Loader](https://fabricmc.net/use/) and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Download AuthCore from [Modrinth](https://modrinth.com/mod/authCore) or [GitHub](https://github.com/DawnOfDedSec/AuthCore/releases).
3. Toss the `.jar` into your `mods/` folder.
4. **Boot it up.** Find your settings at `config/authcore/settings.conf`.

**✅ Done!** Your server is now officially harder to enter than a VIP club.

---

## ✅ Compatibility

| Version | Loader | Status       |
|:--------|:-------|:-------------|
| 1.21.x  | 0.15+  | ✅ Rock Solid |

---

## 🛠️ Commands

### For the Plebeians (Players)
| Command               | What it does         | Example                                    |
|:----------------------|:---------------------|:-------------------------------------------|
| `/register <pw>`      | Create your identity | `/register 12345` (Please don't use 12345) |
| `/login <pw>`         | Prove it's you       | `/login hunter2`                           |
| `/account logout`     | Walk away safely     | `/account logout`                          |
| `/account unregister` | Delete your life     | `/account unregister`                      |

### For the Gods (Admins)
| Command                     | Description                       | Level |
|:----------------------------|:----------------------------------|:------|
| `/authcore reload`          | Fix your config mistakes live     | 3     |
| `/authcore whois <user>`    | Play private investigator         | 3     |
| `/authcore delete <user>`   | The "Nuclear Option" for accounts | 3     |
| `/authcore set-mode online` | Force them to be legit            | 3     |
| `/authcore set-spawn limbo` | Decide where the "unlogged" rot   | 3     |

---

## 🧪 Future Shenanigans

We aren't done yet. Our roadmap is filled with features to make hackers cry:

- 🛡️ **Velocity Support:** Because managing one server is never enough.
- 🤖 **Captcha Chaos:** Force bots to solve math or identify "all squares with traffic lights."
- 🔐 **2FA / MFA:** Make them pull out their phones to play block game.
- ⏱️ **Login Torture:** Increasing wait times for failed attempts. 5 seconds... 30 seconds... 5 years...

---

## 🛠️ Dev Corner

**Building from source (if you're brave):**
- Java 21+ & Gradle 10+
```bash
git clone [https://github.com/DawnOfDedSec/AuthCore.git](https://github.com/DawnOfDedSec/AuthCore.git)
cd AuthCore
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
