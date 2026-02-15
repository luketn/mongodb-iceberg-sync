package com.luketn.mongodb.iceberg.sync.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record SyncConfig(MongoConfig mongodb, IcebergConfig iceberg, SyncSection sync) {

    public SyncConfig normalizeAndValidate() {
        if (mongodb == null) {
            throw new SyncConfigException("mongodb is required");
        }
        if (iceberg == null) {
            throw new SyncConfigException("iceberg is required");
        }
        if (sync == null) {
            throw new SyncConfigException("sync is required");
        }

        SyncSection normalizedSync = sync.withDefaults();
        IcebergConfig normalizedIceberg = iceberg.withDefaults();
        SyncConfig normalized = new SyncConfig(mongodb, normalizedIceberg, normalizedSync);
        normalized.validate();
        return normalized;
    }

    public void validate() {
        mongodb.validate("mongodb");
        iceberg.validate("iceberg");
        sync.validate("sync");
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SyncSection(List<CollectionSyncConfig> collections) {

        public SyncSection withDefaults() {
            List<CollectionSyncConfig> normalizedCollections = ConfigValidators.nullToEmpty(collections)
                    .stream()
                    .map(CollectionSyncConfig::withDefaults)
                    .toList();
            return new SyncSection(normalizedCollections);
        }

        public void validate(String path) {
            if (collections == null || collections.isEmpty()) {
                throw new SyncConfigException(path + ".collections must contain at least one collection config");
            }
            for (int i = 0; i < collections.size(); i++) {
                collections.get(i).validate(path + ".collections[" + i + "]");
            }
        }
    }
}
