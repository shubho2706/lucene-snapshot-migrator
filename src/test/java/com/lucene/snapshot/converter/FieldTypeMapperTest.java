package com.lucene.snapshot.converter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FieldTypeMapper} — all field type mapping logic.
 *
 * No OpenSearch SDK dependency — runs in surefire without uber-JAR.
 */
class FieldTypeMapperTest {

    // ── Modern Point fields (Solr 7+) ──

    @ParameterizedTest(name = "class={0} → {1}")
    @CsvSource({
            "solr.IntPointField,    integer",
            "solr.LongPointField,   long",
            "solr.FloatPointField,  float",
            "solr.DoublePointField, double",
            "solr.DatePointField,   date"
    })
    void pointField_mapsCorrectly(String solrClass, String expectedOsType) {
        Map<String, String> fieldTypes = Map.of("mytype", solrClass);

        Map<String, Object> result = FieldTypeMapper.convertFieldType("mytype", fieldTypes);

        assertEquals(expectedOsType, result.get("type"));
    }

    // ── Legacy Trie fields (Solr 1.4–6.x) ──

    @ParameterizedTest(name = "class={0} → {1}")
    @CsvSource({
            "solr.TrieIntField,    integer",
            "solr.TrieLongField,   long",
            "solr.TrieFloatField,  float",
            "solr.TrieDoubleField, double",
            "solr.TrieDateField,   date"
    })
    void trieField_mapsCorrectly(String solrClass, String expectedOsType) {
        Map<String, String> fieldTypes = Map.of("mytype", solrClass);

        Map<String, Object> result = FieldTypeMapper.convertFieldType("mytype", fieldTypes);

        assertEquals(expectedOsType, result.get("type"));
    }

    // ── Very legacy fields (Solr 1.x–3.x) ──

    @ParameterizedTest(name = "class={0} → {1}")
    @CsvSource({
            "solr.IntField,    integer",
            "solr.LongField,   long",
            "solr.FloatField,  float",
            "solr.DoubleField, double",
            "solr.DateField,   date"
    })
    void legacyField_mapsCorrectly(String solrClass, String expectedOsType) {
        Map<String, String> fieldTypes = Map.of("mytype", solrClass);

        Map<String, Object> result = FieldTypeMapper.convertFieldType("mytype", fieldTypes);

        assertEquals(expectedOsType, result.get("type"));
    }

    // ── String, Text, Boolean, Binary ──

    @Test
    void strField_mapsToKeyword() {
        Map<String, String> fieldTypes = Map.of("mystr", "solr.StrField");

        assertEquals("keyword", FieldTypeMapper.convertFieldType("mystr", fieldTypes).get("type"));
    }

    @Test
    void textField_mapsToText() {
        Map<String, String> fieldTypes = Map.of("mytxt", "solr.TextField");

        assertEquals("text", FieldTypeMapper.convertFieldType("mytxt", fieldTypes).get("type"));
    }

    @Test
    void boolField_mapsToBoolean() {
        Map<String, String> fieldTypes = Map.of("mybool", "solr.BoolField");

        assertEquals("boolean", FieldTypeMapper.convertFieldType("mybool", fieldTypes).get("type"));
    }

    @Test
    void binaryField_mapsToBinary() {
        Map<String, String> fieldTypes = Map.of("mybin", "solr.BinaryField");

        assertEquals("binary", FieldTypeMapper.convertFieldType("mybin", fieldTypes).get("type"));
    }

    // ── Spatial ──

    @Test
    void latLonPointSpatialField_mapsToGeoPoint() {
        Map<String, String> fieldTypes = Map.of("loc", "solr.LatLonPointSpatialField");

        assertEquals("geo_point", FieldTypeMapper.convertFieldType("loc", fieldTypes).get("type"));
    }

    @Test
    void spatialRecursivePrefixTreeField_mapsToGeoPoint() {
        Map<String, String> fieldTypes = Map.of("loc_rpt", "solr.SpatialRecursivePrefixTreeFieldType");

        assertEquals("geo_point", FieldTypeMapper.convertFieldType("loc_rpt", fieldTypes).get("type"));
    }

    // ── Type name shortcuts (resolved without looking up class) ──

    @ParameterizedTest(name = "typeName={0} → {1}")
    @CsvSource({
            "string,  keyword",
            "pint,    integer",
            "int,     integer",
            "plong,   long",
            "long,    long",
            "pfloat,  float",
            "float,   float",
            "pdouble, double",
            "double,  double",
            "pdate,   date",
            "date,    date",
            "boolean, boolean",
            "binary,  binary"
    })
    void typeNameShortcut_mapsCorrectly(String typeName, String expectedOsType) {
        Map<String, String> emptyFieldTypes = Collections.emptyMap();

        Map<String, Object> result = FieldTypeMapper.convertFieldType(typeName, emptyFieldTypes);

        assertEquals(expectedOsType, result.get("type"));
    }

    // ── text_ prefix ──

