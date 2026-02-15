# mongodb-iceberg-sync: Design Document

## 1. Architecture Overview

mongodb-iceberg-sync is a Java daemon that synchronizes data from MongoDB collections to Apache Iceberg tables. It follows a two-phase approach inspired by mongot:

1. **Initial Sync**: Full collection scan, writing all documents to Iceberg.
2. **Steady State**: MongoDB change stream processing for incremental updates.

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      mongodb-iceberg-sync                       │
│                                                                 │
│  ┌──────────────────────┐      ┌─────────────────────────────┐  │
│  │   SyncManager        │      │   Configuration (YAML)      │  │
│  │   (top-level)        │      │   - MongoDB connection      │  │
│  │                      │      │   - Iceberg catalog config   │  │
│  │  ┌────────────────┐  │      │   - Collection→table maps   │  │
│  │  │CollectionSync   │  │      │   - Batch settings          │  │
│  │  │Manager (per     │  │      └─────────────────────────────┘  │
│  │  │collection)      │  │                                       │
│  │  │                 │  │      ┌─────────────────────────────┐  │
│  │  │┌──────────────┐│  │      │   CheckpointStore           │  │
│  │  ││InitialSync   ││  │      │   (Iceberg table)            │  │
│  │  ││Manager       ││  │      └─────────────────────────────┘  │
│  │  │├──────────────┤│  │                                       │
│  │  ││ChangeStream  ││  │      ┌─────────────────────────────┐  │
│  │  ││SyncManager   ││  │      │   IcebergWriteManager        │  │
│  │  │├──────────────┤│  │      │   - RecordBuffer (batching)  │  │
│  │  ││Checkpoint    ││  │      │   - Parquet DataWriter       │  │
│  │  ││Manager       ││  │      │   - AppendFiles commits      │  │
│  │  │└──────────────┘│  │      │   - Equality delete files    │  │
│  │  └────────────────┘  │      └─────────────────────────────┘  │
│  └──────────────────────┘                                       │
└─────────────────────────────────────────────────────────────────┘
         │                                    │
         ▼                                    ▼
   ┌───────────┐                     ┌──────────────┐
   │  MongoDB   │                     │ Iceberg      │
   │  Cluster   │                     │ Catalog      │
   │            │                     │ (Local/S3    │
   │            │                     │  Tables/REST)│
   └───────────┘                     └──────────────┘
```

## 2. Core Components

### 2.1 SyncDaemon (Entry Point)

Main class. Parses configuration, initializes the Iceberg catalog and MongoDB client, creates the `SyncManager`, and registers a shutdown hook.

Uses PicoCLI for command-line argument parsing (config file path, optional overrides).

### 2.2 SyncManager

Top-level orchestrator. Creates and manages one `CollectionSyncManager` per configured collection-to-table mapping.

Responsibilities:
- Create and manage `CollectionSyncManager` instances
- Manage shared thread pools (lifecycle, scan, write, scheduling)
- Coordinate startup and shutdown ordering
- Health/status reporting

### 2.3 CollectionSyncManager

Manages the full sync lifecycle for a single MongoDB collection → Iceberg table mapping. Owns the state machine.

State machine:
```
INITIALIZING → INITIAL_SYNC → STEADY_STATE
                    ↕               ↕
                 BACKOFF          BACKOFF
```

Responsibilities:
- Read checkpoint on startup to determine initial state
- Delegate to `InitialSyncManager` or `ChangeStreamSyncManager`
- Handle transitions between states
- Manage backoff/retry on failures
- Graceful shutdown: flush buffers, write final checkpoint

### 2.4 InitialSyncManager

Performs the full collection scan phase.

Algorithm:
```
1. Open a change stream to capture resume token T0
2. If resuming: set startAfterId = checkpoint.highWaterMarkId
3. Scan: db.collection.find({_id: {$gt: startAfterId}}).sort({_id: 1}).batchSize(1000)
4. For each batch:
   a. Convert via SchemaMapper → Iceberg GenericRecord
   b. Buffer in IcebergWriteManager
   c. On threshold: flush to Parquet, commit to Iceberg
   d. Update checkpoint: { state: INITIAL_SYNC, highWaterMarkId, resumeToken: T0 }
