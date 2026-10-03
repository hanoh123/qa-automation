package com.qanize.automation.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Locates the six customer website-check entry classes, wherever their check package is.
 *
 * <p>Several internal tests read the checks' own source to prove architectural guarantees — that a
 * recorder is used by exactly one check, that no seventh check appeared, that an action is recorded
 * only after the call that performed it. Those tests used to reach the entry classes by listing one
 * flat directory. Each check now owns a package holding its entry class beside its own unit tests,
 * so a flat listing would return directories and would count helper tests as customer checks.
 *
 * <p>Identification is by the class-level {@code @Tag("website-analysis")}, which is what actually
 * makes a class a customer check: Surefire selects on that tag, so a class carrying it runs against
 * a customer's website and a class without it does not. Matching on the tag rather than on a
 * hard-coded list means a seventh tagged check would be found here — and fail the counts below —
 * rather than being quietly skipped.
 *
 * <p>This is a test helper. It declares no test case and changes no production behaviour.
 */
public final class CheckSources {

    /** The root of the per-check packages. */
    public static final Path CHECKS = Path.of("src/test/java/com/qanize/automation/checks");

    private static final String CUSTOMER_TAG = "@Tag(\"website-analysis\")";

    private CheckSources() {
    }

    /**
     * The source file of one customer entry class.
     *
     * @throws AssertionError when no customer check carries that name, so a rename or a lost check
     *                        fails loudly instead of silently reading nothing
     */
    public static Path entryClass(String simpleName) {
        return entryClasses().stream()
            .filter(path -> path.getFileName().toString().equals(simpleName + ".java"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No customer website-check entry class named " + simpleName + " under " + CHECKS));
    }

    /** Every customer entry class under {@link #CHECKS}, in stable sorted order. */
    public static List<Path> entryClasses() {
        try (Stream<Path> files = Files.walk(CHECKS)) {
            return files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .filter(CheckSources::isCustomerCheck)
                .sorted()
                .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read the check packages", unreadable);
        }
    }

    /** File names of the customer entry classes — the per-package form of "list the checks". */
    public static List<String> entryClassFileNames() {
        return entryClasses().stream()
            .map(path -> path.getFileName().toString())
            .sorted()
            .toList();
    }

    /** Class names of the customer entry classes, without the {@code .java} suffix. */
    public static List<String> entryClassNames() {
        return entryClassFileNames().stream()
            .map(name -> name.replace(".java", ""))
            .sorted()
            .toList();
    }

    private static boolean isCustomerCheck(Path source) {
        try {
            return Files.readString(source).contains(CUSTOMER_TAG);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Could not read " + source, unreadable);
        }
    }
}
