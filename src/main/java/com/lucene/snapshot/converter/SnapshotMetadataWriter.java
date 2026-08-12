package com.lucene.snapshot.converter;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.store.OutputStreamIndexOutput;
import org.apache.lucene.store.IndexOutput;
import org.opensearch.Version;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.MappingMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.common.UUIDs;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.ToXContent;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.snapshots.SnapshotId;
import org.opensearch.snapshots.SnapshotState;
import org.opensearch.repositories.IndexId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class SnapshotMetadataWriter {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotMetadataWriter.class);

    private final String snapshotUuid;
    private final String indexUuid;
    private final String metadataIdentifierUuid;
    private final String indexName;
    private final Path schemaPath;
    private final int shardCount;
    private final List<ShardConversionResult> shardResults;
    private final List<String> shardGenerationUuids;
    private final List<String> shardAllocationUuids;
    private final int numberOfReplicas;
    private final Version opensearchVersion = Version.CURRENT;

    private static final String SNAPSHOT_CODEC = "snapshot";
    private static final String METADATA_CODEC = "metadata";
    private static final String INDEX_METADATA_CODEC = "index-metadata";
    private static final String SHARD_SNAPSHOTS_CODEC = "snapshots";
    private static final int CODEC_VERSION = 1;

    private static final ToXContent.Params SNAPSHOT_PARAMS = new ToXContent.MapParams(
        Collections.singletonMap("context_mode", "SNAPSHOT")
    );

    private static final ToXContent.Params GATEWAY_PARAMS = new ToXContent.MapParams(
        Collections.singletonMap(Metadata.CONTEXT_MODE_PARAM, Metadata.CONTEXT_MODE_GATEWAY)
    );

    public SnapshotMetadataWriter(String snapshotUuid, String indexUuid, String indexName,
                                   Path schemaPath, List<ShardConversionResult> shardResults,
                                   int numberOfReplicas) {
        this.snapshotUuid = snapshotUuid;
        this.indexUuid = indexUuid;
        this.schemaPath = schemaPath;
        this.metadataIdentifierUuid = UUIDs.base64UUID();
        this.indexName = indexName;
        this.numberOfReplicas = numberOfReplicas;
        this.shardResults = shardResults;
        this.shardCount = shardResults.size();

        this.shardGenerationUuids = new ArrayList<>();
        this.shardAllocationUuids = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            shardGenerationUuids.add(UUIDs.base64UUID());
            shardAllocationUuids.add(UUIDs.randomBase64UUID());
        }
    }

    public int generateMetadata(Path outputDir) throws IOException {
        int filesCreated = 0;

        Files.createDirectories(outputDir);
        Path indicesDir = outputDir.resolve("indices").resolve(indexUuid);

        SnapshotId snapshotId = new SnapshotId(indexName + "-snapshot", snapshotUuid);
        IndexId indexId = new IndexId(indexName, indexUuid);

        filesCreated += writeSnapshotInfo(outputDir, snapshotId, indexId);
        filesCreated += writeGlobalMetadata(outputDir, snapshotUuid);
        filesCreated += writeRepositoryIndex(outputDir, snapshotId, indexId);
        filesCreated += writeIndexMetadata(indicesDir, indexId);

        for (int shardId = 0; shardId < shardCount; shardId++) {
            Path shardDir = shardResults.get(shardId).getOutputDir();
            filesCreated += writeShardSnapshot(shardDir, snapshotId, shardResults.get(shardId));
            filesCreated += writeShardIndex(shardDir, shardGenerationUuids.get(shardId),
                    snapshotId, shardResults.get(shardId));
        }

        logger.info("    Created {} metadata files ({} shards)", filesCreated, shardCount);
        return filesCreated;
    }

    private int writeSnapshotInfo(Path outputDir, SnapshotId snapshotId, IndexId indexId) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        XContentBuilder builder = XContentFactory.smileBuilder(outputStream);

        builder.startObject();
        builder.startObject("snapshot");
        builder.field("name", snapshotId.getName());
        builder.field("uuid", snapshotId.getUUID());
        builder.field("version_id", opensearchVersion.id);
        builder.field("remote_store_index_shallow_copy", false);

        builder.startArray("indices");
        builder.value(indexId.getName());
        builder.endArray();

        builder.startArray("data_streams");
        builder.endArray();

        builder.field("state", SnapshotState.SUCCESS.toString());
        builder.field("include_global_state", false);
        builder.nullField("metadata");
        builder.field("start_time", System.currentTimeMillis());
        builder.field("end_time", System.currentTimeMillis());
        builder.field("total_shards", shardCount);
        builder.field("successful_shards", shardCount);
        builder.startArray("failures");
        builder.endArray();
        builder.endObject();
        builder.endObject();
        builder.close();

        byte[] wrappedData = wrapWithCodec(outputStream.toByteArray(), SNAPSHOT_CODEC);
        Files.write(outputDir.resolve("snap-" + snapshotUuid + ".dat"), wrappedData);
        return 1;
    }

    private int writeGlobalMetadata(Path outputDir, String clusterUuid) throws IOException {
        Metadata metadata = Metadata.builder()
            .clusterUUID(clusterUuid)
            .version(1L)
            .build();

        byte[] smileData = serializeToSmile(metadata, SNAPSHOT_PARAMS);
        byte[] wrappedData = wrapWithCodec(smileData, METADATA_CODEC);
        Files.write(outputDir.resolve("meta-" + clusterUuid + ".dat"), wrappedData);
        return 1;
    }

    private int writeRepositoryIndex(Path outputDir, SnapshotId snapshotId, IndexId indexId) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        XContentBuilder builder = XContentFactory.jsonBuilder(outputStream);
        builder.startObject();

        builder.startArray("snapshots");
        builder.startObject();
        builder.field("name", snapshotId.getName());
        builder.field("uuid", snapshotId.getUUID());
        builder.field("state", 1);
        builder.startObject("index_metadata_lookup");
        String metadataKey = indexId.getId() + "-_na_-1-1-1";
        builder.field(indexId.getId(), metadataKey);
        builder.endObject();
        builder.field("version", opensearchVersion.toString());
        builder.endObject();
        builder.endArray();

        builder.startObject("indices");
        builder.startObject(indexId.getName());
        builder.field("id", indexId.getId());
        builder.array("snapshots", snapshotId.getUUID());
        builder.startArray("shard_generations");
        for (String genUuid : shardGenerationUuids) {
            builder.value(genUuid);
        }
        builder.endArray();
        builder.endObject();
        builder.endObject();

        builder.startObject("index_metadata_identifiers");
        builder.field(metadataKey, metadataIdentifierUuid);
        builder.endObject();

        builder.endObject();
        builder.close();

        Files.write(outputDir.resolve("index-0"), outputStream.toByteArray());

        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(0L);
        Files.write(outputDir.resolve("index.latest"), buf.array());

        return 2;
    }

    private int writeIndexMetadata(Path indicesDir, IndexId indexId) throws IOException {
        Map<String, Object> properties = new HashMap<>();
        if (schemaPath != null && Files.exists(schemaPath)) {
            properties = parseSolrSchema(schemaPath);
        } else {
            logger.warn("    No schema found, creating index with empty mappings");
        }

        Settings.Builder settingsBuilder = Settings.builder()
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, shardCount)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, numberOfReplicas)
            .put(IndexMetadata.SETTING_VERSION_CREATED, opensearchVersion)
            .put(IndexMetadata.SETTING_INDEX_UUID, indexId.getId());

        IndexMetadata.Builder builder = IndexMetadata.builder(indexId.getName())
            .settings(settingsBuilder.build());

        if (!properties.isEmpty()) {
            Map<String, Object> propertiesWrapper = new HashMap<>();
            propertiesWrapper.put("properties", properties);

            Map<String, Object> docTypeWrapper = new HashMap<>();
            docTypeWrapper.put("_doc", propertiesWrapper);

            MappingMetadata mappingMetadata = new MappingMetadata("_doc", docTypeWrapper);
            builder.putMapping(mappingMetadata);
            logger.info("    Index metadata: {} shards, {} field mappings", shardCount, properties.size());
        }

        for (int shardId = 0; shardId < shardCount; shardId++) {
            builder.putInSyncAllocationIds(shardId,
                    Collections.singleton(shardAllocationUuids.get(shardId)));
        }

        for (int shardId = 0; shardId < shardCount; shardId++) {
            builder.primaryTerm(shardId, 1L);
        }

        IndexMetadata indexMetadata = builder.build();

        byte[] smileData = serializeToSmile(indexMetadata, GATEWAY_PARAMS);
        byte[] wrappedData = wrapWithCodec(smileData, INDEX_METADATA_CODEC);
        Files.write(indicesDir.resolve("meta-" + metadataIdentifierUuid + ".dat"), wrappedData);
        return 1;
    }

    private int writeShardSnapshot(Path shardDir, SnapshotId snapshotId,
                                    ShardConversionResult shardResult) throws IOException {
        List<ShardConversionResult.LuceneFileMetadata> files = shardResult.getFiles();
        long totalSize = shardResult.getTotalBytes();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        XContentBuilder builder = XContentFactory.smileBuilder(outputStream);

        builder.startObject();
        builder.field("name", snapshotId.getName());
        builder.field("index_version", opensearchVersion.id);
        builder.field("start_time", System.currentTimeMillis());
        builder.field("time", 0);
        builder.field("number_of_files", files.size());
        builder.field("total_size", totalSize);

        builder.startArray("files");
        for (ShardConversionResult.LuceneFileMetadata fileMeta : files) {
            builder.startObject();
            builder.field("name", fileMeta.getName());
            builder.field("physical_name", fileMeta.getName());
            builder.field("length", fileMeta.getLength());
            builder.field("written_by", fileMeta.getWrittenBy());
            builder.field("checksum", fileMeta.getChecksum());
            builder.field("meta_hash", fileMeta.getMetaHash());
            builder.endObject();
        }
        builder.endArray();
        builder.endObject();
        builder.close();

        byte[] wrappedData = wrapWithCodec(outputStream.toByteArray(), SNAPSHOT_CODEC);
        Files.write(shardDir.resolve("snap-" + snapshotId.getUUID() + ".dat"), wrappedData);
        return 1;
    }

    private int writeShardIndex(Path shardDir, String generationUuid,
                                 SnapshotId snapshotId,
                                 ShardConversionResult shardResult) throws IOException {
        List<ShardConversionResult.LuceneFileMetadata> files = shardResult.getFiles();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        XContentBuilder builder = XContentFactory.smileBuilder(outputStream);

        builder.startObject();
        builder.startArray("files");
        for (ShardConversionResult.LuceneFileMetadata fileMeta : files) {
            builder.startObject();
            builder.field("name", fileMeta.getName());
            builder.field("physical_name", fileMeta.getName());
            builder.field("length", fileMeta.getLength());
            builder.field("written_by", fileMeta.getWrittenBy());
            builder.field("checksum", fileMeta.getChecksum());
            builder.field("meta_hash", fileMeta.getMetaHash());
            builder.endObject();
        }
        builder.endArray();

        builder.startObject("snapshots");
        builder.startObject(snapshotId.getUUID());
        builder.startArray("files");
        for (ShardConversionResult.LuceneFileMetadata fileMeta : files) {
            builder.startObject();
            builder.field("name", fileMeta.getName());
            builder.endObject();
        }
        builder.endArray();
        builder.endObject();
        builder.endObject();

        builder.endObject();
        builder.close();

        byte[] wrappedData = wrapWithCodec(outputStream.toByteArray(), SHARD_SNAPSHOTS_CODEC);
        Files.write(shardDir.resolve("index-" + generationUuid), wrappedData);
        return 1;
    }

    private byte[] serializeToSmile(ToXContent obj, ToXContent.Params params) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        XContentBuilder builder = XContentFactory.smileBuilder(outputStream);
        builder.startObject();
        obj.toXContent(builder, params);
        builder.endObject();
        builder.close();
        return outputStream.toByteArray();
    }

    private byte[] wrapWithCodec(byte[] smileData, String codecName) throws IOException {
        ByteArrayOutputStream wrappedStream = new ByteArrayOutputStream();
        IndexOutput output = new OutputStreamIndexOutput(
            "wrapped-output", "wrapped-output",
            wrappedStream, smileData.length + 100
        );
        CodecUtil.writeHeader(output, codecName, CODEC_VERSION);
        output.writeBytes(smileData, smileData.length);
        CodecUtil.writeFooter(output);
        output.close();
        return wrappedStream.toByteArray();
    }

    private Map<String, Object> parseSolrSchema(Path schemaPath) throws IOException {
        logger.info("    Parsing Solr schema: {}", schemaPath);
        Map<String, Object> properties = new HashMap<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = factory.newDocumentBuilder();
            Document doc = docBuilder.parse(schemaPath.toFile());
            doc.getDocumentElement().normalize();

            Map<String, String> fieldTypes = FieldTypeMapper.parseFieldTypes(doc);

            NodeList fieldNodes = doc.getElementsByTagName("field");
            for (int i = 0; i < fieldNodes.getLength(); i++) {
                Element fieldElement = (Element) fieldNodes.item(i);
                String fieldName = fieldElement.getAttribute("name");
                String fieldType = fieldElement.getAttribute("type");

                if (fieldName.startsWith("_") && !fieldName.equals("_text_")) {
                    continue;
                }

                Map<String, Object> fieldMapping = FieldTypeMapper.convertFieldType(fieldType, fieldTypes);
                properties.put(fieldName, fieldMapping);
            }

            logger.info("    Parsed {} fields from schema", properties.size());

        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse Solr schema", e);
        }

        return properties;
    }
}

