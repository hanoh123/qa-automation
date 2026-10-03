package com.qanize.automation.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.qanize.automation.browser.ScreenshotDirectoryResolver;
import com.qanize.automation.evidence.EvidenceRecorder;

/**
 * Points the run-specific artifact properties at one run for the duration of a
 * try-with-resources block, then puts back exactly what was there before.
 *
 * <p><strong>Why this exists.</strong> The legacy path forks a Maven process and passes the run's
 * destinations as {@code -D} arguments, so each run gets a fresh JVM and the question never
 * arises. An in-process run has no such boundary: the destinations are read from JVM-global
 * system properties by {@link EvidenceRecorder} and {@link ScreenshotDirectoryResolver}, which
 * means a second run in the same process would silently append to the first run's files unless
 * something installs and removes the values around it. That is this class, and nothing else.
 *
 * <p><strong>What it owns.</strong> Exactly the properties in {@link #propertyKeys()}. The keys
 * are taken from the consumers that read them rather than re-spelled here, so a key can never
 * drift between the producer and the consumer. Two artifacts deliberately have no property and so
 * are not listed:
 *
 * <ul>
 *   <li>the execution-step file is derived as a sibling of the evidence file, so installing the
 *       evidence file already moves it;</li>
 *   <li>the execution timeline is written through an explicit path argument, so it follows the
 *       run object rather than any global state.</li>
 * </ul>
 *
 * <p>The legacy misspelled screenshots key is untouched on purpose. Its consumer consults it only
 * when the canonical key is absent, and this scope always installs the canonical key, so a stale
 * legacy value cannot influence a scoped run and does not need clearing.
 *
 * <p><strong>Concurrency.</strong> System properties are JVM-global, so this offers no isolation
 * between threads and is not a way to run two crawls at once in one process. The intended model is
 * one in-process run per CLI process. Scopes may be nested when they are closed in the reverse
 * order they were opened — each one captures whatever it found and restores precisely that — but
 * there is no manager enforcing that order, and interleaved scopes on several threads will
 * interfere.
 *
 * <p><strong>Discretion.</strong> The scope holds property keys and filesystem destinations and
 * nothing else: no credentials, no site URLs, no selectors, no customer data. It never writes a
 * value to a log, and its {@code toString()} reports only how many properties it holds and whether
 * it is closed, so a path cannot reach a log through a debug statement or an exception trace.
 * Validation failures name the property key and the reason, never the offending value.
 */
public final class RunArtifactPropertyScope implements AutoCloseable {

    /**
     * The properties this scope installs, in installation order. Ordering matters only so that a
     * failure part-way through can be unwound in reverse.
     */
    private static final List<ArtifactProperty> ARTIFACT_PROPERTIES = List.of(
        new ArtifactProperty(
            EvidenceRecorder.EVIDENCE_FILE_PROPERTY,
            AutomationRunPaths::evidenceFile),
        new ArtifactProperty(
            ScreenshotDirectoryResolver.SCREENSHOTS_DIR_PROPERTY,
            AutomationRunPaths::screenshotsDirectory)
    );

    /**
     * The real property store. A stateless constant rather than run state: it holds nothing, so
     * two scopes sharing it share no information.
     */
    static final PropertyAccess SYSTEM_PROPERTIES = new PropertyAccess() {

        @Override
        public String get(String key) {
            return System.getProperty(key);
        }

        @Override
        public void set(String key, String value) {
            System.setProperty(key, value);
        }

        @Override
        public void clear(String key) {
            System.clearProperty(key);
        }
    };

    private final PropertyAccess properties;
    private final List<Restore> restores;
    private boolean closed;

    /**
     * Installs this run's destinations, or changes nothing at all.
     *
     * @throws IllegalArgumentException when any destination is missing or empty; in that case no
     *     property has been written, because every input is validated before the first write
     */
    public static RunArtifactPropertyScope open(AutomationRunPaths paths) {
        return new RunArtifactPropertyScope(paths, SYSTEM_PROPERTIES);
    }

