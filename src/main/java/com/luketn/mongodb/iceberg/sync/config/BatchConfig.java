package com.luketn.mongodb.iceberg.sync.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = false)
public record BatchConfig(Long maxRecords, Long maxBytes, Integer flushIntervalSeconds) {

    public static final long DEFAULT_MAX_RECORDS = 50_000L;
    public static final long DEFAULT_MAX_BYTES = 134_217_728L;
    public static final int DEFAULT_FLUSH_INTERVAL_SECONDS = 60;

    public BatchConfig withDefaults() {
        return new BatchConfig(
                maxRecords == null ? DEFAULT_MAX_RECORDS : maxRecords,
                maxBytes == null ? DEFAULT_MAX_BYTES : maxBytes,
                flushIntervalSeconds == null ? DEFAULT_FLUSH_INTERVAL_SECONDS : flushIntervalSeconds
        );
    }

    public void validate(String path) {
        if (maxRecords == null || maxRecords <= 0) {
            throw new SyncConfigException(path + ".maxRecords must be > 0");
        }
        if (maxBytes == null || maxBytes <= 0) {
            throw new SyncConfigException(path + ".maxBytes must be > 0");
        }
        if (flushIntervalSeconds == null || flushIntervalSeconds <= 0) {
            throw new SyncConfigException(path + ".flushIntervalSeconds must be > 0");
        }
    }
}
