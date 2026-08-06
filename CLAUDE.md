# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Burp Suite extension ("Awesome TLS") that hijacks Burp's HTTP/TLS stack so outgoing
requests carry a spoofed browser TLS fingerprint (JA3) instead of Burp's Java stack
fingerprint. It is a fork of `sleeyax/burp-awesome-tls`.

Two languages, one artifact: a **Java** Burp extension that talks over **JNA** to a
**Go** shared library doing the actual TLS work.

## Build

The Go library must be built first — the compiled binary is `.gitignore`d, so a fresh
clone cannot produce a working jar from Gradle alone.

```sh
# 1. Build the Go c-shared library into the JNA resource dir for your platform
cd src-go/server
go build -o ../../src/main/resources/{OS}-{ARCH}/{PREFIX}server.{EXT} -buildmode=c-shared ./cmd/main.go

# 2. Build the jar (output: ./build/libs/burp-awesome-tls.jar)
./gradlew buildJar
```

`{OS}-{ARCH}` must match JNA's platform naming, and macOS needs a `lib` prefix — see
`ServerLibrary.java:8` for the runtime lookup and `build.sh` for the full table:

| Platform | Resource dir | File |
|---|---|---|
| macOS x64 / arm64 | `darwin-x86-64` / `darwin-aarch64` | `libserver.dylib` |
| Linux x86 / x64 / arm / arm64 | `linux-x86` / `linux-x86-64` / `linux-arm` / `linux-aarch64` | `server.so` |
| Windows x86 / x64 | `win32-x86` / `win32-x86-64` | `server.dll` |

`./build.sh` produces the per-platform *and* fat jars, but expects prebuilt xgo output in
`src-go/server/build/` — it does not compile Go itself. CI (`.github/workflows/release.yaml`)
runs xgo first, then `build.sh`.

The Go library also runs standalone for debugging: `go run ./cmd/main.go -spoof 127.0.0.1:8887`.

## Tests

There is no test framework — no `_test.go` files, no `src/test`, and CI only verifies that
both halves compile.

Two runnable self-checks cover the logic that breaks silently. Both are plain `main` methods
with no Burp dependency:

```sh
./gradlew compileJava
GSON=$(find ~/.gradle/caches -name 'gson-*.jar' | head -1)
java -ea -cp build/classes/java/main:$GSON burp.RuleMatcher   # matching + rule override semantics
java -ea -cp build/classes/java/main:$GSON burp.RuleStore     # on-disk format, atomic write, corruption handling
```

Everything else is verified manually: load the jar into Burp and check the resulting
fingerprint against `tls.peet.ws` or `scrapfly.io/web-scraping-tools/http2-fingerprint`.

## Architecture

### Request flow

```
Any Burp tool — Proxy, Repeater, Intruder, Scanner, other extensions
  └─> Extension.processHttpRequest (registered via api.http().registerHttpHandler)
        ├─ pass through if already rewritten, or if it is Burp's own traffic
        ├─ settings.toTransportConfig(host)      # defaults + most specific domain rule
        ├─ set Host / Scheme / HeaderOrder       # from the original request
        ├─ gson.toJson -> "Awesometlsconfig" header
        └─ request.withService(spoof server)     # redirect to the local Go server
              │
              v  (HTTPS, self-signed CA)
        Go server handler (server.go:41)
              ├─ ParseTransportConfig(header); strip the header
              ├─ NewClient(config)               # utls / tls-client with the chosen fingerprint
              ├─ restore Host/Scheme, re-apply header order
              └─ forward to the real destination, stream the response back to Burp
```

The key property: **all configuration travels per-request inside the magic header.** The
Go server keeps no per-connection config state, which is why per-domain fingerprints are
implemented entirely in Java — the Go side needed no changes at all.

### Per-domain rules

`FingerprintRule` overrides the global defaults for a hostname; `RuleMatcher` resolves which
one applies. An exact host beats a wildcard, and among wildcards the longest suffix wins, so
the outcome does not depend on row order in the UI. Empty rule fields inherit the defaults.

Two things to preserve when touching this path:

- **`Fingerprint` and `HexClientHello` must be overridden as a pair.** The Go side always
  prefers `HexClientHello`, so a rule setting only `Fingerprint` would be silently ignored
  whenever a global hex ClientHello is configured. `Settings.toTransportConfig` clears the
  other field when either is overridden.
- **`Settings` serves everything from memory.** `toTransportConfig` is on the per-request hot
  path; re-reading `Preferences` or re-parsing the rules JSON there would cost real throughput
  under Intruder/Scanner load. Writes update the cache and the store together, and the cached
  rule list is replaced wholesale (volatile) because the EDT writes it while proxy threads read it.

### Where configuration lives

Two different stores, on purpose:

| What | Where | Why |
|---|---|---|
| Listen address, default fingerprint, timeouts, intercept settings | Burp `Preferences` | A handful of short scalars |
| Domain rules | `rules.json` in the OS config dir, beside the Go side's `ca.der` | Unbounded size, hand-editable, diffable, shareable |

