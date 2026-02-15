package com.luketn.mongodb.iceberg.sync.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record MongoConfig(String uri, String database) {

    public void validate(String path) {
        ConfigValidators.requireNonBlank(uri, path + ".uri");
        ConfigValidators.requireNonBlank(database, path + ".database");
    }
}
