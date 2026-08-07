# Awesome TLS

**English** | [简体中文](./README.zh-CN.md)

> **This is a fork of [sleeyax/burp-awesome-tls](https://github.com/sleeyax/burp-awesome-tls).**
> The original extension — its design, its Go/JNA architecture and essentially all of the code this
> builds on — is the work of [@sleeyax](https://github.com/sleeyax) and its contributors.
> This fork continues development on top of it; see [what this fork adds](#what-this-fork-adds).
> Distributed under GPL v3, the same license as upstream.

This extension hijacks Burp's HTTP and TLS stack, allowing you to spoof any browser TLS
fingerprint ([JA3](https://github.com/salesforce/ja3)).
It boosts the power of Burp Suite while reducing the likelihood of fingerprinting by various WAFs like CloudFlare,
PerimeterX, Akamai, DataDome, etc.

This extension works without resorting to ugly hacks, reflection or forked Burp Suite Community code.

![screenshot](./docs/settings.png)

---

## What this fork adds

| | Upstream | This fork |
| --- | --- | --- |
| Fingerprint scope | One global setting | Per-domain rules, with the global setting as the fallback |
| Tools covered | Proxy only | Proxy, Repeater, Intruder, Scanner — every tool |
| Rule storage | — | A JSON file outside the Burp project: hand-editable, diffable, shareable |
| Saving | Manual, per tab | Rules save themselves; import/export to move them between machines |

Two bugs from upstream are fixed along the way: the two `Save all settings` buttons each persisted
only their own tab, so editing two tabs and saving from one silently discarded the other; and a
request that failed to process was dropped rather than forwarded.

The settings UI was rewritten as hand-written Swing, styled through Burp's own
`applyThemeToComponent`. The IntelliJ GUI designer form it replaced was never part of the Gradle
build, so it could only be regenerated from inside the IDE.

---

## Sponsors

> Maintenance of the original project is made possible by all the lovely contributors and sponsors.
> If you'd like to sponsor **the upstream project**, click [here](https://github.com/sponsors/sleeyax). 💖

---

## Showcase

[CloudFlare bot score](https://cloudflare.manfredi.io/en/tools/connection):

![cloudflare bot score of Burp Pro](./docs/cloudflare_bot_score_burp_pro.png)
![cloudflare bot score of Awesome TLS](./docs/cloudflare_bot_score_awesome_tls.png)

This is just one example. If you tested with another dedicated bot detection site, let me know your results!

## How it works

Unfortunately Burp's API is very limited for more advanced use cases like this, so I had to play around with it
to make this work.

Once a request comes in, the extension intercepts it and forwards it to a local HTTPS server that started in the
background (once the extension loaded). This applies to traffic from every Burp tool — Proxy, Repeater, Intruder and
Scanner all get the spoofed fingerprint. Burp's own internal traffic is left alone.
This server works like a proxy; it forwards the request to the destination, while persisting the original header order
and applying a customizable TLS configuration.
Then, the local server forwards the response back to Burp.

Configuration settings and other necessary information like the destination server address and protocol are sent to the
local server per request by a magic header.
This magic header is stripped from the request before it's forwarded to the destination server.

![diagram](./docs/basic_diagram.png)

> :information_source: Another option would've been to code an upstream proxy server and connect burp to it, but I
> personally needed an extension for customization and portability.

## Installation

1. Download the jar file for your operating system
   from [this fork's releases](https://github.com/Robin528919/burp-awesome-tls/releases). You can also download a fat
   jar, which works on all platforms supported by Awesome TLS. This means it's also portable and could be loaded from a
   USB for cross-platform access.
   (Upstream builds live at [sleeyax/burp-awesome-tls/releases](https://github.com/sleeyax/burp-awesome-tls/releases).)
2. Open burp (pro or community), go to Extender > Extensions and click on 'Add'. Then, select `Java` as the extension
   type and browse to the jar file you just downloaded. Click 'Next' at the bottom, and it should load the extension
   without any errors.
3. Check your new 'Awesome TLS' tab in Burp for configuration settings and start hacking!

## Configuration

This extension is 'plug and play' and should speak for itself. You can hover with your mouse over each field in the '
Awesome TLS' tab for more information about each field.

To load your custom Client Hello from WireShark, you can copy the client hello record as hex stream and paste it
into the field "Hex Client Hello".
![screenshot](./docs/wireshark_capture_client_hello.png)

What the three tabs are for:

| Tab | Purpose |
| --- | --- |
| **Defaults** | The global settings. Used by any request no domain rule matches, and the fallback for fields a matching rule leaves empty |
| **Domain rules** | Per-domain overrides. Empty cells inherit from Defaults |
| **Advanced** | Captures a client's **real** TLS fingerprint and replays it. Global — it cannot vary per domain |

### Per-domain fingerprints

The 'Domain rules' tab lets you use a different fingerprint per target instead of one global setting.
Each rule matches either an exact host (`example.com`) or its subdomains (`*.example.com`), and can override the
fingerprint, hex ClientHello, external proxy and timeout independently. Any cell left empty inherits the value from
the 'Defaults' tab.

When several rules could match, the most specific one wins: an exact host beats a wildcard, and a longer wildcard
suffix beats a shorter one. Row order does not matter.

Rules save themselves as you edit — there's no need to press 'Save settings' for them. They are stored as plain JSON
outside the Burp project, so they survive project switches and can be edited by hand, kept in version control, or
shared with a team:

| OS | Location |
| --- | --- |
| macOS | `~/Library/Application Support/burp-awesome-tls/rules.json` |
| Linux | `$XDG_CONFIG_HOME/burp-awesome-tls/rules.json` (or `~/.config/...`) |
| Windows | `%AppData%\burp-awesome-tls\rules.json` |

The previous version is always kept alongside it as `rules.json.bak`. Use 'Export…' and 'Import…' to move rules
between machines; importing asks whether to merge with or replace your current rules.

> :information_source: A rule with an incomplete host pattern is highlighted and simply ignored at request time,
> so you can leave one half-finished without breaking anything.

> :information_source: The settings on the 'Advanced' tab stay global. The local server runs a single shared intercept
> proxy, so those values cannot vary per domain.

#### Fingerprint and hex ClientHello are set as a pair

If a row has both a Fingerprint and a Hex ClientHello, **the hex wins and the fingerprint is ignored** — the Go side
always prefers the hex form.

Conversely, a row that sets only a Fingerprint *clears* the hex ClientHello it would otherwise inherit, rather than
combining the two.

The table shows what actually applies: a cell in the normal color is set by that row and in effect, a muted cell is
either inherited from Defaults or not used at all.

> :warning: `default` in the Fingerprint drop-down is a profile in its own right — it is **not** the same as leaving the
> cell empty. Only an empty cell means "inherit from Defaults".

<details>
  <summary>Advanced usage</summary>

In the 'advanced' tab, you can enable an additional proxy listener that will automatically apply the current fingerprint
from the request:

![screenshot](./docs/advanced_settings.png)

When enabled, the diagram changes to this:

![diagram](./docs/advanced_diagram.png)

> :warning: This takes priority over everything else. Once enabled and a fingerprint has been captured, it overrides the
> fingerprint of **every** domain rule. If your rules appear to have no effect, check that this switch is off first.

</details>

## Manual build Instructions

No particular IDE is required — the settings UI is hand-written Swing, so a JDK and a Go toolchain
are enough. See [workflows](.github/workflows) for the target language versions.

1. Compile the go package within `./src-go/`. Run
   `cd ./src-go/server && go build -o ../../src/main/resources/{OS}-{ARCH}/server.{EXT} -buildmode=c-shared ./cmd/main.go`,
   replacing `{OS}-{ARCH}` with your OS and CPU architecture and `{EXT}` with your platform's preferred extension for
   dynamic C libraries. For example: `linux-x86-64/server.so`. See
   the [JNA docs](https://github.com/java-native-access/jna/blob/master/www/GettingStarted.md) for more info about
   supported platforms.
2. Build the jar with Gradle: `gradle buildJar`.

You should now have one jar file (usually located at `./build/libs`) that works with Burp on your operating system.

## Credits

First and foremost, this project is a fork of
**[sleeyax/burp-awesome-tls](https://github.com/sleeyax/burp-awesome-tls)** by
[@sleeyax](https://github.com/sleeyax) and its
[contributors](https://github.com/sleeyax/burp-awesome-tls/graphs/contributors).

They designed and built the whole thing: the trick of routing Burp's traffic through a local Go
server to escape Burp's own TLS stack, the JNA bridge, the per-request configuration header, the
ClientHello parsing — all of it. This fork only adds features on top of that foundation. If it is
useful to you, go star and sponsor the original.

Special thanks to the maintainers of the following repositories:

- [refraction-networking/utls](https://github.com/refraction-networking/utls)
- [bogdanfinn/tls-client](https://github.com/bogdanfinn/tls-client)

And the creators of the following websites:

- https://tlsfingerprint.io/
- https://kawayiyi.com/tls
- https://tls.peet.ws/
- https://cloudflare.manfredi.io/en/tools/connection
- https://scrapfly.io/web-scraping-tools/http2-fingerprint

## License

[GPL V3](./LICENSE), inherited from the upstream project.

This is a modified version of [sleeyax/burp-awesome-tls](https://github.com/sleeyax/burp-awesome-tls).
The modifications are summarised under [what this fork adds](#what-this-fork-adds) and recorded in
full in the commit history.
