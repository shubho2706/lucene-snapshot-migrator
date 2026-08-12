#!/bin/bash
set -e

# =============================================================================
# Lucene Snapshot Migrator — End-to-End Setup & Test
#
# This script sets up Solr + OpenSearch in Docker, indexes sample data,
# creates a Solr snapshot, builds the converter, runs the conversion,
# registers the snapshot in OpenSearch, restores it, and verifies the data.
#
# Prerequisites: Docker, Java 17+, Maven
# =============================================================================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SOLR_PORT=8983
OPENSEARCH_PORT=9200
SOLR_VERSION=9.4
OPENSEARCH_VERSION=2.11.1
COLLECTION_NAME=test_collection
SNAPSHOT_NAME="snapshot_$(date +%Y%m%d_%H%M%S)"
SNAPSHOT_OUTPUT="/tmp/solr-snapshots/$SNAPSHOT_NAME"
CONVERTED_OUTPUT="/tmp/opensearch-snapshot"
OS_REPO_PATH="/usr/share/opensearch/data/snapshots"

echo -e "${BLUE}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║         Lucene Snapshot Migrator — Setup & Test             ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

# ─── Step 1: Check prerequisites ────────────────────────────────────────────
echo -e "${YELLOW}[1/9]${NC} Checking prerequisites..."

if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}✗ Docker is not running. Please start Docker and try again.${NC}"
    exit 1
fi
echo -e "   ${GREEN}✓${NC} Docker"

if ! java -version 2>&1 | grep -q "17\|18\|19\|20\|21"; then
    echo -e "${RED}✗ Java 17+ required. Found: $(java -version 2>&1 | head -1)${NC}"
    exit 1
fi
echo -e "   ${GREEN}✓${NC} Java $(java -version 2>&1 | head -1 | cut -d'"' -f2)"

if ! mvn -version > /dev/null 2>&1; then
    echo -e "${RED}✗ Maven is not installed.${NC}"
    exit 1
fi
echo -e "   ${GREEN}✓${NC} Maven"
echo ""

# ─── Step 2: Start Solr + OpenSearch ────────────────────────────────────────
echo -e "${YELLOW}[2/9]${NC} Starting Solr ${SOLR_VERSION} + OpenSearch ${OPENSEARCH_VERSION} in Docker..."

docker stop solr-dev opensearch-dev 2>/dev/null || true
docker rm solr-dev opensearch-dev 2>/dev/null || true

docker run -d \
  --name solr-dev \
  -p $SOLR_PORT:8983 \
  -e SOLR_HEAP=512m \
  solr:$SOLR_VERSION \
  solr-precreate $COLLECTION_NAME > /dev/null

docker run -d \
  --name opensearch-dev \
  -p $OPENSEARCH_PORT:9200 \
  -e "discovery.type=single-node" \
  -e "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m" \
  -e "DISABLE_SECURITY_PLUGIN=true" \
  -e "bootstrap.memory_lock=true" \
  -e "path.repo=$OS_REPO_PATH" \
  --ulimit memlock=-1:-1 \
  --ulimit nofile=65536:65536 \
  opensearchproject/opensearch:$OPENSEARCH_VERSION > /dev/null

echo -n "   Waiting for Solr..."
for i in {1..30}; do
    if curl -s http://localhost:$SOLR_PORT/solr/admin/info/system > /dev/null 2>&1; then
        echo -e " ${GREEN}✓${NC}"
        break
    fi
    echo -n "."
    sleep 2
done

echo -n "   Waiting for OpenSearch..."
for i in {1..30}; do
    if curl -s http://localhost:$OPENSEARCH_PORT/_cluster/health > /dev/null 2>&1; then
        echo -e " ${GREEN}✓${NC}"
        break
    fi
    echo -n "."
    sleep 2
done
echo ""

# ─── Step 3: Index sample data ──────────────────────────────────────────────
echo -e "${YELLOW}[3/9]${NC} Indexing 10 sample documents into Solr..."

