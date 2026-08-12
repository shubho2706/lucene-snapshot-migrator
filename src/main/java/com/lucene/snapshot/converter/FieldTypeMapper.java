package com.lucene.snapshot.converter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps Solr field types to OpenSearch field types.
 *
 * Supports three generations of Solr numeric fields:
 * <ul>
 *   <li><b>Point fields</b> (Solr 7+): IntPointField, LongPointField, etc.</li>
 *   <li><b>Trie fields</b> (Solr 1.4–6.x, deprecated in 7.x): TrieIntField, TrieLongField, etc.</li>
 *   <li><b>Legacy fields</b> (Solr 1.x–3.x): IntField, LongField, etc.</li>
 * </ul>
 *
 * This class has no OpenSearch SDK dependencies and can be tested directly.
 */
class FieldTypeMapper {

    private static final Logger logger = LoggerFactory.getLogger(FieldTypeMapper.class);

    /**
     * Lookup table: Solr class substring → OpenSearch type.
     * Order matters — more specific entries (e.g. IntPointField) must come
     * before less specific ones (e.g. IntField) since matching uses contains().
     */
    private static final Map<String, String> CLASS_TO_OS_TYPE = new LinkedHashMap<>();

    static {
        // Modern Point fields (Solr 7+)
        CLASS_TO_OS_TYPE.put("IntPointField", "integer");
        CLASS_TO_OS_TYPE.put("LongPointField", "long");
        CLASS_TO_OS_TYPE.put("FloatPointField", "float");
        CLASS_TO_OS_TYPE.put("DoublePointField", "double");
        CLASS_TO_OS_TYPE.put("DatePointField", "date");

        // Legacy Trie fields (Solr 1.4–6.x, deprecated in 7.x)
        CLASS_TO_OS_TYPE.put("TrieIntField", "integer");
        CLASS_TO_OS_TYPE.put("TrieLongField", "long");
        CLASS_TO_OS_TYPE.put("TrieFloatField", "float");
        CLASS_TO_OS_TYPE.put("TrieDoubleField", "double");
        CLASS_TO_OS_TYPE.put("TrieDateField", "date");

        // Very legacy fields (Solr 1.x–3.x)
        CLASS_TO_OS_TYPE.put("IntField", "integer");
        CLASS_TO_OS_TYPE.put("LongField", "long");
        CLASS_TO_OS_TYPE.put("FloatField", "float");
        CLASS_TO_OS_TYPE.put("DoubleField", "double");
        CLASS_TO_OS_TYPE.put("DateField", "date");

        // String & Text
        CLASS_TO_OS_TYPE.put("StrField", "keyword");
        CLASS_TO_OS_TYPE.put("TextField", "text");

        // Boolean & Binary
        CLASS_TO_OS_TYPE.put("BoolField", "boolean");
        CLASS_TO_OS_TYPE.put("BinaryField", "binary");

        // Spatial
        CLASS_TO_OS_TYPE.put("LatLonPointSpatialField", "geo_point");
        CLASS_TO_OS_TYPE.put("SpatialRecursivePrefixTreeFieldType", "geo_point");
    }

    /**
     * Type name shortcuts — maps well-known Solr type names directly to OS types
     * without needing to resolve the class. E.g. type="pint" → integer.
     */
    private static final Map<String, String> TYPE_NAME_TO_OS_TYPE = Map.ofEntries(
            Map.entry("string", "keyword"),
            Map.entry("pint", "integer"),
            Map.entry("int", "integer"),
            Map.entry("plong", "long"),
            Map.entry("long", "long"),
            Map.entry("pfloat", "float"),
            Map.entry("float", "float"),
            Map.entry("pdouble", "double"),
            Map.entry("double", "double"),
            Map.entry("pdate", "date"),
            Map.entry("date", "date"),
            Map.entry("boolean", "boolean"),
            Map.entry("binary", "binary")
    );

    private FieldTypeMapper() {}

    /**
     * Parse fieldType declarations from a Solr schema XML document.
     *
     * @param doc parsed Solr schema XML
     * @return map of fieldType name → fully-qualified class name
     */
    static Map<String, String> parseFieldTypes(Document doc) {
        Map<String, String> fieldTypes = new HashMap<>();
        NodeList typeNodes = doc.getElementsByTagName("fieldType");
        for (int i = 0; i < typeNodes.getLength(); i++) {
            Element typeElement = (Element) typeNodes.item(i);
            fieldTypes.put(typeElement.getAttribute("name"), typeElement.getAttribute("class"));
        }
        return fieldTypes;
    }

    /**
     * Convert a Solr field type to an OpenSearch field mapping.
     *
     * Resolution order:
     * <ol>
     *   <li>text_ prefix → "text"</li>
     *   <li>Well-known type name shortcut (e.g. "pint" → "integer")</li>
     *   <li>Class name substring match against lookup table</li>
     *   <li>Default: "keyword" with a warning</li>
     * </ol>
     *
     * @param solrType   the Solr field type name (e.g. "pint", "text_general")
     * @param fieldTypes map of type name → class from schema
     * @return OpenSearch mapping, e.g. {"type": "integer"}
     */
    static Map<String, Object> convertFieldType(String solrType, Map<String, String> fieldTypes) {
        Map<String, Object> mapping = new HashMap<>();

        // 1. text_ prefix covers text_general, text_en, text_ws, etc.
        if (solrType.startsWith("text_")) {
            mapping.put("type", "text");
            return mapping;
        }

        // 2. Well-known type name shortcut
        String osType = TYPE_NAME_TO_OS_TYPE.get(solrType);
        if (osType != null) {
            mapping.put("type", osType);
            return mapping;
        }

        // 3. Class name substring match
        String solrClass = fieldTypes.getOrDefault(solrType, "");
        for (Map.Entry<String, String> entry : CLASS_TO_OS_TYPE.entrySet()) {
            if (solrClass.contains(entry.getKey())) {
                mapping.put("type", entry.getValue());
                return mapping;
            }
        }

        // 4. Default to keyword
        logger.warn("    Unknown Solr type '{}' (class: {}), defaulting to keyword", solrType, solrClass);
        mapping.put("type", "keyword");
        return mapping;
    }
}