Rules are **not** in `Preferences` because it is backed by the Java preference store, which
rejects any single value over 8192 characters (`java.util.prefs.Preferences.MAX_VALUE_LENGTH`).
Two rules carrying a full ClientHello hex stream already exceed that, and the failure mode is
an exception thrown mid-save — silent data loss. `RuleStore` owns the file: atomic write via
temp-file + rename, previous version kept as `.bak` (saving is automatic, so there is no undo),
and unparseable content preserved as `.corrupt` rather than overwritten.

`RuleStore.configDir()` reimplements Go's `os.UserConfigDir()` — Java has no equivalent — so
both languages resolve to the same directory. Keep them in sync if either side changes.

Setups predating the file still have rules in `Preferences`; `Settings.loadRulesAtStartup()`
migrates them once and deliberately leaves the old key in place so a downgrade still works.

### The two halves

- `src/main/java/burp/` — Burp extension. `Extension` registers the proxy handler, the
  suite tab, and starts the Go server on a background thread. `Settings` wraps Burp's
  `Preferences` KV store (only String/Boolean/Integer are available). `ServerLibrary` is
  the JNA interface. `SettingsTab` is the UI.
- `src-go/server/` — `server.go` (the local HTTPS server + handler), `transport.go`
  (`TransportConfig` + tls-client construction), `hexclienthello.go` (parses a raw
  ClientHello hex stream into a utls spec), `certificate.go` (self-signed CA, cached under
  the OS config dir as `burp-awesome-tls/ca.der`), `intercept.go` (the optional
  fingerprint-sniffing proxy), `cmd/main.go` (cgo exports).

Go module is named `server` (not a domain path); `cmd/main.go` imports it as `"server"`.

## Non-obvious constraints

**`TransportConfig` is duplicated in both languages and matched by field name.**
`TransportConfig.java` and the struct in `transport.go` must stay in sync — gson serializes
using the Java field names (hence the unusual capitalized public fields) and Go's
`encoding/json` matches them case-insensitively. Renaming a field on one side only causes
a *silent* fallback to the zero value, not an error.

**The magic header name has a hard format restriction.** `Awesometlsconfig` — one leading
capital, rest lowercase. Burp's Extender API mangles anything else (see `server.go:14-16`).

**The handler re-enters itself.** The rewritten request is sent *by Burp*, so it arrives back at
`handleHttpRequestToBeSent` — an `HttpHandler` sees every outgoing request, unlike the
`ProxyRequestHandler` this used to be. The presence of the `Awesometlsconfig` header is what
marks a request as already handled; drop that guard and every request rewrites itself forever.
Burp's own traffic (`ToolType.SUITE` — update checks, Collaborator polling) is passed through
too, since redirecting it through the spoof server would break it.

**The UI is hand-written Swing — do not reintroduce a `.form` file.** The IntelliJ GUI
designer form was removed (along with the `com.intellij:forms_rt` dependency) because it was
never wired into Gradle: there was no `javac2` instrumentation, so `SettingsTab.form` could
only be turned into code from inside the IDE, and the generated `$$$setupUI$$$()` was a
committed build artifact that silently overwrote hand edits. `SettingsTab` now builds its
layout directly.

**Burp disables Swing's HTML rendering.** `new JLabel("<html>…")` displays the markup as
literal text, so the usual trick for wrapping or emphasising label copy does not work. Use
`SettingsTab.descriptionText()` (a borderless, non-editable wrapping `JTextArea`) for anything
longer than one line.

**Let Burp style the UI: `api.userInterface().applyThemeToComponent(component)`.** It applies
Burp's font size, colors and table line spacing for the active theme, and is called once on the
root panel in `Extension`. Never hardcode colors — Burp ships light and dark themes, and a fixed
color renders black-on-black under the dark one. `SettingsTab`'s `UIManager` helpers exist only
as a fallback for components Burp's pass does not reach. Related: `currentTheme()` returns
`LIGHT`/`DARK` if you ever need to branch.

**Burp's official settings-panel API does not fit this extension.** `SettingsPanelBuilder`
(registered via `registerSettingsPanel`) gives you Burp-native styling and persistence for free,
but only supports scalars — `stringSetting` / `integerSetting` / `booleanSetting` /
`listSetting`. There is no table type, so the domain rules cannot use it, and splitting the
config across `Settings > Extensions` and a suite tab would be worse than one coherent tab.
Do not migrate without re-checking whether a table setting has been added.

**Fields need a trailing filler column.** Burp's window is very wide; a `weightx=1` text field
stretches across all of it. `FormPanel` gives fields their natural width and lets a filler
column absorb the slack.

**The intercept proxy is global mutable state.** `server.go:51-63` starts/stops it based on
the `UseInterceptedFingerprint` flag of whichever request arrives. If that flag ever varies
between requests, the proxy will thrash (rebinding its port, cutting live connections).
It must stay a single global setting.

**A new tls-client is constructed per request** (`transport.go:126`) — no connection pooling
or client reuse across requests.

**Fingerprint precedence** (`transport.go:75-124`): intercepted ClientHello > `HexClientHello`
> named `Fingerprint` profile. The named profiles come from `profiles.MappedTLSClients`
(~79 entries) and are surfaced to Java as a newline-joined string via `GetFingerprints()`.

**Errors from the intercept proxy reach Burp as a fake HTTP request** to host
`awesome-tls-error` (`intercept.go:207-224`), which `Extension.java:66-68` recognizes and
re-throws. Don't treat that hostname as a real destination.