curl -s -X POST "http://localhost:$SOLR_PORT/solr/$COLLECTION_NAME/update?commit=true" \
  -H "Content-Type: application/json" \
  --data-binary '[
  {"id":"1","title":"Introduction to Solr","category":"tutorial","content":"Apache Solr is a powerful search platform built on Apache Lucene."},
  {"id":"2","title":"OpenSearch Overview","category":"tutorial","content":"OpenSearch is a community-driven, open source search and analytics suite."},
  {"id":"3","title":"Migration Guide","category":"guide","content":"This guide explains how to migrate from Solr to OpenSearch seamlessly."},
  {"id":"4","title":"Snapshot Best Practices","category":"guide","content":"Learn how to create and manage snapshots effectively."},
  {"id":"5","title":"Lucene Internals","category":"technical","content":"Deep dive into Lucene segment files and index structure."},
  {"id":"6","title":"Search Performance","category":"technical","content":"Optimize your search queries for better performance and scalability."},
  {"id":"7","title":"Data Modeling","category":"design","content":"Best practices for designing your search schema and data models."},
  {"id":"8","title":"Backup Strategies","category":"operations","content":"Different approaches to backing up your search infrastructure."},
  {"id":"9","title":"Monitoring Clusters","category":"operations","content":"How to monitor and maintain healthy search clusters."},
  {"id":"10","title":"Security Setup","category":"security","content":"Configuring authentication and authorization for your search platform."}
]' > /dev/null

TOTAL=$(curl -s "http://localhost:$SOLR_PORT/solr/$COLLECTION_NAME/select?q=*:*&rows=0" | grep -o '"numFound":[0-9]*' | cut -d: -f2)
echo -e "   ${GREEN}✓${NC} $TOTAL documents indexed"
echo ""

# ─── Step 4: Create Solr snapshot ────────────────────────────────────────────
echo -e "${YELLOW}[4/9]${NC} Creating Solr snapshot..."

mkdir -p $SNAPSHOT_OUTPUT

docker exec solr-dev curl -s "http://localhost:8983/solr/$COLLECTION_NAME/replication?command=backup&name=$SNAPSHOT_NAME&location=/var/solr/data" > /dev/null

echo -n "   Waiting for snapshot..."
sleep 5
for i in {1..30}; do
    SNAP_DIR=$(docker exec solr-dev find /var/solr/data -name "snapshot.$SNAPSHOT_NAME" -type d 2>/dev/null | head -1)
    if [ -n "$SNAP_DIR" ]; then
        echo -e " ${GREEN}✓${NC}"
        break
    fi
    echo -n "."
    sleep 2
done

docker cp solr-dev:$SNAP_DIR/. $SNAPSHOT_OUTPUT/ > /dev/null
echo -e "   ${GREEN}✓${NC} Snapshot exported to $SNAPSHOT_OUTPUT"
echo ""

# ─── Step 5: Export Solr schema ──────────────────────────────────────────────
echo -e "${YELLOW}[5/9]${NC} Exporting Solr schema..."

mkdir -p $SNAPSHOT_OUTPUT/conf
docker cp solr-dev:/var/solr/data/$COLLECTION_NAME/conf/managed-schema.xml $SNAPSHOT_OUTPUT/conf/ 2>/dev/null || \
  docker cp solr-dev:/var/solr/data/$COLLECTION_NAME/conf/schema.xml $SNAPSHOT_OUTPUT/conf/managed-schema.xml 2>/dev/null || true

echo -e "   ${GREEN}✓${NC} Schema exported"
echo ""

# ─── Step 6: Build converter ────────────────────────────────────────────────
echo -e "${YELLOW}[6/9]${NC} Building converter JAR..."

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
mvn clean package -q -DskipTests
JAR_PATH="$SCRIPT_DIR/target/lucene-snapshot-converter-1.0.0-SNAPSHOT-jar-with-dependencies.jar"

