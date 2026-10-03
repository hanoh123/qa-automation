package com.qanize.automation.evidence;

import com.qanize.automation.model.Evidence;
import com.qanize.automation.model.TestedItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the JSON Lines evidence file written by the Selenium layer.
 *
 * <p>Records are flat JSON objects with string, number, boolean or null values only, so this
 * parser is deliberately strict and dependency-free. A line that cannot be parsed, or that lacks
 * the fields required to describe a real action, is reported as a parse error and is never turned
 * into a tested item. Missing evidence therefore yields "no items", never "everything passed".
 */
public final class EvidenceFileReader {

    public record Result(List<TestedItem> items, List<String> parseErrors) {
        public Result {
            items = List.copyOf(items);
            parseErrors = List.copyOf(parseErrors);
        }

        public boolean hasParseErrors() {
            return !parseErrors.isEmpty();
        }
    }

    public Result read(Path evidenceFile) {
        if (evidenceFile == null || !Files.isRegularFile(evidenceFile)) {
            return new Result(List.of(), List.of());
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(evidenceFile, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return new Result(List.of(), List.of("Evidence file could not be read: " + failure.getMessage()));
        }

        List<TestedItem> items = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty()) continue;
            try {
                items.add(toItem(parseFlatObject(line)));
            } catch (RuntimeException failure) {
                errors.add("Evidence line " + (index + 1) + " was not readable: " + failure.getMessage());
            }
        }
        return new Result(items, errors);
    }

    private static TestedItem toItem(Map<String, String> values) {
        String testType = require(values, "testType");
        String itemType = require(values, "itemType");
        String statusText = require(values, "status");
        TestedItem.Status status;
        try {
            status = TestedItem.Status.valueOf(statusText);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unsupported status '" + statusText + "'");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().startsWith("metadata.")) {
                metadata.put(entry.getKey().substring("metadata.".length()), entry.getValue());
            }
        }
        return new TestedItem(
            values.get("id"),
            testType,
            itemType,
            values.get("name"),
            values.get("pageUrl"),
            values.get("target"),
            values.get("action"),
            values.get("expectedResult"),
            values.get("actualResult"),
            status,
            values.get("message"),
            values.get("startedAt"),
            values.get("completedAt"),
            optionalLong(values.get("durationMs")),
            values.get("selector"),
            optionalInt(values.get("httpStatus")),
            values.get("severity"),
            metadata
        );
    }

    private static String require(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required field '" + key + "'");
        }
        return value;
    }

    private static Long optionalLong(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Long.valueOf(value); } catch (NumberFormatException failure) { return null; }
    }

    private static Integer optionalInt(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.valueOf(value); } catch (NumberFormatException failure) { return null; }
    }

    /** Strict flat-object parser: {"k":"v","n":1,"b":true,"z":null}. Nested values are rejected. */
    static Map<String, String> parseFlatObject(String line) {
        Map<String, String> values = new LinkedHashMap<>();
        int position = skipSpace(line, 0);
        if (position >= line.length() || line.charAt(position) != '{') {
            throw new IllegalArgumentException("record must start with '{'");
        }
        position = skipSpace(line, position + 1);
        if (position < line.length() && line.charAt(position) == '}') return values;

        while (true) {
            position = skipSpace(line, position);
            if (position >= line.length() || line.charAt(position) != '"') {
                throw new IllegalArgumentException("expected a quoted key");
            }
            StringBuilder key = new StringBuilder();
            position = readString(line, position, key);
            position = skipSpace(line, position);
            if (position >= line.length() || line.charAt(position) != ':') {
                throw new IllegalArgumentException("expected ':' after key '" + key + "'");
            }
            position = skipSpace(line, position + 1);
            if (position >= line.length()) throw new IllegalArgumentException("truncated record");

            char start = line.charAt(position);
            if (start == '"') {
                StringBuilder value = new StringBuilder();
                position = readString(line, position, value);
                values.put(key.toString(), value.toString());
            } else if (start == '{' || start == '[') {
                throw new IllegalArgumentException("nested values are not supported in evidence records");
            } else {
                int end = position;
                while (end < line.length() && line.charAt(end) != ',' && line.charAt(end) != '}') end++;
                String literal = line.substring(position, end).trim();
                if (literal.isEmpty()) throw new IllegalArgumentException("empty value for key '" + key + "'");
                if (!literal.equals("null")) values.put(key.toString(), literal);
                position = end;
            }

            position = skipSpace(line, position);
            if (position >= line.length()) throw new IllegalArgumentException("unterminated record");
            char next = line.charAt(position);
            if (next == ',') { position++; continue; }
            if (next == '}') return values;
            throw new IllegalArgumentException("unexpected character '" + next + "'");
        }
    }

    private static int readString(String line, int position, StringBuilder out) {
        int index = position + 1;
        while (index < line.length()) {
            char current = line.charAt(index);
            if (current == '\\') {
                if (index + 1 >= line.length()) throw new IllegalArgumentException("truncated escape");
                char escaped = line.charAt(index + 1);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'u' -> {
                        if (index + 5 >= line.length()) throw new IllegalArgumentException("truncated unicode escape");
                        out.append((char) Integer.parseInt(line.substring(index + 2, index + 6), 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("unsupported escape '\\" + escaped + "'");
                }
                index += 2;
                continue;
            }
            if (current == '"') return index + 1;
            out.append(current);
            index++;
        }
        throw new IllegalArgumentException("unterminated string");
    }

    private static int skipSpace(String line, int position) {
        int index = position;
        while (index < line.length() && Character.isWhitespace(line.charAt(index))) index++;
        return index;
    }
}