5. On completion: flush remaining, update state to STEADY_STATE
6. Return T0 for change stream handoff
```

If the target table does not exist, it is created during initial sync:
- **Auto mode**: schema inferred from first batch of documents
- **Explicit mode**: schema built from configuration
- Partition spec applied from configuration

### 2.5 ChangeStreamSyncManager

Processes the MongoDB change stream for incremental updates.

Algorithm:
```
1. Open change stream from resumeToken with fullDocument: "updateLookup"
2. For each event:
   - insert/replace/update → convert fullDocument, buffer as upsert
   - delete → buffer as equality delete (by _id)
   - drop/rename/invalidate → trigger re-initial-sync
3. On batch threshold (records, bytes, or time):
   a. Write equality delete files for updates/deletes
   b. Write new data file for inserts/updates
   c. Commit to Iceberg
   d. Update checkpoint with latest resume token
4. On transient error: exponential backoff, reopen from last checkpointed token
```

Uses `fullDocument: UPDATE_LOOKUP` to always receive the complete document on updates, simplifying the mapping pipeline (always have full document to convert).

### 2.6 IcebergWriteManager

Manages all Iceberg write operations for a single table. This is the most complex component, responsible for solving the small-file problem through batching.

Responsibilities:
- Maintain an in-memory `RecordBuffer`
- Flush buffer to Parquet data files when thresholds are reached
- Commit data files via `AppendFiles` transactions
- Write equality delete files for updates/deletes (Merge-on-Read)
- Coordinate with `CompactionManager` for periodic maintenance

### 2.7 CheckpointManager

Manages checkpoint persistence for a single collection sync.

Responsibilities:
- Read checkpoint on startup
- Write checkpoint after each successful Iceberg commit
- Determine startup action (initial sync vs resume steady state)

### 2.8 SchemaMapper

Converts MongoDB BSON documents to Iceberg `GenericRecord` instances.

Responsibilities:
- Map BSON types to Iceberg types
- Handle nested documents (→ StructType) and arrays (→ ListType)
- Apply field projections and renames from configuration
- Handle schema evolution (detect new fields, add columns)

## 3. Configuration Model

Configuration is YAML-based with environment variable substitution.

### Example: Local Iceberg Files

```yaml
mongodb:
  uri: "mongodb://localhost:27017"
  database: "mydb"

iceberg:
  catalog:
    type: "local"                   # local | s3tables | rest
    warehouse: "/data/iceberg/warehouse"

sync:
  collections:
    - source:
        collection: "orders"
      target:
        namespace: "analytics"
        table: "orders"
      mapping:
        mode: "auto"                # auto | explicit
      partitioning:
        - field: "order_date"
          transform: "month"        # identity | year | month | day | hour | bucket[N]
      batch:
        maxRecords: 50000
        maxBytes: 134217728         # 128 MB
        flushIntervalSeconds: 60
```

### Example: AWS S3 Tables

```yaml
mongodb:
  uri: "mongodb+srv://cluster.example.net"
  database: "mydb"

iceberg:
  catalog:
    type: "s3tables"
    warehouse: "arn:aws:s3tables:us-east-1:123456789012:bucket/my-table-bucket"
  properties:                       # passed through to catalog
    client.region: "us-east-1"

sync:
  collections:
    - source:
        collection: "orders"
      target:
        namespace: "analytics"
        table: "orders"
      mapping:
        mode: "explicit"
        fields:
          - source: "_id"
            target: "id"
            type: "string"
          - source: "orderDate"
            target: "order_date"
            type: "timestamp"
          - source: "customer.name"
            target: "customer_name"
            type: "string"
          - source: "items"
            target: "items"
            type: "list"
          - source: "total"
            target: "total"
            type: "decimal"
      partitioning:
        - field: "order_date"
          transform: "month"
      batch:
        maxRecords: 50000
        maxBytes: 134217728
        flushIntervalSeconds: 60

    - source:
        collection: "products"
      target:
        namespace: "analytics"
        table: "products"
      mapping:
        mode: "auto"
