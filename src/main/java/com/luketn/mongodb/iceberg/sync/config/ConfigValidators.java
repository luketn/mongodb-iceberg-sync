package com.luketn.mongodb.iceberg.sync.config;

import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ConfigValidators {

    private ConfigValidators() {
    }

    static void requireNonBlank(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new SyncConfigException(path + " is required");
        }
    }

    static void requireOneOf(String value, String path, String... options) {
        requireNonBlank(value, path);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.equals(normalized)) {
                return;
            }
        }
        throw new SyncConfigException(path + " must be one of: " + String.join(", ", options));
    }

    static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    static <K, V> Map<K, V> nullToEmptyMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }
}
