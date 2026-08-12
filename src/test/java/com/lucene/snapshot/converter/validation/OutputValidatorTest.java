package com.lucene.snapshot.converter.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutputValidatorTest {

    private final OutputValidator validator = new OutputValidator();

    @TempDir
    Path tempDir;

    @Test
    void validSingleShardSnapshot_noErrors() throws IOException {
        createValidSnapshot(tempDir, 1);

        List<InputValidator.ValidationIssue> issues = validator.validate(tempDir, 1);

        long errors = issues.stream().filter(InputValidator.ValidationIssue::isError).count();
        assertEquals(0, errors, "Expected no errors but got: " + issues);
    }

    @Test
    void validMultiShardSnapshot_noErrors() throws IOException {
        createValidSnapshot(tempDir, 3);

        List<InputValidator.ValidationIssue> issues = validator.validate(tempDir, 3);

        long errors = issues.stream().filter(InputValidator.ValidationIssue::isError).count();
        assertEquals(0, errors, "Expected no errors but got: " + issues);
    }

    @Test
    void missingIndex0_reportsError() throws IOException {
        createValidSnapshot(tempDir, 1);
        Files.delete(tempDir.resolve("index-0"));

        List<InputValidator.ValidationIssue> issues = validator.validate(tempDir, 1);

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("index-0")));
    }

    @Test
    void missingIndexLatest_reportsError() throws IOException {
        createValidSnapshot(tempDir, 1);
        Files.delete(tempDir.resolve("index.latest"));

        List<InputValidator.ValidationIssue> issues = validator.validate(tempDir, 1);

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("index.latest")));
    }

    @Test
    void missingRepoSnapDat_reportsError() throws IOException {
        createValidSnapshot(tempDir, 1);
        Files.delete(tempDir.resolve("snap-test-uuid.dat"));

        List<InputValidator.ValidationIssue> issues = validator.validate(tempDir, 1);

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("snap-*.dat")));
    }

    @Test
    void missingRepoMetaDat_reportsError() throws IOException {
        createValidSnapshot(tempDir, 1);
        Files.delete(tempDir.resolve("meta-test-uuid.dat"));

        List<InputValidator.ValidationIssue> issues = validator.validate(tempDir, 1);

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("meta-*.dat")));
    }

    @Test
    void indexLatest_wrongSize_reportsError() throws IOException {
        createValidSnapshot(tempDir, 1);
        Files.write(tempDir.resolve("index.latest"), new byte[]{1, 2, 3});

        List<InputValidator.ValidationIssue> issues = validator.validate(tempDir, 1);

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("expected exactly 8")));
    }

    @Test
    void missingIndicesDir_reportsError() throws IOException {
        Files.createFile(tempDir.resolve("index-0"));
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(0L);
        Files.write(tempDir.resolve("index.latest"), buf.array());
        Files.createFile(tempDir.resolve("snap-x.dat"));
        Files.createFile(tempDir.resolve("meta-x.dat"));

        List<InputValidator.ValidationIssue> issues = validator.validate(tempDir, 1);

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("indices/")));
    }

    @Test
    void missingShardDir_reportsError() throws IOException {
        createValidSnapshot(tempDir, 2);
        // Delete shard 1 directory
        Path shard1Dir = tempDir.resolve("indices/test-idx-uuid/1");
        Files.walk(shard1Dir).sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException e) {} });

        List<InputValidator.ValidationIssue> issues = validator.validate(tempDir, 2);

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("Missing shard directory")));
    }

    @Test
    void shardMissingSnapDat_reportsError() throws IOException {
        createValidSnapshot(tempDir, 1);
        Files.delete(tempDir.resolve("indices/test-idx-uuid/0/snap-shard-uuid.dat"));

        List<InputValidator.ValidationIssue> issues = validator.validate(tempDir, 1);

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("snap-*.dat")));
    }

    @Test
    void shardMissingIndexFile_reportsError() throws IOException {
        createValidSnapshot(tempDir, 1);
        Files.delete(tempDir.resolve("indices/test-idx-uuid/0/index-gen-uuid"));

        List<InputValidator.ValidationIssue> issues = validator.validate(tempDir, 1);

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("index-*")));
    }

    @Test
    void shardNoLuceneFiles_reportsWarning() throws IOException {
        createValidSnapshot(tempDir, 1);
        // Remove the fake Lucene file
        Files.delete(tempDir.resolve("indices/test-idx-uuid/0/segments_1"));

        List<InputValidator.ValidationIssue> issues = validator.validate(tempDir, 1);

        assertTrue(issues.stream().anyMatch(i -> !i.isError() && i.getMessage().contains("no Lucene files")));
    }

    private void createValidSnapshot(Path outputDir, int shardCount) throws IOException {
        // Repo-level files
        Files.createFile(outputDir.resolve("index-0"));
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(0L);
        Files.write(outputDir.resolve("index.latest"), buf.array());
        Files.createFile(outputDir.resolve("snap-test-uuid.dat"));
        Files.createFile(outputDir.resolve("meta-test-uuid.dat"));

        // Index-level
        Path indexDir = Files.createDirectories(outputDir.resolve("indices/test-idx-uuid"));
        Files.createFile(indexDir.resolve("meta-idx-meta-uuid.dat"));

        // Per-shard
        for (int i = 0; i < shardCount; i++) {
            Path shardDir = Files.createDirectories(indexDir.resolve(String.valueOf(i)));
            Files.createFile(shardDir.resolve("snap-shard-uuid.dat"));
            Files.createFile(shardDir.resolve("index-gen-uuid"));
            Files.createFile(shardDir.resolve("segments_1"));
        }
    }
}
