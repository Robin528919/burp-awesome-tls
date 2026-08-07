# Why one global JA3 isn't enough: per-domain TLS fingerprints in Burp Suite

*For authorized penetration testing and security research only. Everything below assumes you have written permission to test the targets involved.*

Burp Suite has a fingerprint problem that has nothing to do with what you send. Before a single byte of your request exists, the JVM has already announced who you are.

## The constant nobody configures

Every TLS connection opens with a ClientHello: protocol versions, cipher suites, extensions, elliptic curves, and the order they appear in. Hash that structure and you get a JA3 — a short, stable identifier for the *stack*, not the traffic.

Java's TLS stack produces a JA3 that no browser on earth produces. It doesn't leak on unusual payloads or bad headers; it leaks on connection setup, every time, identically. A WAF matching that hash needs no heuristics and no scoring model. It is a lookup.

This is why a request that Burp sends and a request Chrome sends can be byte-identical at the HTTP layer and still be classified differently. You never got to the HTTP layer.

## What the existing fix covers

[Awesome TLS](https://github.com/sleeyax/burp-awesome-tls) by [@sleeyax](https://github.com/sleeyax) solved the hard part of this years ago, and solved it cleanly: run a local Go server built on [utls](https://github.com/refraction-networking/utls), redirect Burp's outbound traffic through it, and let Go emit a browser-shaped ClientHello. No reflection into Burp's internals, no forked Burp code. The architecture in this post is entirely theirs — what follows is about two gaps that show up once you use it on real engagements.

## Gap 1: a real engagement is not one origin

Any scope of meaningful size spans several hosts, and they do not share a threat model. The marketing site is behind a permissive CDN. The API gateway is behind Cloudflare with a strict bot score. An internal admin panel has no bot protection at all and a TLS terminator old enough that a modern Chrome ClientHello negotiates poorly against it.

A single global fingerprint forces one setting across all three. Tune it for the strict origin and you may break the legacy one. Tune it for the legacy origin and you fail the strict one. Most testers end up flipping the setting by hand as they move around the scope, which is both tedious and easy to forget — and forgetting is silent. Nothing errors. The request just gets scored differently than you think it did.

## Gap 2: mixed fingerprints from one IP are *worse* than a bad one

This is the gap that surprised me.

An extension that hooks only the Proxy leaves Repeater, Intruder, and Scanner on Burp's default Java stack. So the same source IP, within the same few minutes, presents:

- Chrome's JA3 for browsed traffic
- Java's JA3 for every request you actually attack with

No browser does that. Two distinct TLS stacks from one address is not a weaker signal than plain Java — it is a *stronger* one, because it has no legitimate explanation. You have replaced "this looks automated" with "this is automated and trying not to look it."

Worth noting: Intruder is exactly where you least want to be re-classified, because that is where request volume makes you interesting in the first place.

## What the fork changes

[`burp-awesome-tls-plus`](https://github.com/Robin528919/burp-awesome-tls-plus) keeps upstream's Go + JNA architecture unchanged and addresses both gaps:

**Per-domain rules.** A rule targets an exact host (`api.example.com`) or a wildcard suffix (`*.example.com`). Exact beats wildcard, and among wildcards the longest suffix wins, so behaviour does not depend on the order rows happen to sit in the table. Empty fields inherit the global defaults, so a rule can override the fingerprint alone and leave timeouts as they were.

**Every Burp tool.** Registering an `HttpHandler` instead of a proxy-only handler means Proxy, Repeater, Intruder, Scanner, and requests other extensions send all go through the same path. Burp's own suite traffic — update checks, Collaborator polling — is deliberately left alone, because redirecting it breaks it.

One implementation detail worth knowing if you write rules by hand: the fingerprint profile and the raw hex ClientHello are overridden as a pair. The Go side always prefers a hex ClientHello when one is present, so a rule that set only the named profile would be silently ignored whenever a global hex stream was configured. Setting either one clears the other.

Rules live in `rules.json` in the OS config directory, outside the Burp project file — so they survive project churn, diff cleanly, and can be shared across a team via git.

## Measured effect

Cloudflare bot score, same target, same source IP, Burp Professional:

| | Screenshot |
| --- | --- |
| Burp's default Java stack | ![Bot score with stock Burp](../cloudflare_bot_score_burp_pro.png) |
| With a spoofed Chrome fingerprint | ![Bot score with Awesome TLS](../cloudflare_bot_score_awesome_tls.png) |

The ClientHello actually on the wire, captured in Wireshark:

![ClientHello in Wireshark](../wireshark_capture_client_hello.png)

## Getting started

1. Download the jar for your platform from [releases](https://github.com/Robin528919/burp-awesome-tls-plus/releases) — 8 OS/arch targets are prebuilt, plus a fat jar. No Go toolchain needed.
2. Burp → Extensions → Add → type *Java* → select the jar.
3. Verify against [tls.peet.ws](https://tls.peet.ws/) or [Scrapfly's HTTP/2 fingerprint checker](https://scrapfly.io/web-scraping-tools/http2-fingerprint) from Repeater. If the JA3 you see matches the profile you picked, you're set.

For per-domain rules, add a row in the Domain Rules tab; leave a cell empty to inherit the default.

## Where this stops working

TLS fingerprint alignment is one signal out of many. It does nothing for JavaScript challenges, CAPTCHAs, IP reputation, behavioural timing, or TLS session resumption patterns. A target that scores you on any of those will still score you.

It is also worth being honest about what a "Chrome" profile is: a snapshot of a ClientHello from some Chrome build at some point in time. Chrome ships every few weeks. A profile that matched perfectly last quarter is a slightly stale browser today — usually fine, occasionally the thing that gives you away.

## Credit

The design, the Go/JNA architecture, and essentially all the code this builds on are the work of [@sleeyax](https://github.com/sleeyax) and the [upstream contributors](https://github.com/sleeyax/burp-awesome-tls/graphs/contributors). This fork continues development on top of it under the same GPL-3.0 license. A full [feature comparison](../comparison.md) is in the repo.
