# Lucene Snapshot Migrator

Migrate data from **Apache Solr to OpenSearch** without re-indexing — by converting snapshots at the Lucene level.

## The Key Insight

Solr, OpenSearch, and Elasticsearch all store data using **Apache Lucene** segment files (`.fdt`, `.fdx`, `.si`, `.tim`, etc.). These files are identical across engines. The only difference is the **metadata wrapper** each engine uses to manage snapshots.

This converter:
1. Reads Solr's `segments_N` file to discover which Lucene segment files exist
2. Copies those Lucene files **unchanged** (zero-data-movement)
3. Generates the 7 metadata files OpenSearch expects in a snapshot
4. Result: a valid OpenSearch snapshot that can be restored directly

**No re-indexing. No ETL pipeline. No data transformation.**

A 5TB Solr index? The converter only processes ~2KB of metadata. The terabytes of actual data are copied byte-for-byte.

## Quick Start

**Prerequisites:** Docker, Java 17, Maven

```bash
git clone https://github.com/shubho2706/lucene-snapshot-migrator.git
cd lucene-snapshot-migrator
./setup.sh
```

The setup script does everything end-to-end:

| Step | What happens |
|------|-------------|
| 1 | Checks prerequisites (Docker, Java 17, Maven) |
| 2 | Starts Solr 9.4 + OpenSearch 2.11.1 in Docker |
| 3 | Indexes 10 sample documents into Solr |
| 4 | Creates a Solr snapshot |
| 5 | Exports Solr schema |
| 6 | Builds the converter JAR |
| 7 | Converts the Solr snapshot → OpenSearch format |
| 8 | Registers and restores the snapshot in OpenSearch |
| 9 | Verifies all documents are searchable in OpenSearch |

## Manual Usage

If you want to run the converter on your own Solr snapshot:

```bash
# Build
mvn clean package -DskipTests

# Convert
java -jar target/lucene-snapshot-converter-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
  /path/to/solr/snapshot \
  /path/to/output \
  <index-name>

# Then register + restore in OpenSearch
curl -X PUT 'http://localhost:9200/_snapshot/my_repo' \
  -H 'Content-Type: application/json' \
  -d '{"type":"fs","settings":{"location":"/path/to/output"}}'

curl -X POST 'http://localhost:9200/_snapshot/my_repo/<snapshot_name>/_restore'
```

## How It Works

```
Solr Snapshot                    Converter                      OpenSearch Snapshot
┌─────────────────┐     ┌───────────────────────┐     ┌─────────────────────────┐
│ segments_N      │────▶│ Read segment metadata  │     │ index.latest            │
│ _0.si           │     │                        │────▶│ index-0          (JSON) │
│ _0.fdt          │     │ Generate OS metadata:  │     │ snap-{uuid}.dat (Smile) │
│ _0.fdx          │     │  • Repository index    │     │ meta-{uuid}.dat (Smile) │
│ _0.tim          │     │  • Snapshot info        │     │ indices/{uuid}/         │
│ ...             │     │  • Index metadata      │     │   meta-{uuid}.dat       │
│                 │     │  • Shard metadata      │     │   0/                    │
│                 │     │                        │     │     index-{uuid}        │
│                 │     │ Copy Lucene files       │     │     snap-{uuid}.dat    │
│                 │─────│─────── unchanged ───────│────▶│     segments_N          │
│                 │     │                        │     │     _0.si, _0.fdt, ...  │
└─────────────────┘     └───────────────────────┘     └─────────────────────────┘
```

The converter generates **7 metadata files** (~5KB total) and copies the Lucene segment files as-is.

## Project Structure

```
├── setup.sh                          # End-to-end setup & test script
├── pom.xml                           # Maven build (Lucene 9.8, OpenSearch 2.11.1)
└── src/main/java/com/lucene/snapshot/converter/
    ├── SnapshotConverter.java        # CLI entry point
    ├── SnapshotConverterImpl.java    # Core logic: read segments, copy files, rewrite metadata
    ├── SnapshotMetadataWriter.java   # Generates all 7 OpenSearch metadata files
    └── ConversionResult.java         # Conversion statistics
```

## Validated Results

| Metric | Result |
|--------|--------|
| Documents restored | 1000/1000 (100%) |
| Fields preserved | All fields, all types |
| Search latency | 7ms |
| Data processed by converter | ~2KB (metadata only) |

## Requirements

- **Java 17** (not 21 — see [known issues](#known-issues))
- **Maven 3.6+**
- **Docker** (for the setup script)

## Known Issues

**Java 21 incompatibility:** Lucene's `FSDirectory.open()` auto-selects `MMapDirectory` on Java 21, which requires the Panama `MemorySegment` API. The Lucene JAR bundled with OpenSearch 2.11 doesn't include the Panama provider. The converter uses `NIOFSDirectory` as a workaround, but Java 17 is recommended.

## Current Limitations

- **Single index:** Converts one Solr core at a time. Run multiple times for multiple cores.
- **Single shard:** Assumes one shard per index.
- **Solr 9.x:** Tested with Solr 9.4. Older Solr versions may need Lucene segment upgrades.

## License

AGPL-3.0 — see [LICENSE](LICENSE)
