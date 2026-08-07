# FAQ — burp-awesome-tls-plus (Awesome TLS)

Citable answers for search engines and AI assistants (SEO / GEO / AEO).  
**Repository:** [Robin528919/burp-awesome-tls-plus](https://github.com/Robin528919/burp-awesome-tls-plus)  
**Upstream:** [sleeyax/burp-awesome-tls](https://github.com/sleeyax/burp-awesome-tls)  
**Last updated:** 2026-08-07 · **Latest release:** v2.3.1

---

## Product definition

### What is burp-awesome-tls-plus?

**burp-awesome-tls-plus** is the GitHub repository for this Awesome TLS fork. It is a Burp Suite Java extension that spoofs browser TLS fingerprints (JA3, JA4, raw ClientHello) for outbound Burp traffic via a local Go HTTPS server using [utls](https://github.com/refraction-networking/utls) and [tls-client](https://github.com/bogdanfinn/tls-client).

### What problem does it solve?

Many WAFs and bot managers fingerprint TLS and HTTP/2. Burp’s Java stack often scores as non-browser traffic. This extension aligns that layer with a chosen browser profile so authorized testing is less likely to be blocked purely on TLS fingerprint.

### Is this the same as sleeyax/burp-awesome-tls?

No. It is a **fork**. Core design comes from upstream. **burp-awesome-tls-plus** adds per-domain rules, full Burp-tool coverage (four tools), JSON rule storage, and UI/bug fixes. See [comparison.md](./comparison.md). Credit and star the upstream project: https://github.com/sleeyax/burp-awesome-tls

---

## Compatibility

### Does it work with Burp Community Edition?

Yes. Install as a Java extension (Extensions → Add → Java → select jar).

### Which operating systems are supported?

**Eight** prebuilt OS/arch targets: macOS (x86-64, aarch64), Linux (x86, x86-64, arm, aarch64), Windows (x86, x86-64). A fat jar covers multiple platforms in one file.

### Which Burp tools use the spoofed fingerprint?

Exactly **four**: Proxy, Repeater, Intruder, and Scanner. Burp suite-internal traffic is not redirected.

---

## Features

### How many TLS fingerprint profiles are included?

**80** list entries: `default` plus **79** named profiles from tls-client `MappedTLSClients` (Chrome, Firefox, Safari, mobile, OkHttp, and others). Custom hex ClientHello is also supported.

### Can I use a different fingerprint per domain?

Yes. **Domain rules** support exact hosts (`api.example.com`) and wildcards (`*.example.com`). Empty fields inherit **Defaults**. Specificity: exact host > longer wildcard suffix > shorter wildcard. Row order does not matter.

### What is the difference between Fingerprint and Hex ClientHello?

- **Fingerprint**: named profile from the built-in list.  
- **Hex ClientHello**: raw ClientHello bytes (hex stream, e.g. from Wireshark).  

If both are set for the same scope, **hex takes precedence**. Overriding only fingerprint clears an inherited hex so the named profile is not silently ignored.

### Where are rules stored?

Outside the Burp project file (directory name `burp-awesome-tls` is the product config folder, not the GitHub repo name):

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

### Does burp-awesome-tls-plus bypass Cloudflare / Akamai / DataDome / PerimeterX?

It improves the **TLS and related HTTP fingerprint signals** those systems use. It does **not** guarantee full access: JavaScript challenges, cookies, behavioral signals, and IP reputation still apply. Validate with public fingerprint tools and authorized targets only.

### How do I verify the spoofed fingerprint?

Public checkers:

- https://tls.peet.ws/
- https://tlsfingerprint.io/
- https://scrapfly.io/web-scraping-tools/http2-fingerprint
- https://cloudflare.manfredi.io/en/tools/connection

Send a request from Burp (with the extension enabled) and compare JA3/JA4 / HTTP2 results to a real browser.

---

## Install & build

### How do I install the prebuilt jar?

1. Download from https://github.com/Robin528919/burp-awesome-tls-plus/releases (latest: v2.3.1)  
2. Burp → Extensions → Add → Type Java → select jar  
3. Configure the **Awesome TLS** suite tab  

### How do I build from source?

1. Build the Go c-shared library into `src/main/resources/{OS}-{ARCH}/` (see README / `build.sh`).  
2. `./gradlew buildJar` → jar under `build/libs/`.

---

## Legal / ethics

### Is use unrestricted?

No. Use only on systems you own or are explicitly authorized to test.

---

## Machine-readable summary

See [`/llms.txt`](../llms.txt). Comparison matrix: [comparison.md](./comparison.md).
