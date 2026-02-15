# mongodb-iceberg-sync: Project Plan

## Overall Progress

- [x] Phase 1: Project Setup
- [x] Phase 2: Configuration
- [ ] Phase 3: Schema Mapping
- [ ] Phase 4: Iceberg Write Layer
- [ ] Phase 5: Checkpoint Management
- [ ] Phase 6: Initial Sync
- [ ] Phase 7: Change Stream Processing
- [ ] Phase 8: Sync Orchestration
- [ ] Phase 9: Testing and Hardening
- [ ] Phase 10: Documentation and Release

---

## Phase 1: Project Setup

- [x] Initialize Maven project
  - [x] Create `pom.xml` with Java 25 compiler settings (maven-compiler-plugin)
  - [x] Configure maven-shade-plugin or maven-assembly-plugin for fat JAR
- [x] Add core dependencies
  - [x] Apache Iceberg (iceberg-core, iceberg-parquet, iceberg-aws)
  - [x] AWS S3 Tables (s3-tables-catalog-for-iceberg, software.amazon.awssdk:s3tables)
  - [x] MongoDB Java Driver (mongodb-driver-sync 5.x)
  - [x] Parquet (parquet-avro)
  - [x] Hadoop common (for local HadoopCatalog)
  - [x] PicoCLI for CLI parsing
  - [x] Jackson for YAML/JSON configuration
  - [x] SLF4J + Logback for logging
  - [x] Micrometer for metrics
- [x] Add test dependencies
  - [x] JUnit 5
  - [x] Testcontainers (mongodb, localstack)
  - [x] AssertJ
- [x] Create base package structure (`com.luketn.mongodb.iceberg.sync`)
- [x] Create `SyncDaemon.java` main class (skeleton with PicoCLI)
- [x] Create `logback.xml` with sensible defaults
- [x] Create `reference-config.yaml` with documented examples (local, S3 Tables, REST)
- [x] Verify project compiles and runs an empty main method

## Phase 2: Configuration

- [x] Define configuration data model
  - [x] `SyncConfig` (top-level)
  - [x] `MongoConfig` (URI, database)
  - [x] `IcebergConfig` (catalog type, URI, warehouse, properties)
  - [x] `CollectionSyncConfig` (source, target, mapping, partitioning, batch)
  - [x] `MappingConfig` (mode, field list)
  - [x] `FieldMapping` (source path, target name, type override)
  - [x] `PartitionConfig` (field, transform)
  - [x] `BatchConfig` (maxRecords, maxBytes, flushIntervalSeconds)
- [x] Implement YAML configuration loader using Jackson
  - [x] Environment variable substitution in config values
  - [x] Validation of required fields
  - [x] Sensible defaults for optional fields
- [x] Write unit tests for configuration parsing
  - [x] Valid config loads correctly
  - [x] Missing required fields produce clear errors
  - [x] Defaults applied for optional fields
  - [x] Environment variable substitution works

## Phase 3: Schema Mapping

- [ ] Implement `TypeMapping` (BSON type → Iceberg type static mappings)
- [ ] Implement `BsonToIcebergConverter`
  - [ ] ObjectId → String
  - [ ] Nested documents → StructType (recursive)
  - [ ] Arrays → ListType
  - [ ] Decimal128 → DecimalType with precision
  - [ ] DateTime → TimestampType (UTC micros)
  - [ ] Null and missing fields (optional semantics)
  - [ ] All other BSON types (Boolean, Int32, Int64, Double, Binary, UUID, Regex)
- [ ] Implement `SchemaInferrer` (auto mode)
  - [ ] Sample N documents from collection
  - [ ] Build union schema from all samples
  - [ ] Resolve type conflicts (promote to String)
  - [ ] Generate Iceberg Schema with all fields optional
- [ ] Implement `SchemaMapper`
  - [ ] Explicit mode: build schema from field mappings config
  - [ ] Auto mode: delegate to SchemaInferrer
  - [ ] Convert BSON Document → Iceberg GenericRecord
  - [ ] Handle field renaming (source path → target name)
  - [ ] Handle missing fields (set to null)
- [ ] Write unit tests
  - [ ] Each BSON type converts correctly
  - [ ] Nested documents map recursively
  - [ ] Arrays map to ListType
  - [ ] Missing fields become null
  - [ ] Type conflicts resolve to String
  - [ ] Field renaming works