```

### Example: REST Catalog

```yaml
mongodb:
  uri: "mongodb+srv://cluster.example.net"
  database: "mydb"

iceberg:
  catalog:
    type: "rest"
    uri: "http://localhost:8181"
    warehouse: "s3://my-bucket/warehouse"
  properties:
    s3.access-key-id: "${AWS_ACCESS_KEY_ID}"
    s3.secret-access-key: "${AWS_SECRET_ACCESS_KEY}"

sync:
  collections:
    - source:
        collection: "orders"
      target:
        namespace: "analytics"
        table: "orders"
      mapping:
        mode: "auto"
```

### Configuration Class Hierarchy

```
SyncConfig
  ├── MongoConfig (uri, database)
  ├── IcebergConfig
  │     ├── CatalogConfig (type, uri, warehouse, properties)
  │     └── defaults (file format, compression)
  └── CollectionSyncConfig[]
        ├── SourceConfig (collection name)
        ├── TargetConfig (namespace, table name)
        ├── MappingConfig (mode, field mappings[])
        ├── PartitionConfig (field, transform)[]
        └── BatchConfig (maxRecords, maxBytes, flushIntervalSeconds)
```

## 4. Handling Updates and Deletes in Iceberg

Iceberg supports three approaches for row-level changes. We use **Merge-on-Read (MoR)** with equality deletes:

- For each **update**: write an equality delete file marking the old row by `_id`, then write the new row in the append data file.
- For each **delete**: write an equality delete file marking the row by `_id`.

This avoids rewriting entire data files on every change stream batch. Periodic compaction merges delete files into the base data files.

Why not Copy-on-Write: CoW rewrites entire Parquet files for each batch of changes, which is prohibitively expensive for frequently-updated collections.

## 5. Checkpoint Management

### Storage: Iceberg Table

Checkpoints are stored in a dedicated Iceberg table (`_sync_checkpoints`) in the same catalog as the data tables. This keeps all sync state co-located with the data and requires no external dependencies beyond the Iceberg catalog itself.

Advantages:
- **Co-located**: Checkpoint lives alongside the data in the same catalog — no separate storage system to manage.
- **Queryable**: Can be inspected with any Iceberg-compatible query engine (Spark, Trino, etc.).
- **Portable**: Works identically across local files, S3 Tables, and REST catalogs.
- **No extra dependencies**: No need for a MongoDB checkpoint collection or local state files.

### Checkpoint Table Schema

The `_sync_checkpoints` table is created automatically in a `_sync` namespace:

| Column               | Iceberg Type      | Description                                    |
|----------------------|-------------------|------------------------------------------------|
| `sync_id`            | `STRING`          | Primary key: `"{namespace}.{table}"` (e.g., `"analytics.orders"`) |
| `state`              | `STRING`          | `INITIAL_SYNC` or `STEADY_STATE`               |
| `source_database`    | `STRING`          | MongoDB database name                          |
| `source_collection`  | `STRING`          | MongoDB collection name                        |
| `resume_token`       | `STRING`          | Change stream resume token (JSON-encoded)      |
| `high_water_mark_id` | `STRING`          | Last processed `_id` during initial sync (JSON-encoded) |
| `documents_processed`| `LONG`            | Count of documents processed                   |
| `last_snapshot_id`   | `LONG`            | Iceberg snapshot ID of last data commit        |
| `updated_at`         | `TIMESTAMP`       | Last checkpoint update time (UTC)              |

### Checkpoint Update Mechanism

Checkpoints are updated using Iceberg's row-level operations:
1. Write an equality delete file for the existing checkpoint row (by `sync_id`).
2. Write a new data file with the updated checkpoint row.
3. Commit both in a single Iceberg transaction.

This effectively performs an upsert — the old checkpoint row is deleted and the new one is appended atomically.

### Commit Ordering (At-Least-Once Semantics)

```
1. Flush data records to Parquet data files (local/S3)
2. Commit data files to the target Iceberg table (AppendFiles.commit())
3. Record the target table's new snapshot ID
4. Write updated checkpoint row to _sync_checkpoints table (with resume token + snapshot ID)
```

If the process crashes between steps 2 and 4, the data was committed but the checkpoint was not advanced. On restart, some events will be re-processed (at-least-once). Downstream queries can deduplicate on `_id`.

### Restart Decision Logic

On startup, `CheckpointManager` reads the `_sync_checkpoints` table for each configured collection:

| Condition                              | Action                |
|----------------------------------------|-----------------------|
| No row exists for this sync_id         | `RUN_INITIAL_SYNC`    |
| Row has `state = INITIAL_SYNC`         | `RESUME_INITIAL_SYNC` |
| Row has `state = STEADY_STATE`         | `RESUME_STEADY_STATE` |

## 6. Batching Strategy

### The Small-File Problem

Iceberg tracks every data file in metadata. Too many small files causes slow query planning, inefficient I/O, and metadata bloat.

### Three-Threshold Batching

A flush is triggered when any threshold is reached:

| Parameter              | Default   | Description                           |
|------------------------|-----------|---------------------------------------|
| `maxRecords`           | 50,000    | Records buffered before flush         |
| `maxBytes`             | 128 MB    | Estimated data file size target       |
| `flushIntervalSeconds` | 60        | Maximum seconds between flushes       |

### Write Path

```
Document arrives (from scan or change stream)
  → SchemaMapper.convert(bsonDoc) → GenericRecord
    → RecordBuffer.add(record)
      ├── buffer.size >= maxRecords → flush()
      ├── buffer.estimatedBytes >= maxBytes → flush()
      └── timer fires → flush()

