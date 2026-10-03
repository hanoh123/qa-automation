package com.qanize.automation.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing the automation genuinely inspected or executed.
 *
 * <p>Every field is recorded by the Selenium layer at the moment it acted. Optional fields stay
 * {@code null} when the engine did not collect them; they are never filled with placeholder text,
 * estimated durations or invented HTTP statuses. A {@link Status#PASSED} item therefore always
 * corresponds to a real action whose documented expectation was met.
 */
public record TestedItem(
    String id,
    String testType,
    String itemType,
    String name,
    String pageUrl,
    String target,
    String action,
    String expectedResult,
    String actualResult,
    Status status,
    String message,
    String startedAt,
    String completedAt,
    Long durationMs,
    String selector,
    Integer httpStatus,
    String severity,
    Map<String, String> metadata
) {

    public enum Status {
        PASSED,
        FAILED,
        SKIPPED
    }

    public enum ItemType {
        PAGE,
        LINK,
        FORM,
        FIELD,
        SEARCH,
        LOGIN,

        /**
         * A registered item kind that nothing records yet.
         *
         * <p>Declared last, alongside {@code CheckId.GAME_LAUNCH}, so the contract value is fixed
         * before any check produces one. No module builds an item of this kind.
         */
        GAME
    }

    public TestedItem {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static Builder builder(String testType, ItemType itemType) {
        return new Builder(testType, itemType);
    }

    /** Only set what was actually observed; anything left unset stays absent from the contract. */
    public static final class Builder {
        private final String testType;
        private final ItemType itemType;
        private String id;
        private String name;
        private String pageUrl;
        private String target;
        private String action;
        private String expectedResult;
        private String actualResult;
        private Status status = Status.SKIPPED;
        private String message;
        private String startedAt;
        private String completedAt;
        private Long durationMs;
        private String selector;
        private Integer httpStatus;
        private String severity;
        private final Map<String, String> metadata = new LinkedHashMap<>();

        private Builder(String testType, ItemType itemType) {
            this.testType = testType;
            this.itemType = itemType;
        }

        public Builder id(String value) { this.id = value; return this; }
        public Builder name(String value) { this.name = value; return this; }
        public Builder pageUrl(String value) { this.pageUrl = value; return this; }
        public Builder target(String value) { this.target = value; return this; }
        public Builder action(String value) { this.action = value; return this; }
        public Builder expected(String value) { this.expectedResult = value; return this; }
        public Builder actual(String value) { this.actualResult = value; return this; }
        public Builder status(Status value) { this.status = value; return this; }
        public Builder message(String value) { this.message = value; return this; }
        public Builder startedAt(String value) { this.startedAt = value; return this; }
        public Builder completedAt(String value) { this.completedAt = value; return this; }
        public Builder durationMs(Long value) { this.durationMs = value; return this; }
        public Builder selector(String value) { this.selector = value; return this; }
        public Builder httpStatus(Integer value) { this.httpStatus = value; return this; }
        public Builder severity(String value) { this.severity = value; return this; }

        public Builder meta(String key, String value) {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                metadata.put(key, value);
            }
            return this;
        }

        /**
         * Adds metadata only when the key is still absent, so the first writer keeps the key.
         *
         * <p>This is what lets an outer concern annotate an item — which crawled page it belongs
         * to, say — without any risk of quietly replacing something the check itself observed. A
         * check's own value always wins, and it wins visibly rather than by ordering luck.
         */
        public Builder metaIfAbsent(String key, String value) {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                metadata.putIfAbsent(key, value);
            }
            return this;
        }

        public TestedItem build() {
            return new TestedItem(
                id, testType, itemType.name(), name, pageUrl, target, action,
                expectedResult, actualResult, status, message,
                startedAt, completedAt, durationMs, selector, httpStatus, severity, metadata
            );
        }
    }
}