echo -e "   ${GREEN}✓${NC} Built: $JAR_PATH"
echo ""

# ─── Step 7: Run converter ──────────────────────────────────────────────────
echo -e "${YELLOW}[7/9]${NC} Converting Solr snapshot → OpenSearch format..."

rm -rf $CONVERTED_OUTPUT
java -jar "$JAR_PATH" "$SNAPSHOT_OUTPUT" "$CONVERTED_OUTPUT" "$COLLECTION_NAME"

echo -e "   ${GREEN}✓${NC} Converted snapshot at $CONVERTED_OUTPUT"
echo ""

# ─── Step 8: Register + Restore in OpenSearch ────────────────────────────────
echo -e "${YELLOW}[8/9]${NC} Restoring snapshot in OpenSearch..."

# Copy converted snapshot into OpenSearch container
docker exec opensearch-dev mkdir -p $OS_REPO_PATH
docker cp $CONVERTED_OUTPUT/. opensearch-dev:$OS_REPO_PATH/

# Register snapshot repository
curl -s -X PUT "http://localhost:$OPENSEARCH_PORT/_snapshot/migrated_repo" \
  -H "Content-Type: application/json" \
  -d "{\"type\":\"fs\",\"settings\":{\"location\":\"$OS_REPO_PATH\"}}" > /dev/null

# List snapshots to get the snapshot name
SNAP_INFO=$(curl -s "http://localhost:$OPENSEARCH_PORT/_snapshot/migrated_repo/_all")
SNAP_ID=$(echo "$SNAP_INFO" | grep -o '"snapshot":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$SNAP_ID" ]; then
    echo -e "   ${RED}✗ No snapshot found in repository${NC}"
    echo "   Debug: $SNAP_INFO"
    exit 1
fi

# Restore
curl -s -X POST "http://localhost:$OPENSEARCH_PORT/_snapshot/migrated_repo/$SNAP_ID/_restore" \
  -H "Content-Type: application/json" \
  -d '{"indices":"*","include_global_state":false}' > /dev/null

echo -n "   Waiting for restore..."
sleep 3
for i in {1..30}; do
    HEALTH=$(curl -s "http://localhost:$OPENSEARCH_PORT/_cluster/health" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
    if [ "$HEALTH" = "green" ] || [ "$HEALTH" = "yellow" ]; then
        echo -e " ${GREEN}✓${NC}"
        break
    fi
    echo -n "."
    sleep 2
done
echo ""

# ─── Step 9: Verify ─────────────────────────────────────────────────────────
echo -e "${YELLOW}[9/9]${NC} Verifying migration..."

sleep 2
OS_COUNT=$(curl -s "http://localhost:$OPENSEARCH_PORT/$COLLECTION_NAME/_count" | grep -o '"count":[0-9]*' | cut -d: -f2)
OS_SEARCH=$(curl -s "http://localhost:$OPENSEARCH_PORT/$COLLECTION_NAME/_search?q=*:*&size=1" | grep -o '"title":"[^"]*"' | head -1)

echo -e "   Documents in OpenSearch: ${GREEN}$OS_COUNT${NC}"
echo -e "   Sample hit: $OS_SEARCH"
echo ""

echo -e "${GREEN}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║                   Migration Complete!                       ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${BLUE}Try these queries:${NC}"
echo "   curl 'http://localhost:$OPENSEARCH_PORT/$COLLECTION_NAME/_search?q=*:*&pretty'"
echo "   curl 'http://localhost:$OPENSEARCH_PORT/$COLLECTION_NAME/_search?q=category:tutorial&pretty'"
echo "   curl 'http://localhost:$OPENSEARCH_PORT/$COLLECTION_NAME/_search?q=content:lucene&pretty'"
echo ""
echo -e "${BLUE}Cleanup:${NC}"
echo "   docker stop solr-dev opensearch-dev && docker rm solr-dev opensearch-dev"
echo ""
