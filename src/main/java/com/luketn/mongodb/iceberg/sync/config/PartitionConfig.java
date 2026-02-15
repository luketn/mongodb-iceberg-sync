package com.luketn.mongodb.iceberg.sync.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record PartitionConfig(String field, String transform) {

    public void validate(String path) {
        ConfigValidators.requireNonBlank(field, path + ".field");
        ConfigValidators.requireNonBlank(transform, path + ".transform");
    }
}
