package burp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Reads and writes the domain rules as a JSON file on disk.
 * <p>
 * Burp's own preference store is backed by the Java preference store, which caps a single value
 * at 8192 characters. A couple of rules carrying a full ClientHello hex stream already exceed
 * that, so the rules live in a plain file instead: no size ceiling, and it can be edited by
 * hand, diffed, and shared.
 * <p>
 * Deliberately free of Burp API types so it can be exercised by {@link #main} without Burp.
 */
final class RuleStore {
    /**
     * Bumped only when the on-disk shape changes in a way older readers cannot handle.
     */
    static final int FORMAT_VERSION = 1;

    static final String FILE_NAME = "rules.json";

    private final Path file;
    private final Consumer<String> errorLog;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    RuleStore(Path file, Consumer<String> errorLog) {
        this.file = file;
        this.errorLog = errorLog;
    }

    /**
     * The same directory the Go side keeps its CA in, so all of the extension's state lives
     * together. Java has no equivalent of Go's os.UserConfigDir, so its behaviour is reproduced
     * here: %AppData% on Windows, ~/Library/Application Support on macOS, $XDG_CONFIG_HOME or
     * ~/.config elsewhere.
     */
    static Path configDir() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var home = System.getProperty("user.home", ".");

        String base;
        if (os.contains("win")) {
            base = System.getenv("AppData");
        } else if (os.contains("mac") || os.contains("darwin")) {
            base = home + "/Library/Application Support";
        } else {
            base = System.getenv("XDG_CONFIG_HOME");
            if (base == null || base.isBlank()) {
                base = home + "/.config";
            }
        }

        if (base == null || base.isBlank()) {
            base = home;
        }

        return Path.of(base, "burp-awesome-tls");
    }

    static RuleStore inConfigDir(Consumer<String> errorLog) {
        return new RuleStore(configDir().resolve(FILE_NAME), errorLog);
    }

    Path path() {
        return file;
    }

    boolean exists() {
        return Files.isRegularFile(file);
    }

    /**
     * @return the stored rules, or an empty list if the file is missing or unreadable. A file that
     * cannot be parsed is preserved as {@code .corrupt} rather than being overwritten silently.
     */
    List<FingerprintRule> load() {
        if (!exists()) {
            return List.of();
        }

        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            errorLog.accept("Could not read " + file + ": " + e);
            quarantine();
            return List.of();
        }
    }

    /**
     * Parses the file format, tolerating a bare JSON array so a hand-written or exported list
     * without the wrapper still imports.
     */
    static List<FingerprintRule> parse(String json) {
        var gson = new Gson();

        if (json == null || json.isBlank()) {
            return List.of();
        }

        List<FingerprintRule> parsed;
        if (json.stripLeading().startsWith("[")) {
            parsed = gson.fromJson(json, RuleFile.LIST_TYPE);
        } else {
            var wrapper = gson.fromJson(json, RuleFile.class);
            if (wrapper == null) {
                return List.of();
            }
            if (wrapper.version > FORMAT_VERSION) {
                throw new IllegalArgumentException(
                        "file was written by a newer version of the extension (format " + wrapper.version + ")");
            }
            parsed = wrapper.rules;
        }

        if (parsed == null) {
            return List.of();
        }

        var out = new ArrayList<FingerprintRule>(parsed.size());
        for (var rule : parsed) {
            if (rule != null) {
                out.add(rule.normalized());
            }
        }
        return List.copyOf(out);
    }

    static String serialize(List<FingerprintRule> rules) {
        var wrapper = new RuleFile();
        wrapper.version = FORMAT_VERSION;
        wrapper.rules = rules;
        return new GsonBuilder().setPrettyPrinting().create().toJson(wrapper) + "\n";
    }

    /**
     * Writes via a temporary file and a rename, so an interrupted write cannot leave a truncated
     * rules file behind. The previous contents are kept as {@code .bak}, because rules are saved
     * automatically and there is no undo.
     */
    void save(List<FingerprintRule> rules) throws IOException {
        var wrapper = new RuleFile();
        wrapper.version = FORMAT_VERSION;
        wrapper.rules = rules;

        Files.createDirectories(file.getParent());

        if (exists()) {
            try {
                Files.copy(file, backupPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                errorLog.accept("Could not back up " + file + ": " + e);
            }
        }

        var temp = Files.createTempFile(file.getParent(), "rules", ".tmp");
        try {
            Files.writeString(temp, gson.toJson(wrapper) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some filesystems (and Windows in places) refuse an atomic move; the plain one
                // is still better than writing the destination in place.
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    Path backupPath() {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    private void quarantine() {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            errorLog.accept("Could not quarantine " + file + ": " + e);
        }
    }

    private static final class RuleFile {
        static final java.lang.reflect.Type LIST_TYPE =
                new com.google.gson.reflect.TypeToken<List<FingerprintRule>>() {
                }.getType();

        int version;
        List<FingerprintRule> rules;
    }

    /**
     * Self-check for the on-disk behaviour, which is easy to break without noticing.
     * Run with: {@code java -ea -cp build/classes/java/main:<gson.jar> burp.RuleStore}
     */
    public static void main(String[] args) throws Exception {
        var dir = Files.createTempDirectory("awesome-tls-store");
        var file = dir.resolve(FILE_NAME);
        var errors = new ArrayList<String>();
        var store = new RuleStore(file, errors::add);

        check(store.load().isEmpty(), "a missing file loads as no rules");
        check(!store.exists(), "a missing file does not report as existing");

        var rules = List.of(
                new FingerprintRule("example.com", "chrome", "", "", 60, true),
                new FingerprintRule("*.api.example.com", "", "aabb", "socks5://127.0.0.1:1080", null, false));
        store.save(rules);

        var loaded = store.load();
        check(loaded.size() == 2, "both rules survive a round trip");
        check(loaded.get(0).hostPattern.equals("example.com") && loaded.get(0).httpTimeout == 60,
                "scalar fields survive a round trip");
        check(loaded.get(1).hexClientHello.equals("aabb") && !loaded.get(1).enabled,
                "hex and the enabled flag survive a round trip");
        check(loaded.get(1).httpTimeout == null, "an absent timeout stays absent");

        // Rewriting must leave the previous version recoverable, since saves are automatic.
        store.save(List.of(new FingerprintRule("only.com", "firefox", "", "", null, true)));
        check(store.load().size() == 1, "a rewrite replaces the previous contents");
        check(Files.exists(store.backupPath()), "a rewrite leaves a .bak behind");
        check(parse(Files.readString(store.backupPath())).size() == 2, "the .bak holds the previous rules");

        // A hand-edited or exported bare array is still accepted.
        check(parse("[{\"hostPattern\":\"bare.com\"}]").size() == 1, "a bare JSON array parses");
        check(parse("").isEmpty() && parse("   ").isEmpty(), "empty content parses as no rules");
        check(parse("{\"version\":1}").isEmpty(), "a wrapper without rules parses as no rules");

        // Nulls from hand-edited JSON must not reach callers.
        check(parse("{\"version\":1,\"rules\":[null,{\"hostPattern\":\"ok.com\"}]}").size() == 1,
                "null entries are dropped");
        check(parse("[{\"hostPattern\":\"x.com\"}]").get(0).fingerprint.isEmpty(),
                "parsed rules are normalized, never null-valued");

        var newer = "{\"version\":" + (FORMAT_VERSION + 1) + ",\"rules\":[]}";
        try {
            parse(newer);
            check(false, "a newer format version is rejected");
        } catch (IllegalArgumentException expected) {
            check(true, "a newer format version is rejected");
        }

        // Corrupt content must be preserved for inspection, not silently discarded.
        Files.writeString(file, "{ this is not json");
        check(store.load().isEmpty(), "corrupt content loads as no rules");
        check(Files.exists(file.resolveSibling(FILE_NAME + ".corrupt")), "corrupt content is quarantined");
        check(!errors.isEmpty(), "corrupt content is reported");

        check(configDir().endsWith("burp-awesome-tls"), "the config dir is namespaced");
        check(configDir().isAbsolute(), "the config dir is absolute");

        System.out.println("RuleStore self-check passed (" + dir + ")");
    }

    private static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError("failed: " + what);
    }
}
