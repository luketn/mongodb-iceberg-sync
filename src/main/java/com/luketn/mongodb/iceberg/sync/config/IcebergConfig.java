package com.luketn.mongodb.iceberg.sync.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record IcebergConfig(CatalogConfig catalog, Map<String, String> properties) {

    public IcebergConfig withDefaults() {
        return new IcebergConfig(catalog, ConfigValidators.nullToEmptyMap(properties));
    }

    public void validate(String path) {
        if (catalog == null) {
            throw new SyncConfigException(path + ".catalog is required");
        }
        catalog.validate(path + ".catalog");
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CatalogConfig(String type, String uri, String warehouse) {

        public void validate(String path) {
            ConfigValidators.requireOneOf(type, path + ".type", "local", "s3tables", "rest");
            String normalizedType = type.trim().toLowerCase();
            switch (normalizedType) {
                case "local", "s3tables" -> ConfigValidators.requireNonBlank(warehouse, path + ".warehouse");
                case "rest" -> {
                    ConfigValidators.requireNonBlank(uri, path + ".uri");
                    ConfigValidators.requireNonBlank(warehouse, path + ".warehouse");
                }
                default -> throw new SyncConfigException(path + ".type has unsupported value: " + type);
            }
        }
    }
}
