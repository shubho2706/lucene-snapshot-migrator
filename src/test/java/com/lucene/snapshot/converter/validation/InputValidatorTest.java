package com.lucene.snapshot.converter.validation;

import com.lucene.snapshot.converter.input.ConversionPlan;
import com.lucene.snapshot.converter.input.InputFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InputValidatorTest {

    private final InputValidator validator = new InputValidator();

    @TempDir
    Path tempDir;

    @Test
    void validInput_noErrors() throws IOException {
        Path shardDir = createMinimalLuceneIndex(tempDir.resolve("shard0"));
        Path outputDir = tempDir.resolve("output");

        ConversionPlan plan = new ConversionPlan(
                InputFormat.STANDALONE,
                Collections.singletonList(shardDir),
                tempDir, null, -1);

        List<InputValidator.ValidationIssue> issues = validator.validate(plan, outputDir);

        long errors = issues.stream().filter(InputValidator.ValidationIssue::isError).count();
        assertEquals(0, errors, "Expected no errors but got: " + issues);
    }

    @Test
    void nonExistentInputDir_reportsError() throws IOException {
        Path missing = tempDir.resolve("does-not-exist");
        Path outputDir = tempDir.resolve("output");

        ConversionPlan plan = new ConversionPlan(
                InputFormat.STANDALONE,
                Collections.singletonList(missing),
                missing, null, -1);

        List<InputValidator.ValidationIssue> issues = validator.validate(plan, outputDir);

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("does not exist")));
    }

    @Test
    void missingShardDir_reportsError() throws IOException {
        Path outputDir = tempDir.resolve("output");
        Path missingShard = tempDir.resolve("missing-shard");

        ConversionPlan plan = new ConversionPlan(
                InputFormat.SOLRCLOUD_FULL,
                Collections.singletonList(missingShard),
                tempDir, null, -1);

        List<InputValidator.ValidationIssue> issues = validator.validate(plan, outputDir);

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("Shard directory missing")));
    }

    @Test
    void outputDirWithExistingFiles_reportsWarning() throws IOException {
        Path shardDir = createMinimalLuceneIndex(tempDir.resolve("shard0"));
        Path outputDir = Files.createDirectory(tempDir.resolve("output"));
        Files.createFile(outputDir.resolve("some-file"));

        ConversionPlan plan = new ConversionPlan(
                InputFormat.STANDALONE,
                Collections.singletonList(shardDir),
                tempDir, null, -1);

        List<InputValidator.ValidationIssue> issues = validator.validate(plan, outputDir);

        assertTrue(issues.stream().anyMatch(i -> !i.isError() && i.getMessage().contains("already has")));
    }

    @Test
    void emptyIndex_reportsWarning() throws IOException {
        Path shardDir = createEmptyLuceneIndex(tempDir.resolve("shard0"));
        Path outputDir = tempDir.resolve("output");

        ConversionPlan plan = new ConversionPlan(
                InputFormat.STANDALONE,
                Collections.singletonList(shardDir),
                tempDir, null, -1);

        List<InputValidator.ValidationIssue> issues = validator.validate(plan, outputDir);

        assertTrue(issues.stream().anyMatch(i -> !i.isError() && i.getMessage().contains("0 documents")));
    }

    @Test
    void validationIssue_errorFactory() {
        InputValidator.ValidationIssue issue = InputValidator.ValidationIssue.error("test error");

        assertTrue(issue.isError());
        assertEquals(InputValidator.ValidationIssue.Severity.ERROR, issue.getSeverity());
        assertEquals("test error", issue.getMessage());
        assertTrue(issue.toString().contains("ERROR"));
    }

    @Test
    void validationIssue_warnFactory() {
        InputValidator.ValidationIssue issue = InputValidator.ValidationIssue.warn("test warning");

        assertFalse(issue.isError());
        assertEquals(InputValidator.ValidationIssue.Severity.WARN, issue.getSeverity());
        assertEquals("test warning", issue.getMessage());
        assertTrue(issue.toString().contains("WARN"));
    }

    // ── Phase 5: Negative / failure mode tests ──

    @Test
    void corruptedSegmentsFile_reportsError() throws IOException {
        Path shardDir = tempDir.resolve("corrupt-shard");
        Files.createDirectories(shardDir);
        Files.write(shardDir.resolve("segments_3"), "not a lucene file".getBytes());

        ConversionPlan plan = new ConversionPlan(
                InputFormat.STANDALONE,
                Collections.singletonList(shardDir),
                tempDir, null, -1);

        List<InputValidator.ValidationIssue> issues = validator.validate(plan, tempDir.resolve("output"));

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("cannot read segments")),
                "Expected error about unreadable segments but got: " + issues);
    }

    @Test
    void shardDirWithNoSegments_reportsError() throws IOException {
        Path shardDir = Files.createDirectory(tempDir.resolve("empty-shard"));
        Files.createFile(shardDir.resolve("some-data-file.dat"));

        ConversionPlan plan = new ConversionPlan(
                InputFormat.STANDALONE,
                Collections.singletonList(shardDir),
                tempDir, null, -1);

        List<InputValidator.ValidationIssue> issues = validator.validate(plan, tempDir.resolve("output"));

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("cannot read segments")),
                "Expected error about missing segments but got: " + issues);
    }

    @Test
    void zeroByteSegmentsFile_reportsError() throws IOException {
        Path shardDir = tempDir.resolve("zero-shard");
        Files.createDirectories(shardDir);
        Files.createFile(shardDir.resolve("segments_1"));

        ConversionPlan plan = new ConversionPlan(
                InputFormat.STANDALONE,
                Collections.singletonList(shardDir),
                tempDir, null, -1);

        List<InputValidator.ValidationIssue> issues = validator.validate(plan, tempDir.resolve("output"));

        assertTrue(issues.stream().anyMatch(i -> i.isError() && i.getMessage().contains("cannot read segments")),
                "Expected error about unreadable segments but got: " + issues);
    }

    private Path createMinimalLuceneIndex(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (NIOFSDirectory directory = new NIOFSDirectory(dir);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            Document doc = new Document();
            doc.add(new StringField("id", "1", Field.Store.YES));
            writer.addDocument(doc);
            writer.commit();
        }
        return dir;
    }

    private Path createEmptyLuceneIndex(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (NIOFSDirectory directory = new NIOFSDirectory(dir);
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.commit();
        }
        return dir;
    }
}
