# Mongot Research: How MongoDB Synchronizes to Lucene

## Overview

Mongot is the MongoDB daemon responsible for maintaining Atlas Search and Vector Search indexes. It runs as a sidecar process alongside `mongod`, consuming the MongoDB change stream and maintaining Lucene-based indexes that reflect the current state of source collections. This document describes the architecture and mechanisms mongot uses to achieve reliable, resumable synchronization.

## 1. Overall Architecture

### Entry Point

The daemon starts via `MongotCommunity`, a PicoCLI-based command-line application bootstrapped by `CommunityMongotBootstrapper`. On startup it initializes shared infrastructure and begins managing replication for each configured index.

### Top-Level Orchestration

`MongoDbReplicationManager` is the top-level orchestrator. It manages a set of `ReplicationIndexManager` instances, one per search/vector index generation. Each `ReplicationIndexManager` owns the full lifecycle for a single index: initial sync, steady-state change stream processing, and checkpoint persistence.

### Shared Resources

Several resources are shared across all index managers:

- **InitialSyncQueue**: A queue that serializes initial sync operations so that only a bounded number run concurrently, preventing overload on the source cluster.
- **SteadyStateManager / ChangeStreamManager**: Coordinates change stream consumption across indexes that share the same source namespace.
- **DecodingWorkScheduler**: Shared thread pool for decoding change stream events (CPU-bound).
- **IndexingWorkSchedulerFactory**: Creates per-index indexing thread pools so a slow index does not starve others.

### High-Level Component Diagram

```
MongotCommunity (main entry point)
  └── CommunityMongotBootstrapper
        └── MongoDbReplicationManager
              ├── ReplicationIndexManager (index A)
              │     ├── BufferlessInitialSyncManager (bulk load)
              │     ├── SteadyStateIndexManager → ChangeStreamIndexManager (steady state)
              │     ├── DocumentIndexer → BsonDocumentProcessor (mapping pipeline)
              │     └── PeriodicIndexCommitter (checkpointing)
              ├── ReplicationIndexManager (index B)
              │     └── ...
              └── Shared: InitialSyncQueue, ChangeStreamManager, DecodingWorkScheduler
```

## 2. Initial Sync Mechanism

### Purpose

Initial sync performs a full collection scan to build the Lucene index from scratch (or rebuild it after certain failure conditions). It must capture every document in the collection and determine a change stream resume point so that no documents are missed during the handoff to steady-state.

### Orchestration

`BufferlessInitialSyncManager` orchestrates the initial sync. The term "bufferless" refers to the fact that documents are indexed directly during the scan rather than being buffered into an intermediate store.

### Scan Modes

Two scanner implementations exist behind the `CollectionScanMongoClient` interface:

1. **BufferlessIdOrderCollectionScanner**: Scans documents ordered by `_id`. This is the default mode. It uses either `FindCommandCollectionScanMongoClient` or `AggregateCommandCollectionScanMongoClient` depending on the query requirements. The `_id`-ordered scan allows resumption from a high-water-mark `_id` if the sync is interrupted.

2. **AutoEmbeddingSortedIdCollectionScanner**: A specialized scanner for vector search indexes that need embeddings generated during the scan.

### Resume After Interruption

If initial sync is interrupted, the checkpoint (`IndexCommitUserData`) stores an `InitialSyncResumeInfo` (specifically `BufferlessIdOrderInitialSyncResumeInfo` or `BufferlessNaturalOrderInitialSyncResumeInfo`) containing:

- The high-water-mark `_id` (last successfully processed document ID)
- A change stream resume token captured at the start of the sync (as a `BsonTimestamp` high water mark)
- The sync source host

On restart, the scanner resumes from the high-water-mark `_id`, avoiding a full rescan.

### Handoff to Steady State

When the collection scan completes, the initial sync returns a `ChangeStreamResumeInfo` object. This contains the resume token that was captured at or before the start of the initial sync. The steady-state change stream opens from this token, ensuring that any writes that occurred during the initial sync are replayed. Deduplication is handled by Lucene's document-update semantics (a new document with the same `_id` replaces the old one).

## 3. Change Stream Processing (Steady State)

### Architecture

`ChangeStreamManager` is the singleton that manages change stream tailing. It creates and manages `ChangeStreamIndexManager` instances. Each index manager processes change events relevant to its index definition.

### Client Types

Multiple change stream client implementations handle different scenarios:

- **DefaultChangeStreamMongoClient**: Standard change stream consumption via the MongoDB driver.
- **SplitEventChangeStreamClient**: Handles split change events (used when events exceed the 16MB BSON document limit).
- **ModeAwareChangeStreamClient**: Switches between field-filtering modes based on a heuristic selector.
- **ChangeStreamMongoCursorClient**: Manages the underlying change stream cursor lifecycle.

### Field Filtering Modes

To reduce bandwidth and processing cost, the change stream operates in one of two modes selected by `HeuristicChangeStreamModeSelector`:

