package com.luketn.mongodb.iceberg.sync;

import com.luketn.mongodb.iceberg.sync.config.ConfigLoader;
import com.luketn.mongodb.iceberg.sync.config.SyncConfig;
import com.luketn.mongodb.iceberg.sync.config.SyncConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Entry point for mongodb-iceberg-sync daemon.
 * <p>
 * Synchronizes MongoDB collections to Apache Iceberg tables using a two-phase approach:
 * initial full sync followed by continuous change stream processing.
 */
@Command(
        name = "mongodb-iceberg-sync",
        mixinStandardHelpOptions = true,
        version = "0.1.0-SNAPSHOT",
        description = "Synchronizes MongoDB collections to Apache Iceberg tables."
)
public class SyncDaemon implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(SyncDaemon.class);

    @Option(names = {"-c", "--config"}, description = "Path to YAML configuration file", required = true)
    private Path configFile;

    @Override
    public Integer call() {
        logger.info("mongodb-iceberg-sync starting with config: {}", configFile);

        SyncConfig config;
        try {
            config = new ConfigLoader().load(configFile);
        } catch (SyncConfigException e) {
            logger.error("failed to load configuration: {}", e.getMessage());
            return 1;
        }

        logger.info("loaded {} collection sync configuration(s)", config.sync().collections().size());

        // TODO: Initialize Iceberg catalog via CatalogFactory
        // TODO: Initialize MongoDB client
        // TODO: Create and start SyncManager
        // TODO: Register shutdown hook for graceful stop

        logger.info("mongodb-iceberg-sync started successfully");
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SyncDaemon()).execute(args);
        System.exit(exitCode);
    }
}
