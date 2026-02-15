package com.luketn.mongodb.iceberg.sync.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    private final ConfigLoader loader = new ConfigLoader();

    @TempDir
    Path tempDir;

    @Test
    void validConfigLoadsCorrectly() throws IOException {
        Path config = writeConfig("""
                mongodb:
                  uri: "mongodb://localhost:27017"
                  database: "mydb"
                iceberg:
                  catalog:
                    type: "local"
                    warehouse: "/tmp/warehouse"
                  properties:
                    client.region: "us-east-1"
                sync:
                  collections:
                    - source:
                        collection: "orders"
                      target:
                        namespace: "analytics"
                        table: "orders"
                      mapping:
                        mode: "auto"
                      partitioning:
                        - field: "order_date"
                          transform: "month"
                      batch:
                        maxRecords: 10
                        maxBytes: 100
                        flushIntervalSeconds: 5
                """);

        SyncConfig loaded = loader.load(config);

        assertThat(loaded.mongodb().uri()).isEqualTo("mongodb://localhost:27017");
        assertThat(loaded.mongodb().database()).isEqualTo("mydb");
        assertThat(loaded.iceberg().catalog().type()).isEqualTo("local");
        assertThat(loaded.sync().collections()).hasSize(1);
        assertThat(loaded.sync().collections().getFirst().source().collection()).isEqualTo("orders");
    }

    @Test
    void missingRequiredFieldsProduceClearErrors() throws IOException {
        Path config = writeConfig("""
                mongodb:
                  database: "mydb"
                iceberg:
                  catalog:
                    type: "local"
                    warehouse: "/tmp/warehouse"
                sync:
                  collections:
                    - source:
                        collection: "orders"
                      target:
                        namespace: "analytics"
                        table: "orders"
                """);

        assertThatThrownBy(() -> loader.load(config))
                .isInstanceOf(SyncConfigException.class)
                .hasMessageContaining("mongodb.uri is required");
    }

    @Test
    void defaultsAppliedForOptionalFields() throws IOException {
        Path config = writeConfig("""
                mongodb:
                  uri: "mongodb://localhost:27017"
                  database: "mydb"
                iceberg:
                  catalog:
                    type: "local"
                    warehouse: "/tmp/warehouse"
                sync:
                  collections:
                    - source:
                        collection: "orders"
                      target:
                        namespace: "analytics"
                        table: "orders"
                """);

        SyncConfig loaded = loader.load(config);

        CollectionSyncConfig collection = loaded.sync().collections().getFirst();
        assertThat(collection.mapping().mode()).isEqualTo("auto");
        assertThat(collection.mapping().fields()).isEmpty();
        assertThat(collection.partitioning()).isEmpty();

        assertThat(collection.batch().maxRecords()).isEqualTo(BatchConfig.DEFAULT_MAX_RECORDS);
        assertThat(collection.batch().maxBytes()).isEqualTo(BatchConfig.DEFAULT_MAX_BYTES);
        assertThat(collection.batch().flushIntervalSeconds()).isEqualTo(BatchConfig.DEFAULT_FLUSH_INTERVAL_SECONDS);
        assertThat(loaded.iceberg().properties()).isEmpty();
    }

    @Test
    void environmentVariableSubstitutionWorks() throws IOException {
        String home = System.getenv("HOME");
        assertThat(home).isNotBlank();

        Path config = writeConfig("""
                mongodb:
                  uri: "mongodb://localhost:27017"
                  database: "mydb"
                iceberg:
                  catalog:
                    type: "local"
                    warehouse: "${HOME}/iceberg"
                sync:
                  collections:
                    - source:
                        collection: "orders"
                      target:
                        namespace: "analytics"
                        table: "orders"
                """);

        SyncConfig loaded = loader.load(config);

        assertThat(loaded.iceberg().catalog().warehouse()).isEqualTo(home + "/iceberg");
    }

    private Path writeConfig(String yaml) throws IOException {
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, yaml);
        return file;
    }
}