- **ALL_FIELDS** (`ChangeStreamMode.ALL_FIELDS`): Receives the full document on every change. Simple but higher bandwidth.
- **INDEXED_FIELDS** (`ChangeStreamMode.INDEXED_FIELDS`): Uses a change stream projection to receive only the fields that are part of the index definition. Selected when the index covers a small subset of the document's fields.

### Event Dispatching and Batching

- `ChangeStreamDispatcher` routes individual change events to the appropriate index manager(s).
- `ChangeStreamBatch` accumulates events into batches before they are applied to Lucene, amortizing the cost of Lucene commits.
- `ChangeStreamDocumentUtils` parses the raw change stream documents into typed event structures.

### Event Types Handled

| Event Type     | Action                                                |
|----------------|-------------------------------------------------------|
| `insert`       | Add document to Lucene index                          |
| `update`       | Re-index document (full replacement in Lucene)        |
| `replace`      | Same as update from Lucene's perspective              |
| `delete`       | Remove document from index by `_id`                   |
| `drop`         | Trigger full index rebuild (return to initial sync)   |
| `rename`       | Trigger full index rebuild                            |
| `invalidate`   | Trigger full index rebuild                            |

## 4. Checkpoint and Resume Management

### Storage Mechanism

Mongot stores checkpoint state in **Lucene's commit metadata** via `IndexCommitUserData`. This is a JSON payload persisted atomically with each Lucene commit (segment flush). This co-locates the checkpoint with the indexed data, ensuring they are always consistent — if the Lucene index has the data, it also has the checkpoint that reflects that data.

### Checkpoint Contents

`IndexCommitUserData` contains several optional fields:

| Field                      | Type                              | Description                                        |
|----------------------------|-----------------------------------|----------------------------------------------------|
| `changeStreamResumeInfo`   | `ChangeStreamResumeInfo`          | Resume token (BsonDocument) + namespace for steady state |
| `initialSyncResumeInfo`    | `InitialSyncResumeInfo` (abstract)| High-water-mark, resume token, sync source host    |
| `staleStateInfo`           | `StaleStateInfo`                  | Whether the index is stale and needs rebuild       |
| `indexStateInfo`           | `IndexStateInfo`                  | Current operational state of the index             |
| `exceededLimitsReason`     | `String`                          | Reason if index exceeded size/field limits         |
| `backendIndexVersion`      | `IndexFormatVersion`              | Lucene index format version                        |

### Serialization

`IndexCommitUserData` is serialized to/from JSON using `fromEncodedData()` and `toEncodedData()` methods. The encoded data is stored as a `Map<String, String>` in Lucene's commit user data.

### Periodic Persistence

`PeriodicIndexCommitter` runs on a timer and commits the current Lucene state plus updated checkpoint metadata. This bounds the amount of work that must be replayed after a crash to at most one timer interval's worth of events.

### Restart Decision Logic

On restart, `ReplicationIndexManager` reads `IndexCommitUserData` from the Lucene index commit and determines the `InitAction`:

| Condition                                    | Action                  |
|----------------------------------------------|-------------------------|
| No checkpoint exists (empty index)           | `RUN_INITIAL_SYNC`      |
| Checkpoint has `initialSyncResumeInfo`       | `RESUME_INITIAL_SYNC`   |
| Checkpoint has `changeStreamResumeInfo` only | `RESUME_STEADY_STATE`   |
| Checkpoint indicates stale state             | `RUN_INITIAL_SYNC`      |

## 5. Document Mapping Pipeline (BSON to Lucene)

### Index Definitions

Each index has a definition (`SearchIndexDefinition` or `VectorIndexDefinition`) that declares which fields to index and how. This serves as the schema mapping configuration.

### Field Definition Hierarchy

The mapping uses a hierarchy of field definition types:

- **DocumentFieldDefinition** — base for all document fields
- **TokenFieldDefinition** — text fields with analyzer configuration
- **NumberFieldDefinition** — numeric fields
- **DateFieldDefinition** — date/time fields
- **AutocompleteFieldDefinition** — autocomplete fields
- **EmbeddedDocumentsFieldDefinition** — nested object fields
- **VectorIndexVectorFieldDefinition** — vector embedding fields

### Processing Pipeline

```
Raw BSON Document (bytes)
  └── BsonDocumentProcessor
        ├── Uses BsonBinaryReader to traverse BSON structure field by field
        ├── Consults index definition for each field path
        └── For each indexed field:
              └── DocumentHandler.handleField(fieldName)
                    └── Returns Optional<FieldValueHandler>
                          └── Type-specific handler dispatches by BSON type:
                                ├── String → text analysis (analyzer pipeline)
                                ├── Int/Long/Double → numeric indexing
                                ├── Date → epoch millis as LongPoint
                                ├── GeoJSON → GeometryParser
                                ├── Binary (vector) → KnnVectorParser → KnnFloatVectorField
                                ├── Array → recursive element handling
                                └── Document → recursive traversal (child DocumentHandler)
```

### Key Components

- **BsonDocumentProcessor**: Stateless entry point. Reads raw BSON bytes via `BsonBinaryReader` and walks the document structure.
- **DocumentHandler**: Determines which `FieldValueHandler` to create for each field path based on the index definition. Creates child handlers for nested documents/arrays.
- **FieldValueHandler**: Type-specific logic for converting a BSON value to one or more Lucene fields.
- **LuceneDocumentUtil / StoredDocumentBuilder**: Utilities for constructing the final Lucene `Document` with appropriate field types, stored values, and doc values.

