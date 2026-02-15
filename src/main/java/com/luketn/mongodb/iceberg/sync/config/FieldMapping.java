package com.luketn.mongodb.iceberg.sync.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record FieldMapping(String source, String target, String type) {

    public void validate(String path) {
        ConfigValidators.requireNonBlank(source, path + ".source");
        ConfigValidators.requireNonBlank(target, path + ".target");
    }
}
