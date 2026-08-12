package com.lucene.snapshot.converter.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Post-conversion validation. Verifies the output snapshot structure
 * is complete and consistent before declaring success.
 */
public class OutputValidator {

    private static final Logger logger = LoggerFactory.getLogger(OutputValidator.class);

    public List<InputValidator.ValidationIssue> validate(Path outputDir, int expectedShardCount) {
        List<InputValidator.ValidationIssue> issues = new ArrayList<>();

        validateRepoFiles(outputDir, issues);
        validateIndexLatest(outputDir, issues);
        validateIndexDirectory(outputDir, expectedShardCount, issues);

        return issues;
    }

    private void validateRepoFiles(Path outputDir, List<InputValidator.ValidationIssue> issues) {
        if (!Files.exists(outputDir.resolve("index-0"))) {
            issues.add(InputValidator.ValidationIssue.error("Missing repo index file: index-0"));
        }

        if (!Files.exists(outputDir.resolve("index.latest"))) {
            issues.add(InputValidator.ValidationIssue.error("Missing index.latest"));
        }

        boolean hasSnap = false;
        boolean hasMeta = false;
        try (Stream<Path> files = Files.list(outputDir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                if (name.startsWith("snap-") && name.endsWith(".dat")) hasSnap = true;
                if (name.startsWith("meta-") && name.endsWith(".dat")) hasMeta = true;
            }
        } catch (IOException e) {
            issues.add(InputValidator.ValidationIssue.error("Cannot list output directory: " + e.getMessage()));
            return;
        }

        if (!hasSnap) issues.add(InputValidator.ValidationIssue.error("Missing repo-level snap-*.dat"));
        if (!hasMeta) issues.add(InputValidator.ValidationIssue.error("Missing repo-level meta-*.dat"));
    }

    private void validateIndexLatest(Path outputDir, List<InputValidator.ValidationIssue> issues) {
        Path indexLatest = outputDir.resolve("index.latest");
        if (!Files.exists(indexLatest)) return;

        try {
            long size = Files.size(indexLatest);
            if (size != 8) {
                issues.add(InputValidator.ValidationIssue.error(
                        "index.latest is " + size + " bytes, expected exactly 8 (big-endian long)"));
            }
        } catch (IOException e) {
            issues.add(InputValidator.ValidationIssue.error("Cannot read index.latest: " + e.getMessage()));
        }
    }

    private void validateIndexDirectory(Path outputDir, int expectedShardCount,
                                         List<InputValidator.ValidationIssue> issues) {
        Path indicesDir = outputDir.resolve("indices");
        if (!Files.isDirectory(indicesDir)) {
            issues.add(InputValidator.ValidationIssue.error("Missing indices/ directory"));
            return;
        }

        Path indexUuidDir = null;
        try (Stream<Path> dirs = Files.list(indicesDir)) {
            List<Path> indexDirs = new ArrayList<>();
            dirs.filter(Files::isDirectory).forEach(indexDirs::add);

            if (indexDirs.isEmpty()) {
                issues.add(InputValidator.ValidationIssue.error("No index directories under indices/"));
                return;
            }
            if (indexDirs.size() > 1) {
                issues.add(InputValidator.ValidationIssue.warn(
                        "Multiple index directories found, validating first: " + indexDirs.get(0).getFileName()));
            }
            indexUuidDir = indexDirs.get(0);
        } catch (IOException e) {
            issues.add(InputValidator.ValidationIssue.error("Cannot list indices/: " + e.getMessage()));
            return;
        }

        boolean hasIndexMeta = false;
        try (Stream<Path> files = Files.list(indexUuidDir)) {
            hasIndexMeta = files.anyMatch(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("meta-") && name.endsWith(".dat");
            });
        } catch (IOException e) {
            issues.add(InputValidator.ValidationIssue.error("Cannot list index directory: " + e.getMessage()));
        }
        if (!hasIndexMeta) {
            issues.add(InputValidator.ValidationIssue.error("Missing index-level meta-*.dat"));
        }

        for (int shardId = 0; shardId < expectedShardCount; shardId++) {
            Path shardDir = indexUuidDir.resolve(String.valueOf(shardId));
            if (!Files.isDirectory(shardDir)) {
                issues.add(InputValidator.ValidationIssue.error("Missing shard directory: " + shardId));
                continue;
            }

            validateShardDir(shardDir, shardId, issues);
        }
    }

    private void validateShardDir(Path shardDir, int shardId,
                                   List<InputValidator.ValidationIssue> issues) {
        String label = "shard " + shardId;

        boolean hasSnap = false;
        boolean hasIndex = false;
        int luceneFileCount = 0;

        try (Stream<Path> files = Files.list(shardDir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = p.getFileName().toString();
                if (name.startsWith("snap-") && name.endsWith(".dat")) {
                    hasSnap = true;
                } else if (name.startsWith("index-")) {
                    hasIndex = true;
                } else {
                    luceneFileCount++;
                }
            }
        } catch (IOException e) {
            issues.add(InputValidator.ValidationIssue.error(
                    label + ": cannot list directory: " + e.getMessage()));
            return;
        }

        if (!hasSnap) {
            issues.add(InputValidator.ValidationIssue.error(label + ": missing snap-*.dat"));
        }
        if (!hasIndex) {
            issues.add(InputValidator.ValidationIssue.error(label + ": missing index-* file"));
        }
        if (luceneFileCount == 0) {
            issues.add(InputValidator.ValidationIssue.warn(label + ": no Lucene files found"));
        }
    }
}
