package com.luketn.mongodb.iceberg.sync.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record CollectionSyncConfig(
        SourceConfig source,
        TargetConfig target,
        MappingConfig mapping,
        List<PartitionConfig> partitioning,
        BatchConfig batch
) {

    public CollectionSyncConfig withDefaults() {
        MappingConfig normalizedMapping = mapping == null
                ? new MappingConfig(MappingConfig.MODE_AUTO, List.of())
                : mapping.withDefaults();
        BatchConfig normalizedBatch = batch == null ? new BatchConfig(null, null, null) : batch;
        return new CollectionSyncConfig(
                source,
                target,
                normalizedMapping,
                ConfigValidators.nullToEmpty(partitioning),
                normalizedBatch.withDefaults()
        );
    }

    public void validate(String path) {
        if (source == null) {
            throw new SyncConfigException(path + ".source is required");
        }
        if (target == null) {
            throw new SyncConfigException(path + ".target is required");
        }
        if (mapping == null) {
            throw new SyncConfigException(path + ".mapping is required");
        }
        if (batch == null) {
            throw new SyncConfigException(path + ".batch is required");
        }

        source.validate(path + ".source");
        target.validate(path + ".target");
        mapping.validate(path + ".mapping");
        batch.validate(path + ".batch");

        for (int i = 0; i < partitioning.size(); i++) {
            partitioning.get(i).validate(path + ".partitioning[" + i + "]");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SourceConfig(String collection) {

        public void validate(String path) {
            ConfigValidators.requireNonBlank(collection, path + ".collection");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TargetConfig(String namespace, String table) {

        public void validate(String path) {
            ConfigValidators.requireNonBlank(namespace, path + ".namespace");
            ConfigValidators.requireNonBlank(table, path + ".table");
        }
    }
}