    /** Visible for tests, which substitute a store that can be made to fail on demand. */
    RunArtifactPropertyScope(AutomationRunPaths paths, PropertyAccess properties) {
        if (properties == null) {
            throw new IllegalArgumentException("A property store is required");
        }
        this.properties = properties;
        this.restores = install(plan(paths));
    }

    /** The property keys this scope owns, in installation order. Keys only, never values. */
    public static List<String> propertyKeys() {
        return ARTIFACT_PROPERTIES.stream().map(ArtifactProperty::key).toList();
    }

    /**
     * Restores every property this scope changed, in the reverse order it changed them.
     *
     * <p>A property absent before the scope opened is cleared; a property that had a value gets
     * that exact value back. Closing again does nothing: the scope is marked closed before the
     * first restore, so a later owner of these properties cannot have its values overwritten by a
     * stray second close.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        restore(restores);
    }

    public boolean isClosed() {
        return closed;
    }

    /** Deliberately free of paths: this string may end up in a log or an exception trace. */
    @Override
    public String toString() {
        return "RunArtifactPropertyScope[properties=" + restores.size() + ", closed=" + closed + "]";
    }

    /**
     * Resolves and validates every destination without touching a single property.
     *
     * <p>Separating this from installation is what makes "invalid input changes nothing" true
     * rather than merely likely: by the time the first write happens, every value is known good.
     */
    private static Map<String, String> plan(AutomationRunPaths paths) {
        if (paths == null) {
            throw new IllegalArgumentException("Run paths are required");
        }
        Map<String, String> planned = new LinkedHashMap<>();
        for (ArtifactProperty property : ARTIFACT_PROPERTIES) {
            planned.put(
                property.key(),
                absoluteDestination(property.destination().apply(paths), property.key()));
        }
        return planned;
    }

    /**
     * Absolute and normalized, because a relative destination means something different to every
     * consumer depending on the working directory it happens to read the property from.
     */
    private static String absoluteDestination(Path destination, String key) {
        if (destination == null) {
            throw new IllegalArgumentException("No run destination was supplied for " + key);
        }
        if (destination.toString().isBlank()) {
            throw new IllegalArgumentException("The run destination for " + key + " is empty");
        }
        return destination.toAbsolutePath().normalize().toString();
    }

    /**
     * Writes the planned values, remembering what each one displaced.
     *
     * <p>If any write fails, the writes that already succeeded are undone before the failure
     * propagates, so a half-installed scope is never left behind for the caller to discover.
     */
    private List<Restore> install(Map<String, String> planned) {
        List<Restore> applied = new ArrayList<>();
        try {
            for (Map.Entry<String, String> entry : planned.entrySet()) {
                String key = entry.getKey();
                String previous = properties.get(key);
                properties.set(key, entry.getValue());
                applied.add(new Restore(key, previous));
            }
        } catch (RuntimeException | Error failure) {
            restore(applied);
            throw failure;
        }
        return List.copyOf(applied);
    }

    private void restore(List<Restore> applied) {
        for (int index = applied.size() - 1; index >= 0; index--) {
            Restore entry = applied.get(index);
            if (entry.previousValue() == null) {
                properties.clear(entry.key());
            } else {
                properties.set(entry.key(), entry.previousValue());
            }
        }
    }

    /**
     * The seam between this scope and the JVM's property store.
     *
     * <p>Package-private and used by exactly one production implementation. It exists so a test
     * can prove the rollback path without arranging for a real system property write to fail,
     * which cannot be done safely in a shared JVM.
     */
    interface PropertyAccess {

        String get(String key);

        void set(String key, String value);

        void clear(String key);
    }

    /** One owned property: its key, and where in the run object its destination comes from. */
    private record ArtifactProperty(String key, Function<AutomationRunPaths, Path> destination) {
    }

    /** What one property looked like before this scope changed it. A null value means absent. */
    private record Restore(String key, String previousValue) {
    }
}
