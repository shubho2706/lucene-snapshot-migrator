package com.lucene.snapshot.converter;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Holds the conversion results for a single shard, including the Lucene file
 * metadata needed by the metadata writer to generate per-shard snapshot files.
 */
public class ShardConversionResult {

    private final int shardId;
    private final long docCount;
    private final int segmentCount;
    private final String luceneVersion;
    private final Path outputDir;
    private final List<LuceneFileMetadata> files;

    public ShardConversionResult(int shardId, long docCount, int segmentCount,
                                  String luceneVersion, Path outputDir,
                                  List<LuceneFileMetadata> files) {
        this.shardId = shardId;
        this.docCount = docCount;
        this.segmentCount = segmentCount;
        this.luceneVersion = luceneVersion;
        this.outputDir = outputDir;
        this.files = Collections.unmodifiableList(files);
    }

    public int getShardId() {
        return shardId;
    }

    public long getDocCount() {
        return docCount;
    }

    public int getSegmentCount() {
        return segmentCount;
    }

    public String getLuceneVersion() {
        return luceneVersion;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public List<LuceneFileMetadata> getFiles() {
        return files;
    }

    public long getTotalBytes() {
        return files.stream().mapToLong(LuceneFileMetadata::getLength).sum();
    }

    /**
     * Metadata for a single Lucene file in the shard directory.
     * Collected during file copy so the metadata writer doesn't need to rescan.
     */
    public static class LuceneFileMetadata {

        private final String name;
        private final long length;
        private final String checksum;
        private final String writtenBy;
        private final byte[] metaHash;

        public LuceneFileMetadata(String name, long length, String checksum,
                                   String writtenBy, byte[] metaHash) {
            this.name = name;
            this.length = length;
            this.checksum = checksum;
            this.writtenBy = writtenBy;
            this.metaHash = metaHash;
        }

        public String getName() {
            return name;
        }

        public long getLength() {
            return length;
        }

        public String getChecksum() {
            return checksum;
        }

        public String getWrittenBy() {
            return writtenBy;
        }

        public byte[] getMetaHash() {
            return metaHash;
        }
    }
}
