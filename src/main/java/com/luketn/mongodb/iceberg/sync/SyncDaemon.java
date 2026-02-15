package com.luketn.mongodb.iceberg.sync;

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

        // TODO: Load configuration from configFile
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