flush():
  1. Snapshot buffer, reset for new writes
  2. Write buffered records to Parquet via DataWriter
  3. Close writer → DataFile
  4. table.newAppend().appendFile(dataFile).commit()
  5. Update checkpoint
```

### Compaction

Periodic compaction is essential for long-running syncs:

- **RewriteDataFilesAction**: Merges small files into larger ones (target 256 MB). Run on cold partitions that are no longer actively written to.
- **ExpireSnapshots**: Cleans up old metadata and orphaned data files.
- Scheduled on a timer (e.g., hourly) or after N commits.

## 7. Schema Mapping (BSON → Iceberg)

### Type Mapping

| BSON Type       | Iceberg Type            | Notes                                 |
|-----------------|-------------------------|---------------------------------------|
| ObjectId        | `Types.StringType`      | 24-char hex string                    |
| String          | `Types.StringType`      |                                       |
| Int32           | `Types.IntegerType`     |                                       |
| Int64           | `Types.LongType`        |                                       |
| Double          | `Types.DoubleType`      |                                       |
| Decimal128      | `Types.DecimalType`     | Precision and scale preserved         |
| Boolean         | `Types.BooleanType`     |                                       |
| DateTime        | `Types.TimestampType`   | With timezone, stored as UTC micros   |
| Binary          | `Types.BinaryType`      |                                       |
| Null            | (field is optional)     | All fields default to optional        |
| Array           | `Types.ListType`        | Element type inferred from values     |
| Document        | `Types.StructType`      | Recursively mapped                    |
| UUID            | `Types.UUIDType`        |                                       |
| Regex           | `Types.StringType`      | Pattern stored as string              |
| MinKey/MaxKey   | Skipped                 | Not meaningful for analytics          |

### Auto Mode Schema Inference

1. Sample the first N documents (default: 1000) from the collection.
2. Build a union schema across all sampled documents.
3. All fields marked optional (MongoDB is schemaless).
4. Nested documents → `StructType`. Arrays → `ListType`.
5. Conflicting types for the same field path → promote to `StringType` with JSON serialization.

### Schema Evolution

When a new field is encountered during sync that does not exist in the Iceberg table:

- **Auto mode**: Add the column via `table.updateSchema().addColumn(...)`.commit(). This is metadata-only in Iceberg.
- **Explicit mode**: Log a warning and skip the field.

## 8. Error Handling and Retry

### Transient Errors

| Error Type                    | Action                                         |
|-------------------------------|-------------------------------------------------|
| MongoDB network error         | Exponential backoff, retry from checkpoint      |
| Change stream cursor killed   | Reopen from last checkpointed resume token      |
| Iceberg commit conflict       | Retry commit (optimistic concurrency)           |
| S3/storage I/O error          | Retry with backoff                              |
| Resume token expired          | Fall back to full initial sync                  |

### Backoff Strategy

```
delay = min(baseDelay × 2^attempt, maxDelay)
baseDelay = 1 second
maxDelay  = 60 seconds
```

The daemon retries indefinitely for transient errors (it should keep running).

### Fatal Errors

| Error Type                     | Action                                     |
|--------------------------------|--------------------------------------------|
| Invalid configuration          | Fail fast on startup                       |
| Authentication failure         | Log error, stop sync for that collection   |
| Irreconcilable schema conflict | Log error, require manual intervention     |

### Metrics (via Micrometer)

- `sync.initial.documents.processed` — counter per collection
- `sync.changestream.events.processed` — counter per collection
- `sync.iceberg.commits` — counter
- `sync.iceberg.commit.latency` — timer
- `sync.errors` — counter, tagged by error type
- `sync.state` — gauge per collection (current state ordinal)

## 9. Thread Model

```
Main Thread (SyncDaemon)
  └── SyncManager
        ├── sync-lifecycle-pool (Fixed, size = numCollections, max 8)
        │     ├── CollectionSyncManager-orders
        │     ├── CollectionSyncManager-products
        │     └── ...
        │
        ├── scan-pool (Fixed, size = min(numCollections, 4))
        │     └── Initial sync collection scans
        │
        ├── iceberg-write-pool (Fixed, size = numCollections)
        │     └── Buffer flush + Iceberg commits (one per table)
        │
        ├── flush-scheduler (Scheduled, size = 1)
        │     └── Periodic flush timer for each collection
        │
        └── compaction-scheduler (Scheduled, size = 1)
              └── Periodic compaction jobs
