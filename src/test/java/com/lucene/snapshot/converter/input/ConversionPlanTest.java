package com.lucene.snapshot.converter.input;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ConversionPlanTest {

    @Test
    void gettersReturnConstructorValues() {
        Path shard1 = Path.of("/data/snapshot.shard1");
        Path shard2 = Path.of("/data/snapshot.shard2");
        Path input = Path.of("/data");
        Path schema = Path.of("/data/zk_backup/configs/_default/managed-schema.xml");

        ConversionPlan plan = new ConversionPlan(
                InputFormat.SOLRCLOUD_FULL,
                Arrays.asList(shard1, shard2),
                input, schema, 3);

        assertEquals(InputFormat.SOLRCLOUD_FULL, plan.getFormat());
        assertEquals(2, plan.getShardCount());
        assertEquals(2, plan.getShardPaths().size());
        assertEquals(shard1, plan.getShardPaths().get(0));
        assertEquals(shard2, plan.getShardPaths().get(1));
        assertEquals(input, plan.getInputDir());
        assertEquals(schema, plan.getSchemaPath());
        assertEquals(3, plan.getSolrReplicationFactor());
    }

    @Test
    void shardPathsListIsUnmodifiable() {
        ConversionPlan plan = new ConversionPlan(
                InputFormat.STANDALONE,
                Collections.singletonList(Path.of("/tmp")),
                Path.of("/tmp"), null, -1);

        assertThrows(UnsupportedOperationException.class,
                () -> plan.getShardPaths().add(Path.of("/x")));
    }

    @Test
    void osReplicas_fromSolrRF1_returns0() {
        ConversionPlan plan = makePlan(1);
        assertEquals(0, plan.getOsReplicasFromSolr());
    }

    @Test
    void osReplicas_fromSolrRF2_returns1() {
        ConversionPlan plan = makePlan(2);
        assertEquals(1, plan.getOsReplicasFromSolr());
    }

    @Test
    void osReplicas_fromSolrRF3_returns2() {
        ConversionPlan plan = makePlan(3);
        assertEquals(2, plan.getOsReplicasFromSolr());
    }

    @Test
    void osReplicas_unknownRF_returnsNegative1() {
        ConversionPlan plan = makePlan(-1);
        assertEquals(-1, plan.getOsReplicasFromSolr());
    }

    @Test
    void osReplicas_zeroRF_returnsNegative1() {
        ConversionPlan plan = makePlan(0);
        assertEquals(-1, plan.getOsReplicasFromSolr());
    }

    @Test
    void toString_withRF() {
        ConversionPlan plan = new ConversionPlan(
                InputFormat.SOLRCLOUD_FULL,
                Arrays.asList(Path.of("/a"), Path.of("/b")),
                Path.of("/data"), Path.of("/schema"), 3);

        String str = plan.toString();
        assertTrue(str.contains("SOLRCLOUD_FULL"));
        assertTrue(str.contains("shards=2"));
        assertTrue(str.contains("solrRF=3"));
    }

    @Test
    void toString_withoutRF() {
        ConversionPlan plan = new ConversionPlan(
                InputFormat.STANDALONE,
                Collections.singletonList(Path.of("/a")),
                Path.of("/data"), null, -1);

        String str = plan.toString();
        assertTrue(str.contains("STANDALONE"));
        assertTrue(str.contains("shards=1"));
        assertFalse(str.contains("solrRF"));
    }

    @Test
    void nullSchemaIsAllowed() {
        ConversionPlan plan = new ConversionPlan(
                InputFormat.STANDALONE,
                Collections.singletonList(Path.of("/a")),
                Path.of("/data"), null, -1);

        assertNull(plan.getSchemaPath());
    }

    private ConversionPlan makePlan(int solrRF) {
        return new ConversionPlan(
                InputFormat.STANDALONE,
                Collections.singletonList(Path.of("/tmp")),
                Path.of("/tmp"), null, solrRF);
    }
}
