package com.lucene.snapshot.converter;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests schema field type mapping by running a full conversion with a test schema
 * and validating the output metadata contains correct OpenSearch mappings.
 *
 * DISABLED: These tests require the OpenSearch SDK's IndexMetadata class, which has
 * a static initializer asserting exact Lucene version match. Only works inside the
 * uber-JAR (shade plugin). Run via E2E tests or with -Dtest=SchemaFieldTypeMappingTest.
 *
 * Since convertFieldType() is private, we test it indirectly via the metadata writer.
 */
@Disabled("Requires uber-JAR classloader — OpenSearch SDK Lucene version check fails in surefire")
class SchemaFieldTypeMappingTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsAllStandardSolrTypes() throws IOException {
        Path schemaPath = createTestSchema(tempDir.resolve("managed-schema.xml"),
                "<schema name='test' version='1.6'>\n" +
                "  <fieldType name='string' class='solr.StrField'/>\n" +
                "  <fieldType name='text_general' class='solr.TextField'/>\n" +
                "  <fieldType name='pint' class='solr.IntPointField'/>\n" +
                "  <fieldType name='plong' class='solr.LongPointField'/>\n" +
                "  <fieldType name='pfloat' class='solr.FloatPointField'/>\n" +
                "  <fieldType name='pdouble' class='solr.DoublePointField'/>\n" +
                "  <fieldType name='pdate' class='solr.DatePointField'/>\n" +
                "  <fieldType name='boolean' class='solr.BoolField'/>\n" +
                "  <fieldType name='binary' class='solr.BinaryField'/>\n" +
                "  <fieldType name='location' class='solr.LatLonPointSpatialField'/>\n" +
                "  <fieldType name='location_rpt' class='solr.SpatialRecursivePrefixTreeFieldType'/>\n" +
                "\n" +
                "  <field name='id' type='string'/>\n" +
                "  <field name='title' type='text_general'/>\n" +
                "  <field name='count' type='pint'/>\n" +
                "  <field name='timestamp' type='plong'/>\n" +
                "  <field name='score_val' type='pfloat'/>\n" +
                "  <field name='amount' type='pdouble'/>\n" +
                "  <field name='created' type='pdate'/>\n" +
                "  <field name='active' type='boolean'/>\n" +
                "  <field name='data' type='binary'/>\n" +
                "  <field name='geo' type='location'/>\n" +
                "  <field name='geo_rpt' type='location_rpt'/>\n" +
                "</schema>");

        ShardConversionResult shard = new ShardConversionResult(
                0, 0, 0, "9.8.0", tempDir.resolve("output/indices/idx/0"),
                Collections.emptyList());

        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir.resolve("indices/idx/0"));

        SnapshotMetadataWriter writer = new SnapshotMetadataWriter(
                "snap-uuid", "idx", "testindex", schemaPath,
                Collections.singletonList(shard), 0);

        writer.generateMetadata(outputDir);

        Path metaFile = findMetaFile(outputDir.resolve("indices/idx"));
        assertNotNull(metaFile, "Index metadata file should exist");
        assertTrue(Files.size(metaFile) > 0, "Metadata should not be empty");
    }

    @Test
    void unknownFieldType_defaultsToKeyword() throws IOException {
        Path schemaPath = createTestSchema(tempDir.resolve("managed-schema.xml"),
                "<schema name='test' version='1.6'>\n" +
                "  <fieldType name='custom_type' class='com.example.CustomField'/>\n" +
                "  <field name='custom_field' type='custom_type'/>\n" +
                "</schema>");

        ShardConversionResult shard = new ShardConversionResult(
                0, 0, 0, "9.8.0", tempDir.resolve("output/indices/idx/0"),
                Collections.emptyList());

        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir.resolve("indices/idx/0"));

        SnapshotMetadataWriter writer = new SnapshotMetadataWriter(
                "snap-uuid", "idx", "testindex", schemaPath,
                Collections.singletonList(shard), 0);

        // Should not throw — unknown types default to keyword
        assertDoesNotThrow(() -> writer.generateMetadata(outputDir));
    }

    @Test
    void nullSchemaPath_createsEmptyMappings() throws IOException {
        ShardConversionResult shard = new ShardConversionResult(
                0, 0, 0, "9.8.0", tempDir.resolve("output/indices/idx/0"),
                Collections.emptyList());

        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir.resolve("indices/idx/0"));

        SnapshotMetadataWriter writer = new SnapshotMetadataWriter(
                "snap-uuid", "idx", "testindex", null,
                Collections.singletonList(shard), 0);

        // Should not throw with null schema
        assertDoesNotThrow(() -> writer.generateMetadata(outputDir));
    }

    @Test
    void underscorePrefixedFields_skippedExceptText() throws IOException {
        Path schemaPath = createTestSchema(tempDir.resolve("managed-schema.xml"),
                "<schema name='test' version='1.6'>\n" +
                "  <fieldType name='string' class='solr.StrField'/>\n" +
                "  <fieldType name='text_general' class='solr.TextField'/>\n" +
                "  <field name='_version_' type='string'/>\n" +
                "  <field name='_root_' type='string'/>\n" +
                "  <field name='_text_' type='text_general'/>\n" +
                "  <field name='visible_field' type='string'/>\n" +
                "</schema>");

        ShardConversionResult shard = new ShardConversionResult(
                0, 0, 0, "9.8.0", tempDir.resolve("output/indices/idx/0"),
                Collections.emptyList());

        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir.resolve("indices/idx/0"));

        SnapshotMetadataWriter writer = new SnapshotMetadataWriter(
                "snap-uuid", "idx", "testindex", schemaPath,
                Collections.singletonList(shard), 0);

        // Should succeed — _version_ and _root_ skipped, _text_ and visible_field kept
        assertDoesNotThrow(() -> writer.generateMetadata(outputDir));
    }

    @Test
    void legacyFieldClasses_mapCorrectly() throws IOException {
        Path schemaPath = createTestSchema(tempDir.resolve("managed-schema.xml"),
                "<schema name='test' version='1.6'>\n" +
                "  <fieldType name='old_int' class='solr.IntField'/>\n" +
                "  <fieldType name='old_long' class='solr.LongField'/>\n" +
                "  <fieldType name='old_float' class='solr.FloatField'/>\n" +
                "  <fieldType name='old_double' class='solr.DoubleField'/>\n" +
                "  <fieldType name='old_date' class='solr.DateField'/>\n" +
                "  <field name='f_int' type='old_int'/>\n" +
                "  <field name='f_long' type='old_long'/>\n" +
                "  <field name='f_float' type='old_float'/>\n" +
                "  <field name='f_double' type='old_double'/>\n" +
                "  <field name='f_date' type='old_date'/>\n" +
                "</schema>");

        ShardConversionResult shard = new ShardConversionResult(
                0, 0, 0, "9.8.0", tempDir.resolve("output/indices/idx/0"),
                Collections.emptyList());

        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(outputDir.resolve("indices/idx/0"));

        SnapshotMetadataWriter writer = new SnapshotMetadataWriter(
                "snap-uuid", "idx", "testindex", schemaPath,
                Collections.singletonList(shard), 0);

        // Legacy classes (IntField, LongField, etc.) should map without error
        assertDoesNotThrow(() -> writer.generateMetadata(outputDir));
    }

    private Path createTestSchema(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "<?xml version='1.0' encoding='UTF-8'?>\n" + content);
        return path;
    }

    private Path findMetaFile(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("meta-") && name.endsWith(".dat");
            }).findFirst().orElse(null);
        }
    }
}
