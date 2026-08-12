# End-to-End Test

Automated validation: Solr Collection -> Backup -> Converter -> OpenSearch Restore -> Verify

## Files

| File | Purpose |
|------|---------|
| `e2e-test.sh` | Main test script (10 steps) |
| `generate-taxi-data.py` | Deterministic NYC taxi dataset generator |
| `workspace/` | Created at runtime -- data, backups, snapshots |

## Prerequisites

- Solr 9 SolrCloud cluster running
- OpenSearch cluster running
- Python 3, Java 11+
- Converter JAR built: `mvn clean package -DskipTests`
- Docker (if using containerized clusters)

## Quick Start

```bash
./e2e-test.sh                    # default: 2000 docs, 2 shards
./e2e-test.sh --docs 5000        # more docs
./e2e-test.sh --shards 3         # more shards
./e2e-test.sh --cleanup          # remove artifacts after
```

## Steps

| # | Action | What it validates |
|---|--------|-------------------|
| 1 | Generate NYC taxi dataset | Deterministic data gen (seed=42) |
| 2 | Create Solr collection | SolrCloud collection API, shard count |
| 3 | Index data in batches of 500 | Solr /update endpoint |
| 4 | Verify Solr doc count | Exact match against expected |
| 5 | Trigger full backup (incremental=false) | Solr backup API |
| 6 | Copy backup to local workspace | docker cp or filesystem |
| 7 | Run converter JAR | Conversion + output structure check |
| 8 | Copy snapshot to OS container | docker cp + chown |
| 9 | Register repo + restore | OS _snapshot API |
| 10 | Verify OS data | Doc count, queries, aggregations |

## CLI Options

```
--docs N              Document count (default: 2000)
--shards N            Shard count (default: 2)
--seed N              Random seed (default: 42)
--collection NAME     Solr collection name (default: nyc_taxi_e2e)
--index NAME          OS index name (default: restored_nyc_taxi)
--solr-port PORT      Solr port (default: 8983)
--os-port PORT        OS port (default: 9200)
--skip-solr-setup     Skip steps 1-6, reuse existing backup
--skip-to-restore     Skip steps 1-8, just restore + verify
--cleanup             Delete all artifacts after test
--no-docker           Non-Docker clusters
--work-dir PATH       Custom workspace path
```

## Environment Variables

```bash
SOLR_HOST=mysolr OS_PORT=9200 ./e2e-test.sh
```

| Variable | Default | Description |
|----------|---------|-------------|
| SOLR_HOST | localhost | Solr hostname |
| SOLR_PORT | 8983 | Solr port |
| OS_HOST | localhost | OpenSearch hostname |
| OS_PORT | 9200 | OpenSearch port |
| OS_USER / OS_PASS | admin / admin | OpenSearch credentials |
| USE_DOCKER | true | Containerized clusters |
| SOLR_CONTAINER | solr1 | Solr container name |
| OS_CONTAINER | os-node-1 | OS container name |
| SOLR_BACKUP_VOLUME | solrcloud-backup | Solr backup Docker volume |
| OS_SNAPSHOT_VOLUME | os-snapshot-vol | OS snapshot Docker volume |
| CONVERTER_JAR | (auto-detected) | Path to fat JAR |

## Dataset

2000 NYC taxi trip records (~1.2 MB JSON), deterministic via seed.

Fields exercise multiple Lucene field types:

| Field | OS Mapping | Example |
|-------|-----------|---------|
| id | keyword | taxi_00001 |
| vendor_id | text | CMT, VTS, DDS |
| payment_type | text | CSH, CRD, NOC, DIS |
| pickup_datetime | date | 2024-06-15T14:30:00Z |
| passenger_count | long | 3 |
| trip_distance | double | 12.45 |
| fare_amount | double | 33.62 |
| total_amount | double | 40.18 |
| trip_description | text | "Airport transfer from Midtown..." |
| pickup_latitude | double | 40.759908 |
| medallion | text | F031 |

Standalone usage:

```bash
python3 generate-taxi-data.py 2000 42 output.json
python3 generate-taxi-data.py 5000 99              # stdout
```

## Verification Queries (Step 10)

All counts cross-verified against generated JSON and Solr:

| Query | Type | Expected (seed=42, 2000 docs) |
|-------|------|-------------------------------|
| _count | total | 2000 |
| match vendor_id:CMT | text match | 634 |
| range fare_amount >= 20 | numeric range | 1471 |
| range pickup_datetime June 2024 | date range | 166 |
| match payment_type:CRD | text match | 480 |
| range passenger_count >= 4 | numeric range | 991 |
| match trip_description:airport | full-text | 606 |
| avg(fare_amount) | aggregation | numeric |
| sum(total_amount) | aggregation | numeric |
| avg(trip_distance) | aggregation | numeric |

Note: text fields use `match` not `term` queries because Solr's analyzer lowercases/tokenizes them. Aggregations work on numeric fields (double/long) which have doc_values.

## Testing Different OS Versions

```bash
# Reuse Solr backup, target different OS cluster
OS_PORT=9210 OS_CONTAINER=os13-node-1 \
  ./e2e-test.sh --skip-solr-setup --index taxi_os13

# Just re-restore to another cluster
OS_PORT=9220 ./e2e-test.sh --skip-to-restore --index taxi_os20
```

## Troubleshooting

| Error | Fix |
|-------|-----|
| Converter JAR not found | `mvn clean package -DskipTests` |
| Solr not reachable | `docker ps \| grep solr` |
| Snapshot repo registration failed | Check `path.repo` in OS config, check file ownership |
| Doc count mismatch (0) | Check restore completed: `curl OS/_cluster/health/restored_nyc_taxi` |
| Backup permission denied | `docker exec solr1 ls -la /var/solr/data/backup/` |
