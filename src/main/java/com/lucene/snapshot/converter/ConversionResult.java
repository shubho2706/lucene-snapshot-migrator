package com.lucene.snapshot.converter;

import java.util.Collections;
import java.util.List;

/**
 * Result of snapshot conversion operation.
 */
public class ConversionResult {

    private final String snapshotUuid;
    private final String indexUuid;
    private final int totalShards;
    private final int luceneFilesCopied;
    private final int metadataFilesCreated;
    private final long totalDocs;
    private final long totalBytes;
    private final List<ShardConversionResult> shardResults;

    public ConversionResult(
            String snapshotUuid,
            String indexUuid,
            int totalShards,
            int luceneFilesCopied,
            int metadataFilesCreated,
            long totalDocs,
            long totalBytes,
            List<ShardConversionResult> shardResults) {
        this.snapshotUuid = snapshotUuid;
        this.indexUuid = indexUuid;
        this.totalShards = totalShards;
        this.luceneFilesCopied = luceneFilesCopied;
        this.metadataFilesCreated = metadataFilesCreated;
        this.totalDocs = totalDocs;
        this.totalBytes = totalBytes;
        this.shardResults = Collections.unmodifiableList(shardResults);
    }

    public String getSnapshotUuid() {
        return snapshotUuid;
    }

    public String getIndexUuid() {
        return indexUuid;
    }

    public int getTotalShards() {
        return totalShards;
    }

    public int getLuceneFilesCopied() {
        return luceneFilesCopied;
    }

    public int getMetadataFilesCreated() {
        return metadataFilesCreated;
    }

    public long getTotalDocs() {
        return totalDocs;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public List<ShardConversionResult> getShardResults() {
        return shardResults;
    }

    public String formatTotalBytes() {
        if (totalBytes < 1024) return totalBytes + " B";
        if (totalBytes < 1024 * 1024) return String.format("%.1f KB", totalBytes / 1024.0);
        if (totalBytes < 1024 * 1024 * 1024) return String.format("%.1f MB", totalBytes / (1024.0 * 1024));
        return String.format("%.2f GB", totalBytes / (1024.0 * 1024 * 1024));
    }
}
