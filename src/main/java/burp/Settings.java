package burp;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.persistence.Preferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Configuration store for the extension.
 * <p>
 * Every value is cached in memory and served from there, because
 * {@link #toTransportConfig(String)} runs on every proxied request and Burp's
 * {@link Preferences} is a persistence layer, not a hash map. Writes update the cache and
 * the store together. Fields are volatile because the UI writes them on the EDT while
 * proxy threads read them concurrently.
 */
public class Settings {
    private final Preferences storage;
    private final Logging logging;
    private final Gson gson = new Gson();

    private final String spoofProxyAddress = "SpoofProxyAddress";
    private final String interceptProxyAddress = "InterceptProxyAddress";
    private final String burpProxyAddress = "BurpProxyAddress";
    private final String fingerprint = "Fingerprint";
    private final String hexClientHello = "HexClientHello";
    private final String useInterceptedFingerprint = "UseInterceptedFingerprint";
    private final String httpTimeout = "HttpTimeout";
    private final String externalProxyUrl = "ExternalProxyUrl";

    /**
     * Where rules used to live. Still read once so existing setups migrate, and deliberately not
     * deleted afterwards so downgrading keeps working.
     */
    private final String legacyDomainRules = "DomainRules";

    public static final String DEFAULT_SPOOF_PROXY_ADDRESS = "127.0.0.1:8887";
    public static final String DEFAULT_INTERCEPT_PROXY_ADDRESS = "127.0.0.1:8886";
    public static final String DEFAULT_BURP_PROXY_ADDRESS = "127.0.0.1:8080";
    public static final Integer DEFAULT_HTTP_TIMEOUT = 30;
    public static final String DEFAULT_TLS_FINGERPRINT = "default";
    public static final Boolean USE_INTERCEPTED_FINGERPRINT = false;
    public static final String DEFAULT_EXTERNAL_PROXY_URL = "";

    private volatile String cachedSpoofProxyAddress;
    private volatile String cachedInterceptProxyAddress;
    private volatile String cachedBurpProxyAddress;
    private volatile String cachedFingerprint;
    private volatile String cachedHexClientHello;
    private volatile String cachedExternalProxyUrl;
    private volatile int cachedHttpTimeout;
    private volatile boolean cachedUseInterceptedFingerprint;

    /**
     * Immutable snapshot; replaced wholesale on save so readers never see a half-written list.
     */
    private volatile List<FingerprintRule> cachedRules;
    private volatile RuleMatcher matcher;

    private final RuleStore ruleStore;

    public Settings(MontoyaApi api) {
        this.storage = api.persistence().preferences();
        this.logging = api.logging();
        this.ruleStore = RuleStore.inConfigDir(api.logging()::logToError);

        this.cachedSpoofProxyAddress = read(spoofProxyAddress, DEFAULT_SPOOF_PROXY_ADDRESS);
        this.cachedInterceptProxyAddress = read(interceptProxyAddress, DEFAULT_INTERCEPT_PROXY_ADDRESS);
        this.cachedBurpProxyAddress = read(burpProxyAddress, DEFAULT_BURP_PROXY_ADDRESS);
        this.cachedFingerprint = read(fingerprint, DEFAULT_TLS_FINGERPRINT);
        this.cachedHexClientHello = read(hexClientHello, "");
        this.cachedExternalProxyUrl = read(externalProxyUrl, DEFAULT_EXTERNAL_PROXY_URL);
        this.cachedHttpTimeout = read(httpTimeout, DEFAULT_HTTP_TIMEOUT);
        this.cachedUseInterceptedFingerprint = read(useInterceptedFingerprint, USE_INTERCEPTED_FINGERPRINT);

        applyRules(loadRulesAtStartup());
    }

    /**
     * Rules come from the JSON file. A setup created before the file existed still has them in
     * Burp's preference store, so migrate those once.
     */
    private List<FingerprintRule> loadRulesAtStartup() {
        if (ruleStore.exists()) {
            return ruleStore.load();
        }

        var legacy = loadLegacyRules();
        if (legacy.isEmpty()) {
            return List.of();
        }

        try {
            ruleStore.save(legacy);
            logging.logToOutput("Awesome TLS: migrated " + legacy.size() + " domain rule(s) to " + ruleStore.path());
        } catch (IOException e) {
            // Keep going with the rules in memory; they are still in the preference store.
            logging.logToError("Awesome TLS: could not migrate domain rules to " + ruleStore.path() + ": " + e);
        }

        return legacy;
    }

    private List<FingerprintRule> loadLegacyRules() {
        var json = this.storage.getString(this.legacyDomainRules);
        if (json == null || json.isBlank()) {
            return List.of();
        }

        try {
            List<FingerprintRule> parsed = gson.fromJson(json, new TypeToken<List<FingerprintRule>>() {
            }.getType());
            if (parsed == null) {
                return List.of();
            }
            return parsed.stream().filter(Objects::nonNull).map(FingerprintRule::normalized).toList();
        } catch (Exception e) {
            logging.logToError("Failed to parse the stored domain rules, ignoring them: " + e);
            return List.of();
        }
    }

    private String read(String key, String defaultValue) {
        var value = this.storage.getString(key);
        if (value == null || value.isEmpty()) {
            this.write(key, defaultValue);
            return defaultValue;
        }
        return value;
    }

    private Boolean read(String key, Boolean defaultValue) {
        var value = this.storage.getBoolean(key);
        if (value == null) {
            this.storage.setBoolean(key, defaultValue);
            return defaultValue;
        }
        return value;
    }

    private Integer read(String key, Integer defaultValue) {
        var value = this.storage.getInteger(key);
        if (value == null) {
            this.storage.setInteger(key, defaultValue);
            return defaultValue;
        }
        return value;
    }

    public void write(String key, String value) {
        this.storage.setString(key, value);
    }

    public void write(String key, Boolean value) {
        this.storage.setBoolean(key, value);
    }

    public void write(String key, Integer value) {
        this.storage.setInteger(key, value);
    }

    public String getSpoofProxyAddress() {
        return this.cachedSpoofProxyAddress;
    }

    public void setSpoofProxyAddress(String spoofProxyAddress) {
        this.cachedSpoofProxyAddress = spoofProxyAddress;
        this.write(this.spoofProxyAddress, spoofProxyAddress);
    }

    public String getInterceptProxyAddress() {
        return this.cachedInterceptProxyAddress;
    }

    public void setInterceptProxyAddress(String interceptProxyAddress) {
        this.cachedInterceptProxyAddress = interceptProxyAddress;
        this.write(this.interceptProxyAddress, interceptProxyAddress);
    }

    public String getBurpProxyAddress() {
        return this.cachedBurpProxyAddress;
    }

    public void setBurpProxyAddress(String burpProxyAddress) {
        this.cachedBurpProxyAddress = burpProxyAddress;
        this.write(this.burpProxyAddress, burpProxyAddress);
    }

    public Boolean getUseInterceptedFingerprint() {
        return this.cachedUseInterceptedFingerprint;
    }

    public void setUseInterceptedFingerprint(Boolean useInterceptedFingerprint) {
        this.cachedUseInterceptedFingerprint = useInterceptedFingerprint;
        this.write(this.useInterceptedFingerprint, useInterceptedFingerprint);
    }

    public int getHttpTimeout() {
        return this.cachedHttpTimeout;
    }

    public void setHttpTimeout(Integer httpTimeout) {
        this.cachedHttpTimeout = httpTimeout;
        this.write(this.httpTimeout, httpTimeout);
    }

    public String getFingerprint() {
        return this.cachedFingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.cachedFingerprint = fingerprint;
        this.write(this.fingerprint, fingerprint);
    }

    public String getHexClientHello() {
        return this.cachedHexClientHello;
    }

    public void setHexClientHello(String hexClientHello) {
        this.cachedHexClientHello = hexClientHello;
        this.write(this.hexClientHello, hexClientHello);
    }

    public String getExternalProxyUrl() {
        return this.cachedExternalProxyUrl;
    }

    public void setExternalProxyUrl(String externalProxyUrl) {
        this.cachedExternalProxyUrl = externalProxyUrl;
        this.write(this.externalProxyUrl, externalProxyUrl);
    }

    /**
     * @return the configured per-domain rules, in display order.
     */
    public List<FingerprintRule> getRules() {
        return this.cachedRules;
    }

    /**
     * Applies rules and writes them to disk.
     * <p>
     * The in-memory state is updated first so an edit takes effect on the next request even if
     * the write fails; the caller is expected to surface the failure so the user knows the change
     * will not survive a restart.
     *
     * @throws IOException if the rules could not be written to disk.
     */
    public void setRules(List<FingerprintRule> rules) throws IOException {
        applyRules(rules);
        ruleStore.save(this.cachedRules);
    }

    /**
     * Updates the live configuration without touching disk.
     */
    private void applyRules(List<FingerprintRule> rules) {
        // Filter nulls rather than using List.copyOf directly: hand-edited JSON such as
        // "[null, {...}]" deserializes to a list with null entries, which copyOf rejects.
        var snapshot = rules.stream().filter(Objects::nonNull).toList();
        this.cachedRules = snapshot;
        this.matcher = new RuleMatcher(snapshot);
    }

    /**
     * @return where the rules are stored, for display and for import/export.
     */
    public RuleStore getRuleStore() {
        return this.ruleStore;
    }

    public String[] getFingerprints() {
        return ServerLibrary.INSTANCE.GetFingerprints().split("\n");
    }

    /**
     * Builds the per-request configuration sent to the Go server, applying the most specific
     * domain rule for {@code host} on top of the global defaults.
     */
    public TransportConfig toTransportConfig(String host) {
        var transportConfig = new TransportConfig();
        transportConfig.Fingerprint = this.getFingerprint();
        transportConfig.HexClientHello = this.getHexClientHello();
        transportConfig.HttpTimeout = this.getHttpTimeout();
        transportConfig.ExternalProxyUrl = this.getExternalProxyUrl();

        // These stay global on purpose: the Go server starts and stops a single shared
        // intercept proxy based on these values, so varying them per request would make it
        // thrash (see server.go:51-63).
        transportConfig.UseInterceptedFingerprint = this.getUseInterceptedFingerprint();
        transportConfig.BurpAddr = this.getBurpProxyAddress();
        transportConfig.InterceptProxyAddr = this.getInterceptProxyAddress();

        var rule = this.matcher.match(host);
        if (rule != null) {
            rule.applyTo(transportConfig);
        }

        return transportConfig;
    }
}
