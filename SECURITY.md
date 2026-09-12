# Security Policy

## Supported versions

Security fixes are released for the **1.0.0 line** (the current stable release, including its rolling `latest` development builds). Older builds are not supported - please update before reporting an issue.

| Version | Supported |
|:---|:---|
| 1.0.0 (stable line and `latest` dev builds) | ✅ |
| Older releases / legacy configurations | ❌ (migrate to 1.0.0 first) |

## Reporting a vulnerability

**Please do not report exploitable vulnerabilities through public GitHub Issues, Discussions, or Discord.** Public reports put servers at risk before a fix is available.

Report security issues privately:

1. **Preferred:** open a private GitHub Security Advisory via **Security → Report a vulnerability** on this repository (if that option is available to you), or
2. **Email the maintainers:** [support@potenfyr.in](mailto:support@potenfyr.in) (PotenFYR Studios' published support address). Include `[AuthCore Security]` in the subject so it is routed quickly.

Include as much of the following as you are comfortable sharing: affected AuthCore version and loader (Fabric / Forge / NeoForge / Velocity / BungeeCord), Minecraft and Java versions, configuration areas involved, and a description of the impact. We are happy to work through details interactively.

### What NOT to post publicly

Never post any of the following in public Issues, Discussions, or Discord:

- **Secrets from your configuration** - web panel tokens, `session.security` webhook URLs, SMTP credentials, `velocity-secret`, database passwords, or any `config/authcore/` contents. Treat every value in those files as sensitive.
- **Working exploit code or proof-of-concept payloads** for authentication bypass, session theft, or proxy spoofing.
- **Other operators' player data** (IPs, e-mail addresses, password hashes).

Scrub logs before attaching them: AuthCore logs do not print secrets, but stack traces, debug output (`debugMode = true`), and your own additions may.

## What to expect

- We evaluate every private report and will get back to you as soon as we can - we do not promise specific response or fix times.
- Confirmed vulnerabilities are fixed in the current 1.0.0 line and disclosed in the release notes / changelog once a patched build ships. We credit reporters in the changelog on request.
- Reports about outdated builds will ask you to reproduce on the current release first.

## Scope notes

**In scope:** authentication or session bypass, limbo escape, proxy forwarding spoofing, web panel authentication/REST flaws, password-hash or 2FA weaknesses, cross-server SSO issues, and injection or privilege escalation through AuthCore commands or APIs.

**Out of scope:** denial-of-service and brute-force traffic by volume (mitigate at the network/firewall layer - see [FYRwall](https://github.com/PotenFYR-Studios/FYRwall)), social engineering, reports from misconfigured servers that override AuthCore's fail-closed defaults, and vulnerabilities in Minecraft itself, your loader, or other installed mods.

## Safe and responsible testing

Test only against servers you own or have written permission to test. Automated scanners, bot-join floods, and honeypot-triggering probes against servers you do not operate are abuse regardless of intent.
