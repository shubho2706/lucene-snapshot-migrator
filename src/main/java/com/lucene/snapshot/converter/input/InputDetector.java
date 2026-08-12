package com.lucene.snapshot.converter.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class InputDetector {

    private static final Logger logger = LoggerFactory.getLogger(InputDetector.class);
    private static final Pattern SHARD_DIR_PATTERN = Pattern.compile("snapshot\\.shard(\\d+)");

    public ConversionPlan detect(Path inputDir) throws IOException {
        if (!Files.isDirectory(inputDir)) {
            throw new IOException("Input path is not a directory: " + inputDir);
        }

        if (Files.exists(inputDir.resolve("backup_0.properties"))) {
            throw new IOException(
                    "Incremental backup detected (backup_0.properties found). "
                    + "This format is not supported. Re-run Solr backup with incremental=false:\n"
                    + "  curl '...admin/collections?action=BACKUP&name=NAME"
                    + "&collection=COLL&location=LOC&incremental=false'"
            );
        }

        try (DirectoryStream<Path> children = Files.newDirectoryStream(inputDir)) {
            for (Path child : children) {
                if (Files.isDirectory(child) && Files.exists(child.resolve("backup_0.properties"))) {
                    throw new IOException(
                            "Incremental backup detected in " + child.getFileName()
                            + " (backup_0.properties found). "
                            + "This format is not supported. Re-run Solr backup with incremental=false."
                    );
                }
            }
        }

        List<Path> shardDirs = discoverShardDirs(inputDir);
        if (!shardDirs.isEmpty()) {
            Path schemaPath = findSchemaPath(inputDir);
            int replicationFactor = readReplicationFactor(inputDir);
            logger.info("Detected SolrCloud full backup: {} shards", shardDirs.size());
            for (int i = 0; i < shardDirs.size(); i++) {
                logger.info("  shard{} -> {}", i + 1, shardDirs.get(i).getFileName());
            }
            if (schemaPath != null) {
                logger.info("  Schema: {}", schemaPath);
            }
            if (replicationFactor > 0) {
                logger.info("  Solr replicationFactor: {}", replicationFactor);
            }
            return new ConversionPlan(InputFormat.SOLRCLOUD_FULL, shardDirs, inputDir, schemaPath, replicationFactor);
        }

        Path standaloneDir = findStandaloneIndex(inputDir);
        if (standaloneDir != null) {
            List<Path> singleShard = new ArrayList<>();
            singleShard.add(standaloneDir);
            Path schemaPath = findSchemaPath(inputDir);
            logger.info("Detected standalone single-core backup: {}", standaloneDir);
            return new ConversionPlan(InputFormat.STANDALONE, singleShard, inputDir, schemaPath, -1);
        }

        throw new IOException(
                "Cannot detect Solr backup format in: " + inputDir + "\n"
                + "Expected one of:\n"
                + "  - SolrCloud full backup: snapshot.shard1/, snapshot.shard2/, ...\n"
                + "  - Standalone backup: segments_N file directly or under index/\n"
                + "  - SolrCloud incremental: backup_0.properties (NOT supported, use incremental=false)"
        );
    }

    private List<Path> discoverShardDirs(Path inputDir) throws IOException {
        List<ShardEntry> entries = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inputDir)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) continue;
                Matcher m = SHARD_DIR_PATTERN.matcher(child.getFileName().toString());
                if (m.matches()) {
                    int shardNum = Integer.parseInt(m.group(1));
                    entries.add(new ShardEntry(shardNum, child));
                }
            }
        }

        if (entries.isEmpty()) return new ArrayList<>();

        entries.sort(Comparator.comparingInt(e -> e.num));

        for (int i = 0; i < entries.size(); i++) {
            int expected = i + 1;
            int actual = entries.get(i).num;
            if (actual != expected) {
                throw new IOException(
                        "Shard gap detected: expected shard" + expected + " but found shard" + actual
                        + ". Shard directories must be contiguous starting at 1."
                );
            }
        }

        List<Path> paths = new ArrayList<>();
        for (ShardEntry entry : entries) {
            if (!hasSegmentsFile(entry.path)) {
                throw new IOException(
                        "Shard directory " + entry.path.getFileName()
                        + " does not contain a segments_N file"
                );
            }
            paths.add(entry.path);
        }

        return paths;
    }

    private Path findStandaloneIndex(Path inputDir) throws IOException {
        if (hasSegmentsFile(inputDir)) {
            return inputDir;
        }

        Path indexSubdir = inputDir.resolve("index");
        if (Files.isDirectory(indexSubdir) && hasSegmentsFile(indexSubdir)) {
            return indexSubdir;
        }

        return null;
    }

    private boolean hasSegmentsFile(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith("segments_"));
        }
    }

    private Path findSchemaPath(Path inputDir) {
        String[] zkDirs = {"zk_backup", "zk_backup_0"};
        String[] schemaNames = {"managed-schema.xml", "managed-schema"};

        for (String zkDir : zkDirs) {
            for (String schemaName : schemaNames) {
                Path candidate = inputDir.resolve(zkDir).resolve("configs").resolve("_default").resolve(schemaName);
                if (Files.exists(candidate)) return candidate;
            }
        }

        for (String schemaName : schemaNames) {
            Path candidate = inputDir.resolve("conf").resolve(schemaName);
            if (Files.exists(candidate)) return candidate;
        }

        return null;
    }

    /**
     * Reads replicationFactor from zk_backup/collection_state.json.
     * Returns -1 if the file doesn't exist or can't be parsed.
     *
     * The JSON structure is: { "collectionName": { "replicationFactor": N, ... } }
     */
    private int readReplicationFactor(Path inputDir) {
        String[] zkDirs = {"zk_backup", "zk_backup_0"};

        for (String zkDir : zkDirs) {
            Path stateFile = inputDir.resolve(zkDir).resolve("collection_state.json");
            if (Files.exists(stateFile)) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(stateFile.toFile());

                    Iterator<JsonNode> collections = root.elements();
                    if (collections.hasNext()) {
                        JsonNode collection = collections.next();
                        JsonNode rfNode = collection.get("replicationFactor");
                        if (rfNode != null) {
                            return rfNode.asInt(-1);
                        }
                    }
                } catch (IOException e) {
                    logger.warn("Failed to parse {}: {}", stateFile, e.getMessage());
                }
            }
        }

        return -1;
    }

    private static class ShardEntry {
        final int num;
        final Path path;

        ShardEntry(int num, Path path) {
            this.num = num;
            this.path = path;
        }
    }
}
