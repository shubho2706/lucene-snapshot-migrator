package com.lucene.snapshot.converter;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShardConversionResultTest {

    @Test
    void gettersReturnConstructorValues() {
        ShardConversionResult.LuceneFileMetadata file = new ShardConversionResult.LuceneFileMetadata(
                "_0.fdt", 1024L, "abc123", "9.8.0", new byte[]{1, 2, 3});

        List<ShardConversionResult.LuceneFileMetadata> files = Arrays.asList(file);
        ShardConversionResult shard = new ShardConversionResult(
                0, 500L, 3, "9.8.0", Path.of("/tmp/shard0"), files);

        assertEquals(0, shard.getShardId());
        assertEquals(500L, shard.getDocCount());
        assertEquals(3, shard.getSegmentCount());
        assertEquals("9.8.0", shard.getLuceneVersion());
        assertEquals(Path.of("/tmp/shard0"), shard.getOutputDir());
        assertEquals(1, shard.getFiles().size());
    }

    @Test
    void filesListIsUnmodifiable() {
        ShardConversionResult shard = new ShardConversionResult(
                0, 0, 0, "9.8.0", Path.of("/tmp"), Collections.emptyList());

        assertThrows(UnsupportedOperationException.class,
                () -> shard.getFiles().add(null));
    }

    @Test
    void getTotalBytesAggregatesAllFiles() {
        ShardConversionResult.LuceneFileMetadata f1 = new ShardConversionResult.LuceneFileMetadata(
                "_0.fdt", 1000L, "a", "9.8.0", new byte[0]);
        ShardConversionResult.LuceneFileMetadata f2 = new ShardConversionResult.LuceneFileMetadata(
                "_0.fdx", 2000L, "b", "9.8.0", new byte[0]);
        ShardConversionResult.LuceneFileMetadata f3 = new ShardConversionResult.LuceneFileMetadata(
                "_0.si", 500L, "c", "9.8.0", new byte[0]);

        ShardConversionResult shard = new ShardConversionResult(
                0, 100, 1, "9.8.0", Path.of("/tmp"), Arrays.asList(f1, f2, f3));

        assertEquals(3500L, shard.getTotalBytes());
    }

    @Test
    void getTotalBytesWithNoFiles() {
        ShardConversionResult shard = new ShardConversionResult(
                0, 0, 0, "9.8.0", Path.of("/tmp"), Collections.emptyList());

        assertEquals(0L, shard.getTotalBytes());
    }

    @Test
    void luceneFileMetadataGetters() {
        byte[] hash = {10, 20, 30, 40};
        ShardConversionResult.LuceneFileMetadata meta = new ShardConversionResult.LuceneFileMetadata(
                "segments_3", 4096L, "deadbeef", "9.7.0", hash);

        assertEquals("segments_3", meta.getName());
        assertEquals(4096L, meta.getLength());
        assertEquals("deadbeef", meta.getChecksum());
        assertEquals("9.7.0", meta.getWrittenBy());
        assertArrayEquals(hash, meta.getMetaHash());
    }
}
