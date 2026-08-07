# burp-awesome-tls-plus vs upstream

**This project:** [Robin528919/burp-awesome-tls-plus](https://github.com/Robin528919/burp-awesome-tls-plus)  
**Upstream:** [sleeyax/burp-awesome-tls](https://github.com/sleeyax/burp-awesome-tls)  
**Last updated:** 2026-08-07

## One-sentence difference

**burp-awesome-tls-plus** keeps upstream’s Go + JNA TLS spoof architecture and adds **per-domain fingerprint rules**, spoofing for **every Burp tool** rather than Proxy alone, and **auto-saved shareable `rules.json`**.

## Feature matrix

| Capability | Upstream `sleeyax/burp-awesome-tls` | This fork `burp-awesome-tls-plus` |
| --- | --- | --- |
| Spoof JA3 / JA4 / ClientHello via local Go server | Yes | Yes (same foundation) |
| Named browser profiles (tls-client) | Yes | Yes — **80** list entries (`default` + 79 `MappedTLSClients`) |
| Custom hex ClientHello | Yes | Yes |
| Per-domain fingerprint rules | No (global only) | Yes — exact host and `*.suffix` |
| Rule match specificity | N/A | Exact > longer wildcard > shorter; row order irrelevant |
| Burp tools rewritten | Proxy-focused historically | **Every tool:** Proxy, Repeater, Intruder, Scanner and other extensions; only Burp's own suite traffic is skipped |
| Settings persistence | Manual save, one tab at a time — saving from one tab silently discarded edits made in the other | Rules auto-save; import/export; the split-tab bug is fixed |
| Rule file location | — | OS config dir `burp-awesome-tls-plus/rules.json`, migrated from a pre-rename `burp-awesome-tls/` on first start |
| UI | IntelliJ form heritage / upstream UI | Hand-written Swing + Burp `applyThemeToComponent` |
| License | GPL-3.0 | GPL-3.0 (inherited) |
| Prebuilt jars | Upstream releases | [plus releases](https://github.com/Robin528919/burp-awesome-tls-plus/releases) — **8** OS/arch + fat jar |

## When to use which

| Situation | Prefer |
| --- | --- |
| Need one global fingerprint and follow upstream only | Upstream |
| Need **different fingerprints per target host** | **burp-awesome-tls-plus** |
| Need Repeater / Intruder / Scanner spoofed, not only Proxy | **burp-awesome-tls-plus** |
| Team shares rule files via git | **burp-awesome-tls-plus** |
| Contributing core TLS stack changes | Coordinate with upstream; this fork tracks their design |

## What both do *not* guarantee

TLS fingerprint alignment is only one bot-detection signal. Neither project claims to bypass JavaScript challenges, CAPTCHAs, or IP reputation alone. Validate with checkers such as [tls.peet.ws](https://tls.peet.ws/) and use only on authorized targets.

## Credits

Architecture and core implementation: [@sleeyax](https://github.com/sleeyax) and [upstream contributors](https://github.com/sleeyax/burp-awesome-tls/graphs/contributors).  
Fork features and packaging: [Robin528919/burp-awesome-tls-plus](https://github.com/Robin528919/burp-awesome-tls-plus).
