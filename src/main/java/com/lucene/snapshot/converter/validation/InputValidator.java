package com.lucene.snapshot.converter.validation;

import com.lucene.snapshot.converter.input.ConversionPlan;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-conversion validation. Run after InputDetector, before conversion starts.
 * Returns a list of issues (errors are fatal, warnings are informational).
 */
public class InputValidator {

    private static final Logger logger = LoggerFactory.getLogger(InputValidator.class);

    public List<ValidationIssue> validate(ConversionPlan plan, Path outputDir) {
        List<ValidationIssue> issues = new ArrayList<>();

        validateInputReadable(plan, issues);
        validateOutputWritable(outputDir, issues);
        validateShardSegments(plan, issues);
        validateDiskSpace(plan, outputDir, issues);

        return issues;
    }

    private void validateInputReadable(ConversionPlan plan, List<ValidationIssue> issues) {
        if (!Files.isDirectory(plan.getInputDir())) {
            issues.add(ValidationIssue.error("Input directory does not exist: " + plan.getInputDir()));
            return;
        }

        if (!Files.isReadable(plan.getInputDir())) {
            issues.add(ValidationIssue.error("Input directory is not readable: " + plan.getInputDir()));
        }

        for (int i = 0; i < plan.getShardPaths().size(); i++) {
            Path shardPath = plan.getShardPaths().get(i);
            if (!Files.isDirectory(shardPath)) {
                issues.add(ValidationIssue.error("Shard directory missing: " + shardPath));
            } else if (!Files.isReadable(shardPath)) {
                issues.add(ValidationIssue.error("Shard directory not readable: " + shardPath));
            }
        }
    }

    private void validateOutputWritable(Path outputDir, List<ValidationIssue> issues) {
        Path target = outputDir;
        while (target != null && !Files.exists(target)) {
            target = target.getParent();
        }

        if (target != null && !Files.isWritable(target)) {
            issues.add(ValidationIssue.error("Output directory is not writable: " + target));
        }

        if (Files.isDirectory(outputDir)) {
            try {
                long existingFiles = Files.list(outputDir).count();
                if (existingFiles > 0) {
                    issues.add(ValidationIssue.warn(
                            "Output directory already has " + existingFiles
                            + " entries, existing files may be overwritten: " + outputDir));
                }
            } catch (IOException e) {
                issues.add(ValidationIssue.warn("Cannot list output directory: " + e.getMessage()));
            }
        }
    }

    private void validateShardSegments(ConversionPlan plan, List<ValidationIssue> issues) {
        for (int i = 0; i < plan.getShardPaths().size(); i++) {
            Path shardPath = plan.getShardPaths().get(i);
            if (!Files.isDirectory(shardPath)) continue;

            try (Directory dir = new NIOFSDirectory(shardPath)) {
                SegmentInfos segmentInfos = SegmentInfos.readLatestCommit(dir);
                int majorVersion = segmentInfos.getCommitLuceneVersion().major;

                if (majorVersion < 7) {
                    issues.add(ValidationIssue.error(
                            "Shard " + (i + 1) + ": Lucene " + segmentInfos.getCommitLuceneVersion()
                            + " is not supported (minimum: Lucene 7.x)"));
                } else if (majorVersion == 7) {
                    issues.add(ValidationIssue.warn(
                            "Shard " + (i + 1) + ": Lucene " + segmentInfos.getCommitLuceneVersion()
                            + " detected. Backward compatibility should work but test thoroughly."));
                }

                if (segmentInfos.size() == 0) {
                    issues.add(ValidationIssue.warn(
                            "Shard " + (i + 1) + ": no segments found (empty shard)"));
                }

                if (segmentInfos.totalMaxDoc() == 0) {
                    issues.add(ValidationIssue.warn(
                            "Shard " + (i + 1) + ": 0 documents"));
                }

            } catch (IOException e) {
                issues.add(ValidationIssue.error(
                        "Shard " + (i + 1) + ": cannot read segments: " + e.getMessage()));
            }
        }
    }

    private void validateDiskSpace(ConversionPlan plan, Path outputDir, List<ValidationIssue> issues) {
        try {
            long inputSize = 0;
            for (Path shardPath : plan.getShardPaths()) {
                inputSize += directorySize(shardPath);
            }

            Path target = outputDir;
            while (target != null && !Files.exists(target)) {
                target = target.getParent();
            }

            if (target != null) {
                long usableSpace = target.toFile().getUsableSpace();
                long required = (long) (inputSize * 1.1);

                if (usableSpace < required) {
                    issues.add(ValidationIssue.warn(String.format(
                            "Low disk space: need ~%s (1.1x input), only %s available",
                            formatBytes(required), formatBytes(usableSpace))));
                }
            }
        } catch (IOException e) {
            logger.debug("Could not check disk space: {}", e.getMessage());
        }
    }

    private long directorySize(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        return Files.walk(dir)
                .filter(Files::isRegularFile)
                .mapToLong(p -> {
                    try { return Files.size(p); }
                    catch (IOException e) { return 0; }
                })
                .sum();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static class ValidationIssue {
        public enum Severity { ERROR, WARN }

        private final Severity severity;
        private final String message;

        private ValidationIssue(Severity severity, String message) {
            this.severity = severity;
            this.message = message;
        }

        public static ValidationIssue error(String message) {
            return new ValidationIssue(Severity.ERROR, message);
        }

        public static ValidationIssue warn(String message) {
            return new ValidationIssue(Severity.WARN, message);
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getMessage() {
            return message;
        }

        public boolean isError() {
            return severity == Severity.ERROR;
        }

        @Override
        public String toString() {
            return "[" + severity + "] " + message;
        }
    }
}
