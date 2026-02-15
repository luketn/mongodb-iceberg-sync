package com.luketn.mongodb.iceberg.sync.config;

/**
 * Exception thrown when configuration loading or validation fails.
 */
public final class SyncConfigException extends RuntimeException {

    public SyncConfigException(String message) {
        super(message);
    }

    public SyncConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
