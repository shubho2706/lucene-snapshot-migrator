package com.lucene.snapshot.converter;

import com.lucene.snapshot.converter.input.ConversionPlan;
import com.lucene.snapshot.converter.input.InputDetector;
import com.lucene.snapshot.converter.validation.InputValidator;
import com.lucene.snapshot.converter.validation.OutputValidator;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.NIOFSDirectory;
import org.opensearch.common.UUIDs;
import org.opensearch.index.store.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class SnapshotConverterImpl {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotConverterImpl.class);

    /**
     * Convert with default replicas (read from Solr backup, fallback to 0).
     */
    public ConversionResult convert(Path inputDir, Path outputDir, String indexName) throws IOException {
        return convert(inputDir, outputDir, indexName, -1);
    }

    /**
     * Convert with explicit replica count.
     * @param replicas number of OS replicas. -1 = read from Solr's replicationFactor, fallback to 0.
     */
    public ConversionResult convert(Path inputDir, Path outputDir, String indexName, int replicas) throws IOException {

        logger.info("Step 1: Detecting input format...");
        InputDetector detector = new InputDetector();
        ConversionPlan plan = detector.detect(inputDir);
        logger.info("  {}", plan);

        logger.info("Step 1b: Validating input...");
        InputValidator inputValidator = new InputValidator();
        List<InputValidator.ValidationIssue> inputIssues = inputValidator.validate(plan, outputDir);
        for (InputValidator.ValidationIssue issue : inputIssues) {
            if (issue.isError()) {
                logger.error("  {}", issue);
            } else {
                logger.warn("  {}", issue);
            }
        }
        boolean hasErrors = inputIssues.stream().anyMatch(InputValidator.ValidationIssue::isError);
        if (hasErrors) {
            throw new IOException("Input validation failed. See errors above.");
        }

        String snapshotUuid = UUIDs.base64UUID();
        String indexUuid = UUIDs.base64UUID();
        int shardCount = plan.getShardCount();

        int numberOfReplicas;
        if (replicas >= 0) {
            numberOfReplicas = replicas;
            logger.info("  Using specified replicas: {}", numberOfReplicas);
        } else if (plan.getOsReplicasFromSolr() >= 0) {
            numberOfReplicas = plan.getOsReplicasFromSolr();
            logger.info("  Using Solr replicationFactor {} -> OS replicas: {}", plan.getSolrReplicationFactor(), numberOfReplicas);
        } else {
            numberOfReplicas = 0;
            logger.info("  No replica info available, defaulting to 0");
        }

        Files.createDirectories(outputDir);
        Path indicesBaseDir = outputDir.resolve("indices").resolve(indexUuid);

        logger.info("Step 2: Processing {} shard(s)...", shardCount);

        List<ShardConversionResult> shardResults = new ArrayList<>();

        for (int shardId = 0; shardId < shardCount; shardId++) {
            Path solrShardDir = plan.getShardPaths().get(shardId);
            Path osShardDir = indicesBaseDir.resolve(String.valueOf(shardId));
            Files.createDirectories(osShardDir);

            logger.info("  [shard {}/{}] Reading segments from {}...",
                    shardId + 1, shardCount, solrShardDir.getFileName());

            SegmentInfos segmentInfos = readSegmentInfos(solrShardDir);
            long shardDocs = segmentInfos.totalMaxDoc();
            String luceneVersion = segmentInfos.getCommitLuceneVersion().toString();
            logger.info("  [shard {}/{}] {} segments, {} docs, Lucene {}",
                    shardId + 1, shardCount,
                    segmentInfos.size(), shardDocs, luceneVersion);

            List<ShardConversionResult.LuceneFileMetadata> fileMetadataList =
                    copyLuceneFiles(solrShardDir, osShardDir, segmentInfos, luceneVersion, true);
            logger.info("  [shard {}/{}] Copied {} Lucene files",
                    shardId + 1, shardCount, fileMetadataList.size());

            rewriteSegmentsWithOpenSearchMetadata(osShardDir, segmentInfos);
            logger.info("  [shard {}/{}] Rewrote commitUserData",
                    shardId + 1, shardCount);

            String newSegmentsFile = findNewSegmentsFile(osShardDir);
            ShardConversionResult.LuceneFileMetadata segmentsMeta =
                    buildFileMetadata(osShardDir, newSegmentsFile, luceneVersion);
            fileMetadataList.add(segmentsMeta);
            logger.info("  [shard {}/{}] Segments file: {} ({} bytes)",
                    shardId + 1, shardCount, newSegmentsFile, segmentsMeta.getLength());

            ShardConversionResult shardResult = new ShardConversionResult(
                    shardId, shardDocs, segmentInfos.size(),
                    luceneVersion, osShardDir, fileMetadataList
            );
            shardResults.add(shardResult);
        }

        logger.info("Step 3: Generating OpenSearch metadata ({} shards)...", shardCount);

        Path schemaPath = plan.getSchemaPath();
        SnapshotMetadataWriter metadataWriter = new SnapshotMetadataWriter(
                snapshotUuid, indexUuid, indexName, schemaPath, shardResults, numberOfReplicas
        );

        int metadataFiles = metadataWriter.generateMetadata(outputDir);
        logger.info("  Created {} metadata files", metadataFiles);

        long totalDocs = shardResults.stream().mapToLong(ShardConversionResult::getDocCount).sum();
        long totalBytes = shardResults.stream().mapToLong(ShardConversionResult::getTotalBytes).sum();
        int totalFilesCopied = shardResults.stream().mapToInt(r -> r.getFiles().size()).sum();

        logger.info("Step 4: Validating output...");
        OutputValidator outputValidator = new OutputValidator();
        List<InputValidator.ValidationIssue> outputIssues = outputValidator.validate(outputDir, shardCount);
        for (InputValidator.ValidationIssue issue : outputIssues) {
            if (issue.isError()) {
                logger.error("  {}", issue);
            } else {
                logger.warn("  {}", issue);
            }
        }
        boolean outputHasErrors = outputIssues.stream().anyMatch(InputValidator.ValidationIssue::isError);
        if (outputHasErrors) {
            throw new IOException("Output validation failed. Snapshot may be incomplete. See errors above.");
        }

        ConversionResult result = new ConversionResult(
                snapshotUuid, indexUuid, shardCount,
                totalFilesCopied, metadataFiles, totalDocs, totalBytes, shardResults
        );
        return result;
    }

    private SegmentInfos readSegmentInfos(Path indexDir) throws IOException {
        try (Directory directory = new NIOFSDirectory(indexDir)) {
            return SegmentInfos.readLatestCommit(directory);
        }
    }

    private List<ShardConversionResult.LuceneFileMetadata> copyLuceneFiles(
            Path sourceDir, Path targetDir, SegmentInfos segmentInfos,
            String luceneVersion, boolean skipSegmentsFile) throws IOException {

        Collection<String> fileCollection = segmentInfos.files(true);
        List<ShardConversionResult.LuceneFileMetadata> metadataList = new ArrayList<>();
        String segmentsFileName = segmentInfos.getSegmentsFileName();

        try (Directory srcDirectory = new NIOFSDirectory(sourceDir)) {
            for (String fileName : fileCollection) {
                if (skipSegmentsFile && fileName.equals(segmentsFileName)) {
                    Files.copy(sourceDir.resolve(fileName), targetDir.resolve(fileName),
                            StandardCopyOption.REPLACE_EXISTING);
                    continue;
                }

                Path source = sourceDir.resolve(fileName);
                Path target = targetDir.resolve(fileName);

                if (!Files.exists(source)) {
                    logger.warn("    Missing file (skipping): {}", fileName);
                    continue;
                }

                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);

                long fileSize = Files.size(target);
                String checksum = extractChecksum(srcDirectory, fileName);
                byte[] metaHash = computeMetaHash(fileName, fileSize, checksum, luceneVersion);

                metadataList.add(new ShardConversionResult.LuceneFileMetadata(
                        fileName, fileSize, checksum, luceneVersion, metaHash
                ));
            }
        }

        return metadataList;
    }

    private String findNewSegmentsFile(Path shardDir) throws IOException {
        try (var stream = Files.newDirectoryStream(shardDir, "segments_*")) {
            for (Path p : stream) {
                return p.getFileName().toString();
            }
        }
        throw new IOException("No segments file found in " + shardDir);
    }

    private ShardConversionResult.LuceneFileMetadata buildFileMetadata(
            Path dir, String fileName, String luceneVersion) throws IOException {
        Path filePath = dir.resolve(fileName);
        long fileSize = Files.size(filePath);
        String checksum;
        try (Directory directory = new NIOFSDirectory(dir)) {
            try (IndexInput input = directory.openInput(fileName, IOContext.READONCE)) {
                long rawChecksum = CodecUtil.retrieveChecksum(input);
                checksum = Store.digestToString(rawChecksum);
            }
        }
        byte[] metaHash = computeMetaHash(fileName, fileSize, checksum, luceneVersion);
        return new ShardConversionResult.LuceneFileMetadata(
                fileName, fileSize, checksum, luceneVersion, metaHash
        );
    }

    private String extractChecksum(Directory directory, String fileName) throws IOException {
        try (IndexInput input = directory.openInput(fileName, IOContext.READONCE)) {
            long checksum = CodecUtil.retrieveChecksum(input);
            return Store.digestToString(checksum);
        }
    }

    private byte[] computeMetaHash(String fileName, long fileSize,
                                    String checksum, String writtenBy) throws IOException {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(fileName.getBytes(StandardCharsets.UTF_8));
            sha256.update(String.valueOf(fileSize).getBytes(StandardCharsets.UTF_8));
            sha256.update(checksum.getBytes(StandardCharsets.UTF_8));
            sha256.update(writtenBy.getBytes(StandardCharsets.UTF_8));
            return sha256.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
    }

    private void rewriteSegmentsWithOpenSearchMetadata(Path shardDir, SegmentInfos originalSegments) throws IOException {
        try (Directory directory = new NIOFSDirectory(shardDir)) {
            SegmentInfos newSegments = originalSegments.clone();

            long totalDocs = newSegments.totalMaxDoc();
            long maxSeqNo = Math.max(0, totalDocs - 1);

            Map<String, String> userData = new HashMap<>();

            userData.put("history_uuid", UUIDs.randomBase64UUID());
            userData.put("local_checkpoint", String.valueOf(maxSeqNo));
            userData.put("max_seq_no", String.valueOf(maxSeqNo));
            userData.put("min_retained_seqno", "0");
            userData.put("max_unsafe_auto_id_timestamp", "-1");
            userData.put("translog_uuid", UUIDs.randomBase64UUID());
            userData.put("translog_gen", "1");
            userData.put("min_translog_gen", "1");

            newSegments.setUserData(userData, false);

            try (var stream = Files.newDirectoryStream(shardDir, "segments_*")) {
                for (Path oldSegments : stream) {
                    Files.deleteIfExists(oldSegments);
                }
            }

            newSegments.commit(directory);
        }
    }
}
