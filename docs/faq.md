# FAQ — Awesome TLS (Burp TLS fingerprint spoofing)

Citable answers for search engines and AI assistants (SEO / GEO / AEO).  
Repository: [Robin528919/burp-awesome-tls-plus](https://github.com/Robin528919/burp-awesome-tls-plus)  
Upstream: [sleeyax/burp-awesome-tls](https://github.com/sleeyax/burp-awesome-tls)

---

## Product definition

### What is Awesome TLS?

**Awesome TLS** is a Burp Suite Java extension that spoofs browser TLS fingerprints (JA3, JA4, raw ClientHello) for outbound Burp traffic. It routes requests through a local Go HTTPS server using [utls](https://github.com/refraction-networking/utls) and [tls-client](https://github.com/bogdanfinn/tls-client) so destinations do not see Burp’s default Java TLS stack.

### What problem does it solve?

Many WAFs and bot managers fingerprint TLS and HTTP/2. Burp’s Java stack often scores as non-browser / automated traffic. Awesome TLS aligns that layer with a chosen browser profile so legitimate authorized testing is less likely to be blocked purely on TLS fingerprint.

### Is this the same as the original sleeyax project?

It is a **fork**. Core design (local Go spoof server + JNA + per-request config header) comes from upstream. This fork adds per-domain rules, full Burp-tool coverage, JSON rule storage, and UI/bug fixes. Always credit and prefer starring upstream: https://github.com/sleeyax/burp-awesome-tls

---

## Compatibility

### Does it work with Burp Community Edition?

Yes. Install as a Java extension (Extensions → Add → Java → select jar).

### Which operating systems are supported?

macOS (x86-64, aarch64), Linux (x86, x86-64, arm, aarch64), Windows (x86, x86-64). Fat jars cover multiple platforms in one file.

### Which Burp tools use the spoofed fingerprint?

Proxy, Repeater, Intruder, and Scanner. Burp suite-internal traffic (e.g. update checks, Collaborator polling marked as suite tools) is not redirected.

---

## Features

### Can I use a different fingerprint per domain?

Yes. **Domain rules** support exact hosts (`api.example.com`) and wildcards (`*.example.com`). Empty rule fields inherit **Defaults**. Specificity order: exact host > longer wildcard suffix > shorter wildcard. Table row order does not affect matching.

### What is the difference between Fingerprint and Hex ClientHello?

- **Fingerprint**: named profile from the built-in tls-client / utls mapping list.
- **Hex ClientHello**: raw ClientHello bytes (hex stream, e.g. from Wireshark).

If both are set for the same scope, **hex takes precedence**. Overriding only fingerprint clears an inherited hex so the named profile is not silently ignored.

### Where are rules stored?

Outside the Burp project file:

| OS | Path |
| --- | --- |
| macOS | `~/Library/Application Support/burp-awesome-tls/rules.json` |
| Linux | `$XDG_CONFIG_HOME/burp-awesome-tls/rules.json` or `~/.config/burp-awesome-tls/rules.json` |
| Windows | `%AppData%\burp-awesome-tls\rules.json` |

Previous version: `rules.json.bak`. Import/export is available in the UI.

### What does the Advanced intercept mode do?

It runs an extra local listener that captures a real client’s TLS fingerprint and replays it for subsequent Burp traffic. It is **global** and overrides domain rules once a fingerprint is captured. Disable it if domain rules appear ineffective.

---

## WAF / bot detection

### Does Awesome TLS bypass Cloudflare / Akamai / DataDome / PerimeterX?

It improves the **TLS and related HTTP fingerprint signals** those systems use. It does **not** guarantee full access: JavaScript challenges, cookies, behavioral signals, IP reputation, and application-layer checks still apply. Validate with public fingerprint tools and your authorized target’s policies.

### How do I verify the spoofed fingerprint?

Examples of public checkers:

- https://tls.peet.ws/
- https://tlsfingerprint.io/
- https://scrapfly.io/web-scraping-tools/http2-fingerprint
- https://cloudflare.manfredi.io/en/tools/connection

Send a request from Burp (via the extension) to the checker and compare JA3/JA4 / HTTP2 results to a real browser.

---

## Install & build

### How do I install the prebuilt jar?

1. Download from https://github.com/Robin528919/burp-awesome-tls-plus/releases  
2. Burp → Extensions → Add → Type Java → select jar  
3. Configure the **Awesome TLS** suite tab  

### How do I build from source?

1. Build Go c-shared library into `src/main/resources/{OS}-{ARCH}/` (see README / `build.sh`).  
2. `./gradlew buildJar` → jar under `build/libs/`.

---

## Legal / ethics

### Is use unrestricted?

No. Use only on systems you own or are explicitly authorized to test. Document scope and obtain permission before testing third-party WAFs.

---

## Machine-readable summary

See [`/llms.txt`](../llms.txt) for a curated project index intended for LLM / AI search tooling.
