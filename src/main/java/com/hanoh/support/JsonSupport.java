package com.hanoh.support;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class JsonSupport {

    private JsonSupport() {
    }

    public static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    public static Path writeAtomically(Path outputFile, String content) {
        try {
            Path absolute = outputFile.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("Output file must have a parent directory");
            }
            Files.createDirectories(parent);
            Path temporary = parent.resolve(absolute.getFileName() + ".tmp");
            Files.writeString(temporary, content);
            try {
                Files.move(
                    temporary,
                    absolute,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            return absolute;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write JSON artifact: " + outputFile, failure);
        }
    }
}
