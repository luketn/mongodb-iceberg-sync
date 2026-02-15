package com.luketn.mongodb.iceberg.sync.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record MappingConfig(String mode, List<FieldMapping> fields) {

    public static final String MODE_AUTO = "auto";
    public static final String MODE_EXPLICIT = "explicit";

    public MappingConfig withDefaults() {
        String normalizedMode = mode == null ? MODE_AUTO : mode.trim().toLowerCase();
        return new MappingConfig(normalizedMode, ConfigValidators.nullToEmpty(fields));
    }

    public void validate(String path) {
        ConfigValidators.requireOneOf(mode, path + ".mode", MODE_AUTO, MODE_EXPLICIT);
        if (MODE_EXPLICIT.equals(mode) && fields.isEmpty()) {
            throw new SyncConfigException(path + ".fields must be non-empty when mode is explicit");
        }
        for (int i = 0; i < fields.size(); i++) {
            fields.get(i).validate(path + ".fields[" + i + "]");
        }
    }
}