```

### Thread Safety Notes

- `RecordBuffer`: Thread-safe (concurrent add from scan/changestream, flush from write thread).
- `CheckpointManager`: Uses Iceberg row-level overwrite on the `_sync_checkpoints` table.
- `IcebergWriteManager`: Serializes commits (one at a time per table).

## 10. Technology Choices

| Choice                | Decision                    | Rationale                                |
|-----------------------|-----------------------------|------------------------------------------|
| Build system          | Maven                       | Widely adopted, stable dependency management |
| Java version          | 25                          | Latest Java with modern language features |
| MongoDB driver        | mongodb-driver-sync 5.x    | Latest stable sync driver                |
| Iceberg               | 1.7+                       | Latest stable with MoR V2 support        |
| File format           | Parquet                     | Best Iceberg ecosystem support           |
| CLI framework         | PicoCLI                     | Lightweight, annotation-based            |
| Config parsing        | Jackson (YAML)              | Standard, supports env var substitution  |
| Logging               | SLF4J + Logback             | Standard Java logging stack              |
| Metrics               | Micrometer                  | Vendor-neutral metrics facade            |
| Testing               | JUnit 5 + Testcontainers    | Integration tests with real MongoDB/S3   |

### Catalog Support

| Catalog Type | Implementation Class                                    | Use Case                        |
|-------------|---------------------------------------------------------|---------------------------------|
| `local`     | `org.apache.iceberg.hadoop.HadoopCatalog`               | Local development and testing   |
| `s3tables`  | `software.amazon.s3tables.iceberg.S3TablesCatalog`      | AWS S3 Tables (managed Iceberg) |
| `rest`      | `org.apache.iceberg.rest.RESTCatalog`                   | Any REST-compatible catalog     |

### Key Dependencies (Maven)

| Dependency                                               | Purpose                              |
|----------------------------------------------------------|--------------------------------------|
| `org.apache.iceberg:iceberg-core`                        | Iceberg table operations             |
| `org.apache.iceberg:iceberg-parquet`                     | Parquet file read/write              |
| `org.apache.iceberg:iceberg-aws`                         | AWS integrations (S3 FileIO)         |
| `software.amazon.s3tables:s3-tables-catalog-for-iceberg` | AWS S3 Tables catalog                |
| `software.amazon.awssdk:s3tables`                        | AWS S3 Tables SDK                    |
| `org.mongodb:mongodb-driver-sync`                        | MongoDB Java driver                  |
| `org.apache.hadoop:hadoop-common`                        | Local filesystem catalog (HadoopCatalog) |
| `info.picocli:picocli`                                   | CLI argument parsing                 |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | YAML configuration parsing         |
| `org.slf4j:slf4j-api` + `ch.qos.logback:logback-classic` | Logging                             |
| `io.micrometer:micrometer-core`                          | Metrics                              |
| `org.junit.jupiter:junit-jupiter`                        | Testing                              |
| `org.testcontainers:mongodb` + `org.testcontainers:localstack` | Integration testing             |

## 11. Project Structure

```
mongodb-iceberg-sync/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/luketn/mongodb/iceberg/sync/
│   │   │   ├── SyncDaemon.java                    # Entry point (PicoCLI)
│   │   │   ├── SyncManager.java                   # Top-level orchestrator
│   │   │   ├── config/
│   │   │   │   ├── SyncConfig.java
│   │   │   │   ├── MongoConfig.java
│   │   │   │   ├── IcebergConfig.java
│   │   │   │   ├── CollectionSyncConfig.java
│   │   │   │   └── MappingConfig.java
│   │   │   ├── catalog/
│   │   │   │   └── CatalogFactory.java            # Creates Local/S3Tables/REST catalog
│   │   │   ├── sync/
│   │   │   │   ├── CollectionSyncManager.java      # Per-collection state machine
│   │   │   │   ├── InitialSyncManager.java         # Full collection scan
│   │   │   │   ├── ChangeStreamSyncManager.java    # Incremental sync
│   │   │   │   └── SyncState.java                  # State enum
│   │   │   ├── iceberg/
│   │   │   │   ├── IcebergWriteManager.java        # Write + commit logic
│   │   │   │   ├── RecordBuffer.java               # Thread-safe batch buffer
│   │   │   │   ├── IcebergTableManager.java        # Table create/evolve
│   │   │   │   └── CompactionManager.java          # Periodic maintenance
│   │   │   ├── mapping/
│   │   │   │   ├── SchemaMapper.java               # BSON → GenericRecord
│   │   │   │   ├── BsonToIcebergConverter.java     # Type conversion
│   │   │   │   ├── SchemaInferrer.java             # Auto mode inference
│   │   │   │   └── TypeMapping.java                # Static type map
│   │   │   └── checkpoint/
│   │   │       ├── CheckpointManager.java          # Read/write/decide
│   │   │       ├── CheckpointStore.java            # Interface
│   │   │       ├── IcebergCheckpointStore.java     # Iceberg table implementation
│   │   │       └── CheckpointRecord.java           # Data model
│   │   └── resources/
│   │       ├── logback.xml
│   │       └── reference-config.yaml
│   └── test/
│       └── java/com/luketn/mongodb/iceberg/sync/
│           ├── mapping/
│           │   ├── SchemaMapperTest.java
│           │   └── BsonToIcebergConverterTest.java
│           ├── catalog/
│           │   └── CatalogFactoryTest.java
│           ├── checkpoint/
│           │   └── IcebergCheckpointStoreTest.java
│           ├── sync/
│           │   ├── InitialSyncManagerTest.java
│           │   └── ChangeStreamSyncManagerTest.java
│           └── integration/
│               └── EndToEndSyncTest.java
├── docs/
│   ├── mongot-research.md
│   ├── design.md
│   └── plan.md
└── README.md
```
