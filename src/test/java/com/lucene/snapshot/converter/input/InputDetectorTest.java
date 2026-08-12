package com.lucene.snapshot.converter.input;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InputDetectorTest {

    private final InputDetector detector = new InputDetector();

    @TempDir
    Path tempDir;

    // ── Standalone detection ──

    @Test
    void detectsStandalone_segmentsInRoot() throws IOException {
        Files.createFile(tempDir.resolve("segments_3"));

        ConversionPlan plan = detector.detect(tempDir);

        assertEquals(InputFormat.STANDALONE, plan.getFormat());
        assertEquals(1, plan.getShardCount());
        assertEquals(tempDir, plan.getShardPaths().get(0));
        assertEquals(-1, plan.getSolrReplicationFactor());
    }

    @Test
    void detectsStandalone_segmentsInIndexSubdir() throws IOException {
        Path indexDir = Files.createDirectory(tempDir.resolve("index"));
        Files.createFile(indexDir.resolve("segments_1"));

        ConversionPlan plan = detector.detect(tempDir);

        assertEquals(InputFormat.STANDALONE, plan.getFormat());
        assertEquals(1, plan.getShardCount());
        assertEquals(indexDir, plan.getShardPaths().get(0));
    }

    // ── SolrCloud detection ──

    @Test
    void detectsSolrCloud_twoShards() throws IOException {
        Path shard1 = Files.createDirectory(tempDir.resolve("snapshot.shard1"));
        Path shard2 = Files.createDirectory(tempDir.resolve("snapshot.shard2"));
        Files.createFile(shard1.resolve("segments_1"));
        Files.createFile(shard2.resolve("segments_1"));

        ConversionPlan plan = detector.detect(tempDir);

        assertEquals(InputFormat.SOLRCLOUD_FULL, plan.getFormat());
        assertEquals(2, plan.getShardCount());
        assertEquals(shard1, plan.getShardPaths().get(0));
        assertEquals(shard2, plan.getShardPaths().get(1));
    }

    @Test
    void detectsSolrCloud_fourShards_orderedCorrectly() throws IOException {
        for (int i = 1; i <= 4; i++) {
            Path shard = Files.createDirectory(tempDir.resolve("snapshot.shard" + i));
            Files.createFile(shard.resolve("segments_1"));
        }

        ConversionPlan plan = detector.detect(tempDir);

        assertEquals(InputFormat.SOLRCLOUD_FULL, plan.getFormat());
        assertEquals(4, plan.getShardCount());
        for (int i = 0; i < 4; i++) {
            assertTrue(plan.getShardPaths().get(i).getFileName().toString()
                    .equals("snapshot.shard" + (i + 1)));
        }
    }

    @Test
    void detectsSolrCloud_shardGap_throws() throws IOException {
        Path shard1 = Files.createDirectory(tempDir.resolve("snapshot.shard1"));
        Path shard3 = Files.createDirectory(tempDir.resolve("snapshot.shard3"));
        Files.createFile(shard1.resolve("segments_1"));
        Files.createFile(shard3.resolve("segments_1"));

        IOException ex = assertThrows(IOException.class, () -> detector.detect(tempDir));
        assertTrue(ex.getMessage().contains("Shard gap"));
    }

    @Test
    void detectsSolrCloud_shardMissingSegments_throws() throws IOException {
        Path shard1 = Files.createDirectory(tempDir.resolve("snapshot.shard1"));
        Path shard2 = Files.createDirectory(tempDir.resolve("snapshot.shard2"));
        Files.createFile(shard1.resolve("segments_1"));
        // shard2 has no segments_N file

        IOException ex = assertThrows(IOException.class, () -> detector.detect(tempDir));
        assertTrue(ex.getMessage().contains("does not contain a segments_N"));
    }

    // ── Incremental rejection ──

    @Test
    void rejectsIncremental_rootLevel() throws IOException {
        Files.createFile(tempDir.resolve("backup_0.properties"));

        IOException ex = assertThrows(IOException.class, () -> detector.detect(tempDir));
        assertTrue(ex.getMessage().contains("Incremental backup detected"));
        assertTrue(ex.getMessage().contains("incremental=false"));
    }

    @Test
    void rejectsIncremental_inChildDir() throws IOException {
        Path child = Files.createDirectory(tempDir.resolve("some-dir"));
        Files.createFile(child.resolve("backup_0.properties"));

        IOException ex = assertThrows(IOException.class, () -> detector.detect(tempDir));
        assertTrue(ex.getMessage().contains("Incremental backup detected"));
    }

    // ── Schema detection ──

    @Test
    void findsSchema_zkBackup() throws IOException {
        Files.createFile(tempDir.resolve("segments_1"));
        Path schemaDir = Files.createDirectories(
                tempDir.resolve("zk_backup/configs/_default"));
        Path schema = Files.createFile(schemaDir.resolve("managed-schema.xml"));

        ConversionPlan plan = detector.detect(tempDir);

        assertNotNull(plan.getSchemaPath());
        assertEquals(schema, plan.getSchemaPath());
    }

    @Test
    void findsSchema_zkBackup0() throws IOException {
        Files.createFile(tempDir.resolve("segments_1"));
        Path schemaDir = Files.createDirectories(
                tempDir.resolve("zk_backup_0/configs/_default"));
        Path schema = Files.createFile(schemaDir.resolve("managed-schema.xml"));

        ConversionPlan plan = detector.detect(tempDir);

        assertNotNull(plan.getSchemaPath());
        assertEquals(schema, plan.getSchemaPath());
    }

    @Test
    void findsSchema_confDir() throws IOException {
        Files.createFile(tempDir.resolve("segments_1"));
        Path confDir = Files.createDirectory(tempDir.resolve("conf"));
        Path schema = Files.createFile(confDir.resolve("managed-schema.xml"));

        ConversionPlan plan = detector.detect(tempDir);

        assertNotNull(plan.getSchemaPath());
        assertEquals(schema, plan.getSchemaPath());
    }

    @Test
    void findsSchema_managedSchemaWithoutXmlExtension() throws IOException {
        Files.createFile(tempDir.resolve("segments_1"));
        Path confDir = Files.createDirectory(tempDir.resolve("conf"));
        Path schema = Files.createFile(confDir.resolve("managed-schema"));

        ConversionPlan plan = detector.detect(tempDir);

        assertNotNull(plan.getSchemaPath());
        assertEquals(schema, plan.getSchemaPath());
    }

    @Test
    void noSchema_returnsNull() throws IOException {
        Files.createFile(tempDir.resolve("segments_1"));

        ConversionPlan plan = detector.detect(tempDir);

        assertNull(plan.getSchemaPath());
    }

    // ── Replication factor ──

    @Test
    void readsReplicationFactor_fromCollectionState() throws IOException {
        Path shard1 = Files.createDirectory(tempDir.resolve("snapshot.shard1"));
        Files.createFile(shard1.resolve("segments_1"));

        Path zkDir = Files.createDirectory(tempDir.resolve("zk_backup"));
        Files.writeString(zkDir.resolve("collection_state.json"),
                "{\"myCollection\": {\"replicationFactor\": 3, \"router\": {\"name\": \"compositeId\"}}}");

        ConversionPlan plan = detector.detect(tempDir);

        assertEquals(3, plan.getSolrReplicationFactor());
        assertEquals(2, plan.getOsReplicasFromSolr());
    }

    @Test
    void readsReplicationFactor_fromZkBackup0() throws IOException {
        Path shard1 = Files.createDirectory(tempDir.resolve("snapshot.shard1"));
        Files.createFile(shard1.resolve("segments_1"));

        Path zkDir = Files.createDirectory(tempDir.resolve("zk_backup_0"));
        Files.writeString(zkDir.resolve("collection_state.json"),
                "{\"myCollection\": {\"replicationFactor\": 2}}");

        ConversionPlan plan = detector.detect(tempDir);

        assertEquals(2, plan.getSolrReplicationFactor());
    }

    @Test
    void missingCollectionState_returnsNegative1() throws IOException {
        Files.createFile(tempDir.resolve("segments_1"));

        ConversionPlan plan = detector.detect(tempDir);

        assertEquals(-1, plan.getSolrReplicationFactor());
    }

    @Test
    void malformedCollectionState_returnsNegative1() throws IOException {
        Path shard1 = Files.createDirectory(tempDir.resolve("snapshot.shard1"));
        Files.createFile(shard1.resolve("segments_1"));

        Path zkDir = Files.createDirectory(tempDir.resolve("zk_backup"));
        Files.writeString(zkDir.resolve("collection_state.json"), "not-json");

        ConversionPlan plan = detector.detect(tempDir);

        assertEquals(-1, plan.getSolrReplicationFactor());
    }

    // ── Error cases ──

    @Test
    void nonExistentDir_throws() {
        Path missing = tempDir.resolve("does-not-exist");

        assertThrows(IOException.class, () -> detector.detect(missing));
    }

    @Test
    void emptyDir_noFormat_throws() {
        IOException ex = assertThrows(IOException.class, () -> detector.detect(tempDir));
        assertTrue(ex.getMessage().contains("Cannot detect Solr backup format"));
    }

    @Test
    void fileNotDirectory_throws() throws IOException {
        Path file = Files.createFile(tempDir.resolve("not-a-dir"));

        IOException ex = assertThrows(IOException.class, () -> detector.detect(file));
        assertTrue(ex.getMessage().contains("not a directory"));
    }
}