## Phase 4: Iceberg Write Layer

- [ ] Implement `CatalogFactory`
  - [ ] Create `HadoopCatalog` for `local` type (local filesystem warehouse)
  - [ ] Create `S3TablesCatalog` for `s3tables` type (ARN-based warehouse)
  - [ ] Create `RESTCatalog` for `rest` type (URI-based)
  - [ ] Pass through extra properties from config
  - [ ] Write unit tests for catalog factory (verify correct catalog type created)
- [ ] Implement `IcebergTableManager`
  - [ ] Create table from Schema + PartitionSpec
  - [ ] Load existing table
  - [ ] Schema evolution (add new columns)
  - [ ] Set table properties (file size, format version)
- [ ] Implement `RecordBuffer`
  - [ ] Thread-safe record accumulation
  - [ ] Track estimated byte size
  - [ ] Snapshot-and-reset for flush
  - [ ] Track record count
- [ ] Implement `IcebergWriteManager`
  - [ ] Parquet DataWriter initialization
  - [ ] Flush buffer → write Parquet → DataFile
  - [ ] Commit via AppendFiles transaction
  - [ ] Equality delete files for updates/deletes
  - [ ] Periodic flush via timer
- [ ] Implement `CompactionManager`
  - [ ] Schedule periodic RewriteDataFilesAction
  - [ ] Filter to cold partitions only
  - [ ] Schedule ExpireSnapshots
  - [ ] Configurable interval and target file size
- [ ] Write unit tests
  - [ ] RecordBuffer accumulation and snapshot
  - [ ] Flush triggers at record count, byte size, and timer thresholds
- [ ] Write integration tests
  - [ ] Local catalog (HadoopCatalog in temp dir): create table, write, read, verify
  - [ ] S3 Tables catalog (Testcontainers LocalStack): create table, write, read, verify
  - [ ] Schema evolution adds column correctly
  - [ ] Compaction reduces file count

## Phase 5: Checkpoint Management

- [ ] Define `CheckpointRecord` data model
  - [ ] Sync state enum (INITIAL_SYNC, STEADY_STATE)
  - [ ] Change stream resume token (JSON-encoded)
  - [ ] Initial sync high-water-mark ID (JSON-encoded)
  - [ ] Last Iceberg snapshot ID
  - [ ] Timestamps and counters
- [ ] Define `CheckpointStore` interface
  - [ ] `read(syncId)` → CheckpointRecord
  - [ ] `write(syncId, CheckpointRecord)`
  - [ ] `delete(syncId)`
- [ ] Implement `IcebergCheckpointStore`
  - [ ] Create `_sync_checkpoints` table in `_sync` namespace if not exists
  - [ ] Read checkpoint by scanning for sync_id
  - [ ] Write checkpoint via equality delete (old row) + append (new row)
  - [ ] Atomic commit of checkpoint updates
- [ ] Implement `CheckpointManager`
  - [ ] Read checkpoint on startup, determine action
  - [ ] Update checkpoint after each Iceberg data commit
- [ ] Write unit tests
  - [ ] Checkpoint round-trip (write then read)
  - [ ] Missing checkpoint returns null
  - [ ] Resume token serialization/deserialization
  - [ ] State determination logic (RUN_INITIAL_SYNC / RESUME / STEADY_STATE)
- [ ] Write integration test (local HadoopCatalog)
  - [ ] Checkpoint persistence across simulated restarts
  - [ ] Checkpoint table created automatically

## Phase 6: Initial Sync

- [ ] Implement `InitialSyncManager`
  - [ ] Open change stream before scan (capture resume token)
  - [ ] Scan collection in `_id` order via find().sort({_id: 1})
  - [ ] Resume from high-water-mark if checkpoint exists
  - [ ] Batch documents through SchemaMapper to IcebergWriteManager
  - [ ] Periodic checkpoint updates
  - [ ] On completion: flush, update state to STEADY_STATE, return resume token
- [ ] Handle table creation
  - [ ] Auto mode: infer schema from first batch
  - [ ] Explicit mode: build from config
  - [ ] Apply partition spec
- [ ] Handle schema evolution during scan (auto mode)
  - [ ] Detect new fields in later batches
  - [ ] Add columns via Iceberg evolution API