### Type Mapping (BSON to Lucene)

| BSON Type       | Lucene Representation                  |
|-----------------|----------------------------------------|
| String          | TextField / StringField (analyzed/keyword) |
| Int32 / Int64   | LongPoint + NumericDocValuesField      |
| Double          | DoublePoint + DoubleDocValuesField     |
| Boolean         | StringField ("true"/"false")           |
| DateTime        | LongPoint (epoch millis)               |
| ObjectId        | StringField (hex representation)       |
| Array           | Multiple fields (one per element)      |
| Document        | Recursive traversal (flattened paths)  |
| Binary (vector) | KnnFloatVectorField                    |
| GeoJSON         | LatLonShape / XYShape via GeometryParser |

## 6. Thread Model

Mongot uses a structured thread model to balance parallelism with resource control:

| Thread Pool                    | Purpose                                              | Sizing                |
|--------------------------------|------------------------------------------------------|-----------------------|
| `lifecycleExecutor`            | Manages index lifecycles (start, stop, sync phases)  | Fixed, ~numCPUs / 4   |
| `DecodingWorkScheduler`        | Decodes change stream events (shared across indexes) | Shared, bounded       |
| `IndexingWorkSchedulerFactory` | Creates per-index indexing thread pools               | Per-index, bounded    |
| `PeriodicIndexCommitter`       | Scheduled checkpoint commits                         | Single-threaded timer |

### Design Principles

- **Lifecycle operations** are bounded by the lifecycle executor to prevent too many simultaneous initial syncs from overwhelming the source cluster.
- **Decoding** is shared because it is CPU-bound and benefits from a shared pool with controlled concurrency.
- **Indexing** gets per-index pools so that a slow index (e.g., one with expensive vector embeddings) does not starve others.
- **Checkpointing** is periodic on a timer, not triggered per-event, to amortize I/O costs.

## 7. State Machine

Each `ReplicationIndexManager` follows a state machine that governs its lifecycle:

```
INITIALIZING
    │
    ├── (no checkpoint) ──────────────► INITIAL_SYNC
    │                                       │
    │                                       ├── (complete) ───► STEADY_STATE
    │                                       │                       │
    │                                       │ (failure)             │ (failure / invalidate)
    │                                       ▼                       ▼
    │                                   BACKOFF ◄──────────── BACKOFF
    │                                       │                       │
    │                                       └── (retry) ──► INITIAL_SYNC or STEADY_STATE
    │
    └── (valid resume token) ──────► STEADY_STATE
```

### States

| State               | Description                                                |
|----------------------|------------------------------------------------------------|
| `INITIALIZING`      | Reading checkpoint, determining action                     |
| `INITIAL_SYNC`      | Performing full collection scan + indexing                  |
| `INITIAL_SYNC_BACKOFF` | Waiting after transient failure during initial sync     |
| `STEADY_STATE`      | Processing change stream events continuously               |
| `FAILED`            | Terminal state after exhausting retries                     |
| `FAILED_EXCEEDED`   | Terminal state when index exceeds limits                    |
| `SHUT_DOWN`         | Clean shutdown                                             |

### Key Transitions

- **INITIALIZING → INITIAL_SYNC**: No valid checkpoint or stale state detected.
- **INITIALIZING → STEADY_STATE**: Valid change stream resume token found in checkpoint.
- **INITIAL_SYNC → STEADY_STATE**: Collection scan complete, resume token handed off.
- **STEADY_STATE → INITIAL_SYNC**: Invalidate event (drop/rename) or resume token expired.
- **Any → BACKOFF**: Transient error (network failure, cursor killed, etc.).
- **BACKOFF → previous state**: After exponential backoff delay.

## 8. Summary of Key Patterns

Mongot's architecture provides a robust template for any system that needs to synchronize MongoDB data to an external store:

1. **Two-phase sync**: Initial bulk load followed by continuous change stream tailing. The change stream resume token is captured *before* the bulk scan begins, ensuring no gap.

2. **Atomic checkpointing**: Checkpoint state is co-located with the destination store's commit metadata (in this case, Lucene commit user data), ensuring consistency between the indexed data and the resume point.

3. **Resumable from any point**: Both initial sync (via `_id` high-water-mark) and change stream (via resume token) can resume after crashes without data loss.

4. **Batched writes**: Events are accumulated into batches before being committed, amortizing the cost of destination-store commits.

5. **Bounded concurrency**: Thread pools are carefully sized — shared pools for CPU-bound work, per-index pools for I/O-bound work, and a bounded queue for initial syncs.

6. **State machine lifecycle**: Clear state transitions with backoff/retry for transient failures, and fallback to initial sync when steady-state cannot continue (e.g., expired resume token).

7. **Pluggable mapping**: A handler-based pipeline converts BSON documents to destination-store records, with type-specific handlers for each field type. The mapping is driven by an index definition that declares the desired schema.
