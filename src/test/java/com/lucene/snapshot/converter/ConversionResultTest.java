package com.lucene.snapshot.converter;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversionResultTest {

    @Test
    void gettersReturnConstructorValues() {
        List<ShardConversionResult> shards = Collections.emptyList();
        ConversionResult result = new ConversionResult(
                "snap-uuid", "idx-uuid", 3, 42, 7, 10000L, 5242880L, shards);

        assertEquals("snap-uuid", result.getSnapshotUuid());
        assertEquals("idx-uuid", result.getIndexUuid());
        assertEquals(3, result.getTotalShards());
        assertEquals(42, result.getLuceneFilesCopied());
        assertEquals(7, result.getMetadataFilesCreated());
        assertEquals(10000L, result.getTotalDocs());
        assertEquals(5242880L, result.getTotalBytes());
        assertTrue(result.getShardResults().isEmpty());
    }

    @Test
    void shardResultsListIsUnmodifiable() {
        List<ShardConversionResult> shards = Collections.emptyList();
        ConversionResult result = new ConversionResult(
                "a", "b", 1, 1, 1, 1, 1, shards);

        assertThrows(UnsupportedOperationException.class,
                () -> result.getShardResults().add(null));
    }

    @Test
    void formatTotalBytes_bytes() {
        ConversionResult result = makeResult(512L);
        assertEquals("512 B", result.formatTotalBytes());
    }

    @Test
    void formatTotalBytes_kilobytes() {
        ConversionResult result = makeResult(2048L);
        assertEquals("2.0 KB", result.formatTotalBytes());
    }

    @Test
    void formatTotalBytes_megabytes() {
        ConversionResult result = makeResult(5 * 1024 * 1024L);
        assertEquals("5.0 MB", result.formatTotalBytes());
    }

    @Test
    void formatTotalBytes_gigabytes() {
        ConversionResult result = makeResult(3L * 1024 * 1024 * 1024);
        assertEquals("3.00 GB", result.formatTotalBytes());
    }

    @Test
    void formatTotalBytes_zero() {
        ConversionResult result = makeResult(0L);
        assertEquals("0 B", result.formatTotalBytes());
    }

    @Test
    void formatTotalBytes_boundaryKB() {
        ConversionResult result = makeResult(1024L);
        assertEquals("1.0 KB", result.formatTotalBytes());
    }

    @Test
    void formatTotalBytes_boundaryMB() {
        ConversionResult result = makeResult(1024L * 1024);
        assertEquals("1.0 MB", result.formatTotalBytes());
    }

    @Test
    void formatTotalBytes_boundaryGB() {
        ConversionResult result = makeResult(1024L * 1024 * 1024);
        assertEquals("1.00 GB", result.formatTotalBytes());
    }

    private ConversionResult makeResult(long totalBytes) {
        return new ConversionResult("a", "b", 1, 1, 1, 0, totalBytes, Collections.emptyList());
    }
}