- [ ] Write unit tests
  - [ ] Full scan produces correct records
  - [ ] Resume skips already-processed documents
  - [ ] Checkpoint updated at correct intervals
- [ ] Write integration test
  - [ ] Insert docs into MongoDB, run initial sync, verify in Iceberg
  - [ ] Simulate interruption and resume

## Phase 7: Change Stream Processing

- [ ] Implement `ChangeStreamSyncManager`
  - [ ] Open change stream with resumeAfter + fullDocument: UPDATE_LOOKUP
  - [ ] Handle insert events (convert + buffer append)
  - [ ] Handle update/replace events (equality delete + append)
  - [ ] Handle delete events (equality delete)
  - [ ] Handle drop/rename/invalidate (transition to INITIAL_SYNC)
  - [ ] Flush on batch threshold
  - [ ] Update checkpoint after each committed batch
- [ ] Handle resume token expiration
  - [ ] Detect ChangeStreamInvalidateException / oplog gap
  - [ ] Transition to INITIAL_SYNC
- [ ] Implement error handling and retry
  - [ ] Exponential backoff on transient errors
  - [ ] Reopen stream from last checkpointed token
- [ ] Write unit tests
  - [ ] Insert/update/delete events produce correct operations
  - [ ] Batch flush triggered correctly
  - [ ] Resume token updated after commit
- [ ] Write integration tests
  - [ ] CRUD in MongoDB reflected in Iceberg
  - [ ] Crash and resume from checkpoint
  - [ ] No data loss across restart

## Phase 8: Sync Orchestration

- [ ] Implement `CollectionSyncManager`
  - [ ] State machine: INITIALIZING → INITIAL_SYNC → STEADY_STATE
  - [ ] Read checkpoint to determine initial state
  - [ ] Delegate to InitialSyncManager or ChangeStreamSyncManager
  - [ ] Handle state transitions
  - [ ] Backoff and retry on failures
  - [ ] Graceful shutdown (flush, checkpoint)
- [ ] Implement `SyncManager`
  - [ ] Create CollectionSyncManager per configured collection
  - [ ] Manage shared thread pools
  - [ ] Startup: launch all collection syncs
  - [ ] Shutdown: stop all syncs gracefully
- [ ] Wire up `SyncDaemon` entry point
  - [ ] Parse CLI args (config file path)
  - [ ] Load configuration
  - [ ] Initialize MongoDB client + Iceberg catalog
  - [ ] Create and start SyncManager
  - [ ] Register shutdown hook
- [ ] Write integration test for full lifecycle
  - [ ] Start, initial sync, change stream sync, stop, restart, verify resume

## Phase 9: Testing and Hardening

- [ ] End-to-end integration test suite
  - [ ] Multiple collections synced simultaneously
  - [ ] Large collection initial sync (performance baseline)
  - [ ] High-throughput change stream
  - [ ] Schema evolution during sync
  - [ ] Crash recovery
  - [ ] Resume token expiration fallback
- [ ] Performance testing
  - [ ] Initial sync throughput (docs/sec)
  - [ ] Change stream throughput (events/sec)
  - [ ] Iceberg commit latency
  - [ ] Memory profiling under load
  - [ ] Tune batch sizes and thread pool sizes
- [ ] Error condition testing
  - [ ] MongoDB unavailable during sync
  - [ ] Iceberg catalog unavailable during commit
  - [ ] Storage (S3) errors during file write
  - [ ] Malformed BSON documents
  - [ ] Schema conflicts in auto mode

## Phase 10: Documentation and Release

- [ ] Write comprehensive README.md
  - [ ] Project description and motivation
  - [ ] Quick start guide
  - [ ] Configuration reference
  - [ ] Architecture overview with diagram
  - [ ] Operational guide (monitoring, troubleshooting)
- [ ] Document all config options in reference-config.yaml
- [ ] Add Javadoc to public classes and methods
- [ ] Create Docker build
  - [ ] Dockerfile (multi-stage)
  - [ ] docker-compose.yaml for local dev (MongoDB + MinIO + REST catalog)
- [ ] Create GitHub Actions CI pipeline
  - [ ] Build and test on push
  - [ ] Integration tests with Testcontainers
  - [ ] Publish artifact
- [ ] Tag initial release (v0.1.0)