    @ParameterizedTest(name = "typeName={0} → text")
    @CsvSource({
            "text_general",
            "text_en",
            "text_ws",
            "text_cjk",
            "text_custom_analyzer"
    })
    void textPrefixTypes_mapToText(String typeName) {
        Map<String, String> emptyFieldTypes = Collections.emptyMap();

        assertEquals("text", FieldTypeMapper.convertFieldType(typeName, emptyFieldTypes).get("type"));
    }

    // ── Edge cases ──

    @Test
    void unknownClass_defaultsToKeyword() {
        Map<String, String> fieldTypes = Map.of("custom", "com.example.CustomFieldType");

        assertEquals("keyword", FieldTypeMapper.convertFieldType("custom", fieldTypes).get("type"));
    }

    @Test
    void unknownTypeNameWithNoClass_defaultsToKeyword() {
        Map<String, String> emptyFieldTypes = Collections.emptyMap();

        assertEquals("keyword", FieldTypeMapper.convertFieldType("totally_unknown", emptyFieldTypes).get("type"));
    }

    @Test
    void fullyQualifiedTrieClass_mapsCorrectly() {
        Map<String, String> fieldTypes = Map.of(
                "my_int", "org.apache.solr.schema.TrieIntField"
        );

        assertEquals("integer", FieldTypeMapper.convertFieldType("my_int", fieldTypes).get("type"));
    }

    @Test
    void fullyQualifiedPointClass_mapsCorrectly() {
        Map<String, String> fieldTypes = Map.of(
                "my_long", "org.apache.solr.schema.LongPointField"
        );

        assertEquals("long", FieldTypeMapper.convertFieldType("my_long", fieldTypes).get("type"));
    }

    // ── parseFieldTypes() ──

    @Test
    void parseFieldTypes_extractsAllTypes() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>\n" +
                "<schema name='test' version='1.6'>\n" +
                "  <fieldType name='string' class='solr.StrField'/>\n" +
                "  <fieldType name='text_general' class='solr.TextField'/>\n" +
                "  <fieldType name='pint' class='solr.IntPointField'/>\n" +
                "  <fieldType name='trie_int' class='solr.TrieIntField'/>\n" +
                "</schema>";

        Document doc = parseXml(xml);
        Map<String, String> fieldTypes = FieldTypeMapper.parseFieldTypes(doc);

        assertEquals(4, fieldTypes.size());
        assertEquals("solr.StrField", fieldTypes.get("string"));
        assertEquals("solr.TextField", fieldTypes.get("text_general"));
        assertEquals("solr.IntPointField", fieldTypes.get("pint"));
        assertEquals("solr.TrieIntField", fieldTypes.get("trie_int"));
    }

    @Test
    void parseFieldTypes_handlesEmptySchema() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>\n" +
                "<schema name='test' version='1.6'>\n" +
                "</schema>";

        Document doc = parseXml(xml);
        Map<String, String> fieldTypes = FieldTypeMapper.parseFieldTypes(doc);

        assertTrue(fieldTypes.isEmpty());
    }

    @Test
    void parseFieldTypes_handlesSchemaWithFieldsButNoFieldTypes() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>\n" +
                "<schema name='test' version='1.6'>\n" +
                "  <field name='id' type='string'/>\n" +
                "</schema>";

        Document doc = parseXml(xml);
        Map<String, String> fieldTypes = FieldTypeMapper.parseFieldTypes(doc);

        assertTrue(fieldTypes.isEmpty());
    }

    // ── Integration: full schema with mixed generations ──

    @Test
    void mixedGenerationSchema_allFieldsMappedCorrectly() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>\n" +
                "<schema name='test' version='1.6'>\n" +
                "  <fieldType name='string' class='solr.StrField'/>\n" +
                "  <fieldType name='text_general' class='solr.TextField'/>\n" +
                "  <fieldType name='pint' class='solr.IntPointField'/>\n" +
                "  <fieldType name='trie_long' class='solr.TrieLongField'/>\n" +
                "  <fieldType name='old_float' class='solr.FloatField'/>\n" +
                "  <fieldType name='trie_date' class='solr.TrieDateField'/>\n" +
                "  <fieldType name='location' class='solr.LatLonPointSpatialField'/>\n" +
                "  <fieldType name='flag' class='solr.BoolField'/>\n" +
                "</schema>";

        Document doc = parseXml(xml);
        Map<String, String> fieldTypes = FieldTypeMapper.parseFieldTypes(doc);

        assertEquals("keyword", FieldTypeMapper.convertFieldType("string", fieldTypes).get("type"));
        assertEquals("text", FieldTypeMapper.convertFieldType("text_general", fieldTypes).get("type"));
        assertEquals("integer", FieldTypeMapper.convertFieldType("pint", fieldTypes).get("type"));
        assertEquals("long", FieldTypeMapper.convertFieldType("trie_long", fieldTypes).get("type"));
        assertEquals("float", FieldTypeMapper.convertFieldType("old_float", fieldTypes).get("type"));
        assertEquals("date", FieldTypeMapper.convertFieldType("trie_date", fieldTypes).get("type"));
        assertEquals("geo_point", FieldTypeMapper.convertFieldType("location", fieldTypes).get("type"));
        assertEquals("boolean", FieldTypeMapper.convertFieldType("flag", fieldTypes).get("type"));
    }

    // ── Helper ──

    private Document parseXml(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
