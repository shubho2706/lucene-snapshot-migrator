# Version Compatibility Matrix

Tracks tested/untested combinations and known limitations.

## Compatibility Rules

- OpenSearch uses Lucene internally and can only read indexes from its own Lucene major version and one prior
- OS 2.x (Lucene 9) reads Lucene 8.x and 9.x
- OS 1.x (Lucene 8) reads Lucene 7.x and 8.x
- The converter itself runs on Lucene 9.8 -- it can read Solr 8 and Solr 9 index files, but not Solr 7
- The converter currently hardcodes version_id from the SDK (OS 2.11.1). To target other OS versions, SDK removal + --os-version flag is needed.

## Test Matrix

| Solr Version | Lucene Version | OS Version | OS Lucene | Compatible | Tested | Status | Notes |
|-------------|---------------|------------|-----------|------------|--------|--------|-------|
| 9.4 | 9.8.0 | 2.11.1 | 9.7.0 | YES | YES | PASS | 2-node: 2K docs, 2 shards, RF=2, all queries verified |
| 9.4 | 9.8.0 | 2.11.1 | 9.7.0 | YES | YES | PASS | 8-node (3m+5d): 10K docs, 4 shards, RF=3, all queries verified |
| 8.11.4 | 8.11.4 | 2.11.1 | 9.7.0 | YES | YES | PASS | 2-node: 2K docs, 2 shards, RF=2, all queries verified. Lucene 9 reads 8.x via backward-codecs |
| 9.x | 9.x | 2.19.1 | 9.10.0 | YES | NO | - | Needs SDK removal + --os-version flag (different version_id) |
| 9.x | 9.x | 2.0 | 9.1.0 | YES | NO | - | Needs SDK removal + --os-version flag |
| 9.x | 9.x | 1.x | 8.x | NO | NO | N/A | OS 1.x cannot read Lucene 9 indexes |
| 8.x | 8.x | 1.x | 8.x | YES | NO | - | Needs SDK removal + --os-version flag + Lucene 8 runtime |
| 7.7 | 7.7.3 | 2.11.1 | 9.7.0 | NO | YES | FAIL | Converter Lucene 9.8 cannot read Lucene 7.x |
| 7.x | 7.x | 1.x | 8.x | MAYBE | NO | - | OS can read it, but converter can't (see workarounds) |
| 6.x | 6.x | any | any | NO | NO | N/A | Lucene 6 too old for any current runtime |

## Version IDs

These go into the snapshot SMILE metadata. OS rejects restore if version_id doesn't match a known version.

The converter currently uses `Version.CURRENT` from the OS 2.11.1 SDK, which writes `2110199`.
After SDK removal, an `--os-version` flag will let users target any OS version.

| OS Version | version_id | Lucene | Source | Verified |
|-----------|------------|--------|--------|----------|
| 2.11.1 | 2110199 | 9.7.0 | SDK `Version.V_2_11_1` | YES (e2e pass on 2-node + 8-node) |
| 2.19.1 | TBD | 9.12.0 | Need cluster test | NO |
| 2.0.0 | TBD | 9.1.0 | Need cluster test | NO |
| 1.3.x | TBD | 8.10.1 | Need cluster test | NO |
| 1.0.0 | TBD | 8.8.2 | Need cluster test | NO |

Note: Testing other OS versions is blocked on SDK removal. The current converter always writes `2110199` (OS 2.11.1). A snapshot with this version_id can only be restored on OS 2.11.1. After SDK removal, we can write any version_id and test the full matrix.

## Known Limitations

| Limitation | Impact | Workaround |
|-----------|--------|------------|
| No _source field | Restored docs have no _source, GET by ID returns empty | Use _reindex from Solr after restore, or reconstruct from stored fields (Phase 4) |
| Text fields mapped as `text` not `keyword` | Term queries return 0, text field aggregations fail | Use `match` queries instead of `term`; aggregations work on numeric fields; post-restore reindex with correct mappings |
| Solr 7 indexes unreadable by converter | Converter runs Lucene 9.8 which can only read N-1 major | See Solr 7 workarounds below |
| Solr 9 incremental backups | Different format, not supported | Use `incremental=false` flag when taking backup |
| Hardcoded OS version | Only targets OS 2.11.1 | SDK removal + --os-version flag (planned) |
| Single converter Lucene runtime | Can't support all Solr versions in one JAR | Future: multi-profile build or raw file copy mode |

## Solr 7 Workarounds

The converter uses Lucene 9.8 at runtime to read SegmentInfos and rewrite commitUserData. Lucene 9 only supports reading N-1 major version (Lucene 8), so Lucene 7 indexes fail.

**Option A: Upgrade Solr first**
1. Upgrade Solr 7 -> Solr 8 (or 9) in place
2. Solr will upgrade Lucene indexes on startup
3. Take backup from upgraded Solr
4. Run converter normally

**Option B: Target OS 1.x**
1. OS 1.x uses Lucene 8 which CAN read Lucene 7
2. But the converter still can't read the files to rewrite commitUserData
3. Requires a separate converter build with Lucene 8 runtime (future work)

**Option C: Lucene index upgrader**
1. Use Lucene's IndexUpgrader tool to upgrade 7.x indexes to 8.x offline
2. Then run converter against upgraded indexes
3. `java -cp lucene-backward-codecs-8.x.jar:lucene-core-8.x.jar org.apache.lucene.index.IndexUpgrader <index-path>`

**Option D: Raw file copy (future)**
1. Skip SegmentInfos reading entirely
2. Copy all files from Solr backup as-is
3. Build metadata from file listing + checksums only
4. Let OS handle the index reading at restore time
5. Simplest but loses doc count reporting and commitUserData rewrite

## Test Plan

Priority order:

1. ~~Solr 9.4 -> OS 2.11.1 (2 shards, RF=2, 2000 docs, 2-node cluster)~~ DONE
2. ~~Solr 9.4 -> OS 2.11.1 (4 shards, RF=3, 10K docs, 8-node cluster)~~ DONE
3. ~~Solr 8.11.4 -> OS 2.11.1 (2 shards, RF=2, 2000 docs, 2-node cluster)~~ DONE
4. SDK removal (replace OS dependency with direct SMILE writing, add --os-version flag)
5. Solr 9 -> OS 2.19.1 (requires --os-version flag from step 4)
6. Solr 9 -> OS 2.0 (requires --os-version flag from step 4)
7. Solr 8 -> OS 1.3 (requires --os-version flag + Lucene 8 backward-codecs)
8. Solr 7 -> OS 1.3 via workaround (if feasible)
9. Scale test (100K+ docs, stress test)
10. Edge cases: empty shards, single shard, special characters in field names
