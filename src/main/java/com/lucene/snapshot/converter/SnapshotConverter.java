package com.lucene.snapshot.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Main entry point for Solr to OpenSearch snapshot converter.
 *
 * Supports standalone single-core backups and SolrCloud full backups
 * with multiple shards. Input format is auto-detected.
 *
 * Usage:
 *   java -jar converter.jar <solr-backup-dir> <output-snapshot-dir> <index-name> [replicas]
 */
public class SnapshotConverter {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotConverter.class);

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: SnapshotConverter <solr-backup-dir> <output-dir> <index-name> [replicas]");
            System.err.println();
            System.err.println("Arguments:");
            System.err.println("  solr-backup-dir   : Path to Solr backup (standalone or SolrCloud full)");
            System.err.println("  output-dir        : Path to output OpenSearch snapshot repository");
            System.err.println("  index-name        : Name for the OpenSearch index");
            System.err.println("  replicas          : Number of OS replicas (default: read from Solr backup,");
            System.err.println("                      fallback to 0 if not found)");
            System.err.println();
            System.err.println("Supported input formats:");
            System.err.println("  - Standalone: directory containing segments_N directly");
            System.err.println("  - SolrCloud full: directory with snapshot.shard1/, snapshot.shard2/, ...");
            System.err.println("  - SolrCloud incremental: NOT supported (use incremental=false)");
            System.err.println();
            System.err.println("Example:");
            System.err.println("  java -jar converter.jar /backups/test_backup /tmp/os-snapshot myindex");
            System.exit(1);
        }

        Path inputDir = Paths.get(args[0]);
        Path outputDir = Paths.get(args[1]);
        String indexName = args[2];
        int replicas = args.length >= 4 ? Integer.parseInt(args[3]) : -1;

        logger.info("=== Solr to OpenSearch Snapshot Converter ===");

        try {
            SnapshotConverterImpl converter = new SnapshotConverterImpl();
            ConversionResult result = converter.convert(inputDir, outputDir, indexName, replicas);

            logger.info("");
            logger.info("Conversion completed successfully!");
            logger.info("  Snapshot UUID : {}", result.getSnapshotUuid());
            logger.info("  Index UUID    : {}", result.getIndexUuid());
            logger.info("  Total shards  : {}", result.getTotalShards());
            logger.info("  Total docs    : {}", result.getTotalDocs());
            logger.info("  Total size    : {}", result.formatTotalBytes());
            logger.info("  Lucene files  : {}", result.getLuceneFilesCopied());
            logger.info("  Metadata files: {}", result.getMetadataFilesCreated());

            if (result.getTotalShards() > 1) {
                logger.info("");
                logger.info("Per-shard breakdown:");
                for (ShardConversionResult shard : result.getShardResults()) {
                    logger.info("  shard {}: {} docs, {} segments, {} files, Lucene {}",
                            shard.getShardId(), shard.getDocCount(),
                            shard.getSegmentCount(), shard.getFiles().size(),
                            shard.getLuceneVersion());
                }
            }

            logger.info("");
            logger.info("OpenSearch snapshot ready at: {}", outputDir);

        } catch (Exception e) {
            logger.error("Conversion failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
