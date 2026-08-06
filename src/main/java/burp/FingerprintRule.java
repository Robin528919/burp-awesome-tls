package burp;

/**
 * A per-domain override of the global transport settings.
 * <p>
 * Any field left null or empty inherits the corresponding global default, so a rule can
 * override just the fingerprint while still using the global proxy and timeout.
 * <p>
 * Unlike {@link TransportConfig}, this type never crosses the JNA boundary: it is only
 * serialized to JSON for Burp's preference store, so it uses normal Java naming.
 */
public class FingerprintRule {
    /**
     * Exact hostname ({@code example.com}) or wildcard suffix ({@code *.example.com}).
     * Matching is case-insensitive and ignores the port.
     */
    public String hostPattern = "";

    /**
     * Named TLS profile, or empty to inherit the global fingerprint.
     */
    public String fingerprint = "";

    /**
     * Raw ClientHello as a hex stream, or empty to inherit.
     * Takes precedence over {@link #fingerprint}, mirroring the Go side's ordering.
     */
    public String hexClientHello = "";

    /**
     * Upstream proxy URL, or empty to inherit.
     */
    public String externalProxyUrl = "";

    /**
     * Connection timeout in seconds, or null to inherit.
     */
    public Integer httpTimeout;

    /**
     * Disabled rules stay in the table but never match.
     */
    public boolean enabled = true;

    public FingerprintRule() {
    }

    public FingerprintRule(String hostPattern, String fingerprint, String hexClientHello, String externalProxyUrl, Integer httpTimeout, boolean enabled) {
        this.hostPattern = hostPattern;
        this.fingerprint = fingerprint;
        this.hexClientHello = hexClientHello;
        this.externalProxyUrl = externalProxyUrl;
        this.httpTimeout = httpTimeout;
        this.enabled = enabled;
    }

    /**
     * Applies this rule's non-empty fields on top of an already populated {@code config}.
     * <p>
     * Must be called on a {@link #normalized()} instance, which {@link RuleMatcher} guarantees
     * for anything it returns.
     */
    void applyTo(TransportConfig config) {
        // The Go side always prefers HexClientHello over Fingerprint, so the two have to be
        // overridden as a pair. Otherwise a rule that only sets Fingerprint would be silently
        // ignored whenever a global hex ClientHello is configured.
        if (!hexClientHello.isEmpty()) {
            config.HexClientHello = hexClientHello;
            config.Fingerprint = "";
        } else if (!fingerprint.isEmpty()) {
            config.Fingerprint = fingerprint;
            config.HexClientHello = "";
        }

        if (!externalProxyUrl.isEmpty()) {
            config.ExternalProxyUrl = externalProxyUrl;
        }

        if (httpTimeout != null && httpTimeout > 0) {
            config.HttpTimeout = httpTimeout;
        }
    }

    /**
     * Gson populates fields directly and bypasses the constructor, so string fields may be
     * null when loading JSON written by an older version. Normalize instead of null-checking
     * at every use site.
     */
    FingerprintRule normalized() {
        return new FingerprintRule(
                orEmpty(hostPattern).trim(),
                orEmpty(fingerprint).trim(),
                orEmpty(hexClientHello).trim(),
                orEmpty(externalProxyUrl).trim(),
                httpTimeout,
                enabled
        );
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
