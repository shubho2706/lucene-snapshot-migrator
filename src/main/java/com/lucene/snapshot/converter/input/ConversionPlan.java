package com.lucene.snapshot.converter.input;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

public class ConversionPlan {

    private final InputFormat format;
    private final List<Path> shardPaths;
    private final int shardCount;
    private final Path inputDir;
    private final Path schemaPath;
    private final int solrReplicationFactor;

    public ConversionPlan(InputFormat format, List<Path> shardPaths, Path inputDir, Path schemaPath,
                          int solrReplicationFactor) {
        this.format = format;
        this.shardPaths = Collections.unmodifiableList(shardPaths);
        this.shardCount = shardPaths.size();
        this.inputDir = inputDir;
        this.schemaPath = schemaPath;
        this.solrReplicationFactor = solrReplicationFactor;
    }

    public InputFormat getFormat() {
        return format;
    }

    public List<Path> getShardPaths() {
        return shardPaths;
    }

    public int getShardCount() {
        return shardCount;
    }

    public Path getInputDir() {
        return inputDir;
    }

    public Path getSchemaPath() {
        return schemaPath;
    }

    /**
     * Solr replicationFactor from collection_state.json.
     * Returns -1 if not found (standalone backup or missing file).
     *
     * Solr RF=1 means 1 copy (no replicas). OS equivalent: number_of_replicas=0
     * Solr RF=2 means 2 copies (1 replica).  OS equivalent: number_of_replicas=1
     * Conversion: os_replicas = solrReplicationFactor - 1
     */
    public int getSolrReplicationFactor() {
        return solrReplicationFactor;
    }

    /**
     * Returns the OS-equivalent number_of_replicas derived from Solr's replicationFactor.
     * Returns -1 if Solr RF is unknown.
     */
    public int getOsReplicasFromSolr() {
        return solrReplicationFactor > 0 ? solrReplicationFactor - 1 : -1;
    }

    @Override
    public String toString() {
        String rfInfo = solrReplicationFactor > 0
                ? ", solrRF=" + solrReplicationFactor
                : "";
        return String.format("ConversionPlan{format=%s, shards=%d%s, input=%s, schema=%s}",
                format, shardCount, rfInfo, inputDir, schemaPath);
    }
}
